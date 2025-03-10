// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptorIfAny
import org.jetbrains.kotlin.idea.codeinsight.api.classic.quickfixes.KotlinQuickFixAction
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.isError

class ChangeAccessorTypeFix(element: KtPropertyAccessor) : KotlinQuickFixAction<KtPropertyAccessor>(element) {
    private fun getType(): KotlinType? = element!!.property.resolveToDescriptorIfAny()?.type?.takeUnless(KotlinType::isError)

    override fun isAvailable(project: Project, editor: Editor?, file: KtFile) = getType() != null

    override fun getFamilyName() = KotlinBundle.message("fix.change.accessor.family")

    override fun getText(): String {
        val element = element ?: return ""
        val type = getType() ?: return familyName
        val renderedType = IdeDescriptorRenderers.SOURCE_CODE_SHORT_NAMES_NO_ANNOTATIONS.renderType(type)

        return if (element.isGetter) {
            KotlinBundle.message("fix.change.accessor.getter", renderedType)
        } else {
            KotlinBundle.message("fix.change.accessor.setter.parameter", renderedType)
        }
    }

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val element = element ?: return
        val type = getType()!!
        val newTypeReference = KtPsiFactory(project).createType(IdeDescriptorRenderers.SOURCE_CODE.renderType(type))

        val typeReference = if (element.isGetter) element.returnTypeReference else element.parameter!!.typeReference

        val insertedTypeRef = typeReference!!.replaced(newTypeReference)
        ShortenReferences.DEFAULT.process(insertedTypeRef)
    }
}
