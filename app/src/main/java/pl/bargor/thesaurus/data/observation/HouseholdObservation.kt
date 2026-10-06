package pl.bargor.thesaurus.data.observation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryOrder
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

/** A missing first snapshot differs from a successfully loaded empty collection. */
data class ObservedSource<T>(
    val observation: SyncObservation<T>? = null,
    val value: T? = null,
) {
    val isLoading: Boolean get() = observation == null
    val hasSnapshot: Boolean get() = value != null
    val hasError: Boolean get() = observation?.let { it.error != null || it.state == SyncState.ERROR } == true
    fun accept(next: SyncObservation<T>): ObservedSource<T> = copy(
        observation = next,
        value = next.value?.let { if (it == value) value else it } ?: value,
    )
}

/** Error > queued writes > cache/offline > synced, including errors without an ERROR flag. */
fun reduceSyncState(observations: Iterable<SyncObservation<*>>): SyncState {
    val values = observations.toList()
    return when {
        values.any { it.state == SyncState.ERROR || it.error != null } -> SyncState.ERROR
        values.any { it.state == SyncState.PENDING } -> SyncState.PENDING
        values.any { it.state == SyncState.OFFLINE } -> SyncState.OFFLINE
        else -> SyncState.SYNCED
    }
}

data class ObservationSubscriptions(
    val entries: Boolean = false,
    val members: Boolean = false,
    val order: Boolean = false,
    val household: Boolean = false,
)

data class HouseholdReadModel(
    val householdId: String,
    val actorId: String?,
    val entries: ObservedSource<List<LedgerEntry>> = ObservedSource(),
    val categories: ObservedSource<List<Category>> = ObservedSource(),
    val members: ObservedSource<List<Member>> = ObservedSource(),
    val order: ObservedSource<CategoryOrder> = ObservedSource(),
    val household: ObservedSource<Household> = ObservedSource(),
    val subcategories: Map<String, ObservedSource<List<Subcategory>>> = emptyMap(),
    val subscriptions: ObservationSubscriptions = ObservationSubscriptions(),
) {
    /** Every enabled taxonomy source must emit once; a null/error emission still counts. */
    val isTaxonomyReady: Boolean get() = !categories.isLoading &&
        (!subscriptions.order || !order.isLoading) && subcategories.values.none { it.isLoading }
    val isReady: Boolean get() = isTaxonomyReady &&
        (!subscriptions.entries || !entries.isLoading) &&
        (!subscriptions.members || !members.isLoading) &&
        (!subscriptions.household || !household.isLoading)
    val observations: Map<String, SyncObservation<*>> get() = buildMap {
        this@HouseholdReadModel.entries.observation?.let { put("entries", it) }
        this@HouseholdReadModel.categories.observation?.let { put("categories", it) }
        this@HouseholdReadModel.members.observation?.let { put("members", it) }
        this@HouseholdReadModel.order.observation?.let { put("order", it) }
        this@HouseholdReadModel.household.observation?.let { put("household", it) }
        subcategories.forEach { (id, source) -> source.observation?.let { put("sub:$id", it) } }
    }
    val syncState: SyncState get() = reduceSyncState(observations.values)
    val hasError: Boolean get() = observations.values.any { it.error != null || it.state == SyncState.ERROR }
    val subcategoryValues: Map<String, List<Subcategory>> get() =
        subcategories.mapNotNull { (id, source) -> source.value?.let { id to it } }.toMap()
}

/**
 * Cold by design: each collector owns one listener group for exactly its lifetime.
 * Collect once in a ViewModel scope and expose that ViewModel's StateFlow to UI consumers.
 * Cancellation (including household/actor switches and retry) disposes every child listener.
 * Taxonomy metadata changes update values without restarting unchanged category listeners.
 */
class HouseholdObservation(
    private val ledger: LedgerRepository?,
    private val taxonomy: TaxonomyRepository,
    private val households: HouseholdRepository? = null,
) {
    fun observe(
        householdId: String,
        actorId: String? = null,
        includeHousehold: Boolean = false,
        historicalCategoryIds: Set<String> = emptySet(),
        initial: HouseholdReadModel? = null,
    ): Flow<HouseholdReadModel> = channelFlow {
        val mutex = Mutex()
        val subscriptions = ObservationSubscriptions(ledger != null, households != null,
            actorId != null, includeHousehold && households != null)
        var model = (initial?.takeIf { it.householdId == householdId && it.actorId == actorId &&
            it.subscriptions == subscriptions }
            ?: HouseholdReadModel(householdId, actorId)).copy(subscriptions = subscriptions)
        val jobs = mutableMapOf<String, Job>()
        suspend fun publish(change: (HouseholdReadModel) -> HouseholdReadModel) = mutex.withLock {
            model = change(model)
            val required = model.categories.value.orEmpty().mapTo(mutableSetOf()) { it.id }
            model.entries.value.orEmpty().filterNot { it.deleted }.mapTo(required) { it.categoryId }
            required.addAll(historicalCategoryIds)
            ((jobs.keys + model.subcategories.keys) - required).forEach { id ->
                jobs.remove(id)?.cancel()
                model = model.copy(subcategories = model.subcategories - id)
            }
            required.filterNot { it in jobs }.forEach { id ->
                if (id !in model.subcategories) {
                    model = model.copy(subcategories = model.subcategories + (id to ObservedSource()))
                }
                // Register before collecting even a synchronous flow.
                val job = launch(start = CoroutineStart.LAZY) {
                    taxonomy.observeSubcategories(householdId, id).withObservationErrors().collect { next ->
                        mutex.withLock subcategoryUpdate@ {
                            // A removed listener must not resurrect its cache.
                            if (jobs[id] !== coroutineContext[Job]) return@subcategoryUpdate
                            val filtered = next.copy(value = next.value?.filter {
                                it.householdId == householdId && it.categoryId == id
                            })
                            val source = model.subcategories[id] ?: ObservedSource()
                            model = model.copy(subcategories = model.subcategories + (id to source.accept(filtered)))
                            send(model)
                        }
                    }
                }
                jobs[id] = job
                job.start()
            }
            send(model)
        }
        send(model)
        ledger?.let { repository -> launch {
            repository.observeEntries(householdId).withObservationErrors().collect { next ->
                publish { it.copy(entries = it.entries.accept(next.copy(
                    value = next.value?.filter { entry -> entry.householdId == householdId },
                ))) }
            }
        } }
        launch {
            taxonomy.observeCategories(householdId).withObservationErrors().collect { next ->
                publish { it.copy(categories = it.categories.accept(next.copy(
                    value = next.value?.filter { category -> category.householdId == householdId },
                ))) }
            }
        }
        actorId?.let { actor -> launch {
            taxonomy.observeCategoryOrder(householdId, actor).withObservationErrors().collect { next ->
                publish {
                    val filtered = next.copy(value = next.value?.takeIf { order ->
                        order.householdId == householdId && order.userId == actor
                    })
                    // A successful absent preference clears a previously loaded order.
                    val source = if (next.error == null && next.state != SyncState.ERROR)
                        ObservedSource(filtered, if (filtered.value == it.order.value) it.order.value else filtered.value)
                    else it.order.accept(filtered)
                    it.copy(order = source)
                }
            }
        } }
        households?.let { repository ->
            launch { repository.observeMembers(householdId).withObservationErrors().collect { next ->
                publish { it.copy(members = it.members.accept(next)) }
            } }
            if (includeHousehold) launch {
                repository.observeHousehold(householdId).withObservationErrors().collect { next ->
                    publish { it.copy(household = it.household.accept(next.copy(
                        value = next.value?.takeIf { household -> household.id == householdId },
                    ))) }
                }
            }
        }
        awaitCancellation()
    }
}

fun <T> Flow<SyncObservation<T>>.withObservationErrors(): Flow<SyncObservation<T>> =
    catch { emit(SyncObservation(state = SyncState.ERROR, error = it)) }
