package pl.bargor.thesaurus

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SummaryPeriod
import pl.bargor.thesaurus.data.model.aggregateReportEntries
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceUiState
import pl.bargor.thesaurus.ui.entries.EntryListItem
import pl.bargor.thesaurus.ui.entries.EntryListScreen
import pl.bargor.thesaurus.ui.entries.EntryListUiState
import pl.bargor.thesaurus.ui.reports.ReportEntryItem
import pl.bargor.thesaurus.ui.reports.ReportFilterDraft
import pl.bargor.thesaurus.ui.reports.ReportPeriodMode
import pl.bargor.thesaurus.ui.reports.ReportsScreen
import pl.bargor.thesaurus.ui.reports.ReportsUiState
import pl.bargor.thesaurus.ui.taxonomy.CategoryWithSubcategories
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyScreen
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyUiState

/** CI-only visual proof uses synthetic data and never opens authentication or Firebase. */
class ResponsibilitySplitVisualEvidenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun movedScreensKeepTheirNavigationControlsAndProduceReviewableEvidence() {
        val today = LocalDate.of(2026, 9, 30)
        val categories = listOf(
            Category("jedzenie", "proof", "Jedzenie", color = "amber", authorId = "proof", updatedById = "proof"),
            Category("dom", "proof", "Dom", color = "blue", authorId = "proof", updatedById = "proof"),
            Category("wplywy", "proof", "Wpływy", color = "green", defaultEntryType = EntryType.INCOME,
                authorId = "proof", updatedById = "proof"),
        )
        val shop = Subcategory("supermarket", "proof", "jedzenie", "Supermarket", authorId = "proof", updatedById = "proof")
        val ledger = listOf(
            LedgerEntry("salary", "proof", 100_000, today.minusDays(10), categoryId = "wplywy", authorId = "proof", updatedById = "proof"),
            LedgerEntry("food", "proof", -12_000, today.minusDays(3), title = "Zakupy", categoryId = "jedzenie",
                subcategoryId = shop.id, authorId = "proof", updatedById = "proof"),
            LedgerEntry("home", "proof", -6_000, today.minusDays(1), title = "Internet", categoryId = "dom", authorId = "proof", updatedById = "proof"),
        )
        val byId = categories.associateBy { it.id }
        val aggregation = aggregateReportEntries(ledger, SummaryPeriod(today.withDayOfMonth(1), today), ReportTypeFilter.ALL)
        var reports by mutableStateOf(ReportsUiState(today, YearMonth.from(today), Year.from(today),
            aggregation = aggregation, isLoading = false, categories = categories, allSubcategories = listOf(shop),
            entries = ledger.map { ReportEntryItem(it, byId.getValue(it.categoryId).name,
                byId.getValue(it.categoryId).color, if (it.subcategoryId == shop.id) shop.name else null) }))
        val list = EntryListUiState(isLoading = false, entries = ledger.reversed().map {
            EntryListItem(it, byId.getValue(it.categoryId).name, if (it.subcategoryId == shop.id) shop.name else null,
                "Użytkownik testowy", true, byId.getValue(it.categoryId).color)
        })
        val taxonomy = TaxonomyUiState(isLoading = false, categories = categories.map {
            CategoryWithSubcategories(it, if (it.id == shop.categoryId) listOf(shop) else emptyList())
        })
        compose.setContent {
            ThesaurusTheme(darkTheme = false) {
                Surface {
                    HouseholdApp(
                        actorId = "proof", householdId = "proof",
                        entriesContent = { settings, add, family, edit ->
                            EntryListScreen(list, {}, {}, {}, settings, add, onOpenFamily = family, onEditEntry = edit)
                        },
                        summaryContent = {}, browseContent = {},
                        reportsContent = { open ->
                            ReportsScreen(reports, {}, {}, {}, {}, {}, {}, {}, open, {},
                                onOpenFilters = { reports = reports.copy(filterDraft = ReportFilterDraft(
                                    ReportPeriodMode.MONTH, reports.month, reports.year,
                                    customFromInput = reports.customFromInput, customToInput = reports.customToInput)) },
                                onDismissFilters = { reports = reports.copy(filterDraft = null) })
                        },
                        taxonomyContent = { back -> TaxonomyScreen(taxonomy, {}, onBack = back) },
                        balanceState = GlobalAccountBalanceUiState(amountGrosze = aggregation.totals.netGrosze, isLoading = false),
                    )
                }
            }
        }
        compose.onNodeWithTag("add-entry").assertIsDisplayed()
        capture("entries")
        compose.onNodeWithTag(Destination.Reports.navigationTestTag).performClick()
        compose.onNodeWithTag("reports-open-filters").assertIsDisplayed()
        capture("reports")
        compose.onNodeWithTag("reports-open-filters").performClick()
        compose.onNodeWithTag("reports-filter-dialog").assertIsDisplayed()
        capture("report-filters")
        compose.onNodeWithTag("reports-cancel-filters").assertIsDisplayed().performClick()
        compose.onNodeWithTag("global-open-settings").performClick()
        compose.onNodeWithTag("settings-categories").assertIsDisplayed()
        capture("settings")
        compose.onNodeWithTag("settings-categories").performClick()
        compose.onNodeWithTag("taxonomy-category-header-jedzenie").performClick()
        compose.onNodeWithText("Supermarket").assertIsDisplayed()
        capture("categories-expanded")
        compose.onNodeWithTag("taxonomy-add-category").performClick()
        compose.onNodeWithTag("taxonomy-name").performTextInput("Transport")
        compose.onNodeWithTag("taxonomy-save").assertIsDisplayed()
        capture("category-editor")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "CI screenshot unavailable: $name" }
        try {
            val external = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            val directory = File(external, "visual-evidence/issue94")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use {
                assertTrue("PNG capture failed: $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
