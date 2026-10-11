package pl.bargor.thesaurus.ui.backupjson

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import pl.bargor.thesaurus.data.transfer.*

enum class JsonBackupPhase { EMPTY, PREPARING, PREVIEW, IMPORTING, COMPLETE }

data class JsonBackupUiState(
    val phase: JsonBackupPhase = JsonBackupPhase.EMPTY,
    val busy: Boolean = false,
    val report: JsonMergeReport? = null,
    val processed: Int = 0,
    val total: Int = 0,
    val errorCode: String? = null,
    val exportedArtifact: JsonBackupExportArtifact? = null,
    val canRetry: Boolean = false,
)

/** Caller retains this controller in a ViewModel and binds it to its selected household.
 * Source bytes are private memory only: never put them in a Bundle/SavedStateHandle. */
class JsonBackupController(private val coordinator: JsonBackupCoordinator) {
    private val mutableState = MutableStateFlow(JsonBackupUiState())
    val state: StateFlow<JsonBackupUiState> = mutableState.asStateFlow()
    private var source: ByteArray? = null
    private var householdId: String? = null
    private var session: JsonMergeSession? = null

    suspend fun prepare(householdId: String, bytes: ByteArray) {
        if (state.value.busy) return
        this.householdId = householdId
        source = bytes.copyOf()
        prepareRetained()
    }

    /** Retry always prepares a fresh read-only preview; a second confirmation is required. */
    suspend fun retry() {
        if (state.value.busy || source == null) return
        prepareRetained()
    }

    private suspend fun prepareRetained() {
        session = null
        mutableState.value = JsonBackupUiState(phase = JsonBackupPhase.PREPARING, busy = true)
        try {
            val prepared = withContext(Dispatchers.Default) { coordinator.prepare(requireNotNull(householdId), requireNotNull(source)) }
            session = prepared
            val report = prepared.report
            mutableState.value = JsonBackupUiState(JsonBackupPhase.PREVIEW, report = report, total = report.newCount, canRetry = true)
        } catch (cancelled: CancellationException) {
            mutableState.value = state.value.copy(busy = false, phase = JsonBackupPhase.EMPTY, canRetry = true)
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = JsonBackupUiState(errorCode = safeCode(failure), canRetry = true)
        }
    }

    suspend fun confirm() {
        val prepared = session ?: return
        if (state.value.busy || state.value.phase != JsonBackupPhase.PREVIEW || state.value.total == 0) return
        mutableState.value = state.value.copy(phase = JsonBackupPhase.IMPORTING, busy = true, processed = 0, errorCode = null)
        try {
            val report = withContext(Dispatchers.Default) {
                coordinator.commit(prepared) {
                    // Keep progress scalar: no copying the complete report for each item.
                    mutableState.value = state.value.copy(processed = state.value.processed + 1)
                }
            }
            mutableState.value = state.value.copy(phase = JsonBackupPhase.COMPLETE, busy = false, report = report,
                errorCode = if (report.errorCount > 0 || report.newCount > 0) "JSON_IMPORT_WRITE_FAILED" else null)
        } catch (cancelled: CancellationException) {
            mutableState.value = state.value.copy(phase = JsonBackupPhase.COMPLETE, busy = false, report = prepared.report,
                errorCode = "JSON_IMPORT_WRITE_FAILED")
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = state.value.copy(phase = JsonBackupPhase.COMPLETE, busy = false, report = prepared.report, errorCode = safeCode(failure))
        }
    }

    /** The leaf hands the validated whole artifact to its caller; #114 owns the picker. */
    suspend fun export(householdId: String, exportedAt: Instant = Instant.now(), appVersion: String? = null): JsonBackupExportArtifact? {
        if (state.value.busy) return null
        mutableState.value = state.value.copy(busy = true, errorCode = null, exportedArtifact = null)
        return try {
            val artifact = withContext(Dispatchers.Default) { coordinator.export(householdId, exportedAt, appVersion) }
            mutableState.value = state.value.copy(busy = false, exportedArtifact = artifact)
            artifact
        } catch (cancelled: CancellationException) {
            mutableState.value = state.value.copy(busy = false)
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = state.value.copy(busy = false, errorCode = safeCode(failure))
            null
        }
    }

    private fun safeCode(failure: Exception): String = when (failure) {
        is JsonBackupException -> failure.code
        is BackupContractException -> failure.code.name
        else -> "JSON_OPERATION_FAILED"
    }
}
