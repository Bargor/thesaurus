package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.math.BigInteger
import java.text.NumberFormat
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.ReportAggregation
import pl.bargor.thesaurus.data.model.ReportCategoryValue
import pl.bargor.thesaurus.data.model.ReportTotals
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SyncState

class ReportCategoryChartTest {
    @get:Rule val compose = createComposeRule()

    @Test fun categoryRowsMatchActualArcPixelsInReportOrderAndSurviveUnrelatedStateChanges() {
        val ids = listOf("category-5", "category-1", "category-4", "category-0", "category-3", "category-2")
        val names = ids.map { "Kategoria $it" }
        var state by mutableStateOf(fixture(ids, names, List(ids.size) { BigInteger.valueOf(1_250) }))
        compose.setContent { ThesaurusTheme { ReportsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        val before = arcColors(ids.size)
        assertEquals("Colliding persisted colors must produce distinct actual chart pixels", ids.size, before.toSet().size)
        compose.onAllNodes(SemanticsMatcher("category rows") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("reports-category-row-") == true
        }, useUnmergedTree = true).assertCountEquals(ids.size)
        ids.forEachIndexed { index, id ->
            val row = compose.onNodeWithTag("reports-category-row-$id", useUnmergedTree = true).performScrollTo()
            row.assertIsDisplayed()
            val bounds = row.getUnclippedBoundsInRoot()
            // Scrolling moves the whole document. Compare adjacent rows in this same coordinate frame.
            if (index < ids.lastIndex) {
                val next = compose.onNodeWithTag("reports-category-row-${ids[index + 1]}", useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue(bounds.bottom <= next.top)
            }
            compose.onNodeWithTag("reports-category-name-$id", useUnmergedTree = true).assertTextEquals(names[index])
            compose.onNodeWithTag("reports-category-amount-$id", useUnmergedTree = true).assertTextContains("12,50", substring = true)
            compose.onNodeWithContentDescription("Segment wykresu kategorii: ${names[index]}: 12,50 zł (17%)", useUnmergedTree = true).assertIsDisplayed()
            assertEquals("Swatch $id must match its painted arc, not merely the palette helper", before[index], swatchColor(id))
        }
        compose.runOnIdle { state = state.copy(syncState = SyncState.PENDING) }
        assertEquals("Unrelated recomposition must not recolor arcs", before, arcColors(ids.size))
        ids.forEachIndexed { index, id -> assertEquals(before[index], swatchColor(id)) }
    }

    @Test fun narrowPortraitLargeFontKeepsLongNamesAndExactLargeCurrencyReadable() = assertCompactRows(280, false, 1f)

    @Test fun darkPortraitHigherDisplayDensityKeepsLongNamesAndExactLargeCurrencyReadable() = assertCompactRows(320, true, 1.25f)

    @Test fun extendedPaletteKeepsEveryCategoryRowOrderedAndReachable() {
        val ids = (0 until 35).map { "extended-${it.toString().padStart(2, '0')}" }.reversed()
        val names = ids.map { "Kategoria $it" }
        val state = fixture(ids, names, List(ids.size) { BigInteger.valueOf(1_250) })
        compose.setContent { ThesaurusTheme { ReportsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        val colors = arcColors(ids.size)
        assertEquals(35, colors.toSet().size)
        compose.onAllNodes(SemanticsMatcher("category rows") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("reports-category-row-") == true
        }, useUnmergedTree = true).assertCountEquals(35)
        ids.zipWithNext().forEach { (first, second) ->
            val firstBounds = compose.onNodeWithTag("reports-category-row-$first", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val secondBounds = compose.onNodeWithTag("reports-category-row-$second", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("Extended category rows must preserve order and not overlap", firstBounds.bottom <= secondBounds.top)
        }
        listOf(0, 17, 34).forEach { index ->
            val id = ids[index]
            compose.onNodeWithTag("reports-category-row-$id", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("reports-category-name-$id", useUnmergedTree = true).assertTextEquals(names[index])
            compose.onNodeWithTag("reports-category-amount-$id", useUnmergedTree = true).assertTextContains("12,50", substring = true)
            assertEquals("Extended swatch must match its actual chart segment", colors[index], swatchColor(id))
        }
    }

    private fun assertCompactRows(width: Int, dark: Boolean, densityMultiplier: Float) {
        val ids = listOf("long-food", "long-transport")
        val names = listOf("Zakupy spożywcze na cały tydzień dla całej rodziny i zaproszonych gości", "Przejazdy komunikacją miejską oraz dalekobieżnymi pociągami")
        val amounts = listOf(BigInteger("98765432109876543210"), BigInteger("12345678901234567890"))
        val state = fixture(ids, names, amounts)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * densityMultiplier, 1.8f)) {
                ThesaurusTheme(darkTheme = dark) {
                    Box(Modifier.width(width.dp).fillMaxHeight()) { ReportsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
                }
            }
        }
        ids.forEachIndexed { index, id ->
            val name = compose.onNodeWithTag("reports-category-name-$id", useUnmergedTree = true).performScrollTo()
            name.assertTextEquals(names[index]).assertIsDisplayed()
            assertReadableText("reports-category-name-$id")
            val amount = compose.onNodeWithTag("reports-category-amount-$id", useUnmergedTree = true).performScrollTo()
            amount.assertTextContains(currency(amounts[index]), substring = true).assertIsDisplayed()
            assertReadableText("reports-category-amount-$id")
            val nameBounds = name.getUnclippedBoundsInRoot()
            val amountBounds = amount.getUnclippedBoundsInRoot()
            assertTrue("Name and currency must not overlap", nameBounds.bottom <= amountBounds.top || nameBounds.right <= amountBounds.left)
            val rowBounds = compose.onNodeWithTag("reports-category-row-$id", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(nameBounds.left >= rowBounds.left && nameBounds.right <= rowBounds.right)
            assertTrue(amountBounds.left >= rowBounds.left && amountBounds.right <= rowBounds.right)
            assertTrue("Each row follows the entire chart", rowBounds.top > compose.onNodeWithTag("reports-category-chart").getUnclippedBoundsInRoot().bottom)
            assertEquals(arcColors(2)[index], swatchColor(id))
        }
    }

    private fun assertReadableText(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<TextLayoutResult>()
            assertTrue(action(results))
            assertTrue(results.isNotEmpty())
            results.forEach { result ->
                assertTrue("$tag must show all text without ellipsis", (0 until result.lineCount).none(result::isLineEllipsized))
                assertTrue("$tag must not clip text vertically", !result.didOverflowHeight)
                (0 until result.lineCount).forEach { line ->
                    assertTrue("$tag line $line exceeds its allocated width", result.getLineRight(line) <= result.size.width + 1f)
                }
            }
        }
    }

    private fun arcColors(count: Int): List<Color> {
        val chart = compose.onNodeWithTag("reports-category-chart").performScrollTo()
        val image = chart.captureToImage()
        val pixels = image.toPixelMap()
        val bounds = chart.getUnclippedBoundsInRoot()
        val pxPerDp = image.height / (bounds.bottom - bounds.top).value
        val outer = minOf(image.width, image.height) - 16 * pxPerDp
        val radius = outer * .39
        // Equal slices in the first test; amount-weighted centers for the large-number fixture.
        val fractions = if (count == 2) listOf(98765432109876543210.0 / 111111111011111111100.0, 12345678901234567890.0 / 111111111011111111100.0) else List(count) { 1.0 / count }
        var start = -90.0
        return fractions.map { fraction ->
            val angle = Math.toRadians(start + fraction * 180)
            start += fraction * 360
            pixels[(image.width / 2.0 + cos(angle) * radius).roundToInt(), (image.height / 2.0 + sin(angle) * radius).roundToInt()]
        }
    }

    private fun swatchColor(id: String): Color {
        val image = compose.onNodeWithTag("reports-category-swatch-$id", useUnmergedTree = true).performScrollTo().captureToImage()
        return image.toPixelMap()[image.width / 2, image.height / 2]
    }

    private fun currency(value: BigInteger) = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL")).format(BigDecimal(value, 2))

    private fun fixture(ids: List<String>, names: List<String>, amounts: List<BigInteger>) = ReportsUiState(
        today = LocalDate.of(2026, 2, 15), month = YearMonth.of(2026, 2), year = Year.of(2026), isLoading = false,
        aggregation = ReportAggregation(ReportTotals(BigInteger.ZERO, amounts.reduce(BigInteger::add), ids.size), ids.mapIndexed { index, id -> ReportCategoryValue(id, amounts[index]) }, emptyList()),
        entries = ids.mapIndexed { index, id -> ReportEntryItem(LedgerEntry(id = "entry-$id", householdId = "home", amountGrosze = -1250, date = LocalDate.of(2026, 2, 2), categoryId = id, authorId = "anna", updatedById = "anna", title = "Zakupy"), names[index], "blue") },
        typeFilter = ReportTypeFilter.EXPENSE,
    )
}
