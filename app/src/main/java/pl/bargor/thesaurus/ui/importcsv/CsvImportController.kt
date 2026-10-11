package pl.bargor.thesaurus.ui.importcsv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.bargor.thesaurus.data.transfer.*

/** Explicit commands own writes. Construction, binding and restore never import a row. */
class CsvImportController(
    private val coordinator: CsvImportCoordinator,
    private val checkpoints: CsvImportCheckpointStore,
) {
    private val mutableState = MutableStateFlow(CsvImportUiState())
    val state: StateFlow<CsvImportUiState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var session: CsvImportSession? = null
    private var checkpoint: CsvImportCheckpoint? = null
    private var token: String? = null
    private val unacknowledgedDraftRows = mutableSetOf<Int>()
    private val attentionStatuses = setOf(CsvImportRowStatus.INVALID, CsvImportRowStatus.SERVER_ERROR, CsvImportRowStatus.PENDING)

    suspend fun reset() = mutex.withLock {
        session = null; checkpoint = null; token = null
        unacknowledgedDraftRows.clear()
        mutableState.value = CsvImportUiState()
    }

    private suspend fun command(action: suspend () -> Unit) = mutex.withLock {
        mutableState.value = mutableState.value.copy(busy = true, errorCode = null)
        try { action() }
        catch (cancelled: CancellationException) {
            withContext(NonCancellable) { session?.let { publish(it.report); persist() } }
            throw cancelled
        } catch (error: Exception) {
            if (mutableState.value.phase == CsvImportPhase.IMPORTING) session?.let { current ->
                publish(current.report)
                withContext(NonCancellable) { runCatching { persist() } }
            }
            mutableState.value = mutableState.value.copy(errorCode = when (error) {
                is BackupContractException -> error.code.name
                else -> "IMPORT_OPERATION_FAILED"
            })
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }

    suspend fun load(householdId: String, bytes: ByteArray) = command { loadLocked(householdId, CsvParser.parse(bytes)) }
    suspend fun loadParsed(householdId: String, parsed: CsvParseResult) = command { loadLocked(householdId, parsed) }
    private suspend fun loadLocked(householdId: String, parsed: CsvParseResult) {
        require(parsed.rows.size <= BackupLimits.MAX_ENTRIES && parsed.headers.size <= BackupLimits.MAX_CSV_COLUMNS)
        val identity = checkNotNull(coordinator.currentIdentity())
        val detection = CsvHeaderDetector.detect(parsed.headers)
        val preparedCheckpoint = CsvImportCheckpoint(householdId, identity.uid, parsed, detection.mapping, CsvDateFormat.AUTO,
            mappingConfirmed = !detection.requiresManualMapping)
        // Publish a replacement only after authorization and its private checkpoint both succeed.
        val prepared = coordinator.prepare(householdId, parsed, detection.mapping)
        val report = prepared.report
        val preparedToken = checkpoints.create(preparedCheckpoint.copy(rows = report.rows))
        checkpoint = preparedCheckpoint
        session = prepared
        token = preparedToken
        unacknowledgedDraftRows.clear()
        mutableState.value = CsvImportUiState(headers = parsed.headers, mapping = detection.mapping,
            total = parsed.rows.size, phase = if (detection.requiresManualMapping) CsvImportPhase.MAPPING else CsvImportPhase.READY,
            busy = true, checkpointToken = preparedToken)
        if (!detection.requiresManualMapping) publish(report, CsvImportPhase.READY)
        persist()
    }

    suspend fun setMapping(mapping: CsvColumnMapping, dateFormat: CsvDateFormat = CsvDateFormat.AUTO) = command {
        val saved = checkNotNull(checkpoint)
        check(!saved.started)
        val indices = listOfNotNull(mapping.date, mapping.amount, mapping.title, mapping.category, mapping.subcategory, mapping.tags, mapping.type)
        require(indices.all { it < saved.parsed.headers.size })
        val prepared = coordinator.prepare(saved.householdId, saved.parsed, mapping, dateFormat)
        checkpoint = saved.copy(mapping = mapping, dateFormat = dateFormat, mappingConfirmed = mapping.isComplete)
        session = prepared
        mutableState.value = mutableState.value.copy(mapping = mapping, dateFormat = dateFormat)
        publish(session!!.report, if (mapping.isComplete) CsvImportPhase.READY else CsvImportPhase.MAPPING)
        persist()
    }

    suspend fun restore(token: String, householdId: String, uid: String) = command {
        val saved = checkpoints.read(token) ?: return@command
        check(saved.householdId == householdId && saved.uid == uid && coordinator.currentIdentity()?.uid == uid)
        val restored = coordinator.prepare(householdId, saved.parsed, saved.mapping, saved.dateFormat)
        coordinator.restoreRows(restored, saved.rows, saved.repaired, saved.fingerprints, saved.categorySelections, saved.subcategorySelections)
        session = restored; checkpoint = saved; this.token = token
        unacknowledgedDraftRows.clear()
        mutableState.value = CsvImportUiState(headers = saved.parsed.headers, mapping = saved.mapping,
            dateFormat = saved.dateFormat, drafts = saved.drafts, checkpointToken = token, busy = true)
        publish(restored.report, if (!saved.mapping.isComplete || !saved.mappingConfirmed) CsvImportPhase.MAPPING else
            if (!saved.started) CsvImportPhase.READY else if (restored.report.rows.none { it.status in attentionStatuses }) CsvImportPhase.COMPLETE else CsvImportPhase.RESTORED)
    }

    suspend fun start() = command {
        check(state.value.phase == CsvImportPhase.READY)
        commitLocked()
    }
    suspend fun resume() = command {
        check(state.value.phase == CsvImportPhase.RESTORED || state.value.phase == CsvImportPhase.ATTENTION)
        commitLocked()
    }
    private suspend fun commitLocked() {
        val current = checkNotNull(session)
        check(checkpoint!!.mapping.isComplete && checkpoint!!.mappingConfirmed)
        check(current.rows.none { it.source.rowNumber in unacknowledgedDraftRows && it.status in attentionStatuses })
        val drafts = state.value.drafts.filterKeys { number -> current.rows[current.rowIndices.getValue(number)].status in attentionStatuses }
        if (drafts.isNotEmpty()) {
            coordinator.draftBatch(current, drafts.map { (number, cells) -> CsvImportDraft(number, cells) })
            mutableState.value = mutableState.value.copy(drafts = state.value.drafts - drafts.keys)
            publish(current.report, state.value.phase)
        }
        checkpoint = checkpoint!!.copy(started = true)
        persist()
        mutableState.value = mutableState.value.copy(phase = CsvImportPhase.IMPORTING)
        var processed = current.report.rows.count { it.status !in attentionStatuses }
        var attention = current.report.rows.count { it.status in attentionStatuses }
        val report = coordinator.commit(current) { row ->
            if (row.status == CsvImportRowStatus.SERVER_ERROR) {
                withContext(NonCancellable) { checkpoints.append(checkNotNull(token), row) }
            }
            if (row.status !in attentionStatuses) {
                withContext(NonCancellable) { checkpoints.append(checkNotNull(token), row) }
                attention--
            }
            processed++
            mutableState.value = mutableState.value.copy(processed = processed, attention = attention)
        }
        publish(report)
        mutableState.value = mutableState.value.copy(processed = processed)
        persist()
    }

    suspend fun editRow(rowNumber: Int, cells: List<String>) = mutex.withLock {
        try {
            requireAttention(rowNumber)
            // A failed edit must not let later commands silently use an older acknowledged draft.
            unacknowledgedDraftRows.add(rowNumber)
            ImportRow(rowNumber, cells)
            val copiedCells = cells.toList()
            val drafts = state.value.drafts + (rowNumber to copiedCells)
            require(drafts.values.sumOf { it.sumOf(String::length).toLong() } <= BackupLimits.MAX_BYTES)
            checkpoints.saveDraft(checkNotNull(token), rowNumber, copiedCells)
            checkpoint = checkNotNull(checkpoint).copy(drafts = drafts)
            unacknowledgedDraftRows.remove(rowNumber)
            // Observers may recreate the ViewModel as soon as this acknowledgement is visible.
            mutableState.value = mutableState.value.copy(drafts = drafts, errorCode = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = mutableState.value.copy(errorCode = "IMPORT_OPERATION_FAILED") }
    }
    suspend fun revalidate(rowNumber: Int) = command {
        requireAttention(rowNumber)
        coordinator.draft(checkNotNull(session), rowNumber, workingCells(rowNumber))
        publish(session!!.report); persist()
    }
    suspend fun saveRepair(rowNumber: Int) = command {
        requireAttention(rowNumber)
        val current = checkNotNull(session)
        val cells = workingCells(rowNumber)
        coordinator.draft(current, rowNumber, cells)
        persist()
        val report = coordinator.repair(current, rowNumber, cells) { row ->
            if (row.status !in attentionStatuses || row.status == CsvImportRowStatus.SERVER_ERROR)
                withContext(NonCancellable) { checkpoints.append(checkNotNull(token), row) }
        }
        mutableState.value = mutableState.value.copy(drafts = state.value.drafts - rowNumber)
        publish(report); persist()
    }
    suspend fun discard(rowNumber: Int) = command {
        requireAttention(rowNumber)
        publish(coordinator.discard(checkNotNull(session), rowNumber))
        unacknowledgedDraftRows.remove(rowNumber)
        mutableState.value = mutableState.value.copy(drafts = state.value.drafts - rowNumber)
        persist()
    }
    suspend fun finishDiscardingRemaining() = command {
        val current = checkNotNull(session)
        val report = coordinator.discardRemaining(current)
        unacknowledgedDraftRows.clear()
        mutableState.value = mutableState.value.copy(drafts = emptyMap())
        publish(report, CsvImportPhase.COMPLETE); persist()
    }

    suspend fun selectCategory(rowNumber: Int, categoryId: String, applyToMatchingOriginal: Boolean = false) = command {
        selectLocked(rowNumber, categoryId, null, false, applyToMatchingOriginal)
    }
    suspend fun selectSubcategory(rowNumber: Int, subcategoryId: String?, applyToMatchingOriginal: Boolean = false) = command {
        val current = checkNotNull(session)
        val categoryId = current.selectedCategories[rowNumber] ?: current.report.rows.first { it.source.rowNumber == rowNumber }.entry?.categoryId
            ?: state.value.categories.singleOrNull { it.name.trim().equals(workingCells(rowNumber).getOrElse(checkpoint!!.mapping.category!!) { "" }.trim(), true) }?.id
        selectLocked(rowNumber, checkNotNull(categoryId), subcategoryId, true, applyToMatchingOriginal)
    }
    suspend fun createCategory(rowNumber: Int, name: String, applyToMatchingOriginal: Boolean = false) = command {
        val category = coordinator.createCategory(checkNotNull(session), name)
        selectLocked(rowNumber, category.id, null, false, applyToMatchingOriginal)
    }
    suspend fun createSubcategory(rowNumber: Int, categoryId: String, name: String, applyToMatchingOriginal: Boolean = false) = command {
        val subcategory = coordinator.createSubcategory(checkNotNull(session), categoryId, name)
        selectLocked(rowNumber, categoryId, subcategory.id, true, applyToMatchingOriginal)
    }
    private suspend fun selectLocked(rowNumber: Int, categoryId: String, subcategoryId: String?, sub: Boolean, batch: Boolean) {
        requireAttention(rowNumber)
        val current = checkNotNull(session)
        val mapping = checkpoint!!.mapping
        val originals = checkpoint!!.parsed.rows.associateBy { it.rowNumber }
        val original = originals.getValue(rowNumber)
        fun raw(row: ImportRow, column: Int?) = column?.let { row.cells.getOrElse(it) { "" } }
        val targets = current.report.rows.filter { it.status in attentionStatuses &&
            (it.source.rowNumber == rowNumber || batch && raw(originals.getValue(it.source.rowNumber), mapping.category) == raw(original, mapping.category) &&
                (!sub || raw(originals.getValue(it.source.rowNumber), mapping.subcategory) == raw(original, mapping.subcategory))) }
        coordinator.draftBatch(current, targets.map { row -> CsvImportDraft(row.source.rowNumber, workingCells(row.source.rowNumber),
            categoryId, subcategoryId, selectTaxonomy = true, selectSubcategory = sub) })
        publish(current.report); persist()
    }
    private fun requireAttention(number: Int) { require(state.value.report?.rows?.any { it.source.rowNumber == number && it.status in attentionStatuses } == true) }
    private fun workingCells(number: Int): List<String> {
        check(number !in unacknowledgedDraftRows)
        return state.value.drafts[number] ?: checkNotNull(session).sources.getValue(number).cells
    }
    private fun publish(report: CsvImportReport, phase: CsvImportPhase? = null) {
        val taxonomy = coordinator.taxonomy(checkNotNull(session))
        val attention = report.rows.count { it.status in attentionStatuses }
        mutableState.value = mutableState.value.copy(report = report, categories = taxonomy.first, subcategories = taxonomy.second,
            selectedCategories = session!!.selectedCategories.toMap(), selectedSubcategories = session!!.selectedSubcategories.toMap(),
            total = report.rows.size, attention = attention, processed = report.rows.count { it.status !in attentionStatuses },
            phase = phase ?: if (attention == 0) CsvImportPhase.COMPLETE else CsvImportPhase.ATTENTION)
    }
    private suspend fun persist() {
        val saved = checkNotNull(checkpoint)
        val current = session
        checkpoint = saved.copy(rows = current?.report?.rows.orEmpty(), drafts = state.value.drafts,
            categorySelections = current?.selectedCategories?.toMap().orEmpty(),
            subcategorySelections = current?.selectedSubcategories?.toMap().orEmpty(), repaired = current?.repaired?.toSet().orEmpty())
        token?.let { checkpoints.save(it, checkpoint!!) }
    }
}
