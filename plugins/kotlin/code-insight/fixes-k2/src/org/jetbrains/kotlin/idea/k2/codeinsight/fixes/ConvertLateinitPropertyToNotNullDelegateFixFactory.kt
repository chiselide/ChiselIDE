// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.idea.base.analysis.api.utils.shortenReferencesInRange
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixFactory
import org.jetbrains.kotlin.idea.quickfix.ConvertLateinitPropertyToNotNullDelegateFixBase
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance

internal object ConvertLateinitPropertyToNotNullDelegateFixFactory {

    val convertLateinitPropertyToNotNullDelegateFixFactory =
        KotlinQuickFixFactory.ModCommandBased { diagnostic: KaFirDiagnostic.InapplicableLateinitModifier ->
            val property = diagnostic.psi as? KtProperty ?: return@ModCommandBased emptyList()
            if (!property.hasModifier(KtTokens.LATEINIT_KEYWORD) || !property.isVar || property.hasInitializer()) {
                return@ModCommandBased emptyList()
            }
            val type = property.typeReference?.getKaType() ?: return@ModCommandBased emptyList()
            if (!type.isPrimitive || type.isMarkedNullable) return@ModCommandBased emptyList()

            val renderedType = type.render(
                renderer = KaTypeRendererForSource.WITH_SHORT_NAMES,
                position = Variance.INVARIANT
            )

            listOf(
                ConvertLateinitPropertyToNotNullDelegateFix(property, renderedType)
            )
        }

    private class ConvertLateinitPropertyToNotNullDelegateFix(
        element: KtProperty,
        type: String,
    ) : ConvertLateinitPropertyToNotNullDelegateFixBase(element, type) {

        override fun shortenReferences(element: KtProperty) {
            shortenReferencesInRange(element)
        }
    }
}
