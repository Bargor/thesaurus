package pl.bargor.thesaurus.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigInteger
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.firebase.observeOrderedCategories
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.normalizeTags

data class EntryCategory(
    val category: Category,
    val subcategories: List<Subcategory>,
)

data class EntryFormUiState(
    val isLoading: Boolean = true,
    val categories: List<EntryCategory> = emptyList(),
    val amount: String = "",
    val date: LocalDate = LocalDate.now(),
    val title: String = "",
    val tags: String = "",
    val categoryId: String? = null,
    val subcategoryId: String? = null,
    val type: EntryType = EntryType.EXPENSE,
    val syncState: SyncState = SyncState.SYNCED,
    val error: EntryFormError? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** The entry is in Firestore's local queue and will be sent when connectivity returns. */
    val queuedOffline: Boolean = false,
    /** Null for creation; an existing id means this form updates that immutable record. */
    val editingEntryId: String? = null,
)

sealed interface EntryFormError {
    data object InvalidAmount : EntryFormError
    data object CategoryRequired : EntryFormError
    data object FutureDate : EntryFormError
    data object InvalidTitle : EntryFormError
    data object InvalidTags : EntryFormError
    data object InactiveTaxonomy : EntryFormError
    data object SaveFailed : EntryFormError
}

/**
 * Input belongs to the form, whereas [LedgerEntry] is already a signed, persisted record.
 * Decimal point is intentionally accepted alongside the Polish decimal comma for keyboard users.
 */
object EntryFormValidation {
    const val MAX_TITLE_LENGTH = 160

    fun parseMagnitudeGrosze(raw: String): Long? {
        val value = raw.trim()
        val match = AMOUNT.matchEntire(value) ?: return null
        val whole = match.groupValues[1].toLongOrNull() ?: return null
        val decimal = match.groupValues.getOrNull(3).orEmpty()
        val fraction = when (decimal.length) {
            0 -> 0L
            1 -> decimal.toLong() * 10L
            2 -> decimal.toLong()
            else -> return null
        }
        return runCatching { Math.addExact(Math.multiplyExact(whole, 100L), fraction) }
            .getOrNull()
            ?.takeIf { it > 0L }
    }

    fun titleOrNull(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    fun validTitle(raw: String): Boolean = raw.trim().length <= MAX_TITLE_LENGTH

    fun normalizedTags(raw: String): List<String>? = runCatching {
        val rawUniqueCount = raw.split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.lowercase(Locale.ROOT) }
            .distinct()
            .count()
        require(rawUniqueCount <= 10)
        normalizeTags(raw.split(','))
    }.getOrNull()

    fun signedAmount(magnitudeGrosze: Long, type: EntryType): Long = when (type) {
        EntryType.INCOME -> magnitudeGrosze
        EntryType.EXPENSE -> -magnitudeGrosze
    }

    private val AMOUNT = Regex("^(\\d+)([,.](\\d{1,2}))?$")
}

@HiltViewModel
class EntryFormViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    private val taxonomyRepository: TaxonomyRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EntryFormUiState())
    val state: StateFlow<EntryFormUiState> = mutableState.asStateFlow()

    private var context: Triple<String, String, String?>? = null
    private var pendingEntryId: String? = null
    private var editingEntry: LedgerEntry? = null
    private var draftInitialized = false
    private var entryObservationJob: Job? = null
    private var taxonomyObservationJob: Job? = null

    fun start(householdId: String, actorId: String, today: LocalDate) =
        start(householdId, actorId, entryId = null, today = today)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(
        householdId: String,
        actorId: String,
        entryId: String? = null,
        today: LocalDate = LocalDate.now(),
    ) {
        if (context == Triple(householdId, actorId, entryId)) return
        entryObservationJob?.cancel()
        taxonomyObservationJob?.cancel()
        val restoreDraft = savedStateHandle.get<String>("entry.household") == householdId &&
            savedStateHandle.get<String>("entry.actor") == actorId &&
            savedStateHandle.get<String>("entry.editingId") == entryId &&
            savedStateHandle.get<Boolean>("entry.hasDraft") == true
        context = Triple(householdId, actorId, entryId)
        editingEntry = null
        pendingEntryId = if (restoreDraft) savedStateHandle["entry.pendingId"] else null
        draftInitialized = restoreDraft || entryId == null
        mutableState.value = if (restoreDraft) {
            EntryFormUiState(
                date = savedStateHandle.get<String>("entry.date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today,
                editingEntryId = entryId,
                amount = savedStateHandle["entry.amount"] ?: "",
                title = savedStateHandle["entry.title"] ?: "",
                tags = savedStateHandle["entry.tags"] ?: "",
                categoryId = savedStateHandle["entry.categoryId"],
                subcategoryId = savedStateHandle["entry.subcategoryId"],
                type = EntryType.entries.firstOrNull { it.name == savedStateHandle.get<String>("entry.type") } ?: EntryType.EXPENSE,
                saved = savedStateHandle["entry.saved"] ?: false,
                queuedOffline = savedStateHandle["entry.queuedOffline"] ?: false,
            )
        } else EntryFormUiState(date = today, editingEntryId = entryId)
        persistDraft()
        entryObservationJob = viewModelScope.launch {
            ledgerRepository.observeEntries(householdId).collect { observation ->
                val stored = entryId?.let { requestedId ->
                    observation.value.orEmpty().firstOrNull { it.id == requestedId }
                }
                val pendingId = pendingEntryId
                mutableState.update { old ->
                    // An edit already exists under this ID before its write begins. Only a
                    // snapshot of this draft acknowledges a restored pending write.
                    val pendingVisible = pendingId?.let { id ->
                        observation.value.orEmpty().any { it.id == id && it.updatedById == actorId && it.matchesDraft(old) }
                    } == true
                    val becameUnavailable = entryId != null && editingEntry != null && stored == null && observation.error == null
                    if (becameUnavailable) editingEntry = null
                    val loaded = stored?.takeIf { editingEntry?.id != it.id }
                    if (loaded != null) {
                        editingEntry = loaded
                        draftInitialized = true
                    }
                    val populated = if (loaded != null && !restoreDraft) {
                        old.copy(
                            amount = magnitudeForForm(loaded.amountGrosze),
                            date = loaded.date,
                            title = loaded.normalizedTitle.orEmpty(),
                            tags = loaded.normalizedTags.joinToString(", "),
                            categoryId = loaded.categoryId,
                            subcategoryId = loaded.subcategoryId,
                            type = loaded.type,
                        )
                    } else old
                    when {
                        observation.error != null && pendingId != null -> {
                            pendingEntryId = null
                            populated.copy(saving = false, saved = false, queuedOffline = false, error = EntryFormError.SaveFailed)
                        }
                        pendingVisible -> {
                            val queued = observation.state != SyncState.SYNCED
                            if (!queued) pendingEntryId = null
                            populated.copy(
                                saving = false,
                                saved = true,
                                queuedOffline = queued,
                                syncState = observation.state,
                                error = null,
                            )
                        }
                        else -> populated.copy(
                            syncState = observation.state,
                            error = if (becameUnavailable) EntryFormError.SaveFailed else populated.error,
                        )
                    }
                }
                persistDraft()
            }
        }
        taxonomyObservationJob = viewModelScope.launch {
            taxonomyRepository.observeOrderedCategories(householdId, actorId)
                .flatMapLatest { categoryObservation ->
                    val categories = categoryObservation.value.orEmpty()
                    val subcategoryObservations = categories.map { category ->
                        taxonomyRepository.observeSubcategories(householdId, category.id)
                    }
                    if (subcategoryObservations.isEmpty()) {
                        flowOf(EntryTaxonomySnapshot(categories, emptyMap(), categoryObservation, categoryObservation.value != null))
                    } else {
                        combine(subcategoryObservations) { observations ->
                            EntryTaxonomySnapshot(
                                categories = categories,
                                subcategories = categories.indices.mapNotNull { index ->
                                    observations[index].value?.let { categories[index].id to it }
                                }.toMap(),
                                observation = SyncObservation<Unit>(
                                    state = relevantSyncState(categoryObservation.state, observations.map { it.state }),
                                    error = categoryObservation.error ?: observations.firstNotNullOfOrNull { it.error },
                                ),
                                hasCategorySnapshot = categoryObservation.value != null,
                            )
                        }
                    }
                }
                .collect { snapshot ->
                    mutableState.update { old ->
                        // Retain archived values in state so an entry loaded after this taxonomy emission
                        // can still preserve its historical selection. The screen only exposes active values
                        // plus the current historical selection.
                        // A listener may initially emit an empty loading snapshot; do not erase
                        // restored IDs before the locally cached taxonomy has been delivered.
                        if (!snapshot.hasCategorySnapshot) {
                            return@update old.copy(
                                isLoading = old.isLoading && snapshot.observation.error == null,
                                syncState = snapshot.observation.state,
                                error = snapshot.observation.error?.let { EntryFormError.SaveFailed } ?: old.error,
                            )
                        }
                        val available = snapshot.categories
                        val subcategories = available.associate { category ->
                            category.id to (snapshot.subcategories[category.id]
                                ?: old.categories.firstOrNull { it.category.id == category.id }?.subcategories.orEmpty())
                        }
                        val selected = old.categoryId?.let { id -> available.firstOrNull { it.id == id } }
                        val categoryId = selected?.id
                        val validSubcategory = categoryId?.let { id ->
                            // A failed/not-yet-loaded listener cannot invalidate a restored ID.
                            id !in snapshot.subcategories || subcategories[id].orEmpty().any { it.id == old.subcategoryId }
                        } == true
                        old.copy(
                            isLoading = false,
                            categories = available.map { category ->
                                EntryCategory(category, subcategories[category.id].orEmpty())
                            },
                            categoryId = categoryId,
                            subcategoryId = old.subcategoryId.takeIf { validSubcategory },
                            syncState = snapshot.observation.state,
                            error = snapshot.observation.error?.let { EntryFormError.SaveFailed } ?: old.error,
                        )
                    }
                    persistDraft()
                }
        }
    }

    fun updateAmount(value: String) = update { copy(amount = value, error = null, saved = false, queuedOffline = false) }
    fun updateTitle(value: String) = update { copy(title = value, error = null, saved = false, queuedOffline = false) }
    fun updateTags(value: String) = update { copy(tags = value, error = null, saved = false, queuedOffline = false) }
    fun updateDate(value: LocalDate) = update { copy(date = value, error = null, saved = false, queuedOffline = false) }
    fun updateType(value: EntryType) = update { copy(type = value, error = null, saved = false, queuedOffline = false) }

    fun selectCategory(categoryId: String) {
        val category = state.value.categories.firstOrNull { it.category.id == categoryId && !it.category.archived } ?: return
        if (state.value.categoryId == categoryId) return
        update {
            copy(
                categoryId = categoryId,
                subcategoryId = null,
                type = category.category.defaultEntryType,
                error = null,
                saved = false,
                queuedOffline = false,
            )
        }
    }

    fun selectSubcategory(subcategoryId: String?) {
        val selectedCategory = state.value.categories.firstOrNull { it.category.id == state.value.categoryId }
        if (selectedCategory == null) return
        if (subcategoryId != null && selectedCategory.subcategories.none { it.id == subcategoryId && !it.archived }) return
        update { copy(subcategoryId = subcategoryId, error = null, saved = false, queuedOffline = false) }
    }

    fun save(today: LocalDate = LocalDate.now()) {
        val (householdId, actorId) = context ?: return
        // An edit route is never a create route. In particular, a stale local edit must not turn a
        // remotely tombstoned/missing document into a new active entry under a different id.
        if (state.value.editingEntryId != null && editingEntry == null) {
            update { copy(error = EntryFormError.SaveFailed) }
            return
        }
        val current = state.value
        if (current.saving || current.saved) return
        val magnitude = EntryFormValidation.parseMagnitudeGrosze(current.amount)
        val error = when {
            magnitude == null -> EntryFormError.InvalidAmount
            current.date.isAfter(today) -> EntryFormError.FutureDate
            !EntryFormValidation.validTitle(current.title) -> EntryFormError.InvalidTitle
            EntryFormValidation.normalizedTags(current.tags) == null -> EntryFormError.InvalidTags
            current.categoryId == null -> EntryFormError.CategoryRequired
            current.categories.none { it.category.id == current.categoryId } -> EntryFormError.InactiveTaxonomy
            current.subcategoryId != null && current.categories.first { it.category.id == current.categoryId }
                .subcategories.none { it.id == current.subcategoryId } -> EntryFormError.InactiveTaxonomy
            else -> null
        }
        if (error != null) {
            update { copy(error = error) }
            return
        }
        viewModelScope.launch {
            update { copy(saving = true, error = null) }
            val existing = editingEntry
            val entry = LedgerEntry(
                // A process may stop after the local save starts but before its listener responds.
                // Retrying that restored draft must write the same record rather than a duplicate.
                id = existing?.id ?: pendingEntryId ?: UUID.randomUUID().toString(),
                householdId = householdId,
                amountGrosze = EntryFormValidation.signedAmount(magnitude!!, current.type),
                date = current.date,
                title = EntryFormValidation.titleOrNull(current.title),
                categoryId = current.categoryId!!,
                subcategoryId = current.subcategoryId,
                tags = EntryFormValidation.normalizedTags(current.tags).orEmpty(),
                authorId = existing?.authorId ?: actorId,
                updatedById = actorId,
                createdAt = existing?.createdAt,
            )
            pendingEntryId = entry.id
            persistDraft()
            // Firestore's task intentionally remains unfinished while offline. The entry listener above
            // releases the form as soon as the locally persisted pending snapshot contains this exact id.
            runCatching { ledgerRepository.save(entry) }
                .onFailure {
                    if (pendingEntryId == entry.id) {
                        pendingEntryId = null
                        update {
                            copy(saving = false, saved = false, queuedOffline = false, error = EntryFormError.SaveFailed)
                        }
                    }
                }
        }
    }

    private fun update(transform: EntryFormUiState.() -> EntryFormUiState) {
        mutableState.update(transform)
        draftInitialized = true
        persistDraft()
    }

    private fun persistDraft() {
        if (!draftInitialized) return
        val (householdId, actorId, entryId) = context ?: return
        val draft = mutableState.value
        savedStateHandle["entry.household"] = householdId
        savedStateHandle["entry.actor"] = actorId
        savedStateHandle["entry.editingId"] = entryId
        savedStateHandle["entry.amount"] = draft.amount
        savedStateHandle["entry.title"] = draft.title
        savedStateHandle["entry.tags"] = draft.tags
        savedStateHandle["entry.date"] = draft.date.toString()
        savedStateHandle["entry.categoryId"] = draft.categoryId
        savedStateHandle["entry.subcategoryId"] = draft.subcategoryId
        savedStateHandle["entry.type"] = draft.type.name
        savedStateHandle["entry.pendingId"] = pendingEntryId
        savedStateHandle["entry.saved"] = draft.saved
        savedStateHandle["entry.queuedOffline"] = draft.queuedOffline
        savedStateHandle["entry.hasDraft"] = true
    }
}

private fun LedgerEntry.matchesDraft(draft: EntryFormUiState): Boolean {
    val magnitude = EntryFormValidation.parseMagnitudeGrosze(draft.amount) ?: return false
    val draftTags = EntryFormValidation.normalizedTags(draft.tags) ?: return false
    return !deleted && amountGrosze == EntryFormValidation.signedAmount(magnitude, draft.type) &&
        date == draft.date && categoryId == draft.categoryId && subcategoryId == draft.subcategoryId &&
        normalizedTitle == EntryFormValidation.titleOrNull(draft.title) && normalizedTags == draftTags
}

/** Absolute value avoids exposing a persisted sign as editable input. */
private fun magnitudeForForm(amountGrosze: Long): String {
    val magnitude = BigInteger.valueOf(amountGrosze).abs()
    val (whole, fraction) = magnitude.divideAndRemainder(BigInteger.valueOf(100))
    return if (fraction == BigInteger.ZERO) {
        whole.toString()
    } else {
        "%d,%02d".format(Locale.ROOT, whole, fraction)
    }
}

private data class EntryTaxonomySnapshot(
    val categories: List<Category>,
    val subcategories: Map<String, List<Subcategory>>,
    val observation: SyncObservation<*>,
    val hasCategorySnapshot: Boolean,
)

private fun relevantSyncState(categoryState: SyncState, subcategoryStates: List<SyncState>): SyncState = when {
    categoryState == SyncState.ERROR || SyncState.ERROR in subcategoryStates -> SyncState.ERROR
    categoryState == SyncState.PENDING || SyncState.PENDING in subcategoryStates -> SyncState.PENDING
    categoryState == SyncState.OFFLINE || SyncState.OFFLINE in subcategoryStates -> SyncState.OFFLINE
    else -> SyncState.SYNCED
}
