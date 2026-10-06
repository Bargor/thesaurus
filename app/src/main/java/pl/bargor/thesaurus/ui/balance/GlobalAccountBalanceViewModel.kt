package pl.bargor.thesaurus.ui.balance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.absoluteAccountBalance
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.observation.reduceSyncState

data class GlobalAccountBalanceUiState(
    val actorId: String? = null,
    val householdId: String? = null,
    val amountGrosze: BigInteger? = null,
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val syncState: SyncState = SyncState.SYNCED,
)

/** All active entries count, including dates outside the current screen's period. */
fun globalAccountBalance(entries: List<LedgerEntry>, householdId: String, openingBalanceGrosze: Long = 0L): BigInteger =
    absoluteAccountBalance(openingBalanceGrosze, entries.filter { it.householdId == householdId })

@HiltViewModel
class GlobalAccountBalanceViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    private val householdRepository: HouseholdRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GlobalAccountBalanceUiState())
    val state: StateFlow<GlobalAccountBalanceUiState> = mutableState.asStateFlow()
    private var identity: Pair<String, String>? = null
    private var observationJob: Job? = null
    private var generation = 0L

    fun start(householdId: String, actorId: String) {
        val nextIdentity = householdId to actorId
        if (identity == nextIdentity && observationJob?.isActive == true) return
        observationJob?.cancel()
        val request = ++generation
        identity = nextIdentity
        // Clear immediately, before the new listener can publish any data.
        mutableState.value = GlobalAccountBalanceUiState(actorId = actorId, householdId = householdId)
        observationJob = viewModelScope.launch {
            var knownEntries: List<LedgerEntry>? = null
            var knownHousehold: Household? = null
            try {
                combine(ledgerRepository.observeEntries(householdId),
                    householdRepository.observeHousehold(householdId)) { entries, household -> entries to household }
                    .collect { (observation, household) ->
                    if (generation != request) return@collect
                    val failed = observation.state == SyncState.ERROR || observation.error != null ||
                        household.state == SyncState.ERROR || household.error != null ||
                        household.value?.id?.let { it != householdId } == true ||
                        (household.state == SyncState.SYNCED && household.value == null)
                    if (failed) {
                        knownEntries = null; knownHousehold = null
                    } else {
                        observation.value?.let { knownEntries = it }
                        household.value?.let { knownHousehold = it }
                    }
                    val amount = if (failed) null else knownEntries?.let { entries ->
                        knownHousehold?.let { settings -> globalAccountBalance(entries, householdId, settings.openingBalanceGrosze) }
                    }
                    mutableState.value = GlobalAccountBalanceUiState(
                        actorId = actorId,
                        householdId = householdId,
                        // A listener error must never present a stale number as current.
                        amountGrosze = amount,
                        isLoading = !failed && amount == null,
                        hasError = failed,
                        syncState = if (failed) SyncState.ERROR else reduceSyncState(listOf(observation, household)),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == request) {
                    mutableState.value = GlobalAccountBalanceUiState(
                        actorId = actorId, householdId = householdId,
                        isLoading = false, hasError = true, syncState = SyncState.ERROR,
                    )
                }
            }
        }
    }

    fun retry() {
        val current = identity ?: return
        observationJob?.cancel()
        observationJob = null
        start(current.first, current.second)
    }

    fun stop() {
        ++generation
        observationJob?.cancel()
        observationJob = null
        identity = null
        mutableState.value = GlobalAccountBalanceUiState()
    }
}
