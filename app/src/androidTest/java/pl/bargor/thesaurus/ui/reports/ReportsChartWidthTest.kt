package pl.bargor.thesaurus.ui.reports

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.math.BigInteger
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.*

/** Uses real chart wrappers and rendered pixels; every viewport fits the CI device unchanged. */
class ReportsChartWidthTest {
    @get:Rule val compose = createComposeRule()
    private var foreground = Color.Unspecified
    private var primary = Color.Unspecified
    private var surface = Color.Unspecified
    private var density = 1f
    private val first = LocalDate.of(2026, 1, 1)
    private val last = LocalDate.of(2026, 12, 1)
    private val period = SummaryPeriod(first, last)

    @Test fun ordinaryAxesAt320DpUseNaturalTickWidth() = prove("narrow-320", 320, 780)
    @Test fun ordinaryAxesAtCurrent411DpViewportUseNaturalTickWidth() = prove("current-411", 411, 914)
    @Test fun ordinaryAxesAtWidePortraitUseNaturalTickWidth() = prove("wide-600", 600, 1000)
    @Test fun ordinaryAxesAtLandscapeUseNaturalTickWidth() = prove("landscape-840", 840, 420)
    @Test fun ordinaryAxesAtFont18UseNaturalTickWidth() = prove("current-411-font18", 411, 914, 1.8f)
    @Test fun hugeSignedAmountsAt320DpFont18RemainFullyPainted() =
        prove("huge-320-font18", 320, 1100, 1.8f, huge = true)

    private fun prove(name: String, width: Int, height: Int, fontScale: Float = 1f, huge: Boolean = false) {
        val amount = if (huge) BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TEN else 1250.toBigInteger()
        val balance = ReportBalanceTrend(period, ReportBalanceGranularity.MONTHLY,
            listOf(ReportBalanceBucket(first, first, amount * BigInteger.valueOf(2), amount),
                ReportBalanceBucket(last, last, -amount * BigInteger.valueOf(2), -amount)),
            entryCount = 2, startBalanceGrosze = -amount)
        val monthly = listOf(ReportTrendValue(first, amount), ReportTrendValue(last, -amount))
        compose.setContent {
            ThesaurusTheme(darkTheme = false) {
                foreground = MaterialTheme.colorScheme.onSurface
                primary = MaterialTheme.colorScheme.primary
                surface = MaterialTheme.colorScheme.surface
                BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    val deviceDensity = LocalDensity.current
                    density = min(with(deviceDensity) { maxWidth.toPx() } / width,
                        with(deviceDensity) { maxHeight.toPx() } / height)
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        Column(Modifier.size(width.dp, height.dp).background(MaterialTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState()).padding(12.dp).testTag("width-fixture"),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Reports • $width × $height dp • font $fontScale", Modifier.fillMaxWidth()
                                .testTag("width-caption"), style = MaterialTheme.typography.bodySmall)
                            ReportsBalanceChart(balance)
                            Column(Modifier.fillMaxWidth().testTag("width-trend-section")) {
                                TrendChart(monthly, ReportTypeFilter.ALL)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        for (target in listOf(foreground, primary)) {
            val other = if (target == foreground) primary else foreground
            assertFalse("Blank surface cannot count as requested ink", isPaintedInk(surface, target))
            assertTrue("Opaque requested color must count as ink", isPaintedInk(target, target))
            assertFalse("The other chart color cannot count as requested ink", isPaintedInk(other, target))
            for (coverage in listOf(.25f, .5f)) {
                assertTrue("Antialiased requested color must count as ink at coverage=$coverage",
                    isPaintedInk(blendWithSurface(target, coverage), target))
                assertFalse("Antialiased other chart color cannot count as requested ink at coverage=$coverage",
                    isPaintedInk(blendWithSurface(other, coverage), target))
            }
        }
        // Keep a pending, explicitly diagnostic frame even if an assertion below
        // fails. Only paint-validated evidence is published to the media collection.
        storeBitmap("diagnostic-$name-initial", compose.onRoot().captureToImage().asAndroidBitmap(), publish = false)
        assertEquals(width * density, compose.onNodeWithTag("width-fixture", true)
            .fetchSemanticsNode().size.width.toFloat(), 1.1f)
        verifyChart("reports-balance", amount, step = true, ordinary = !huge)
        verifyChart("reports-trend", amount, step = false, ordinary = !huge)
        compose.onNodeWithTag("reports-balance-total").assertTextEquals(
            "Końcowe saldo: ${currency(-amount)}")
        compose.onNodeWithTag("reports-trend-summary").assertDoesNotExist()
        listOf("reports-balance-details", "reports-balance-bucket", "reports-balance-previous",
            "reports-balance-next").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }

        // Portrait shows the pair in one frame. Landscape intentionally captures each
        // complete section after scrolling, rather than squeezing two charts vertically.
        if (width > height) {
            compose.onNodeWithTag("reports-balance-section", true).performScrollTo()
            saveRoot("$name-balance", listOf("reports-balance-chart", "reports-balance-x-axis", "reports-balance-total"))
            compose.onNodeWithTag("width-trend-section", true).performScrollTo()
            saveRoot("$name-monthly", listOf("reports-trend-title", "reports-trend-chart", "reports-trend-x-axis"))
        } else {
            compose.onNodeWithTag("width-caption").performScrollTo()
            saveRoot("$name-pair", listOf("width-caption", "reports-balance-chart", "reports-balance-x-axis",
                "reports-balance-total", "reports-trend-title", "reports-trend-chart", "reports-trend-x-axis"))
        }
    }

    private fun verifyChart(prefix: String, amount: BigInteger, step: Boolean, ordinary: Boolean) {
        compose.onNodeWithTag(if (step) "reports-balance-section" else "width-trend-section", true).performScrollTo()
        val chart = compose.onNodeWithTag("$prefix-chart").assertIsDisplayed()
        val node = chart.fetchSemanticsNode()
        val plot = node.config[ReportAmountChartPlotBounds]
        val layouts = mutableListOf<TextLayoutResult>()
        chart.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("Positive, zero and negative ticks must actually be painted", 3, layouts.size)
        layouts.forEach(::assertReadable)
        val naturalWidth = layouts.maxOf { it.size.width }
        assertEquals("Y gutter must follow the widest natural painted tick, with only the 8dp gap",
            naturalWidth + 8 * density, plot.left, 1.1f)
        assertEquals(node.size.width - 8 * density, plot.right, 1.1f)
        assertEquals(8 * density, plot.top, 1.1f)
        assertEquals(node.size.height - 8 * density, plot.bottom, 1.1f)
        if (ordinary) {
            assertTrue("Ordinary labels leave most of the chart for data", plot.width > node.size.width * .65f)
            layouts.forEach { layout ->
                val textWidth = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
                assertEquals("Ordinary tick measurement must not include an artificial fixed-width text column",
                    ceil(textWidth), layout.size.width.toFloat(), 1.1f)
            }
        }
        val description = node.config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(currency(amount) in description && currency(-amount) in description)
        val yDescription = compose.onNodeWithTag("$prefix-y-axis", true).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString()
        listOf(amount, BigInteger.ZERO, -amount).forEach { assertTrue(currency(it) in yDescription) }
        val image = chart.captureToImage().toPixelMap()
        // Bound the scan to the measured gutter; plot lines cannot masquerade as labels.
        for (band in 0 until 3) {
            val bounds = Rect(0f, image.height * band / 3f, naturalWidth.toFloat(), image.height * (band + 1) / 3f)
            val glyphPixels = countGlyphInk(image, bounds)
            val opaquePixels = countOpaqueColor(image, foreground, bounds)
            assertTrue("$prefix paints its Y label in band $band: ink=$glyphPixels, opaque=$opaquePixels, " +
                "density=$density, gutter=${naturalWidth}px, image=${image.width}x${image.height}", glyphPixels >= 8)
        }
        assertTrue("$prefix line starts at the actual plot edge",
            countColor(image, primary, around(plot.left, if (step) plot.bottom else plot.top, 5 * density)) >= 4)
        assertTrue("$prefix line ends at the actual plot edge",
            countColor(image, primary, around(plot.right, plot.bottom, 5 * density)) >= 4)
        assertEquals("Line must never paint over Y labels or outside horizontal plot insets", 0,
            countColor(image, primary, Rect(0f, 0f, plot.left - 5 * density, image.height.toFloat()), fullyInside = true) +
                countColor(image, primary, Rect(plot.right + 5 * density, 0f, image.width.toFloat(), image.height.toFloat()), fullyInside = true))
        val dates = compose.onAllNodes(hasAnyAncestor(hasTestTag("$prefix-x-axis")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), true).fetchSemanticsNodes()
        assertTrue("Both endpoint dates remain visible", dates.size in 2..3)
        val expectedDates = if (!step) listOf("sty 2026", "gru 2026")
            else if (dates.size == 3) listOf("1 sty 2026", "17 cze 2026", "1 gru 2026")
            else listOf("1 sty 2026", "1 gru 2026")
        assertEquals(expectedDates,
            dates.map { it.config[SemanticsProperties.Text].single().text })
        val dateLayouts = mutableListOf<TextLayoutResult>()
        val chartBounds = node.boundsInRoot
        assertEquals(chartBounds.left + plot.left, dates.first().boundsInRoot.left, 1.1f)
        assertEquals(chartBounds.left + plot.right, dates.last().boundsInRoot.right, 1.1f)
        if (dates.size == 3) {
            val midpoint = period.from.plusDays(167)
            val ratio = (midpoint.toEpochDay() - period.from.toEpochDay() + 1f) /
                (period.to.toEpochDay() - period.from.toEpochDay() + 1f)
            // Integer Layout placement adds up to one pixel to the half-pixel
            // inset rounding; the Canvas keeps the unrounded calendar coordinate.
            assertEquals("Middle date uses the same end-of-day calendar coordinate as the step line",
                chartBounds.left + plot.left + plot.width * ratio, dates[1].boundsInRoot.center.x, 1.5f)
        }
        dates.forEach { date ->
            val bounds = date.boundsInRoot
            assertTrue("$prefix date text stays within precisely the same plot",
                bounds.left >= chartBounds.left + plot.left - 1.1f &&
                    bounds.right <= chartBounds.left + plot.right + 1.1f)
            val interaction = compose.onNode(SemanticsMatcher("date ${date.id}") { it.id == date.id }, true)
            interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(dateLayouts)) }
            assertTrue("Date glyphs must be painted", countGlyphInk(interaction.captureToImage().toPixelMap()) >= 8)
        }
        dates.zipWithNext().forEach { (before, after) ->
            assertTrue("Date columns do not overlap", before.boundsInRoot.right <= after.boundsInRoot.left + 1.1f)
        }
        dateLayouts.forEach(::assertReadable)
    }

    private fun assertReadable(layout: TextLayoutResult) {
        assertFalse("Text clips horizontally: ${layout.layoutInput.text}", layout.didOverflowWidth)
        assertFalse("Text clips vertically: ${layout.layoutInput.text}", layout.didOverflowHeight)
        assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
    }

    private fun saveRoot(name: String, proofTags: List<String>) {
        compose.waitForIdle()
        val root = compose.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val image = root.captureToImage()
        val pixels = image.toPixelMap()
        proofTags.forEach { tag ->
            val proof = compose.onNodeWithTag(tag, true).assertIsDisplayed().fetchSemanticsNode()
            val bounds = proof.boundsInRoot
            assertTrue("Saved frame fully contains $tag", bounds.left >= rootBounds.left && bounds.right <= rootBounds.right &&
                bounds.top >= rootBounds.top && bounds.bottom <= rootBounds.bottom)
            val local = Rect(bounds.left - rootBounds.left, bounds.top - rootBounds.top,
                bounds.right - rootBounds.left, bounds.bottom - rootBounds.top)
            assertTrue("Saved frame paints $tag", countGlyphInk(pixels, local) >= 8)
            if (tag.endsWith("-chart")) {
                val gutter = proof.config[ReportAmountChartPlotBounds].left - 8 * density
                for (band in 0 until 3) {
                    val labelBounds = Rect(local.left, local.top + local.height * band / 3f,
                        local.left + gutter, local.top + local.height * (band + 1) / 3f)
                    assertTrue("Saved frame paints measured $tag Y label band $band",
                        countGlyphInk(pixels, labelBounds) >= 8)
                }
                assertTrue("Saved frame paints chart data $tag", countColor(pixels, primary, local) >= 8)
            }
        }
        storeBitmap(name, image.asAndroidBitmap(), publish = true)
    }

    private fun storeBitmap(name: String, bitmap: Bitmap, publish: Boolean) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ThesaurusTestEvidence/issue105/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(collection, values))
        requireNotNull(resolver.openOutputStream(uri)).use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        if (publish) assertEquals(1, resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null))
    }

    private fun around(x: Float, y: Float, radius: Float) = Rect(x - radius, y - radius, x + radius, y + radius)
    private fun countGlyphInk(pixels: PixelMap,
        bounds: Rect = Rect(0f, 0f, pixels.width.toFloat(), pixels.height.toFloat())) = countColor(pixels, foreground, bounds)

    private fun blendWithSurface(target: Color, coverage: Float) = Color(
        surface.red + coverage * (target.red - surface.red),
        surface.green + coverage * (target.green - surface.green),
        surface.blue + coverage * (target.blue - surface.blue))

    private fun isPaintedInk(pixel: Color, target: Color): Boolean {
        val red = target.red - surface.red
        val green = target.green - surface.green
        val blue = target.blue - surface.blue
        val squaredContrast = red * red + green * green + blue * blue
        check(squaredContrast > 0f) { "Requested ink and surface must differ for a painted-pixel proof" }
        // Antialiased ink is P = surface + coverage * (requested color - surface).
        // Project onto that known RGB direction, then reject other colors by
        // residual. 20% coverage is visible ink; blank surface has coverage 0.
        // Ink need not fully cover a pixel. RGB residual and all counts stay unchanged.
        val coverage = ((pixel.red - surface.red) * red + (pixel.green - surface.green) * green +
            (pixel.blue - surface.blue) * blue) / squaredContrast
        return coverage in .2f..1.03f &&
            abs(pixel.red - (surface.red + coverage * red)) < .03f &&
            abs(pixel.green - (surface.green + coverage * green)) < .03f &&
            abs(pixel.blue - (surface.blue + coverage * blue)) < .03f
    }

    private fun countColor(pixels: PixelMap, color: Color,
        bounds: Rect = Rect(0f, 0f, pixels.width.toFloat(), pixels.height.toFloat()),
        fullyInside: Boolean = false) = countPixels(pixels, bounds, fullyInside) { isPaintedInk(it, color) }

    private fun countOpaqueColor(pixels: PixelMap, color: Color, bounds: Rect) = countPixels(pixels, bounds) {
        abs(it.red - color.red) < .03f && abs(it.green - color.green) < .03f && abs(it.blue - color.blue) < .03f
    }

    private fun countPixels(pixels: PixelMap, bounds: Rect, fullyInside: Boolean = false,
        matches: (Color) -> Boolean): Int {
        // Negative/outside proofs inspect only cells completely inside their logical
        // crop. floor/ceil would include boundary cells straddling the 5dp guard.
        // Positive ink proofs include intersecting edge cells, as before.
        val left = (if (fullyInside) ceil(bounds.left) else floor(bounds.left)).toInt().coerceAtLeast(0)
        val right = (if (fullyInside) floor(bounds.right) else ceil(bounds.right)).toInt().coerceAtMost(pixels.width)
        val top = (if (fullyInside) ceil(bounds.top) else floor(bounds.top)).toInt().coerceAtLeast(0)
        val bottom = (if (fullyInside) floor(bounds.bottom) else ceil(bounds.bottom)).toInt().coerceAtMost(pixels.height)
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                if (matches(pixels[x, y])) count++
            }
        }
        return count
    }

    private fun currency(amount: BigInteger) = PlnMoney.currency(amount, PlnSign.EXPLICIT_POSITIVE)
}
