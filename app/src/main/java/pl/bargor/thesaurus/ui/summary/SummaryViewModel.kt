package pl.bargor.thesaurus.ui.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.ui.entries.EntryListItem

enum class SummaryPeriodMode { MONTH, YEAR }

data class SummaryUiState(
    val month: YearMonth,
    val year: Year,
    val mode: SummaryPeriodMode = SummaryPeriodMode.MONTH,
    val cards: List<SummaryPeriodCard> = emptyList(),
    val detailCard: SummaryPeriodCard? = null,
    val detailEntries: List<EntryListItem> = emptyList(),
    val isLoading: Boolean = true,
    val syncState: SyncState = SyncState.SYNCED,
    val hasError: Boolean = false,
    val currentYear: Int = year.value,
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    private val taxonomyRepository: TaxonomyRepository,
    private val householdRepository: HouseholdRepository,
    private val clock: Clock,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val currentMonth = YearMonth.now(clock)
    private val initialMonth = runCatching {
        val year = (savedStateHandle.get<Int>("summary.year") ?: currentMonth.year).coerceAtMost(currentMonth.year)
        val month = (savedStateHandle.get<Int>("summary.month") ?: currentMonth.monthValue).coerceIn(1, 12)
        YearMonth.of(year, month).coerceAtMost(currentMonth)
    }.getOrDefault(currentMonth)
    private val mutableState = MutableStateFlow(
        SummaryUiState(
            month = initialMonth, year = Year.of(initialMonth.year),
            mode = (savedStateHandle.get<Any?>("summary.mode") as? String)?.let {
                SummaryPeriodMode.entries.firstOrNull { mode -> mode.name == it }
            } ?: SummaryPeriodMode.MONTH,
            currentYear = currentMonth.year,
        ),
    )
    val state: StateFlow<SummaryUiState> = mutableState.asStateFlow()
    private var identity: Pair<String, String>? = null
    private var observationJob: Job? = null
    private var entries: List<LedgerEntry> = emptyList()
    private var categories: List<Category> = emptyList()
    private var members: List<Member> = emptyList()
    private val subcategories = mutableMapOf<String, List<Subcategory>>()
    private val subcategoryJobs = mutableMapOf<String, Job>()
    private val observations = mutableMapOf<String, SyncObservation<*>>()
    private var entriesObserved = false
    private var entriesLoaded = false
    private var preparedSource: List<LedgerEntry>? = null
    private var preparedToday: LocalDate? = null
    private val preparedCards = mutableMapOf<Pair<SummaryPeriodMode, Int>, List<SummaryPeriodCard>>()
    private var detailKey = decodeKey(savedStateHandle.get<Any?>("summary.detail") as? String)

    init { savePeriod() }

    fun start(householdId: String, actorId: String) {
        val nextIdentity = householdId to actorId
        if (identity == nextIdentity && observationJob?.isActive == true) return
        val changed = identity != nextIdentity
        observationJob?.cancel()
        subcategoryJobs.clear()
        if (changed) {
            if (identity != null || savedStateHandle.get<Any?>("summary.detailHousehold") != householdId) {
                detailKey = null
                saveDetail()
            }
            entries = emptyList(); categories = emptyList(); members = emptyList()
            subcategories.clear(); observations.clear(); entriesObserved = false; entriesLoaded = false
            preparedSource = null; preparedCards.clear()
            mutableState.update { it.copy(cards = emptyList(), detailCard = null, detailEntries = emptyList(),
                isLoading = true, hasError = false, syncState = SyncState.SYNCED) }
        }
        identity = nextIdentity
        observationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            launch {
                ledgerRepository.observeEntries(householdId).withErrors().collect { observation ->
                    observations["entries"] = observation
                    observation.value?.let {
                        entriesLoaded = true
                        if (it != entries) entries = it
                    }
                    entriesObserved = true
                    reconcileSubcategories(householdId)
                    recalculate()
                }
            }
            launch {
                taxonomyRepository.observeCategories(householdId).withErrors().collect { observation ->
                    observations["categories"] = observation
                    observation.value?.let { categories = it.filter { category -> category.householdId == householdId } }
                    reconcileSubcategories(householdId)
                    recalculate()
                }
            }
            launch {
                householdRepository.observeMembers(householdId).withErrors().collect { observation ->
                    observations["members"] = observation
                    observation.value?.let { members = it }
                    recalculate()
                }
            }
        }
        observationJob?.start()
    }

    private fun reconcileSubcategories(householdId: String) {
        val today = LocalDate.now(clock)
        val required = categories.mapTo(mutableSetOf()) { it.id }
        entries.filter { it.householdId == householdId && !it.deleted && !it.date.isAfter(today) }
            .mapTo(required) { it.categoryId }
        // Retrying cancels listeners but preserves their cached values/status. Prune
        // those caches as well, even when the corresponding job is already absent.
        val observedIds = observations.keys.filter { it.startsWith("sub:") }.map { it.removePrefix("sub:") }
        ((subcategoryJobs.keys + subcategories.keys + observedIds) - required).forEach { id ->
            subcategoryJobs.remove(id)?.cancel()
            subcategories.remove(id)
            observations.remove("sub:$id")
        }
        val parent = observationJob ?: return
        required.filterNot { it in subcategoryJobs }.forEach { id ->
            subcategoryJobs[id] = viewModelScope.launch(parent) {
                taxonomyRepository.observeSubcategories(householdId, id).withErrors().collect { observation ->
                    observations["sub:$id"] = observation
                    observation.value?.let { values ->
                        subcategories[id] = values.filter { it.householdId == householdId && it.categoryId == id }
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

    fun selectPeriodMode(mode: SummaryPeriodMode) = updatePeriod { it.copy(mode = mode) }

    private fun changeMonth(delta: Long) {
        updatePeriod { old ->
            val month = old.month.plusMonths(delta).coerceAtMost(YearMonth.now(clock))
            old.copy(month = month, year = Year.of(month.year))
        }
    }

    private fun changeYear(delta: Long) {
        updatePeriod { old ->
            val year = old.year.plusYears(delta).coerceAtMost(Year.now(clock))
            old.copy(year = year, month = YearMonth.of(year.value, old.month.monthValue))
        }
    }

    private fun updatePeriod(transform: (SummaryUiState) -> SummaryUiState) {
        // Publish the new scope together with its cards, never with the previous list.
        recalculate(transform(mutableState.value))
        savePeriod()
    }

    private fun savePeriod() {
        val period = mutableState.value
        savedStateHandle["summary.mode"] = period.mode.name
        savedStateHandle["summary.year"] = period.year.value
        savedStateHandle["summary.month"] = period.month.monthValue
    }

    fun openPeriod(key: SummaryPeriodKey) {
        if (mutableState.value.cards.none { it.key == key }) return
        detailKey = key
        saveDetail()
        recalculate()
    }

    fun closePeriod() {
        detailKey = null
        saveDetail()
        mutableState.update { it.copy(detailCard = null, detailEntries = emptyList()) }
    }

    private fun saveDetail() {
        savedStateHandle["summary.detail"] = detailKey?.let { "${it.mode.name}:${it.year}:${it.month ?: 0}" }
        savedStateHandle["summary.detailHousehold"] = if (detailKey != null) identity?.first else null
    }

    private fun recalculate(period: SummaryUiState = mutableState.value) {
        val (householdId, actorId) = identity ?: run {
            mutableState.value = period
            return
        }
        val today = LocalDate.now(clock)
        if (entries !== preparedSource || today != preparedToday) {
            preparedSource = entries
            preparedToday = today
            preparedCards.clear()
        }
        fun cards(mode: SummaryPeriodMode, year: Int): List<SummaryPeriodCard> =
            preparedCards.getOrPut(mode to if (mode == SummaryPeriodMode.YEAR) 0 else year) {
                prepareSummaryOverview(entries, householdId, Year.of(year), mode, today)
            }
        val categoryById = categories.associateBy { it.id }
        fun bind(card: SummaryPeriodCard) = card.copy(highestExpenseCategory = categoryById[card.highestExpenseCategoryId])
        val old = period
        val overview = if (entriesLoaded) cards(old.mode, old.year.value).map(::bind) else emptyList()
        val detail = if (entriesLoaded) detailKey?.let { key ->
            cards(key.mode, key.year).firstOrNull { it.key == key }?.let(::bind)
        } else null
        // Empty monthly periods remain valid; yearly periods disappear with their last entry.
        if (detailKey != null && detail == null && entriesObserved && observations["entries"]?.value != null) {
            detailKey = null
            saveDetail()
        }
        val authors = members.associateBy { it.uid }
        val items = detail?.entries.orEmpty().map { entry ->
            val category = categoryById[entry.categoryId]
            val subcategory = subcategories[entry.categoryId].orEmpty().firstOrNull { it.id == entry.subcategoryId }
            val author = authors[entry.authorId]
            EntryListItem(entry, category?.name, subcategory?.name,
                author?.displayName?.takeIf { it.isNotBlank() } ?: author?.email ?: entry.authorId,
                entry.authorId == actorId || authors[actorId]?.role == MemberRole.OWNER, category?.color)
        }
        val states = observations.values.map { it.state }
        val sync = when {
            SyncState.ERROR in states -> SyncState.ERROR
            SyncState.PENDING in states -> SyncState.PENDING
            SyncState.OFFLINE in states -> SyncState.OFFLINE
            else -> SyncState.SYNCED
        }
        mutableState.value = old.copy(cards = overview, detailCard = detail, detailEntries = items,
            isLoading = !entriesObserved, syncState = sync, currentYear = today.year,
            hasError = observations.values.any { observation -> observation.error != null || observation.state == SyncState.ERROR })
    }

    private fun decodeKey(encoded: String?): SummaryPeriodKey? = runCatching {
        val parts = encoded?.split(':') ?: return null
        require(parts.size == 3)
        val mode = SummaryPeriodMode.valueOf(parts[0])
        val year = parts[1].toInt()
        val month = parts[2].toInt()
        require(mode != SummaryPeriodMode.YEAR || month == 0)
        val start = if (mode == SummaryPeriodMode.MONTH) YearMonth.of(year, month).atDay(1) else Year.of(year).atDay(1)
        if (start.isAfter(LocalDate.now(clock))) null else SummaryPeriodKey(mode, year, if (mode == SummaryPeriodMode.MONTH) month else null)
    }.getOrNull()
}

private fun <T> Flow<SyncObservation<T>>.withErrors(): Flow<SyncObservation<T>> =
    catch { emit(SyncObservation(state = SyncState.ERROR, error = it)) }
