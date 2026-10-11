package pl.bargor.thesaurus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.data.transfer.*
import pl.bargor.thesaurus.ui.backupjson.*

/** DEV-only entry point; successful actions mutate only synthetic memory. */
class JsonBackupPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val preview: JsonBackupPreviewViewModel = viewModel()
            val state by preview.controller.state.collectAsStateWithLifecycle()
            ThesaurusTheme {
                Surface {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Text(stringResource(R.string.dev_json_backup_banner), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                            PreviewButton(R.string.dev_json_backup_prepare, "json_backup_prepare", !state.busy) { preview.load(false, false) }
                            PreviewButton(R.string.dev_json_backup_conflict, "json_backup_conflict", !state.busy) { preview.load(true, false) }
                            PreviewButton(R.string.dev_json_backup_failure, "json_backup_failure", !state.busy) { preview.load(false, true) }
                            PreviewButton(R.string.dev_json_backup_repeat, "json_backup_repeat", !state.busy && state.canRetry) { preview.repeat() }
                            state.exportedArtifact?.let { artifact ->
                                val decoded = BackupJsonCodec.decode(artifact.bytes)
                                Text(stringResource(R.string.dev_json_backup_artifact, artifact.suggestedFilename, artifact.bytes.size, decoded.entries.size), modifier = Modifier.testTag("json_backup_artifact"))
                            }
                        }
                        JsonBackupRoute(preview.controller, preview.viewModelScope, JSON_PREVIEW_HOUSEHOLD, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PreviewButton(label: Int, tag: String, enabled: Boolean, click: () -> Unit) {
    OutlinedButton(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(stringResource(label)) }
}

class JsonBackupPreviewViewModel : ViewModel() {
    private val backend = SyntheticJsonBackupBackend()
    val controller = JsonBackupController(JsonBackupCoordinator(backend))
    fun load(conflict: Boolean, failure: Boolean) = viewModelScope.launch {
        if (controller.state.value.busy) return@launch
        backend.reset(conflict, failure)
        controller.prepare(JSON_PREVIEW_HOUSEHOLD, BackupJsonCodec.encode(jsonPreviewDocument()))
    }
    fun repeat() = viewModelScope.launch { controller.retry() }
}

private const val JSON_PREVIEW_HOUSEHOLD = "synthetic-json-household"
private const val JSON_PREVIEW_OWNER = "synthetic-json-owner"

private fun jsonPreviewDocument() = BackupDocument(
    ExportMetadata(Instant.parse("2026-01-10T12:00:00Z"), "synthetic-source"), 123_456L,
    categories = listOf(
        BackupCategory("food", "Żywność", "former-member", "former-member", color = "green"),
        BackupCategory("archive", "Dawne wydatki", JSON_PREVIEW_OWNER, JSON_PREVIEW_OWNER, color = "blue", archived = true)),
    subcategories = listOf(BackupSubcategory("shop", "food", "Sklep", "former-member", "former-member"),
        BackupSubcategory("old", "archive", "Archiwum", "former-member", "former-member", archived = true)),
    entries = listOf(
        BackupEntry("active", -4250, LocalDate.of(2026, 1, 1), "food", "former-member", "former-member", "Zakupy", "shop", listOf("dom")),
        BackupEntry("deleted", -1500, LocalDate.of(2026, 1, 2), "archive", "former-member", "former-member", "Usunięty wpis", "old", deleted = true, deletedAt = Instant.parse("2026-01-03T12:00:00Z"), deletedById = "former-member")))

/** Mirrors principal/revision/taxonomy fences and insert-only outcomes in synthetic memory. */
private class SyntheticJsonBackupBackend : JsonBackupBackend {
    private val identity = CsvImportIdentity(JSON_PREVIEW_OWNER, "synthetic-json-session")
    private var snapshot = emptySnapshot()
    private var failNextEntry = false
    override fun currentIdentity() = identity
    private fun emptySnapshot() = JsonBackupSnapshot(identity, Household(JSON_PREVIEW_HOUSEHOLD, "Symulacja", JSON_PREVIEW_OWNER),
        emptyList(), emptyList(), emptyList(), setOf(JSON_PREVIEW_OWNER))

    fun reset(conflict: Boolean, failure: Boolean) {
        snapshot = emptySnapshot()
        failNextEntry = failure
        if (conflict) {
            val category = Category("existing-archive", JSON_PREVIEW_HOUSEHOLD, "Dawne wydatki", "blue", true, authorId = JSON_PREVIEW_OWNER, updatedById = JSON_PREVIEW_OWNER)
            val subcategory = Subcategory("existing-old", JSON_PREVIEW_HOUSEHOLD, category.id, "Archiwum", true, JSON_PREVIEW_OWNER, JSON_PREVIEW_OWNER)
            val entry = LedgerEntry("existing-active", JSON_PREVIEW_HOUSEHOLD, -1500, LocalDate.of(2026, 1, 2), "Usunięty wpis", category.id, subcategory.id, authorId = JSON_PREVIEW_OWNER, updatedById = JSON_PREVIEW_OWNER)
            snapshot = snapshot.copy(household = snapshot.household.copy(openingBalanceGrosze = 999, ledgerRevision = 1), categories = listOf(category), subcategories = listOf(subcategory), entries = listOf(entry))
        }
    }

    override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity, requireOwner: Boolean): JsonBackupSnapshot {
        check(householdId == JSON_PREVIEW_HOUSEHOLD && expectedIdentity == identity)
        check(identity.uid in snapshot.memberIds && (!requireOwner || snapshot.household.ownerId == identity.uid))
        return snapshot
    }

    override suspend fun commit(snapshot: JsonBackupSnapshot, operation: JsonMergeOperation): JsonMergeWriteResult {
        delay(220)
        check(snapshot.identity == identity && snapshot.household.id == JSON_PREVIEW_HOUSEHOLD && snapshot.household.ownerId == identity.uid)
        check(identity.uid in this.snapshot.memberIds && snapshot.household.ledgerRevision == this.snapshot.household.ledgerRevision)
        val revision = this.snapshot.household.ledgerRevision
        fun result(outcome: JsonMergeWriteOutcome) = JsonMergeWriteResult(this.snapshot.household.ledgerRevision, outcome)
        when (operation) {
            is JsonMergeOperation.OpeningBalance -> {
                if (this.snapshot.household.openingBalanceGrosze == operation.amountGrosze) return result(JsonMergeWriteOutcome.SKIPPED)
                if (revision != 0L || this.snapshot.entries.isNotEmpty() || this.snapshot.household.openingBalanceGrosze != 0L) return result(JsonMergeWriteOutcome.CONFLICT)
                this.snapshot = this.snapshot.copy(household = this.snapshot.household.copy(openingBalanceGrosze = operation.amountGrosze))
            }
            is JsonMergeOperation.NewCategory -> {
                val existing = this.snapshot.categories.firstOrNull { it.id == operation.category.id }
                if (existing != null) return result(if (jsonCategoryContentKey(existing) == jsonCategoryContentKey(operation.category)) JsonMergeWriteOutcome.SKIPPED else JsonMergeWriteOutcome.CONFLICT)
                this.snapshot = this.snapshot.copy(categories = this.snapshot.categories + operation.category)
            }
            is JsonMergeOperation.NewSubcategory -> {
                check(this.snapshot.categories.any { it.id == operation.subcategory.categoryId })
                val existing = this.snapshot.subcategories.firstOrNull { it.categoryId == operation.subcategory.categoryId && it.id == operation.subcategory.id }
                if (existing != null) return result(if (jsonSubcategoryContentKey(existing) == jsonSubcategoryContentKey(operation.subcategory)) JsonMergeWriteOutcome.SKIPPED else JsonMergeWriteOutcome.CONFLICT)
                this.snapshot = this.snapshot.copy(subcategories = this.snapshot.subcategories + operation.subcategory)
            }
            is JsonMergeOperation.NewEntry -> {
                if (failNextEntry) { failNextEntry = false; throw JsonBackupException("JSON_IMPORT_WRITE_FAILED") }
                val entry = operation.entry
                check(this.snapshot.categories.any { it.id == entry.categoryId })
                check(entry.subcategoryId == null || this.snapshot.subcategories.any { it.categoryId == entry.categoryId && it.id == entry.subcategoryId })
                val existing = this.snapshot.entries.firstOrNull { it.id == entry.id }
                if (existing != null) return result(jsonEntryMergeOutcome(existing, entry))
                this.snapshot = this.snapshot.copy(household = this.snapshot.household.copy(ledgerRevision = revision + 1), entries = this.snapshot.entries + entry)
            }
        }
        return result(JsonMergeWriteOutcome.ADDED)
    }
}
