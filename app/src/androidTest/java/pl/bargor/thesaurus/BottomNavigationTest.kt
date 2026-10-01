package pl.bargor.thesaurus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class BottomNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val labels = listOf("Wpisy", "Przegląd", "Podsumowanie", "Raporty")
    private val expectedDestinationOrder = listOf(Destination.Entries, Destination.Browse, Destination.Summary, Destination.Reports)

    @Test
    fun normalBarIs16DpShorterThanThePreviousMaterialBar() {
        assertCompactBaseline()
    }

    @Test
    fun compactBaselineFits320PhysicalPixelsAtHalfDensityWithoutRoundingDrift() {
        val density = assertCompactBaseline(viewportWidthPx = 320)
        assertEquals(0.5f, density, 0f)
        assertEquals(320f, bounds("baseline-viewport").width, 0f)
        assertEquals(32f, bounds("bottom-navigation").height, 0f)
        assertEquals(40f, bounds("previous-navigation").height, 0f)
    }

    private fun assertCompactBaseline(viewportWidthPx: Int? = null): Float {
        var density = 1f
        compose.setContent {
            val deviceDensity = LocalDensity.current.density
            val viewportModifier = if (viewportWidthPx == null) Modifier else
                Modifier.width((viewportWidthPx / deviceDensity).dp)
            BoxWithConstraints(viewportModifier.testTag("baseline-viewport")) {
                // Keep the logical 411 dp fixture within the actual viewport. Binary density
                // steps make 64/80 dp heights and 16 dp label lines exact integer pixels;
                // an arbitrary scale such as 320/411 accumulates component rounding errors.
                val maximumDensity = minOf(deviceDensity, constraints.maxWidth / 411f)
                var fixtureDensity = 1f
                while (fixtureDensity > maximumDensity) fixtureDensity /= 2f
                while (fixtureDensity * 2f <= maximumDensity) fixtureDensity *= 2f
                CompositionLocalProvider(LocalDensity provides Density(fixtureDensity, fontScale = 1f)) {
                    density = fixtureDensity
                    ThesaurusTheme {
                        Column(Modifier.width(411.dp)) {
                            HouseholdNavigationBar(Destination.Entries.route, {}, WindowInsets(0, 0, 0, 0))
                            NavigationBar(Modifier.testTag("previous-navigation"), windowInsets = WindowInsets(0, 0, 0, 0)) {
                                Destination.entries.forEach {
                                    NavigationBarItem(
                                        selected = it == Destination.Entries, onClick = {},
                                        icon = { Icon(Icons.Default.Description, null) },
                                        label = { Text(stringResource(it.labelRes)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val compact = bounds("bottom-navigation")
        val previous = bounds("previous-navigation")
        assertEquals(64f * density, compact.height, 0f)
        assertEquals(80f * density, previous.height, 0f)
        assertEquals(16f * density, previous.height - compact.height, 0f)
        assertContentFits(density)
        return density
    }

    @Test
    fun fourTabsGrowTo80DpAtNarrowPortraitWidthWithoutClippingLabels() {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            ThesaurusTheme {
                Column(Modifier.width(320.dp)) {
                    HouseholdNavigationBar(Destination.Entries.route, {}, WindowInsets(0, 0, 0, 0))
                }
            }
        }
        assertEquals(80f, bounds("bottom-navigation").height / density, 1f)
        assertContentFits(density)
        Destination.entries.forEach {
            val item = bounds(it.navigationTestTag)
            assertTrue(item.width / density >= 48f && item.height / density >= 48f)
        }
    }

    @Test
    fun destinationsHavePolishLabelsTabRolesAndAccessibleTargets() {
        val selected = mutableStateOf(Destination.Entries.route)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            ThesaurusTheme {
                HouseholdNavigationBar(selected.value, { selected.value = it.route }, WindowInsets(0, 0, 0, 0))
            }
        }
        Destination.entries.forEachIndexed { index, destination ->
            val item = compose.onNodeWithTag(destination.navigationTestTag)
            item.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assertTextEquals(labels[index]).assertIsDisplayed().performClick().assertIsSelected()
            val rect = bounds(destination.navigationTestTag)
            assertTrue(rect.width / density >= 48f)
            assertTrue(rect.height / density >= 48f)
            Destination.entries.filter { it != destination }.forEach {
                compose.onNodeWithTag(it.navigationTestTag)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
            }
        }
        assertContentFits(density)
    }

    @Test
    fun bottomAndHorizontalSafeAreaInsetsRemainOutsideTheTouchTargets() {
        val testDensity = mutableStateOf(1f)
        var deviceDensity = 1f
        compose.setContent {
            deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(testDensity.value)) {
                ThesaurusTheme {
                    Column(Modifier.width(320.dp)) {
                        HouseholdNavigationBar(
                            Destination.Entries.route, {},
                            WindowInsets(left = 12.dp, top = 0.dp, right = 18.dp, bottom = 24.dp),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        listOf(1f, deviceDensity).distinct().forEach { density ->
            compose.runOnIdle { testDensity.value = density }
            val bar = bounds("bottom-navigation")
            val first = bounds(Destination.Entries.navigationTestTag)
            val last = bounds(Destination.entries.last().navigationTestTag)
            val leftInsetPx = (12f * density).roundToInt()
            val rightInsetPx = (18f * density).roundToInt()
            val contentWidthPx = bar.width.roundToInt() - leftInsetPx - rightInsetPx
            val itemCount = Destination.entries.size
            // Native EqualWeight divides integer pixels equally and leaves the remainder
            // at the trailing edge when the available width is not evenly divisible.
            val trailingRemainderPx = contentWidthPx % itemCount
            assertTrue("Wrapped four-tab content must retain its natural height", first.height / density >= 80f - 1f)
            assertEquals("Bottom inset must be added below the full natural content height", first.height / density + 24f, bar.height / density, 1f)
            assertEquals(leftInsetPx.toFloat(), first.left - bar.left, 0f)
            assertEquals((rightInsetPx + trailingRemainderPx).toFloat(), bar.right - last.right, 0f)
            assertTrue("The right safe area must remain clear", bar.right - last.right >= rightInsetPx)
            assertEquals(24f, (bar.bottom - first.bottom) / density, 1f)
            Destination.entries.forEach { destination ->
                val item = bounds(destination.navigationTestTag)
                assertEquals((contentWidthPx / itemCount).toFloat(), item.width, 0f)
                assertTrue(item.width / density >= 48f && item.height / density >= 48f)
            }
            assertContentFits(density)
        }
    }

    @Test
    fun largeFontAndDisplayDensityGrowTheBarToFitWrappedLabels() {
        var density = 1f
        compose.setContent {
            val deviceDensity = LocalDensity.current.density
            density = deviceDensity * 1.1f
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.8f)) {
                ThesaurusTheme {
                    Column(Modifier.width(360.dp)) {
                        HouseholdNavigationBar(Destination.Summary.route, {}, WindowInsets(0, 0, 0, 0))
                    }
                }
            }
        }
        assertTrue("Large labels should increase the natural height", bounds("bottom-navigation").height / density > 64f)
        assertContentFits(density)
        Destination.entries.forEach {
            val item = bounds(it.navigationTestTag)
            assertTrue(item.height / density >= 48f && item.width / density >= 48f)
        }
    }

    @Test
    fun selectedIndicatorIsVisibleAndHasSpaceInsideTheCompactItem() {
        var density = 1f
        var indicatorColor = Color.Unspecified
        compose.setContent {
            density = LocalDensity.current.density
            ThesaurusTheme {
                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                HouseholdNavigationBar(Destination.Entries.route, {}, WindowInsets(0, 0, 0, 0))
            }
        }
        val icon = bounds("navigation-entries-icon", unmerged = true)
        val item = bounds("navigation-entries")
        val indicator = Rect(icon.center.x - 28f * density, icon.center.y - 16f * density,
            icon.center.x + 28f * density, icon.center.y + 16f * density)
        assertContains(item, indicator)
        val bar = bounds("bottom-navigation")
        val pixels = compose.onNodeWithTag("bottom-navigation").captureToImage().toPixelMap()
        val x = (icon.left - 8f * density - bar.left).toInt()
        val y = (icon.center.y - bar.top).toInt()
        assertEquals(indicatorColor, pixels[x, y])
    }

    private fun assertContentFits(density: Float) {
        val spatialOrder = Destination.entries.sortedBy { bounds(it.navigationTestTag).left }
        assertEquals("Tabs must appear left to right as Wpisy, Przegląd, Podsumowanie, Raporty", expectedDestinationOrder, spatialOrder)
        expectedDestinationOrder.zipWithNext().forEach { (left, right) ->
            assertTrue("${left.route} must be wholly left of ${right.route}",
                bounds(left.navigationTestTag).right <= bounds(right.navigationTestTag).left + 1f)
        }
        Destination.entries.forEachIndexed { index, destination ->
            val item = bounds(destination.navigationTestTag)
            val icon = bounds("${destination.navigationTestTag}-icon", unmerged = true)
            val label = bounds("${destination.navigationTestTag}-label", unmerged = true)
            compose.onNodeWithTag("${destination.navigationTestTag}-label", useUnmergedTree = true)
                .assertTextEquals(labels[index]).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults ->
                    val results = mutableListOf<TextLayoutResult>()
                    assertTrue("Label must expose its text layout", getResults(results))
                    assertTrue(results.isNotEmpty())
                    results.forEach { result ->
                        val detail = "${result.layoutInput.text}: size=${result.size}, " +
                            "paragraphWidth=${result.multiParagraph.width}, intrinsicWidth=${result.multiParagraph.maxIntrinsicWidth}, " +
                            "lines=${result.lineCount}, bounds=" + (0 until result.lineCount).joinToString {
                                "${result.getLineLeft(it)}..${result.getLineRight(it)} / ${result.getLineTop(it)}..${result.getLineBottom(it)}"
                            }
                        // Material measures text in the whole cell, then reports its intrinsic
                        // width. didOverflowWidth compares those allocations, not glyph bounds.
                        // Check every rendered line against the actual label instead.
                        (0 until result.lineCount).forEach { line ->
                            assertTrue("Label line must fit horizontally: $detail",
                                result.getLineLeft(line) >= -1f && result.getLineRight(line) <= result.size.width + 1f)
                            assertTrue("Label must not be ellipsized: $detail", !result.isLineEllipsized(line))
                        }
                        assertEquals("All label characters must be laid out: $detail",
                            result.layoutInput.text.length, result.getLineEnd(result.lineCount - 1))
                        assertTrue("Label height must not overflow: $detail", !result.didOverflowHeight)
                    }
                }
            assertContains(item, icon)
            assertContains(item, label)
            assertTrue("Icon and label must not overlap", icon.bottom <= label.top)
            assertEquals(24f, icon.height / density, 1f)
            // Native Material indicator/ripple has 4 dp above and below the 24 dp icon.
            assertContains(item, Rect(icon.left, icon.top - 4f * density, icon.right, icon.bottom + 4f * density))
        }
    }

    private fun bounds(tag: String, unmerged: Boolean = false): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    private fun assertContains(outer: Rect, inner: Rect) {
        assertTrue("$inner should fit in $outer", inner.left >= outer.left - 1f && inner.right <= outer.right + 1f &&
            inner.top >= outer.top - 1f && inner.bottom <= outer.bottom + 1f)
    }
}
