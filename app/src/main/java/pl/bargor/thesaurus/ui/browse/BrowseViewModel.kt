package pl.bargor.thesaurus.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Year
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryOrder
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SummaryPeriod
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.orderedBy
import pl.bargor.thesaurus.data.model.summaryPeriod
import pl.bargor.thesaurus.ui.entries.EntryListItem
import pl.bargor.thesaurus.ui.summary.SummaryPeriodMode

data class BrowseUiState(
    val month: YearMonth,
    val year: Year,
    val mode: SummaryPeriodMode = SummaryPeriodMode.MONTH,
    val categories: List<BrowseCategoryGroup> = emptyList(),
    val expandedCategoryIds: Set<String> = emptySet(),
    val expandedSubcategories: Set<BrowseSubcategoryKey> = emptySet(),
    val entryItems: Map<String, EntryListItem> = emptyMap(),
    val isLoading: Boolean = true,
    val syncState: SyncState = SyncState.SYNCED,
    val hasError: Boolean = false,
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    private val taxonomyRepository: TaxonomyRepository,
    private val householdRepository: HouseholdRepository,
    clock: Clock,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val currentMonth = YearMonth.now(clock)
    private val initialMonth = runCatching {
        YearMonth.of(savedStateHandle["browse.year"] ?: currentMonth.year,
            savedStateHandle["browse.month"] ?: currentMonth.monthValue)
    }.getOrDefault(currentMonth)
    private val mutableState = MutableStateFlow(BrowseUiState(
        month = initialMonth, year = Year.of(initialMonth.year),
        mode = SummaryPeriodMode.entries.firstOrNull {
            it.name == savedStateHandle.get<String>("browse.mode")
        } ?: SummaryPeriodMode.MONTH,
    ))
    val state: StateFlow<BrowseUiState> = mutableState.asStateFlow()
    private var identity: Pair<String, String>? = null
    private var observationJob: Job? = null
    private var entries: List<LedgerEntry> = emptyList()
    private var entriesSource: List<LedgerEntry>? = null
    private var historicalCategoryIds: Set<String> = emptySet()
    private var categories: List<Category> = emptyList()
    private var categoriesSource: List<Category>? = null
    private var order: CategoryOrder? = null
    private var members: List<Member> = emptyList()
    private val subcategories = mutableMapOf<String, List<Subcategory>>()
    private val subcategorySources = mutableMapOf<String, List<Subcategory>>()
    private var taxonomyRevision = 0L
    private var preparedSource: List<LedgerEntry>? = null
    private var preparedPeriod: SummaryPeriod? = null
    private var prepared: List<BrowseCategoryGroup> = emptyList()
    private var boundPrepared: List<BrowseCategoryGroup>? = null
    private var boundCategories: List<Category>? = null
    private var boundOrder: CategoryOrder? = null
    private var boundTaxonomyRevision = -1L
    private var groups: List<BrowseCategoryGroup> = emptyList()
    private var itemGroups: List<BrowseCategoryGroup>? = null
    private var itemMembers: List<Member>? = null
    private var entryItems: Map<String, EntryListItem> = emptyMap()
    private val subcategoryJobs = mutableMapOf<String, Job>()
    private val observations = mutableMapOf<String, SyncObservation<*>>()
    private var entriesObserved = false

    init { savePeriod() }

    fun start(householdId: String, actorId: String) {
        val nextIdentity = householdId to actorId
        if (identity == nextIdentity && observationJob?.isActive == true) return
        val changed = identity != nextIdentity
        observationJob?.cancel()
        subcategoryJobs.clear()
        identity = nextIdentity
        if (changed) {
            entries = emptyList(); categories = emptyList(); order = null; members = emptyList()
            entriesSource = null; categoriesSource = null
            historicalCategoryIds = emptySet()
            preparedSource = null; preparedPeriod = null; boundPrepared = null
            boundCategories = null; boundOrder = null; boundTaxonomyRevision = -1L
            prepared = emptyList(); groups = emptyList(); itemGroups = null; itemMembers = null
            entryItems = emptyMap(); subcategorySources.clear(); taxonomyRevision = 0L
            subcategories.clear(); observations.clear(); entriesObserved = false
            mutableState.update { it.copy(categories = emptyList(), entryItems = emptyMap(),
                expandedCategoryIds = emptySet(), expandedSubcategories = emptySet(),
                isLoading = true, hasError = false, syncState = SyncState.SYNCED) }
        }
        observationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            launch {
                ledgerRepository.observeEntries(householdId).withErrors().collect { observation ->
                    observations["entries"] = observation
                    observation.value?.let { value ->
                        if (value !== entriesSource && value != entriesSource) {
                            entriesSource = value
                            entries = value.filter { entry -> entry.householdId == householdId }
                            historicalCategoryIds = entries.asSequence().filterNot { it.deleted }
                                .map { it.categoryId }.toSet()
                        }
                    }
                    entriesObserved = true
                    reconcileSubcategories(householdId)
                    recalculate()
                }
            }
            launch {
                taxonomyRepository.observeCategories(householdId).withErrors().collect { observation ->
                    observations["categories"] = observation
                    observation.value?.let { value ->
                        if (value !== categoriesSource && value != categoriesSource) {
                            categoriesSource = value
                            categories = value.filter { category -> category.householdId == householdId }
                        }
                    }
                    reconcileSubcategories(householdId)
                    recalculate()
                }
            }
            launch {
                taxonomyRepository.observeCategoryOrder(householdId, actorId).withErrors().collect { observation ->
                    observations["order"] = observation
                    // A successful absent preference clears an older one; errors retain last good order.
                    if (observation.error == null && observation.state != SyncState.ERROR) {
                        val value = observation.value?.takeIf { it.householdId == householdId && it.userId == actorId }
                        if (value != order) order = value
                    } else observation.value?.let {
                        if (it.householdId == householdId && it.userId == actorId && it != order) order = it
                    }
                    recalculate()
                }
            }
            launch {
                householdRepository.observeMembers(householdId).withErrors().collect { observation ->
                    observations["members"] = observation
                    observation.value?.let { if (it !== members && it != members) members = it }
                    recalculate()
                }
            }
        }
        observationJob?.start()
    }

    /** Each category owns its cache and listener, so regrouping cannot reuse another category's data. */
    private fun reconcileSubcategories(householdId: String) {
        val required = categories.mapTo(mutableSetOf()) { it.id }
        required.addAll(historicalCategoryIds)
        (subcategoryJobs.keys - required).forEach { id ->
            subcategoryJobs.remove(id)?.cancel()
            subcategories.remove(id)
            subcategorySources.remove(id)
            taxonomyRevision++
            observations.remove("sub:$id")
        }
        val parent = observationJob ?: return
        required.filterNot { it in subcategoryJobs }.forEach { id ->
            subcategoryJobs[id] = viewModelScope.launch(parent) {
                taxonomyRepository.observeSubcategories(householdId, id).withErrors().collect { observation ->
                    observations["sub:$id"] = observation
                    observation.value?.let { values ->
                        if (values !== subcategorySources[id] && values != subcategorySources[id]) {
                            subcategorySources[id] = values
                            subcategories[id] = values.filter {
                                it.householdId == householdId && it.categoryId == id
                            }
                            taxonomyRevision++
                        }
                    }
                    recalculate()
                }
            }
        }
    }

    fun retry() {
        val (householdId, actorId) = identity ?: return
        observationJob?.cancel()
        observationJob = null
        start(householdId, actorId)
    }

    fun previousMonth() = changeMonth(-1)
    fun nextMonth() = changeMonth(1)
    fun previousYear() = changeYear(-1)
    fun nextYear() = changeYear(1)
    fun selectPeriodMode(mode: SummaryPeriodMode) = changePeriod { it.copy(mode = mode) }
    private fun changeMonth(delta: Long) = changePeriod {
        val month = it.month.plusMonths(delta)
        it.copy(month = month, year = Year.of(month.year))
    }
    private fun changeYear(delta: Long) = changePeriod {
        val year = it.year.plusYears(delta)
        it.copy(year = year, month = YearMonth.of(year.value, it.month.monthValue))
    }
    private fun changePeriod(transform: (BrowseUiState) -> BrowseUiState) {
        mutableState.update(transform)
        savePeriod()
        recalculate()
    }
    private fun savePeriod() {
        val state = mutableState.value
        savedStateHandle["browse.mode"] = state.mode.name
        savedStateHandle["browse.year"] = state.year.value
        savedStateHandle["browse.month"] = state.month.monthValue
    }

    fun toggleCategory(id: String) = mutableState.update { old ->
        if (old.categories.none { it.categoryId == id }) old else old.copy(
            expandedCategoryIds = old.expandedCategoryIds.toggled(id),
            expandedSubcategories = if (id in old.expandedCategoryIds)
                old.expandedSubcategories.filterNot { it.categoryId == id }.toSet() else old.expandedSubcategories,
        )
    }
    fun toggleSubcategory(key: BrowseSubcategoryKey) = mutableState.update { old ->
        if (key.categoryId !in old.expandedCategoryIds || old.categories
                .none { group -> group.subcategories.any { it.key == key } }) old
        else old.copy(expandedSubcategories = old.expandedSubcategories.toggled(key))
    }

    private fun recalculate() {
        val (householdId, actorId) = identity ?: return
        mutableState.update { old ->
            val period = when (old.mode) {
                SummaryPeriodMode.MONTH -> old.month.summaryPeriod()
                SummaryPeriodMode.YEAR -> old.year.summaryPeriod()
            }
            if (entries !== preparedSource || period != preparedPeriod) {
                preparedSource = entries
                preparedPeriod = period
                prepared = prepareBrowseEntries(entries, householdId, period)
            }
            if (prepared !== boundPrepared || categories !== boundCategories ||
                order !== boundOrder || taxonomyRevision != boundTaxonomyRevision
            ) {
                boundPrepared = prepared
                boundCategories = categories
                boundOrder = order
                boundTaxonomyRevision = taxonomyRevision
                val rebound = bindBrowseTaxonomy(prepared, householdId, categories.orderedBy(order), subcategories)
                // StateFlow suppresses equal states. Keep its published list identity in that case.
                if (rebound != groups) groups = rebound
            }
            val categoryIds = groups.mapTo(mutableSetOf()) { it.categoryId }
            val keys = groups.flatMap { it.subcategories }.mapTo(mutableSetOf()) { it.key }
            if (groups !== itemGroups || members !== itemMembers) {
                itemGroups = groups
                itemMembers = members
                val authors = members.associateBy { it.uid }
                val refreshedItems = buildMap {
                    groups.forEach { group ->
                        group.subcategories.forEach { subgroup ->
                            subgroup.entries.forEach { entry ->
                                val author = authors[entry.authorId]
                                put(entry.id, EntryListItem(
                                    entry, group.category?.name, subgroup.subcategory?.name,
                                    author?.displayName?.takeIf { it.isNotBlank() } ?: author?.email ?: entry.authorId,
                                    entry.authorId == actorId || authors[actorId]?.role == MemberRole.OWNER,
                                    group.category?.color,
                                ))
                            }
                        }
                    }
                }
                if (refreshedItems != entryItems) entryItems = refreshedItems
            }
            val states = observations.values.map { it.state }
            val sync = when {
                SyncState.ERROR in states -> SyncState.ERROR
                SyncState.PENDING in states -> SyncState.PENDING
                SyncState.OFFLINE in states -> SyncState.OFFLINE
                else -> SyncState.SYNCED
            }
            old.copy(categories = groups, entryItems = entryItems,
                expandedCategoryIds = old.expandedCategoryIds.intersect(categoryIds),
                expandedSubcategories = old.expandedSubcategories.intersect(keys),
                isLoading = !entriesObserved, syncState = sync,
                hasError = observations.values.any { it.error != null || it.state == SyncState.ERROR })
        }
    }
}

private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value
private fun <T> Flow<SyncObservation<T>>.withErrors(): Flow<SyncObservation<T>> =
    catch { emit(SyncObservation(state = SyncState.ERROR, error = it)) }
