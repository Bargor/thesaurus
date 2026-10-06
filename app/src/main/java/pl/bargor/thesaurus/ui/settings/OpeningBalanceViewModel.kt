package pl.bargor.thesaurus.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import pl.bargor.thesaurus.data.model.PlnMoney
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.OpeningBalanceRepository
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.parseSignedPlnGrosze

enum class OpeningBalanceMode { OPENING, CURRENT }
enum class OpeningBalanceError { LOAD, INVALID_AMOUNT, SAVE, CURRENT_REQUIRES_SERVER }

data class OpeningBalanceUiState(
    val householdId: String? = null,
    val actorId: String? = null,
    val loading: Boolean = true,
    val isOwner: Boolean = false,
    val amount: String = "",
    val mode: OpeningBalanceMode = OpeningBalanceMode.OPENING,
    val openingBalanceGrosze: Long? = null,
    val syncState: SyncState = SyncState.SYNCED,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val queuedOffline: Boolean = false,
    val error: OpeningBalanceError? = null,
)

@HiltViewModel
class OpeningBalanceViewModel @Inject constructor(
    private val householdRepository: HouseholdRepository,
    private val openingBalanceRepository: OpeningBalanceRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OpeningBalanceUiState())
    val state = mutableState.asStateFlow()
    private var identity: Pair<String, String>? = null
    private var generation = 0L
    private var observationJob: Job? = null
    private var saveJob: Job? = null
    private var draftInitialized = false
    private var pendingOpening: Long? = null
    private var knownHousehold: Household? = null

    fun start(householdId: String, actorId: String) {
        if (identity == (householdId to actorId) && observationJob?.isActive == true) return
        observationJob?.cancel()
        saveJob?.cancel()
        val request = ++generation
        if (identity != (householdId to actorId)) knownHousehold = null
        identity = householdId to actorId
        val restore = savedStateHandle.get<String>("balance.household") == householdId &&
            savedStateHandle.get<String>("balance.actor") == actorId &&
            savedStateHandle.get<Boolean>("balance.hasDraft") == true
        draftInitialized = restore
        val restoredQueued = restore && savedStateHandle.get<Boolean>("balance.queuedOffline") == true
        pendingOpening = if (restoredQueued) savedStateHandle["balance.pendingOpening"] else null
        mutableState.value = OpeningBalanceUiState(
            householdId = householdId, actorId = actorId,
            amount = if (restore) savedStateHandle["balance.amount"] ?: "" else "",
            mode = if (restore) OpeningBalanceMode.entries.firstOrNull {
                it.name == savedStateHandle.get<String>("balance.mode")
            } ?: OpeningBalanceMode.OPENING else OpeningBalanceMode.OPENING,
            saved = restore && savedStateHandle.get<Boolean>("balance.saved") == true,
            queuedOffline = restoredQueued,
        )
        persist()
        observationJob = viewModelScope.launch {
            try {
                householdRepository.observeHousehold(householdId).collect { observation ->
                    if (request != generation) return@collect
                    val missing = observation.state == SyncState.SYNCED && observation.value == null
                    val foreign = observation.value?.id?.let { it != householdId } == true
                    val failed = observation.error != null || observation.state == SyncState.ERROR || missing || foreign
                    if (failed) knownHousehold = null
                    else if (observation.value != null) knownHousehold = observation.value
                    val household = if (failed) null else observation.value ?: knownHousehold?.takeIf {
                        observation.state == SyncState.OFFLINE || observation.state == SyncState.PENDING
                    }
                    val matchesPending = !failed && pendingOpening != null && observation.value?.openingBalanceGrosze == pendingOpening &&
                        (observation.state == SyncState.PENDING || mutableState.value.queuedOffline)
                    val firstAmount = if (!draftInitialized && household != null) {
                        draftInitialized = true
                        PlnMoney.balanceInput(household.openingBalanceGrosze)
                    } else mutableState.value.amount
                    if (matchesPending && observation.state == SyncState.SYNCED) pendingOpening = null
                    if (failed) pendingOpening = null
                    mutableState.update { old ->
                        old.copy(
                            loading = !failed && household == null,
                            isOwner = !failed && household?.ownerId == actorId,
                            openingBalanceGrosze = household?.openingBalanceGrosze,
                            amount = firstAmount,
                            syncState = observation.state,
                            saving = if (matchesPending || failed) false else old.saving,
                            saved = if (matchesPending) true else if (failed) false else old.saved,
                            queuedOffline = if (matchesPending) observation.state != SyncState.SYNCED
                                else if (failed) false else old.queuedOffline,
                            error = if (failed) OpeningBalanceError.LOAD
                                else old.error?.takeUnless { it == OpeningBalanceError.LOAD },
                        )
                    }
                    persist()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (request == generation) {
                    knownHousehold = null
                    mutableState.update {
                        it.copy(loading = false, isOwner = false, saving = false, error = OpeningBalanceError.LOAD)
                    }
                }
            }
        }
    }

    fun changeAmount(amount: String) {
        if (mutableState.value.saving || mutableState.value.saved) return
        draftInitialized = true
        mutableState.update { it.copy(amount = amount, error = null) }
        persist()
    }

    fun changeMode(mode: OpeningBalanceMode) {
        if (mutableState.value.saving || mutableState.value.saved) return
        mutableState.update { it.copy(mode = mode, error = null) }
        persist()
    }

    fun save() {
        val old = mutableState.value
        val currentIdentity = identity ?: return
        if (old.loading || !old.isOwner || old.saving || old.saved) return
        val amount = runCatching { parseSignedPlnGrosze(old.amount) }.getOrNull()
        if (amount == null) {
            mutableState.update { it.copy(error = OpeningBalanceError.INVALID_AMOUNT) }
            return
        }
        if (old.mode == OpeningBalanceMode.CURRENT && old.syncState != SyncState.SYNCED) {
            mutableState.update { it.copy(error = OpeningBalanceError.CURRENT_REQUIRES_SERVER) }
            return
        }
        val request = generation
        pendingOpening = if (old.mode == OpeningBalanceMode.OPENING) amount else null
        mutableState.update { it.copy(saving = true, error = null, queuedOffline = false) }
        persist()
        saveJob = viewModelScope.launch {
            try {
                if (old.mode == OpeningBalanceMode.OPENING) {
                    openingBalanceRepository.saveOpeningBalance(currentIdentity.first, amount)
                } else {
                    openingBalanceRepository.saveCurrentBalance(currentIdentity.first, amount)
                }
                if (request != generation) return@launch
                pendingOpening = null
                mutableState.update { it.copy(saving = false, saved = true, queuedOffline = false, error = null) }
                persist()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (request != generation) return@launch
                pendingOpening = null
                mutableState.update { it.copy(saving = false, saved = false, queuedOffline = false,
                    error = OpeningBalanceError.SAVE) }
                persist()
            }
        }
    }

    fun retry() {
        val current = identity ?: return
        observationJob?.cancel()
        observationJob = null
        start(current.first, current.second)
    }

    private fun persist() {
        val current = mutableState.value
        savedStateHandle["balance.household"] = current.householdId
        savedStateHandle["balance.actor"] = current.actorId
        savedStateHandle["balance.hasDraft"] = draftInitialized
        savedStateHandle["balance.amount"] = current.amount
        savedStateHandle["balance.mode"] = current.mode.name
        savedStateHandle["balance.pendingOpening"] = pendingOpening
        savedStateHandle["balance.saved"] = current.saved
        savedStateHandle["balance.queuedOffline"] = current.queuedOffline
    }
}
