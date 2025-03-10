// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.fir.extensions

import com.intellij.ide.impl.isTrusted
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.runAndLogException
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.openapi.util.registry.RegistryValueListener
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.FacetEntity
import com.intellij.platform.workspace.storage.EntityChange
import com.intellij.util.concurrency.SynchronizedClearableLazy
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.orNull
import com.intellij.util.lang.UrlClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.analysis.project.structure.KtCompilerPluginsProvider
import org.jetbrains.kotlin.analysis.project.structure.KtSourceModule
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.plugins.processCompilerPluginsOptions
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.extensions.ProjectExtensionDescriptor
import org.jetbrains.kotlin.fir.extensions.FirAssignExpressionAltererExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.idea.base.projectStructure.ideaModule
import org.jetbrains.kotlin.idea.base.projectStructure.moduleInfo
import org.jetbrains.kotlin.idea.base.projectStructure.moduleInfo.ModuleTestSourceInfo
import org.jetbrains.kotlin.idea.base.util.Frontend10ApiUsage
import org.jetbrains.kotlin.idea.base.util.caching.getChanges
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsListener
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.util.ServiceLoaderLite
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentMap

@OptIn(ExperimentalCompilerApi::class)
internal class KtCompilerPluginsProviderIdeImpl(private val project: Project, cs: CoroutineScope) : KtCompilerPluginsProvider(), Disposable {
    private val pluginsCacheCachedValue: SynchronizedClearableLazy<PluginsCache?> = SynchronizedClearableLazy { createNewCache() }
    private val pluginsCache: PluginsCache?
        get() = pluginsCacheCachedValue.value

    private val onlyBundledPluginsEnabledRegistryValue: RegistryValue =
        Registry.get("kotlin.k2.only.bundled.compiler.plugins.enabled")

    private val onlyBundledPluginsEnabled: Boolean
        get() = onlyBundledPluginsEnabledRegistryValue.asBoolean()

    init {
        cs.launch {
            WorkspaceModel.getInstance(project).eventLog.collect { event ->
                val hasChanges = event.getChanges<FacetEntity>().any { change ->
                    change.facetTypes.any { it == KotlinFacetType.ID }
                }
                if (hasChanges) {
                    resetPluginsCache()
                }
            }
        }
        val messageBusConnection = project.messageBus.connect(this)
        messageBusConnection.subscribe(KotlinCompilerSettingsListener.TOPIC,
            object : KotlinCompilerSettingsListener {
                override fun <T> settingsChanged(oldSettings: T?, newSettings: T?) {
                    resetPluginsCache()
                }
            }
        )

        onlyBundledPluginsEnabledRegistryValue.addListener(
            object : RegistryValueListener {
                override fun afterValueChanged(value: RegistryValue) {
                    resetPluginsCache()
                }
            },
            this
        )
    }

    private val EntityChange<FacetEntity>.facetTypes: List<String>
        get() = when (this) {
            is EntityChange.Added -> listOf(entity.typeId.name)
            is EntityChange.Removed -> listOf(entity.typeId.name)
            is EntityChange.Replaced -> listOf(oldEntity.typeId.name, newEntity.typeId.name)
        }

    private fun createNewCache(): PluginsCache? {
        if (!project.isTrusted()) return null
        val pluginsClassLoader: UrlClassLoader = UrlClassLoader.build().apply {
            parent(KtSourceModule::class.java.classLoader)

            val allModules = ModuleManager.getInstance(project).modules
            val pluginClasspaths = collectSubstitutedPluginClasspaths(allModules.map { it.getCompilerArguments() })

            files(pluginClasspaths)
        }.get()
        return PluginsCache(
            pluginsClassLoader,
            ContainerUtil.createConcurrentWeakMap<KtSourceModule, Optional<CompilerPluginRegistrar.ExtensionStorage>>()
        )
    }

    private class PluginsCache(
        val pluginsClassLoader: UrlClassLoader,
        val registrarForModule: ConcurrentMap<KtSourceModule, Optional<CompilerPluginRegistrar.ExtensionStorage>>
    )

    override fun <T : Any> getRegisteredExtensions(module: KtSourceModule, extensionType: ProjectExtensionDescriptor<T>): List<T> {
        val registrarForModule = pluginsCache?.registrarForModule ?: return emptyList()
        val extensionStorage = registrarForModule.computeIfAbsent(module) {
            Optional.ofNullable(computeExtensionStorage(module))
        }.orNull() ?: return emptyList()
        val registrars = extensionStorage.registeredExtensions[extensionType] ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return registrars as List<T>
    }

    override fun isPluginOfTypeRegistered(module: KtSourceModule, pluginType: CompilerPluginType): Boolean {
        val extension = when (pluginType) {
            CompilerPluginType.ASSIGNMENT -> FirAssignExpressionAltererExtension::class
            else -> return false
        }

        return getRegisteredExtensions(module, FirExtensionRegistrarAdapter)
            .map { (it as FirExtensionRegistrar).configure() }
            .any { it.extensions[extension]?.isNotEmpty() == true }
    }

    @OptIn(Frontend10ApiUsage::class)
    private fun computeExtensionStorage(module: KtSourceModule): CompilerPluginRegistrar.ExtensionStorage? {
        val classLoader = pluginsCache?.pluginsClassLoader ?: return null
        val compilerArguments = module.ideaModule.getCompilerArguments()
        val pluginClasspaths = collectSubstitutedPluginClasspaths(listOf(compilerArguments)).map { it.toFile() }
        if (pluginClasspaths.isEmpty()) return null

        val logger = logger<KtCompilerPluginsProviderIdeImpl>()

        ProgressManager.checkCanceled()

        val pluginRegistrars =
            logger.runAndLogException { ServiceLoaderLite.loadImplementations<CompilerPluginRegistrar>(pluginClasspaths, classLoader) }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        ProgressManager.checkCanceled()

        val commandLineProcessors = logger.runAndLogException {
            ServiceLoaderLite.loadImplementations<CommandLineProcessor>(pluginClasspaths, classLoader)
        } ?: return null

        ProgressManager.checkCanceled()

        val compilerConfiguration = CompilerConfiguration().apply {
            // Temporary work-around for KTIJ-24320. Calls to 'setupCommonArguments()' and 'setupJvmSpecificArguments()'
            // (or even a platform-agnostic alternative) should be added.
            if (compilerArguments is K2JVMCompilerArguments) {
                val compilerExtension = CompilerModuleExtension.getInstance(module.ideaModule)
                val outputUrl = when (module.moduleInfo) {
                    is ModuleTestSourceInfo -> compilerExtension?.compilerOutputUrlForTests
                    else -> compilerExtension?.compilerOutputUrl
                }

                putIfNotNull(JVMConfigurationKeys.JVM_TARGET, compilerArguments.jvmTarget?.let(JvmTarget::fromString))
                putIfNotNull(JVMConfigurationKeys.OUTPUT_DIRECTORY, outputUrl?.let { File(it) })
                put(JVMConfigurationKeys.IR, true) // FIR cannot work with the old backend
            }

            processCompilerPluginsOptions(this, compilerArguments.pluginOptions?.toList(), commandLineProcessors)
        }

        val storage = CompilerPluginRegistrar.ExtensionStorage()
        for (pluginRegistrar in pluginRegistrars) {
            ProgressManager.checkCanceled()

            with(pluginRegistrar) {
                try {
                    storage.registerExtensions(compilerConfiguration)
                }
                catch (e : ProcessCanceledException) {
                    throw e
                }
                catch (e: Throwable) {
                    LOG.error(e)
                }
            }
        }
        return storage
    }

    /**
     * Returns the paths defined in [CommonCompilerArguments.pluginClasspaths]
     * in the absolute form with the expansion of the present path macros
     * (like 'KOTLIN_BUNDLED').
     */
    private fun CommonCompilerArguments.getOriginalPluginClasspaths(): List<Path> {
        val pluginClassPaths = this.pluginClasspaths

        if (pluginClassPaths.isNullOrEmpty()) return emptyList()

        val pathMacroManager = PathMacroManager.getInstance(project)
        val expandedPluginClassPaths = pluginClassPaths.map { pathMacroManager.expandPath(it) }

        return expandedPluginClassPaths.map { Path.of(it).toAbsolutePath() }
    }

    /**
     * Collects the substituted plugin classpaths for the given [compilerArguments] list.
     *
     * We process the whole [compilerArguments] list at once, because it gives us opportunity
     * to not call [substitutePluginJar] multiple times if [compilerArguments] use the same
     * compiler plugins jars.
     */
    private fun collectSubstitutedPluginClasspaths(compilerArguments: List<CommonCompilerArguments>): List<Path> {
        val combinedOriginalClasspaths = compilerArguments.asSequence()
            .flatMap { it.getOriginalPluginClasspaths() }
            .distinct()

        val substitutedClasspaths = combinedOriginalClasspaths.mapNotNull(::substitutePluginJar).distinct()

        return substitutedClasspaths.toList()
    }

    /**
     * We have the following logic for plugins' substitution:
     * 1. Always replace our own plugins (like "allopen", "noarg", etc.) with bundled ones to avoid binary incompatibility.
     * 2. Allow to use other compiler plugins only if [onlyBundledPluginsEnabled] is set to false; otherwise, filter them.
     */
    private fun substitutePluginJar(userSuppliedPluginJar: Path): Path? {
        ProgressManager.checkCanceled()

        val bundledPlugin = KotlinK2BundledCompilerPlugins.findCorrespondingBundledPlugin(userSuppliedPluginJar)
        if (bundledPlugin != null) return bundledPlugin.bundledJarLocation

        return userSuppliedPluginJar.takeUnless { onlyBundledPluginsEnabled }
    }


    private fun Module.getCompilerArguments(): CommonCompilerArguments {
        return KotlinFacet.get(this)?.configuration?.settings?.mergedCompilerArguments
            ?: KotlinCommonCompilerArgumentsHolder.getInstance(project).settings
    }

    override fun dispose() {
        resetPluginsCache()
    }

    /**
     * Throws away the cache for all the registered plugins, and executes all the disposables
     * registered in the corresponding [CompilerPluginRegistrar.ExtensionStorage]s.
     */
    private fun resetPluginsCache() {
        val droppedCache = pluginsCacheCachedValue.drop() ?: return

        val extensionStorages = droppedCache.registrarForModule.values.mapNotNull { it.orNull() }

        for (storage in extensionStorages) {
            for (disposable in storage.disposables) {
                LOG.runAndLogException {
                    disposable.dispose()
                }
            }
        }
    }

    companion object {
        fun getInstance(project: Project): KtCompilerPluginsProviderIdeImpl {
            return project.getService(KtCompilerPluginsProvider::class.java) as KtCompilerPluginsProviderIdeImpl
        }
        private val LOG = logger<KtCompilerPluginsProviderIdeImpl>()
    }
}
