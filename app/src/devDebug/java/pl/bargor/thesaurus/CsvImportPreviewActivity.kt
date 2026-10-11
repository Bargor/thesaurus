package pl.bargor.thesaurus

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.data.transfer.*
import pl.bargor.thesaurus.ui.importcsv.*

/** DEV-only explicit entry point. No repositories, auth providers or Firebase clients. */
class CsvImportPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val preview: CsvImportPreviewViewModel = viewModel()
            val state by preview.controller.state.collectAsStateWithLifecycle()
            ThesaurusTheme {
                Surface {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Text(stringResource(R.string.dev_csv_import_banner), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp).testTag("csv_import_preview_banner"))
                        if (state.phase == CsvImportPhase.EMPTY || state.phase == CsvImportPhase.COMPLETE) {
                            Button(onClick = { preview.load(false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("csv_import_preview_auto")) { Text(stringResource(R.string.dev_csv_import_auto)) }
                            OutlinedButton(onClick = { preview.load(true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("csv_import_preview_manual")) { Text(stringResource(R.string.dev_csv_import_manual)) }
                        }
                        CsvImportRoute(preview.controller, preview.viewModelScope, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

class CsvImportPreviewViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val backend = SyntheticPreviewBackend()
    val controller = CsvImportController(CsvImportCoordinator(backend),
        PrivateCsvImportCheckpointStore(File(application.noBackupFilesDir, "csv-import-preview-checkpoints")))
    init {
        viewModelScope.launch {
            saved.get<String>("preview_checkpoint")?.let { controller.restore(it, PREVIEW_HOUSEHOLD, PREVIEW_OWNER) }
            controller.state.collect { state -> state.checkpointToken?.let { saved["preview_checkpoint"] = it } }
        }
    }
    fun load(manual: Boolean) = viewModelScope.launch {
        val header = if (manual) "Kiedy;Ile;Opis;Dział;Szczegóły;Oznaczenia" else "date;amount;title;category;subcategory;tags"
        val csv = "$header\n2026-01-01;-42.50;Zakupy;Żywność;Sklep;\n2026-01-01;-42.50;Zakupy;Żywność;Sklep;\n2026-01-02;błędna kwota;Napraw kwotę;Żywność;Sklep;\n2026-01-03;-10.00;Nowa kategoria;Transport;Autobus;\n2026-01-04;-15.00;Ta sama kategoria;Transport;Autobus;\n2026-01-05;-12.00;Inna podkategoria;Żywność;Bazar;\n"
        controller.load(PREVIEW_HOUSEHOLD, csv.toByteArray(Charsets.UTF_8))
    }
}

private const val PREVIEW_HOUSEHOLD = "synthetic-csv-preview-household"
private const val PREVIEW_OWNER = "synthetic-csv-preview-owner"

/** Deliberately in memory: even successful imports and taxonomy edits write no database. */
private class SyntheticPreviewBackend : CsvImportBackend {
    private val identity = CsvImportIdentity(PREVIEW_OWNER, "synthetic-preview-session")
    private var snapshot = CsvImportSnapshot(identity, Household(PREVIEW_HOUSEHOLD, "Symulacja", PREVIEW_OWNER), emptyList(),
        listOf(Category("food", PREVIEW_HOUSEHOLD, "Żywność", authorId = PREVIEW_OWNER, updatedById = PREVIEW_OWNER)),
        listOf(Subcategory("shop", PREVIEW_HOUSEHOLD, "food", "Sklep", authorId = PREVIEW_OWNER, updatedById = PREVIEW_OWNER)))
    override fun currentIdentity() = identity
    override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity): CsvImportSnapshot {
        check(householdId == PREVIEW_HOUSEHOLD && expectedIdentity == identity)
        return snapshot
    }
    override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
        delay(100)
        check(snapshot.identity == identity && snapshot.household.ledgerRevision == this.snapshot.household.ledgerRevision)
        if (this.snapshot.entries.any { csvImportFingerprint(it) == csvImportFingerprint(entry) }) return CsvImportCommitResult(this.snapshot.household.ledgerRevision, false)
        val revision = this.snapshot.household.ledgerRevision + 1
        this.snapshot = this.snapshot.copy(household = this.snapshot.household.copy(ledgerRevision = revision), entries = this.snapshot.entries + entry)
        return CsvImportCommitResult(revision, true)
    }
    override suspend fun createCategory(snapshot: CsvImportSnapshot, category: Category) {
        check(snapshot.identity == identity)
        this.snapshot = CsvImportSnapshot(identity, this.snapshot.household, this.snapshot.entries, this.snapshot.categories + category, this.snapshot.subcategories)
    }
    override suspend fun createSubcategory(snapshot: CsvImportSnapshot, subcategory: Subcategory) {
        check(snapshot.identity == identity)
        this.snapshot = CsvImportSnapshot(identity, this.snapshot.household, this.snapshot.entries, this.snapshot.categories, this.snapshot.subcategories + subcategory)
    }
}
