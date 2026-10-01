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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
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
import java.math.MathContext
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
import pl.bargor.thesaurus.data.onboarding.StarterTaxonomy
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
            val row = compose.onNodeWithTag("reports-category-row-$id", useUnmergedTree = true).revealInReport()
            row.assertIsDisplayed()
            val bounds = row.getUnclippedBoundsInRoot()
            // Scrolling moves the whole document. Compare adjacent rows in this same coordinate frame.
            if (index < ids.lastIndex) {
                val next = compose.onNodeWithTag("reports-category-row-${ids[index + 1]}", useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue(bounds.bottom <= next.top)
            }
            compose.onNodeWithTag("reports-category-name-$id", useUnmergedTree = true).assertTextEquals(names[index])
            compose.onNodeWithTag("reports-category-amount-$id", useUnmergedTree = true).assertTextEquals(currency(BigInteger.valueOf(1_250)))
            compose.onNodeWithContentDescription("Segment wykresu kategorii: ${names[index]}: 12,50 zł (17%)", useUnmergedTree = true).assertIsDisplayed()
            assertEquals("Swatch $id must match its painted arc, not merely the palette helper", before[index], swatchColor(id))
        }
        compose.runOnIdle { state = state.copy(syncState = SyncState.PENDING) }
        assertEquals("Unrelated recomposition must not recolor arcs", before, arcColors(ids.size))
        ids.forEachIndexed { index, id -> assertEquals(before[index], swatchColor(id)) }
    }

    @Test fun narrowPortraitLargeFontKeepsLongNamesAndExactLargeCurrencyReadable() = assertCompactRows(280, false, 1f)

    @Test fun darkPortraitHigherDisplayDensityKeepsLongNamesAndExactLargeCurrencyReadable() = assertCompactRows(320, true, 1.25f)

    @Test fun tenBuiltInCategoriesKeepVariedExactAmountsRightAlignedBesidePercentages() {
        val ids = StarterTaxonomy.categories.map { it.id }
        val names = StarterTaxonomy.categories.map { it.name }
        // Different digit/group counts catch a centered Text inside a shared column:
        // equal amounts would give the same right edge under either alignment.
        val amounts = listOf(1_906_250L, 100_000L, 50_000L, 25_000L, 12_500L, 6_250L, 3_125L, 1_562L, 781L, 532L).map(BigInteger::valueOf)
        val total = amounts.reduce(BigInteger::add)
        val paintedAmountRights = mutableListOf<Float>()
        compose.setContent {
            ThesaurusTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
                    ReportsScreen(fixture(ids, names, amounts), {}, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        val viewport = compose.onNodeWithTag("reports-category-legend-table", useUnmergedTree = true)
        val content = compose.onNodeWithTag("reports-category-legend-content", useUnmergedTree = true)
        val viewportBounds = viewport.getUnclippedBoundsInRoot()
        val contentBounds = content.getUnclippedBoundsInRoot()
        assertEquals("Built-in category names and 19 062,50 PLN must fit without horizontal scrolling", (viewportBounds.right - viewportBounds.left).value, (contentBounds.right - contentBounds.left).value, 1f)
        ids.forEachIndexed { index, id ->
            compose.onNodeWithTag("reports-category-row-$id", useUnmergedTree = true).revealInReport().assertIsDisplayed()
            compose.onNodeWithTag("reports-category-name-$id", useUnmergedTree = true).assertTextEquals(names[index])
            compose.onNodeWithTag("reports-category-amount-$id", useUnmergedTree = true).assertTextEquals(currency(amounts[index]))
            val share = String.format(Locale.forLanguageTag("pl-PL"), "%.0f%%", BigDecimal(amounts[index]).multiply(BigDecimal.valueOf(100)).divide(BigDecimal(total), MathContext.DECIMAL64))
            compose.onNodeWithTag("reports-category-percentage-$id", useUnmergedTree = true).assertTextEquals(share)
            assertSingleAlignedRow(id)
            val row = compose.onNodeWithTag("reports-category-row-$id", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val amount = compose.onNodeWithTag("reports-category-amount-$id", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val percentage = compose.onNodeWithTag("reports-category-percentage-$id", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals("Amount and percentage columns must remain adjacent with a 12 dp gap", 12f, (percentage.left - amount.right).value, 1f)
            assertEquals("Percentage must end at the right edge", row.right.value, percentage.right.value, 1f)
            paintedAmountRights += assertPaintedValuesEndAtColumnEdges(id)
            assertGroupedRow(id, names[index], currency(amounts[index]), share)
            assertTrue("One horizontal row must remain substantially shorter than the old three stacked texts", row.bottom - row.top <= 28.dp)
        }
        assertTrue("Different currency widths must share the same painted right edge: $paintedAmountRights", paintedAmountRights.max() - paintedAmountRights.min() <= 1f)
        val first = compose.onNodeWithTag("reports-category-row-${ids.first()}", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val last = compose.onNodeWithTag("reports-category-row-${ids.last()}", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("All ten categories should occupy at most 320 dp, rather than roughly 700 dp of stacked rows", last.bottom - first.top <= 320.dp)
    }

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
            compose.onNodeWithTag("reports-category-row-$id", useUnmergedTree = true).revealInReport().assertIsDisplayed()
            compose.onNodeWithTag("reports-category-name-$id", useUnmergedTree = true).assertTextEquals(names[index])
            compose.onNodeWithTag("reports-category-amount-$id", useUnmergedTree = true).assertTextEquals(currency(BigInteger.valueOf(1_250)))
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
        val viewport = compose.onNodeWithTag("reports-category-legend-table", useUnmergedTree = true)
        val content = compose.onNodeWithTag("reports-category-legend-content", useUnmergedTree = true)
        val contentBounds = content.getUnclippedBoundsInRoot()
        val viewportBounds = viewport.getUnclippedBoundsInRoot()
        assertTrue("Huge exact values and large font must expose a horizontal scroll fallback", contentBounds.right - contentBounds.left > viewportBounds.right - viewportBounds.left)
        val scrollRange = viewport.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("Overflowing single lines must be reachable by horizontal scrolling", scrollRange.maxValue() > 0f)
        val colors = arcColors(2)
        ids.forEachIndexed { index, id ->
            val name = compose.onNodeWithTag("reports-category-name-$id", useUnmergedTree = true).revealInReport()
            name.assertTextEquals(names[index]).assertIsDisplayed()
            assertReadableText("reports-category-name-$id")
            val nameX = name.fetchSemanticsNode().positionInRoot.x
            val amount = compose.onNodeWithTag("reports-category-amount-$id", useUnmergedTree = true).revealInReport()
            amount.assertTextEquals(currency(amounts[index])).assertIsDisplayed()
            assertReadableText("reports-category-amount-$id")
            assertTrue("Scrolling to the amount must move the long category towards the left", name.fetchSemanticsNode().positionInRoot.x < nameX)
            val percentage = if (index == 0) "89%" else "11%"
            compose.onNodeWithTag("reports-category-percentage-$id", useUnmergedTree = true).revealInReport().assertTextEquals(percentage).assertIsDisplayed()
            assertReadableText("reports-category-percentage-$id")
            assertSingleAlignedRow(id)
            assertPaintedValuesEndAtColumnEdges(id)
            assertGroupedRow(id, names[index], currency(amounts[index]), percentage)
            val nameBounds = name.getUnclippedBoundsInRoot()
            val amountBounds = amount.getUnclippedBoundsInRoot()
            assertTrue("Single-line name and currency must not overlap horizontally", nameBounds.right <= amountBounds.left)
            val rowBounds = compose.onNodeWithTag("reports-category-row-$id", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(nameBounds.left >= rowBounds.left && nameBounds.right <= rowBounds.right)
            assertTrue(amountBounds.left >= rowBounds.left && amountBounds.right <= rowBounds.right)
            assertTrue("Each row follows the entire chart", rowBounds.top > compose.onNodeWithTag("reports-category-chart").getUnclippedBoundsInRoot().bottom)
            assertEquals(colors[index], swatchColor(id))
        }
    }

    private fun assertGroupedRow(id: String, name: String, amount: String, percentage: String) {
        // The default merged tree is the accessibility surface; each row must expose its full
        // name and exact value even when only part of the horizontal table is in the viewport.
        compose.onNodeWithTag("reports-category-row-$id")
            .assertContentDescriptionEquals("Segment wykresu kategorii: $name: $amount ($percentage)")
    }

    private fun assertPaintedValuesEndAtColumnEdges(id: String): Float {
        val amountTag = "reports-category-amount-$id"
        val percentageTag = "reports-category-percentage-$id"
        val amountLayout = assertReadableText(amountTag)
        val percentageLayout = assertReadableText(percentageTag)
        val amount = compose.onNodeWithTag(amountTag, useUnmergedTree = true).fetchSemanticsNode()
        val percentage = compose.onNodeWithTag(percentageTag, useUnmergedTree = true).fetchSemanticsNode()
        val paintedAmountRight = amount.positionInRoot.x + amountLayout.getLineRight(0)
        val paintedPercentageRight = percentage.positionInRoot.x + percentageLayout.getLineRight(0)
        assertEquals("Actual amount text must end at its shared column edge", amount.positionInRoot.x + amount.size.width, paintedAmountRight, 1f)
        assertEquals("Actual percentage text must end at its shared column edge", percentage.positionInRoot.x + percentage.size.width, paintedPercentageRight, 1f)
        return paintedAmountRight
    }

    private fun assertSingleAlignedRow(id: String) {
        val tags = listOf("reports-category-name-$id", "reports-category-amount-$id", "reports-category-percentage-$id")
        val baselines = tags.map { tag ->
            val result = assertReadableText(tag)
            compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y + result.firstBaseline
        }
        assertTrue("All text in row $id must share a single baseline: $baselines", baselines.max() - baselines.min() <= 1f)
        val bounds = tags.map { compose.onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot() }
        assertTrue("Row $id text must share the same vertical position", bounds.maxOf { it.top.value } - bounds.minOf { it.top.value } <= 1f)
        assertTrue("Row $id text columns must not overlap", bounds[0].right <= bounds[1].left && bounds[1].right <= bounds[2].left)
        val swatch = compose.onNodeWithTag("reports-category-swatch-$id", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Swatch must precede the category", swatch.right <= bounds[0].left)
        assertEquals("Swatch must be vertically centered on the text line", ((bounds[0].top + bounds[0].bottom) / 2).value, ((swatch.top + swatch.bottom) / 2).value, 1f)
    }

    private fun assertReadableText(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(results))
            assertTrue(results.isNotEmpty())
            results.forEach { result ->
                assertEquals("$tag must occupy exactly one line", 1, result.lineCount)
                assertTrue("$tag must show all text without ellipsis", (0 until result.lineCount).none(result::isLineEllipsized))
                assertTrue("$tag must not clip text horizontally: allocated=${result.size.width}px, intrinsic=${result.multiParagraph.maxIntrinsicWidth}px, lineRight=${result.getLineRight(0)}px, constraints=${result.layoutInput.constraints}", !result.didOverflowWidth)
                assertTrue("$tag must not clip text vertically", !result.didOverflowHeight)
                (0 until result.lineCount).forEach { line ->
                    assertTrue("$tag line $line exceeds its allocated width", result.getLineRight(line) <= result.size.width + 1f)
                }
            }
        }
        return results.single()
    }

    private fun SemanticsNodeInteraction.revealInReport(): SemanticsNodeInteraction {
        // performScrollTo stops at the nearest scroll ancestor, which is horizontal for
        // legend rows. Move the report vertically first, then the shared table horizontally.
        val report = compose.onNode(SemanticsMatcher("vertical report scroller") {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }, useUnmergedTree = true)
        val verticalDelta = fetchSemanticsNode().positionInRoot.y - report.fetchSemanticsNode().positionInRoot.y
        report.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, verticalDelta) }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("reports-category-legend-table", useUnmergedTree = true)
        val viewportNode = viewport.fetchSemanticsNode()
        val target = fetchSemanticsNode()
        val leftDelta = target.positionInRoot.x - viewportNode.positionInRoot.x
        val rightDelta = leftDelta + target.size.width - viewportNode.size.width
        val horizontalDelta = when {
            target.size.width >= viewportNode.size.width -> leftDelta
            leftDelta < 0f -> leftDelta
            rightDelta > 0f -> rightDelta
            else -> 0f
        }
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(horizontalDelta, 0f) }
        compose.waitForIdle()
        return this
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
        val image = compose.onNodeWithTag("reports-category-swatch-$id", useUnmergedTree = true).revealInReport().captureToImage()
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
