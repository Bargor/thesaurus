package pl.bargor.thesaurus.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Year
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryOrder
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SummaryPeriod
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.orderedBy
import pl.bargor.thesaurus.data.model.summaryPeriod
import pl.bargor.thesaurus.data.observation.HouseholdObservation
import pl.bargor.thesaurus.data.observation.HouseholdReadModel
import pl.bargor.thesaurus.data.observation.reduceSyncState
import pl.bargor.thesaurus.ui.entries.EntryListItem
import pl.bargor.thesaurus.ui.entries.presentEntry
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
    private var categories: List<Category> = emptyList()
    private var order: CategoryOrder? = null
    private var members: List<Member> = emptyList()
    private val subcategories = mutableMapOf<String, List<Subcategory>>()
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
    private val observations = mutableMapOf<String, SyncObservation<*>>()
    private var readModel: HouseholdReadModel? = null
    private var entriesObserved = false

    init { savePeriod() }

    fun start(householdId: String, actorId: String) {
        val nextIdentity = householdId to actorId
        if (identity == nextIdentity && observationJob?.isActive == true) return
        val changed = identity != nextIdentity
        observationJob?.cancel()
        identity = nextIdentity
        if (changed) {
            readModel = null
            entries = emptyList(); categories = emptyList(); order = null; members = emptyList()
            preparedSource = null; preparedPeriod = null; boundPrepared = null
            boundCategories = null; boundOrder = null; boundTaxonomyRevision = -1L
            prepared = emptyList(); groups = emptyList(); itemGroups = null; itemMembers = null
            entryItems = emptyMap(); taxonomyRevision = 0L
            subcategories.clear(); observations.clear(); entriesObserved = false
            mutableState.update { it.copy(categories = emptyList(), entryItems = emptyMap(),
                expandedCategoryIds = emptySet(), expandedSubcategories = emptySet(),
                isLoading = true, hasError = false, syncState = SyncState.SYNCED) }
        }
        observationJob = viewModelScope.launch {
            HouseholdObservation(ledgerRepository, taxonomyRepository, householdRepository)
                .observe(householdId, actorId, initial = readModel).collect { snapshot ->
                    if (identity != nextIdentity) return@collect
                    readModel = snapshot
                    observations.clear(); observations.putAll(snapshot.observations)
                    snapshot.entries.value?.let { entries = it }
                    snapshot.categories.value?.let { categories = it }
                    order = snapshot.order.value
                    snapshot.members.value?.let { members = it }
                    val nextSubcategories = snapshot.subcategoryValues
                    if (subcategories != nextSubcategories) {
                        subcategories.clear(); subcategories.putAll(nextSubcategories); taxonomyRevision++
                    }
                    entriesObserved = snapshot.entries.observation != null
                    recalculate()
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
                val categoryById = categories.associateBy { it.id }
                val refreshedItems = buildMap {
                    groups.forEach { group ->
                        group.subcategories.forEach { subgroup ->
                            subgroup.entries.forEach { entry ->
                                put(entry.id, presentEntry(entry, categoryById,
                                    subcategories, authors, actorId).listItem(entry))
                            }
                        }
                    }
                }
                if (refreshedItems != entryItems) entryItems = refreshedItems
            }
            val sync = reduceSyncState(observations.values)
            old.copy(categories = groups, entryItems = entryItems,
                expandedCategoryIds = old.expandedCategoryIds.intersect(categoryIds),
                expandedSubcategories = old.expandedSubcategories.intersect(keys),
                isLoading = !entriesObserved, syncState = sync,
                hasError = observations.values.any { it.error != null || it.state == SyncState.ERROR })
        }
    }
}

private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value
