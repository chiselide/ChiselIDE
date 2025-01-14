// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.workspace

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.ApiStatus.Experimental
import javax.swing.Icon

@Experimental
interface SubprojectHandler {
  companion object {
    val EP_NAME: ExtensionPointName<SubprojectHandler> = ExtensionPointName.create("com.intellij.workspace.subprojectHandler")
  }

  fun getSubprojects(workspace: Project): List<Subproject>
  fun canImportFromFile(file: VirtualFile): Boolean
  fun removeSubprojects(workspace: Project, subprojects: List<Subproject>)
  fun importFromProject(project: Project): ImportedProjectSettings?

  fun suppressGenericImportFor(module: Module): Boolean = false

  val subprojectIcon: Icon?
}

interface WorkspaceSettingsImporter {
  companion object {
    val EP_NAME: ExtensionPointName<WorkspaceSettingsImporter> = ExtensionPointName.create("com.intellij.workspace.settingsImporter")
  }

  fun importFromProject(project: Project): ImportedProjectSettings?
}

interface ImportedProjectSettings {
  suspend fun applyTo(workspace: Project): Boolean
}
