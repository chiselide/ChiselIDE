// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.quickFix

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KtTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.types.KtTypeNullability
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixFactory
import org.jetbrains.kotlin.idea.codeinsight.api.classic.quickfixes.KotlinQuickFixAction
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeInfo
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureProcessor
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinMethodDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.Variance

object ChangeParameterTypeFixFactory {

    val typeMismatchFactory = KotlinQuickFixFactory.IntentionBased { diagnostic: KaFirDiagnostic.ArgumentTypeMismatch ->
        val psi = diagnostic.psi
        val targetType = diagnostic.actualType
        createTypeMismatchFixes(psi, targetType)
    }

    val nullForNotNullTypeFactory = KotlinQuickFixFactory.IntentionBased { diagnostic: KaFirDiagnostic.NullForNonnullType ->
       createTypeMismatchFixes(diagnostic.psi, diagnostic.expectedType.withNullability(KtTypeNullability.NULLABLE))
    }

    context(KaSession)
    private fun createTypeMismatchFixes(psi: PsiElement, targetType: KtType): List<KotlinQuickFixAction<*>> {
        val valueArgument = psi.parent as? KtValueArgument ?: return emptyList()

        val callElement = valueArgument.parentOfType<KtCallElement>() ?: return emptyList()
        val memberCall = (callElement.resolveCallOld() as? KaErrorCallInfo)?.candidateCalls?.firstOrNull() as? KaFunctionCall<*>
        val functionLikeSymbol = memberCall?.symbol ?: return emptyList()

        val paramSymbol = memberCall.argumentMapping[valueArgument.getArgumentExpression()]
        val parameter = paramSymbol?.symbol?.psi as? KtParameter ?: return emptyList()

        val functionName = getDeclarationName(functionLikeSymbol) ?: return emptyList()

        val approximatedType = targetType.approximateToSuperPublicDenotableOrSelf(true)

        val typePresentation = approximatedType.render(KtTypeRendererForSource.WITH_SHORT_NAMES, position = Variance.IN_VARIANCE)
        val typeFQNPresentation = approximatedType.render(position = Variance.IN_VARIANCE)

        return listOf(ChangeParameterTypeFix(
            parameter,
            typePresentation,
            typeFQNPresentation,
            functionLikeSymbol is KaConstructorSymbol && functionLikeSymbol.isPrimary,
            functionName
        ))
    }
}

internal class ChangeParameterTypeFix(
    element: KtParameter,
    val typePresentation: String,
    val typeFQNPresentation: String,
    private val isPrimaryConstructorParameter: Boolean,
    private val functionName: String
) : KotlinQuickFixAction<KtParameter>(element) {

    override fun startInWriteAction(): Boolean = false

    override fun getText(): String = element?.let {
        when {
            isPrimaryConstructorParameter -> {
                KotlinBundle.message(
                    "fix.change.return.type.text.primary.constructor",
                    it.name.toString(), functionName, typePresentation
                )
            }
            else -> {
                KotlinBundle.message(
                    "fix.change.return.type.text.function",
                    it.name.toString(), functionName, typePresentation
                )
            }
        }
    } ?: ""

    override fun getFamilyName() = KotlinBundle.message("fix.change.return.type.family")

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val element = element ?: return
        val function = element.ownerFunction as? KtFunction ?: return

        val parameterIndex = function.valueParameters.indexOf(element)

        val methodDescriptor = KotlinMethodDescriptor(function)

        val changeInfo = KotlinChangeInfo(methodDescriptor)
        val parameterInfo = changeInfo.newParameters[if (methodDescriptor.receiver != null) parameterIndex + 1 else parameterIndex]

        parameterInfo.setType(typeFQNPresentation)

        KotlinChangeSignatureProcessor(project, changeInfo).run()
    }
}