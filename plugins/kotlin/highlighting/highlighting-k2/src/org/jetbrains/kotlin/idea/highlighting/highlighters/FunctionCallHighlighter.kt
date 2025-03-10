// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.highlighting.highlighters

import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.*
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaSymbolKind
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.idea.highlighter.HighlightingFactory
import org.jetbrains.kotlin.idea.highlighter.KotlinHighlightInfoTypeSemanticNames
import org.jetbrains.kotlin.idea.highlighting.KotlinCallHighlighterExtension
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*

context(KaSession)
internal class FunctionCallHighlighter(holder: HighlightInfoHolder) : KotlinSemanticAnalyzer(holder) {
    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        highlightBinaryExpression(expression)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        highlightCallExpression(expression)
    }

    private fun highlightBinaryExpression(expression: KtBinaryExpression) {
        val operationReference = expression.operationReference as? KtReferenceExpression ?: return
        if (operationReference.isAssignment()) return
        val call = expression.resolveCallOld()?.successfulCallOrNull<KaCall>() ?: return
        if (call is KaSimpleFunctionCall && (call.symbol as? KaFunctionSymbol)?.isOperator == true) return
        val highlightInfoType = getDefaultHighlightInfoTypeForCall(call)
        if (highlightInfoType != null) {
            val builder = HighlightingFactory.highlightName(operationReference, highlightInfoType)
            holder.add(builder?.create())
        }
    }

    private fun KtReferenceExpression.isAssignment() =
        (this as? KtOperationReferenceExpression)?.operationSignTokenType == KtTokens.EQ

    private fun highlightCallExpression(expression: KtCallExpression) {
        val callee = expression.calleeExpression ?: return
        val call = expression.resolveCallOld()?.singleCallOrNull<KaCall>() ?: return
        if (callee is KtLambdaExpression || callee is KtCallExpression /* KT-16159 */) return
        val highlightInfoType = getHighlightInfoTypeForCallFromExtension(callee, call)
            ?: getDefaultHighlightInfoTypeForCall(call)
            ?: return
        holder.add(HighlightingFactory.highlightName(callee, highlightInfoType)?.create())
    }

    private fun getHighlightInfoTypeForCallFromExtension(callee: KtExpression, call: KaCall): HighlightInfoType? =
        KotlinCallHighlighterExtension.EP_NAME.extensionList.firstNotNullOfOrNull { it.highlightCall(callee, call) }

    private fun getDefaultHighlightInfoTypeForCall(call: KaCall): HighlightInfoType? {
        if (call !is KaSimpleFunctionCall) return null
        return when (val function = call.symbol) {
            is KaConstructorSymbol -> KotlinHighlightInfoTypeSemanticNames.CONSTRUCTOR_CALL
            is KaAnonymousFunctionSymbol -> null
            is KaFunctionSymbol -> when {
                function.isSuspend -> KotlinHighlightInfoTypeSemanticNames.SUSPEND_FUNCTION_CALL
                call.isImplicitInvoke -> if (function.isBuiltinFunctionInvoke) {
                    KotlinHighlightInfoTypeSemanticNames.VARIABLE_AS_FUNCTION_CALL
                } else {
                    KotlinHighlightInfoTypeSemanticNames.VARIABLE_AS_FUNCTION_LIKE_CALL
                }

                function.callableId == KOTLIN_SUSPEND_BUILT_IN_FUNCTION_FQ_NAME_CALLABLE_ID -> KotlinHighlightInfoTypeSemanticNames.KEYWORD
                function.isExtension -> KotlinHighlightInfoTypeSemanticNames.EXTENSION_FUNCTION_CALL
                function.symbolKind == KaSymbolKind.TOP_LEVEL -> KotlinHighlightInfoTypeSemanticNames.PACKAGE_FUNCTION_CALL
                else -> KotlinHighlightInfoTypeSemanticNames.FUNCTION_CALL
            }

            else -> KotlinHighlightInfoTypeSemanticNames.FUNCTION_CALL //TODO ()
        }
    }

    companion object {
        private val KOTLIN_SUSPEND_BUILT_IN_FUNCTION_FQ_NAME_CALLABLE_ID =
            CallableId(StandardNames.BUILT_INS_PACKAGE_FQ_NAME, Name.identifier("suspend"))
    }
}