// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.codeInsight

import com.intellij.psi.statistics.StatisticsInfo
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.base.KtKeywordsRenderer
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.KaRendererAnnotationsFilter
import org.jetbrains.kotlin.analysis.api.renderer.declarations.KaCallableReturnTypeFilter
import org.jetbrains.kotlin.analysis.api.renderer.declarations.KtDeclarationRenderer
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KtDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.declarations.renderers.callables.KaCallableSignatureRenderer
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.utils.printer.PrettyPrinter
import org.jetbrains.kotlin.analysis.utils.printer.prettyPrint
import org.jetbrains.kotlin.lexer.KtKeywordToken

/**
 * Implementation in K1: [org.jetbrains.kotlin.idea.completion.KotlinStatisticsInfo]
 */
object K2StatisticsInfoProvider {
    /**
     * The renderer skips some features of a declaration to provide concise (but still unambiguous) description of the declaration.
     */
    private val renderer = KtDeclarationRendererForSource.WITH_QUALIFIED_NAMES.with {
        annotationRenderer = annotationRenderer.with { annotationFilter = KaRendererAnnotationsFilter.NONE }
        modifiersRenderer = modifiersRenderer.with { keywordsRenderer = KtKeywordsRenderer.NONE }

        returnTypeFilter = object : KaCallableReturnTypeFilter {
            override fun shouldRenderReturnType(analysisSession: KaSession, type: KtType, symbol: KaCallableSymbol): Boolean {
                return symbol !is KaFunctionLikeSymbol
            }
        }

        callableSignatureRenderer = object : KaCallableSignatureRenderer {
            override fun renderCallableSignature(
                analysisSession: KaSession,
                symbol: KaCallableSymbol,
                keyword: KtKeywordToken?,
                declarationRenderer: KtDeclarationRenderer,
                printer: PrettyPrinter
            ) {
                return when (symbol) {
                    is KaValueParameterSymbol -> {
                        returnTypeRenderer.renderReturnType(analysisSession, symbol, declarationRenderer, printer)
                    }
                    else -> {
                        KaCallableSignatureRenderer.FOR_SOURCE
                            .renderCallableSignature(analysisSession, symbol, keyword = null, declarationRenderer, printer)
                    }
                }
            }
        }
    }

    context(KaSession)
    fun forDeclarationSymbol(symbol: KaDeclarationSymbol, context: String = ""): StatisticsInfo = when (symbol) {
        is KaClassLikeSymbol -> symbol.classId?.asFqNameString()?.let { StatisticsInfo(context, it) }
        is KaCallableSymbol -> symbol.callableId?.let { callableId ->
            val containerFqName = callableId.classId?.asFqNameString() ?: callableId.packageName
            val declarationText = prettyPrint { renderer.renderDeclaration(analysisSession, symbol, this) }
            StatisticsInfo(context, "$containerFqName###$declarationText")
        }

        else -> null
    } ?: StatisticsInfo.EMPTY
}
