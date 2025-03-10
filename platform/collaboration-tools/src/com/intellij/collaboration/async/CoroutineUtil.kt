// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.intellij.collaboration.async

import com.intellij.collaboration.util.ComputedResult
import com.intellij.collaboration.util.HashingUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.cancelOnDispose
import com.intellij.util.containers.CollectionFactory
import com.intellij.util.containers.HashingStrategy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


inline fun <reified T> T.classAsCoroutineName() = CoroutineName(T::class.java.name)
inline fun <reified T> classAsCoroutineName() = CoroutineName(T::class.java.name)

/**
 * Prefer creating a service to supply a parent scope
 */
@ApiStatus.Obsolete
@Suppress("FunctionName")
fun DisposingMainScope(parentDisposable: Disposable): CoroutineScope {
  return MainScope().also {
    Disposer.register(parentDisposable) {
      it.cancel()
    }
  }
}

/**
 * Prefer creating a service to supply a parent scope
 */
@ApiStatus.Obsolete
fun Disposable.disposingMainScope(): CoroutineScope = DisposingMainScope(this)

/**
 * Prefer creating a service to supply a parent scope
 */
@ApiStatus.Obsolete
fun Disposable.disposingScope(context: CoroutineContext = SupervisorJob()): CoroutineScope = CoroutineScope(context).also {
  Disposer.register(this) {
    it.cancel()
  }
}

@OptIn(InternalCoroutinesApi::class)
@ApiStatus.Experimental
fun CoroutineScope.nestedDisposable(): Disposable {
  val job = coroutineContext[Job]
  require(job != null) {
    "Found no Job in context: $coroutineContext"
  }
  return Disposer.newDisposable().also {
    job.invokeOnCompletion(onCancelling = true, handler = { _ ->
      Disposer.dispose(it)
    })
  }
}

fun CoroutineScope.cancelledWith(disposable: Disposable): CoroutineScope = apply {
  val job = coroutineContext[Job]
  requireNotNull(job) { "Coroutine scope without a parent job $this" }
  job.cancelOnDispose(disposable, false)
}

fun CoroutineScope.launchNow(context: CoroutineContext = EmptyCoroutineContext, block: suspend CoroutineScope.() -> Unit): Job =
  launch(context, CoroutineStart.UNDISPATCHED, block)

/**
 * Similar to [launchIn], but starts the collection with [CoroutineStart.UNDISPATCHED] and allows overriding context
 */
fun <T> Flow<T>.launchNowIn(scope: CoroutineScope, context: CoroutineContext = EmptyCoroutineContext): Job =
  scope.launch(context, CoroutineStart.UNDISPATCHED) {
    collect()
  }

@ApiStatus.Experimental
fun <T1, T2, R> combineState(scope: CoroutineScope,
                             state1: StateFlow<T1>,
                             state2: StateFlow<T2>,
                             transform: (T1, T2) -> R): StateFlow<R> =
  combine(state1, state2, transform)
    .stateIn(scope, SharingStarted.Eagerly, transform(state1.value, state2.value))

@ApiStatus.Experimental
fun <T1, T2, T3, R> combineState(scope: CoroutineScope,
                                 state1: StateFlow<T1>,
                                 state2: StateFlow<T2>,
                                 state3: StateFlow<T3>,
                                 transform: (T1, T2, T3) -> R): StateFlow<R> =
  combine(state1, state2, state3, transform)
    .stateIn(scope, SharingStarted.Eagerly, transform(state1.value, state2.value, state3.value))

@ApiStatus.Experimental
suspend fun <T1, T2> combineAndCollect(
  flow1: Flow<T1>,
  flow2: Flow<T2>,
  action: suspend (T1, T2) -> Unit
) {
  return combine(flow1, flow2) { value1, value2 ->
    value1 to value2
  }.collect { (value1, value2) ->
    action(value1, value2)
  }
}

@ApiStatus.Experimental
suspend fun <T1, T2, T3> combineAndCollect(
  flow1: Flow<T1>,
  flow2: Flow<T2>,
  flow3: Flow<T3>,
  action: suspend (T1, T2, T3) -> Unit
) {
  return combine(flow1, flow2, flow3) { value1, value2, value3 ->
    Triple(value1, value2, value3)
  }.collect { (value1, value2, value3) ->
    action(value1, value2, value3)
  }
}

fun Flow<Boolean>.inverted() = map { !it }

@ApiStatus.Experimental
fun <T, M> StateFlow<T>.mapState(
  scope: CoroutineScope,
  mapper: (value: T) -> M
): StateFlow<M> = map { mapper(it) }.stateIn(scope, SharingStarted.Eagerly, mapper(value))

@ApiStatus.Internal
fun <T, M> StateFlow<T>.mapStateInNow(
  scope: CoroutineScope,
  mapper: (value: T) -> M
): StateFlow<M> = map { mapper(it) }.stateInNow(scope, mapper(value))

@ApiStatus.Experimental
fun <T, M> StateFlow<T>.mapState(mapper: (value: T) -> M): StateFlow<M> = MappedStateFlow(this) { mapper(value) }

private class MappedStateFlow<T, R>(private val source: StateFlow<T>, private val mapper: (T) -> R) : StateFlow<R> {
  override val value: R
    get() = mapper(source.value)

  override val replayCache: List<R>
    get() = source.replayCache.map(mapper)

  override suspend fun collect(collector: FlowCollector<R>): Nothing {
    source.map(mapper).distinctUntilChanged().collect(collector)
    awaitCancellation()
  }
}

@ApiStatus.Experimental
fun <T1, T2, R> StateFlow<T1>.combineState(other: StateFlow<T2>, combiner: (T1, T2) -> R): StateFlow<R> =
  DerivedStateFlow(combine(other, combiner)) { combiner(value, other.value) }

/**
 * Not great, because will always compute [combiner] twice at the start
 * To be used when you need the flow to handle [CoroutineStart.UNDISPATCHED], because pure [combine] does not
 */
@ApiStatus.Internal
fun <T1, T2, R> combineStateIn(cs: CoroutineScope, sf1: StateFlow<T1>, sf2: StateFlow<T2>, combiner: (T1, T2) -> R): StateFlow<R> =
  combine(sf1, sf2, combiner).stateIn(cs, SharingStarted.Eagerly, combiner(sf1.value, sf2.value))

/**
 * Special state flow which value is supplied by [valueSupplier] and collection is delegated to [source]
 *
 * [valueSupplier] should NEVER THROW to avoid contract violation
 *
 *
 * https://github.com/Kotlin/kotlinx.coroutines/issues/2631#issuecomment-870565860
 */
private class DerivedStateFlow<T>(
  private val source: Flow<T>,
  private val valueSupplier: () -> T
) : StateFlow<T> {

  override val value: T get() = valueSupplier()
  override val replayCache: List<T> get() = listOf(value)

  @InternalCoroutinesApi
  override suspend fun collect(collector: FlowCollector<T>): Nothing {
    coroutineScope { source.distinctUntilChanged().stateIn(this).collect(collector) }
  }
}

@ApiStatus.Experimental
fun <T, R> Flow<T>.mapScoped(supervisor: Boolean, mapper: CoroutineScope.(T) -> R): Flow<R> = mapScoped2(supervisor, mapper)

@ApiStatus.Experimental
fun <T, R> Flow<T>.mapScoped(mapper: CoroutineScope.(T) -> R): Flow<R> = mapScoped2(false, mapper)

/**
 * Maps each value from a source flow to a distinct [CoroutineScope]
 *
 * Can handle [CoroutineStart.UNDISPATCHED]
 */
@ApiStatus.Experimental
private fun <T, R> Flow<T>.mapScoped2(supervisor: Boolean, mapper: suspend CoroutineScope.(T) -> R): Flow<R> =
  flow {
    coroutineScope {
      var lastScope: Job? = null
      // need a breaker to allow re-emitting in the same coroutine that started the flow
      val breaker = Channel<R>()
      launchNow {
        try {
          collect { state ->
            lastScope?.cancelAndJoinSilently()
            lastScope = launchNow {
              val scopeBody: suspend CoroutineScope.() -> Unit = {
                val result = mapper(state)
                breaker.send(result)
              }
              if (supervisor) supervisorScope(scopeBody) else coroutineScope(scopeBody)
            }
          }
        }
        finally {
          breaker.close()
        }
      }
      breaker.consumeAsFlow().collect(this@flow)
    }
  }

@ApiStatus.Experimental
private fun <T, R> Flow<T>.mapScoped2(mapper: suspend CoroutineScope.(T) -> R): Flow<R> = mapScoped2(false, mapper)

/**
 * Performs mapping only if the source value is not null
 */
@ApiStatus.Experimental
fun <T, R> Flow<T?>.mapNullable(mapper: (T) -> R): Flow<R?> = map { it?.let(mapper) }

/**
 * Performs mapping only if the source value is not null
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ApiStatus.Experimental
fun <T, R> Flow<T?>.mapNullableLatest(mapper: suspend (T) -> R): Flow<R?> = mapLatest {
  if (it != null) mapper(it) else null
}

@ApiStatus.Experimental
fun <T, R> Flow<T?>.mapNullableScoped(mapper: CoroutineScope.(T) -> R): Flow<R?> = mapScoped2 { if (it == null) null else mapper(it) }

@ApiStatus.Experimental
suspend fun <T> Flow<T>.collectScoped(block: suspend CoroutineScope.(T) -> Unit) = mapScoped2(block).collect()

@ApiStatus.Experimental
suspend fun <T> Flow<T>.collectWithPrevious(initial: T, collector: suspend (prev: T, current: T) -> Unit) {
  var prev = initial
  collect {
    collector(prev, it)
    prev = it
  }
}

@ApiStatus.Experimental
fun <T> Flow<T>.withInitial(initial: T): Flow<T> = flow {
  emit(initial)
  emitAll(this@withInitial)
}

/**
 * In principle, it is an analogue of [stateIn] with [SharingStarted.Eagerly],
 * with a notable difference being that a [defaultValue] may never be emitted if a value is already available in the source flow
 */
fun <T> Flow<T>.stateInNow(cs: CoroutineScope, defaultValue: T): StateFlow<T> {
  val result = MutableStateFlow(defaultValue)
  cs.launchNow {
    collect(result)
  }
  return result.asStateFlow()
}

/**
 * Lazy shared flow that logs all exceptions as errors and never throws (beside cancellation)
 */
fun <T> Flow<T>.modelFlow(cs: CoroutineScope, log: Logger): SharedFlow<T> =
  catch { log.error(it) }.shareIn(cs, SharingStarted.Lazily, 1)

/**
 * Associate each *item* [T] *key* [K] in the iterable from the receiver flow (source list) with a *value* [V]
 * Keys are distinguished by a [hashingStrategy]
 *
 * When a new iterable is received:
 * * a new [CoroutineScope] and a new value is created via [valueExtractor] for new items
 * * existing values are updated via [update] if it was supplied
 * * values for missing items are removed and destroyed via [destroy]
 *
 * Order of the values in the resulting map is the same as in the source iterable
 * All [CoroutineScope]'s of values are only active while the resulting flow is being collected
 */
fun <T, K, V> Flow<Iterable<T>>.associateCachingBy(keyExtractor: (T) -> K,
                                                   hashingStrategy: HashingStrategy<K>,
                                                   valueExtractor: CoroutineScope.(T) -> V,
                                                   destroy: suspend V.() -> Unit,
                                                   update: (suspend V.(T) -> Unit)? = null)
  : Flow<Map<K, V>> = flow {
  coroutineScope {
    val container = MappingScopedItemsContainer(this, keyExtractor, hashingStrategy, valueExtractor, destroy, update)
    launchNow {
      collect { items ->
        container.update(items)
      }
    }
    container.mappingState.collect {
      emit(it)
    }
  }
}

/**
 * Allows mapping a collection of items [T] to scoped (coroutine scope bound) values [V]
 * An intermittent key [K] is used to uniquely identify items
 *
 * @param cs parent scope for value scopes
 * @param keyExtractor should be a quick-to-run function extracting a key from item
 * @param hashingStrategy strategy used to compare keys
 * @param mapper factory function to create a value from item
 * @param destroy destructor function to destroy a value
 * @param update function used to update value if a new item is supplied for the existing key
 */
class MappingScopedItemsContainer<T, K, V>(
  private val cs: CoroutineScope,
  private val keyExtractor: (T) -> K,
  private val hashingStrategy: HashingStrategy<K>,
  private val mapper: CoroutineScope.(T) -> V,
  private val destroy: suspend V.() -> Unit,
  private val update: (suspend V.(T) -> Unit)? = null
) {
  private val _mappingState = MutableStateFlow<Map<K, ScopingWrapper<V>>>(emptyMap())
  val mappingState: StateFlow<Map<K, V>> = _mappingState.mapState { it.mapValues { (_, value) -> value.value } }
  private val mapGuard = Mutex()

  suspend fun update(items: Iterable<T>) = mapGuard.withLock {
    withContext(NonCancellable) {
      val currentMap = _mappingState.value
      var hasStructureChanges = false
      val newItemsSet = CollectionFactory.createLinkedCustomHashingStrategySet(hashingStrategy).also {
        items.mapTo(it, keyExtractor)
      }

      val result = createLinkedMap<K, ScopingWrapper<V>>(hashingStrategy)
      // destroy missing
      for ((key, scopedValue) in currentMap) {
        if (!newItemsSet.contains(key)) {
          hasStructureChanges = true
          scopedValue.value.destroy()
          scopedValue.cancel()
        }
        else {
          result[key] = scopedValue
        }
      }

      // add new or update existing
      for (item in items) {
        val itemKey = keyExtractor(item)
        val existing = result[itemKey]
        if (existing == null) {
          val valueScope = cs.childScope()
          result[itemKey] = ScopingWrapper(valueScope, mapper(valueScope, item))
          hasStructureChanges = true
        }
        else {
          // if not inferring nullability fsr
          update?.let { existing.value.it(item) }
          result[itemKey] = existing
        }
      }

      if (hasStructureChanges) {
        _mappingState.value = result
      }
    }
  }

  suspend fun addIfAbsent(item: T): V = mapGuard.withLock {
    withContext(NonCancellable) {
      val key = keyExtractor(item)
      _mappingState.value[key]?.value ?: _mappingState.updateAndGet {
        val valueScope = cs.childScope()
        val newValue = ScopingWrapper(valueScope, mapper(valueScope, item))
        it + (key to newValue)
      }[key]!!.value
    }
  }

  companion object {
    fun <T, V> byIdentity(cs: CoroutineScope, mapper: CoroutineScope.(T) -> V) =
      MappingScopedItemsContainer(cs, { it }, HashingStrategy.identity(), mapper, {})
  }
}

private data class ScopingWrapper<T>(val scope: CoroutineScope, val value: T) {
  suspend fun cancel() = scope.cancelAndJoinSilently()
}

private fun <T, R> createLinkedMap(hashingStrategy: HashingStrategy<T>): MutableMap<T, R> =
  CollectionFactory.createLinkedCustomHashingStrategyMap(hashingStrategy)

/**
 * @see associateCachingBy
 *
 * Shorthand for cases where key is the same as item destructor simply cancels the value scope
 */
private fun <T, R> Flow<Iterable<T>>.associateCaching(hashingStrategy: HashingStrategy<T>,
                                                      mapper: CoroutineScope.(T) -> R,
                                                      update: (suspend R.(T) -> Unit)? = null): Flow<Map<T, R>> {
  return associateCachingBy({ it }, hashingStrategy, { mapper(it) }, { }, update)
}

fun <ID : Any, T, R> Flow<Iterable<T>>.mapCaching(sourceIdentifier: (T) -> ID,
                                                  mapper: CoroutineScope.(T) -> R,
                                                  destroy: suspend R.() -> Unit,
                                                  update: (suspend R.(T) -> Unit)? = null): Flow<List<R>> =
  associateCachingBy(sourceIdentifier, HashingStrategy.canonical(), mapper, destroy, update).map { it.values.toList() }

/**
 * Creates a list of model objects from DTOs
 */
fun <T, R> Flow<Iterable<T>>.mapDataToModel(sourceIdentifier: (T) -> Any,
                                            mapper: CoroutineScope.(T) -> R,
                                            update: (suspend R.(T) -> Unit)): Flow<List<R>> =
  associateCaching(HashingUtil.mappingStrategy(sourceIdentifier), mapper, update).map { it.values.toList() }

/**
 * Create a list of view models from models
 */
fun <T, R> Flow<Iterable<T>>.mapModelsToViewModels(mapper: CoroutineScope.(T) -> R): Flow<List<R>> =
  associateCaching(HashingStrategy.identity(), mapper).map { it.values.toList() }

fun <T> Flow<Collection<T>>.mapFiltered(predicate: (T) -> Boolean): Flow<List<T>> = map { it.filter(predicate) }

/**
 * Treats 'this' flow as representing a single list of results. Meaning each emitted value is accumulated
 * with the previous ones into a single list and re-emitted.
 */
fun <T> Flow<List<T>>.collectBatches(): Flow<List<T>> {
  val result = mutableListOf<T>()
  return transform {
    result.addAll(it)
    emit(result.toList())
  }
}

/**
 * Hack class to wrap any type to ensure equality checking is done through referential equality only.
 */
private class ReferentiallyComparedValue<T : Any>(val value: T) {
  override fun equals(other: Any?): Boolean =
    value === other

  override fun hashCode(): Int =
    System.identityHashCode(value)
}

/**
 * Transforms a flow of consecutive successes. The flow is reset when a failure is encountered if [resetOnFailure] is `true`.
 * This means that, if [resetOnFailure] is `true`, the [transformer] block is called once for every series of consecutive
 * successes. If it is `false`, the [transformer] block is called only once with a flow that receives every success value.
 *
 * This acts as a replacement of consecutive `asResultFlow` and `throwFailure` and avoids that exceptions cancel the flow.
 */
@JvmName("transformConsecutiveResultSuccesses")
fun <T, R> Flow<Result<T>>.transformConsecutiveSuccesses(
  resetOnFailure: Boolean = true,
  transformer: suspend Flow<T>.() -> Flow<R>
): Flow<Result<R>> =
  channelFlow {
    val successFlows = MutableStateFlow(ReferentiallyComparedValue(MutableSharedFlow<T>(1)))

    launchNow {
      successFlows
        .collectLatest { successes ->
          successes.value
            .transformer()
            .collect {
              send(Result.success(it))
            }
        }
    }

    collect {
      it.fold(
        onSuccess = { v -> successFlows.value.value.emit(v) },
        onFailure = { ex ->
          if (resetOnFailure) {
            successFlows.value = ReferentiallyComparedValue(MutableSharedFlow(1))
          }
          send(Result.failure(ex))
        }
      )
    }
  }

/**
 * Transforms a flow of consecutive successes. The flow is reset when a failure is encountered if [resetOnFailure] is `true`.
 * This means that, if [resetOnFailure] is `true`, the [transformer] block is called once for every series of consecutive
 * successes. If it is `false`, the [transformer] block is called only once with a flow that receives every success value.
 */
fun <T, R> Flow<ComputedResult<T>>.transformConsecutiveSuccesses(
  resetOnFailure: Boolean = true,
  transformer: suspend Flow<T>.() -> Flow<R>
): Flow<ComputedResult<R>> =
  channelFlow {
    val successFlows = MutableStateFlow(ReferentiallyComparedValue(MutableSharedFlow<T>(1)))

    launchNow {
      successFlows
        .collectLatest { successes ->
          successes.value
            .transformer()
            .collect {
              send(ComputedResult.success(it))
            }
        }
    }

    collect {
      it.result?.fold(
        onSuccess = { v -> successFlows.value.value.emit(v) },
        onFailure = { ex ->
          if (resetOnFailure) {
            successFlows.value = ReferentiallyComparedValue(MutableSharedFlow(1))
          }
          send(ComputedResult.failure(ex))
        }
      )
    }
  }

/**
 * Transforms the flow of some computation requests to a flow of computation states of this request
 * Will not emit "loading" state if the computation was completed before handling its state
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<Deferred<T>>.computationState(): Flow<ComputedResult<T>> =
  transformLatest { request ->
    if (!request.isCompleted) {
      emit(ComputedResult.loading())
    }
    try {
      val value = request.await()
      emit(ComputedResult.success(value))
    }
    catch (e: Exception) {
      if (e !is CancellationException) {
        emit(ComputedResult.failure(e))
      }
    }
  }


@OptIn(ExperimentalCoroutinesApi::class)
inline fun <A, T> computationStateFlow(arguments: Flow<A>, crossinline computer: suspend (A) -> T): Flow<ComputedResult<T>> =
  arguments.transformLatest { parameters ->
    supervisorScope {
      val req = async(start = CoroutineStart.UNDISPATCHED) {
        computer(parameters)
      }
      if (!req.isCompleted) {
        emit(ComputedResult.loading())
      }

      val toEmit = ComputedResult.compute { req.await() }
      currentCoroutineContext().ensureActive()
      if (toEmit != null) {
        emit(toEmit)
      }
    }
  }

/**
 * Awaits the first non-canceled computation and returns the result or throws the exception
 */
@JvmName("awaitCompletedDeferred")
@ApiStatus.Internal
suspend fun <T> Flow<Deferred<T>>.awaitCompleted(): T =
  mapLatest {
    runCatching { it.await() }.also {
      currentCoroutineContext().ensureActive()
    }
  }.filter {
    it.exceptionOrNull() !is CancellationException
  }.first().getOrThrow()

/**
 * Maps the flow of requests to a flow of successfully computed values
 */
fun <T> Flow<CompletableFuture<T>>.values(): Flow<T> = mapNotNull {
  try {
    it.asDeferred().await()
  }
  catch (_: Throwable) {
    null
  }
}

/**
 * Maps values in the flow to successful results and catches and wraps any exception into a failure result.
 */
fun <T> Flow<T>.asResultFlow(): Flow<Result<T>> =
  map { Result.success(it) }.catch { emit(Result.failure(it)) }

/**
 * Maps a flow or results to a flow of a mapped result
 */
fun <T, R> Flow<Result<T>>.mapCatching(mapper: suspend (T) -> R): Flow<Result<R>> =
  map { it.mapCatching { value -> mapper(value) } }

/**
 * Maps a flow or results to a flow of values from successful results. Failure results are re-thrown as exceptions.
 */
fun <T> Flow<Result<T>>.throwFailure(): Flow<T> =
  map { it.getOrThrow() }

/**
 * Cancel the scope, await its completion but ignore the completion exception if any to void cancelling the caller
 */
suspend fun CoroutineScope.cancelAndJoinSilently() {
  val cs = this
  cs.coroutineContext[Job]?.cancelAndJoinSilently() ?: error("Missing Job in $this")
}

/**
 * Cancel the job, await its completion but ignore the completion exception if any to void cancelling the caller
 */
suspend fun Job.cancelAndJoinSilently() {
  val job = this
  try {
    job.cancelAndJoin()
  }
  catch (ignored: Exception) {
  }
}

/**
 * Await the deferred value and cancel if the waiting was canceled
 */
suspend fun <T> Deferred<T>.awaitCancelling(): T {
  return try {
    await()
  }
  catch (ce: CancellationException) {
    if (!isCompleted) cancel()
    throw ce
  }
}
