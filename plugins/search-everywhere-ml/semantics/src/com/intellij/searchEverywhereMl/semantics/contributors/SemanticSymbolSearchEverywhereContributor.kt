package com.intellij.searchEverywhereMl.semantics.contributors

import com.intellij.concurrency.SensitiveProgressWrapper
import com.intellij.ide.actions.SearchEverywherePsiRenderer
import com.intellij.ide.actions.searcheverywhere.SymbolSearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.ide.actions.searcheverywhere.PossibleSlowContributor
import com.intellij.ide.actions.searcheverywhere.PsiItemWithSimilarity
import com.intellij.ide.actions.searcheverywhere.SearchListener
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.searchEverywhereMl.SemanticSearchEverywhereContributor
import com.intellij.searchEverywhereMl.semantics.providers.SemanticSymbolsProvider
import com.intellij.util.Processor
import org.jetbrains.annotations.ApiStatus

/**
 * Contributor that adds semantic search functionality when searching for symbols in Search Everywhere.
 * Supports the search of Java methods and Kotlin functions.
 * For search logic refer to [SemanticSymbolsProvider].
 * For indexing logic refer to [com.intellij.searchEverywhereMl.semantics.services.SymbolEmbeddingStorage].
 * Delegates some of the rendering and data retrieval functionality to [SymbolSearchEverywhereContributor].
 */
@ApiStatus.Experimental
class SemanticSymbolSearchEverywhereContributor(initEvent: AnActionEvent)
  : SymbolSearchEverywhereContributor(initEvent), SemanticSearchEverywhereContributor,
    SearchEverywhereConcurrentPsiElementsFetcher, PossibleSlowContributor {
  private val project = initEvent.project ?: ProjectManager.getInstance().openProjects[0]

  override val itemsProvider = SemanticSymbolsProvider(project, createModel(project))

  override val psiElementsRenderer = elementsRenderer as SearchEverywherePsiRenderer

  override var searchListener: SearchListener? = null

  override fun getSearchProviderId(): String = SymbolSearchEverywhereContributor::class.java.simpleName

  override fun fetchWeightedElements(pattern: String, progressIndicator: ProgressIndicator,
                                     consumer: Processor<in FoundItemDescriptor<Any>>) {
    // We wrap the progressIndicator here to make sure we don't run standard search under the same indicator
    ProgressManager.getInstance().executeProcessUnderProgress(
      { fetchElementsConcurrently(pattern, SensitiveProgressWrapper(progressIndicator), consumer) }, progressIndicator)
  }

  override fun isElementSemantic(element: Any) = element is PsiItemWithSimilarity<*> && element.isPureSemantic

  override fun defaultFetchElements(pattern: String, progressIndicator: ProgressIndicator,
                                    consumer: Processor<in FoundItemDescriptor<Any>>) {
    super.fetchWeightedElements(pattern, progressIndicator, consumer)
  }

  override fun onStandardSearchFoundNoResults() {
    searchListener?.standardSearchFoundNoResults(this)
  }

  override fun checkScopeIsDefaultAndAutoSet(): Boolean = isScopeDefaultAndAutoSet

  override fun syncSearchSettings() {
    itemsProvider.model = createModel(project)
  }
}