package pl.bargor.thesaurus.ui.transfer

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.transfer.*
import pl.bargor.thesaurus.ui.backupjson.*
import pl.bargor.thesaurus.ui.data.*
import pl.bargor.thesaurus.ui.importcsv.*

/** Synthetic fixtures on real 320dp displays / 200% font, including dialog windows. */
class TransferAccessibilityTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val narrowConfiguration = NarrowLargeFontRule()
    @get:Rule val configurationAndCompose: RuleChain = RuleChain.outerRule(narrowConfiguration).around(compose)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val headers = listOf("Data 東京", "Kwota دخل", "Typ", "Tytuł 😀", "Kategoria Żółć", "Podkategoria", "Tagi")
    private val mapping = CsvColumnMapping(date = 0, amount = 1, type = 2, title = 3, category = 4, subcategory = 5, tags = 6)

    private fun display(content: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val configuration = compose.activity.resources.configuration
            assertEquals("The actual activity must use 200% font, including dialog windows", 2f, configuration.fontScale, 0.001f)
            assertTrue("The actual viewport must be 320dp: ${configuration.screenWidthDp}", configuration.screenWidthDp in 319..321)
            // Usable activity height excludes device-specific system insets. The rule
            // independently verifies the exact physical viewport for all dialog windows.
            assertTrue("The usable activity height must fit the configured ${narrowConfiguration.displayHeightDp}dp display: " +
                "${configuration.screenHeightDp}dp", configuration.screenHeightDp in 1..narrowConfiguration.displayHeightDp)
        }
        compose.setContent {
            ThesaurusTheme(darkTheme = false) {
                Surface { Box(Modifier.size(320.dp, 480.dp)) { content() } }
            }
        }
    }

    @Test fun paintedTextBoundsRejectAnIntentionallyClippedFixture() {
        display {
            Text("Intentionally clipped multilingual text 東京 Żółć", modifier = Modifier.width(24.dp).testTag("clipped-fixture"),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
        val error = assertThrows(AssertionError::class.java) { assertTextFits(hasTestTag("clipped-fixture")) }
        val reason = error.message.orEmpty()
        assertTrue("Expected a painted-text clipping failure; actual: $reason", listOf(
            "Text line must fit horizontally", "Text line must fit vertically", "Text must not be ellipsized",
            "Every character must be laid out", "Text height must not overflow",
        ).any(reason::startsWith))
    }

    @Test fun everyManualMappingChoiceIsLabeledScrollableAndHasMinimumTouchTarget() {
        display {
            CsvImportScreen(CsvImportUiState(phase = CsvImportPhase.MAPPING, headers = headers, mapping = mapping), {})
        }
        val fields = listOf(
            "date" to R.string.csv_import_date, "amount" to R.string.csv_import_amount,
            "type" to R.string.csv_import_type, "title" to R.string.csv_import_title_field,
            "category" to R.string.csv_import_category, "subcategory" to R.string.csv_import_subcategory,
            "tags" to R.string.csv_import_tags,
        )
        fields.forEach { (field, label) ->
            val tag = "csv_import_mapping_$field"
            val node = compose.onNodeWithTag(tag).performScrollTo()
            assertAccessibleTarget(node)
            val spoken = node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
            assertTrue("Missing spoken field label for $field: $spoken", spoken.contains(context.getString(label)))
            assertTextFits(hasAnyAncestor(hasTestTag(tag)))
        }
        compose.onNodeWithTag("csv_import_screen").performScrollToNode(hasTestTag("csv_import_date_format"))
        assertAccessibleTarget(compose.onNodeWithTag("csv_import_date_format"))
        compose.onNodeWithTag("csv_import_screen").performScrollToNode(hasTestTag("csv_import_confirm_mapping"))
        assertAccessibleTarget(compose.onNodeWithTag("csv_import_confirm_mapping"))
        assertTextFits(hasAnyAncestor(hasTestTag("csv_import_confirm_mapping")))
    }

    @Test fun multilingualRepairFieldsErrorsAndActionsRemainReachableAtLargeFont() {
        val actions = mutableListOf<CsvImportAction>()
        display { CsvImportScreen(attention(), actions::add) }
        compose.onNodeWithTag("csv_import_screen").performScrollToNode(hasTestTag("csv_import_edit_2"))
        assertAccessibleTarget(compose.onNodeWithTag("csv_import_edit_2"))
        compose.onNodeWithTag("csv_import_edit_2").performClick()
        val labels = listOf(R.string.csv_import_date, R.string.csv_import_amount, R.string.csv_import_type,
            R.string.csv_import_title_field, R.string.csv_import_category, R.string.csv_import_subcategory, R.string.csv_import_tags)
        labels.forEachIndexed { index, label ->
            val tag = "csv_import_edit_field_$index"
            val field = compose.onNodeWithTag(tag)
            scrollWithin("csv_import_editor", field)
            field.assertIsDisplayed().assertTextContains(context.getString(label))
            assertTextFits(hasAnyAncestor(hasTestTag(tag)))
            // TextField's internal scroll is not the viewport for supporting text.
            val textNodes = compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            for (textIndex in textNodes.fetchSemanticsNodes().indices) {
                scrollWithin("csv_import_editor", textNodes[textIndex], fullyVisible = true)
            }
        }
        val issue = hasTestTag("csv_import_issue_2_date_INVALID_DATE") and hasAnyAncestor(hasTestTag("csv_import_editor"))
        val issueNode = compose.onNode(issue, useUnmergedTree = true)
        scrollWithin("csv_import_editor", issueNode, fullyVisible = true)
        assertTrue(issueNode.fetchSemanticsNode().config[SemanticsProperties.Error].isNotBlank())
        assertTextFits(issue)
        val batch = compose.onNodeWithTag("csv_import_batch_mapping").performScrollTo()
        assertAccessibleTarget(batch)
        batch.assertContentDescriptionEquals(context.getString(R.string.csv_import_batch)).assertIsOff()
        batch.performClick().assertIsOn()
        assertTrue("Toggling bulk mode alone must not dispatch a data change", actions.isEmpty())
        listOf("csv_import_category", "csv_import_subcategory", "csv_import_revalidate", "csv_import_save_repair").forEach { tag ->
            assertAccessibleTarget(compose.onNodeWithTag(tag).performScrollTo())
            assertTextFits(hasAnyAncestor(hasTestTag(tag)))
        }
        compose.onNodeWithTag("csv_import_category").performScrollTo().performClick()
        compose.onNode(hasText("Żywność 東京") and hasAnyAncestor(isPopup())).performClick()
        assertEquals(listOf(CsvImportAction.Category(2, "food", true)), actions)
        actions.clear()
        // Inspect the original multilingual source and edit callback, beyond static labels.
        compose.onNodeWithTag("csv_import_edit_field_3").performScrollTo().performTextReplacement("Żółć 東京 دخل 😀 poprawione")
        assertEquals(CsvImportAction.Edit(2, attention().report!!.rows.single().source.cells.toMutableList().apply {
            this[3] = "Żółć 東京 دخل 😀 poprawione"
        }), actions.single())
    }

    @Test fun csvCompletedCountsAreReadableAndHeadingIsExposed() {
        val counts = context.getString(R.string.csv_import_summary_counts, 1, 2, 3, 4, 0)
        display { CsvImportScreen(CsvImportUiState(phase = CsvImportPhase.COMPLETE,
            report = CsvImportReport(emptyList(), ImportSummary(10, 10, 0, added = 1, repairedAndAdded = 2,
                duplicates = 3, discarded = 4))), {}) }
        compose.onNodeWithText(counts).performScrollTo().assertIsDisplayed()
        assertTextFits(hasText(counts))
        compose.onNode(hasText(context.getString(R.string.csv_import_summary)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = true).assertExists()
    }

    @Test fun jsonCompletedCountsAndExportRemainReadableAndDoNotOverlap() {
        val report = JsonMergeReport(JsonMergeStatus.entries.mapIndexed { index, status ->
            JsonMergeItem(JsonMergeItemKind.ENTRY, "Żółć-東京-$index", "target-$index", status,
                if (status == JsonMergeStatus.CONFLICT) "JSON_ENTRY_CONFLICT" else null)
        })
        display { JsonBackupScreen(JsonBackupUiState(phase = JsonBackupPhase.COMPLETE, report = report, canRetry = true), {}) }
        val counts = context.getString(R.string.json_backup_kind_counts, context.getString(R.string.json_backup_entries), 1, 1, 1, 1, 1)
        compose.onNodeWithText(counts).performScrollTo().assertIsDisplayed()
        assertTextFits(hasText(counts))
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_conflict_reason"))
        compose.onNodeWithTag("json_backup_conflict_reason").assertIsDisplayed()
        assertTextFits(hasTestTag("json_backup_conflict_reason"))
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_export"))
        assertAccessibleTarget(compose.onNodeWithTag("json_backup_export"))
        assertTextFits(hasAnyAncestor(hasTestTag("json_backup_export")))
        val retry = compose.onNodeWithTag("json_backup_retry").fetchSemanticsNode().boundsInRoot
        val export = compose.onNodeWithTag("json_backup_export").fetchSemanticsNode().boundsInRoot
        assertTrue("Retry and export touch targets overlap", retry.bottom <= export.top)
    }

    @Test fun privacyWarningAndExplicitActionsFitAtLargeFontBeforeExport() {
        assertPrivacyWarningReachable()
    }

    @Test fun privacyWarningAndExplicitActionsFitAtLargeFontOnShortViewport() {
        assertPrivacyWarningReachable()
    }

    private fun assertPrivacyWarningReachable() {
        var confirms = 0
        var dismisses = 0
        display { DataScreen(DataUiState(warning = DataFormat.JSON), {}, {}, { confirms++ }, { dismisses++ }, {}) }
        val warning = compose.onNodeWithText(context.getString(R.string.data_privacy_warning))
        val title = compose.onNodeWithTag("data-privacy-title")
        title.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertTextFits(hasTestTag("data-privacy-title"))
        assertTextFits(hasText(context.getString(R.string.data_privacy_warning)))
        val noticeScroll = compose.onNodeWithTag("data-privacy-notice-scroll")
        noticeScroll.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
        noticeScroll.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true))
        val range = noticeScroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("The short viewport must exercise notice scrolling", range.maxValue() > 0f)
        scrollNoticeLineIntoView(title, noticeScroll, lastLine = false)
        scrollNoticeLineIntoView(title, noticeScroll, lastLine = true)
        scrollNoticeLineIntoView(warning, noticeScroll, lastLine = false)
        scrollNoticeLineIntoView(warning, noticeScroll, lastLine = true)
        val confirm = compose.onNodeWithTag("data-privacy-confirm")
        val cancel = compose.onNodeWithTag("data-privacy-cancel")
        assertAccessibleTarget(confirm)
        assertAccessibleTarget(cancel)
        assertTextFits(hasAnyAncestor(hasTestTag("data-privacy-confirm")))
        assertTextFits(hasAnyAncestor(hasTestTag("data-privacy-cancel")))
        val warningBounds = noticeScroll.fetchSemanticsNode().boundsInRoot
        val confirmBounds = confirm.fetchSemanticsNode().boundsInRoot
        val cancelBounds = cancel.fetchSemanticsNode().boundsInRoot
        assertTrue("Privacy warning overlaps confirmation", warningBounds.bottom <= confirmBounds.top)
        assertTrue("Privacy actions overlap: confirm=$confirmBounds, cancel=$cancelBounds, " +
            "intersection=${confirmBounds.intersect(cancelBounds)}", confirmBounds.intersect(cancelBounds).isEmpty)
        assertEquals(0, confirms)
        assertEquals(0, dismisses)
        cancel.performClick()
        assertEquals(0, confirms)
        assertEquals(1, dismisses)
        confirm.performClick()
        assertEquals(1, confirms)
        assertEquals(1, dismisses)
    }

    /** Scroll the named outer viewport, bypassing nested editable-text scroll semantics. */
    private fun scrollWithin(viewportTag: String, node: SemanticsNodeInteraction, fullyVisible: Boolean = false) {
        val viewport = compose.onNodeWithTag(viewportTag)
        val visible = viewport.fetchSemanticsNode().boundsInRoot
        val density = viewport.fetchSemanticsNode().layoutInfo.density.density
        val bounds = node.getUnclippedBoundsInRoot()
        val top = bounds.top.value * density
        val bottom = bounds.bottom.value * density
        val delta = when {
            bottom - top > visible.height -> (top + bottom - visible.top - visible.bottom) / 2f
            top < visible.top -> top - visible.top
            bottom > visible.bottom -> bottom - visible.bottom
            else -> 0f
        }
        if (delta != 0f) viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        node.assertIsDisplayed()
        if (fullyVisible) {
            val after = node.getUnclippedBoundsInRoot()
            assertTrue("Text must be fully reachable in $viewportTag: text=$after, viewport=$visible",
                after.top.value * density >= visible.top - 1f && after.bottom.value * density <= visible.bottom + 1f)
        }
    }

    private fun scrollNoticeLineIntoView(text: SemanticsNodeInteraction, viewport: SemanticsNodeInteraction, lastLine: Boolean) {
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val line = if (lastLine) layout.lineCount - 1 else 0
        val density = text.fetchSemanticsNode().layoutInfo.density.density
        val textTop = text.getUnclippedBoundsInRoot().top.value * density
        val visible = viewport.fetchSemanticsNode().boundsInRoot
        val beforeTop = textTop + layout.getLineTop(line)
        val beforeBottom = textTop + layout.getLineBottom(line)
        val delta = when {
            beforeTop < visible.top -> beforeTop - visible.top
            beforeBottom > visible.bottom -> beforeBottom - visible.bottom
            else -> 0f
        }
        if (delta != 0f) viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        text.assertIsDisplayed()
        val afterTop = text.getUnclippedBoundsInRoot().top.value * density
        val lineTop = afterTop + layout.getLineTop(line)
        val lineBottom = afterTop + layout.getLineBottom(line)
        assertTrue("Notice line $line must be visible after scrolling: line=$lineTop..$lineBottom, viewport=$visible",
            lineTop >= visible.top - 1f && lineBottom <= visible.bottom + 1f)
    }

    private fun assertAccessibleTarget(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed().assertHasClickAction()
        val semantics = node.fetchSemanticsNode()
        val bounds = semantics.boundsInRoot
        val pixelsPerDp = semantics.layoutInfo.density.density
        assertTrue("Touch target height ${bounds.height / pixelsPerDp}dp is below 48dp (density=$pixelsPerDp, bounds=$bounds)", bounds.height + 1f >= 48f * pixelsPerDp)
        assertTrue("Touch target width is below 48dp (density=$pixelsPerDp, bounds=$bounds)", bounds.width + 1f >= 48f * pixelsPerDp)
        assertTrue("Clickable control has no spoken label", semantics.config.contains(SemanticsProperties.Text) ||
            semantics.config.contains(SemanticsProperties.ContentDescription))
    }

    private fun assertTextFits(matcher: SemanticsMatcher) {
        val layouts = mutableListOf<TextLayoutResult>()
        val nodes = compose.onAllNodes(matcher and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        assertTrue("Expected text layout semantics", nodes.fetchSemanticsNodes().isNotEmpty())
        for (index in nodes.fetchSemanticsNodes().indices) {
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        }
        layouts.forEach { layout ->
            val input = layout.layoutInput
            val lines = (0 until layout.lineCount).joinToString { line ->
                "${layout.getLineLeft(line)}..${layout.getLineRight(line)} / ${layout.getLineTop(line)}..${layout.getLineBottom(line)}"
            }
            val detail = "${input.text}; size=${layout.size}; constraints=${input.constraints}; " +
                "maxLines=${input.maxLines}; lineCount=${layout.lineCount}; density=${input.density}; " +
                "fontScale=${input.density.fontScale}; paragraphWidth=${layout.multiParagraph.width}; " +
                "widthOverflow=${layout.didOverflowWidth}; heightOverflow=${layout.didOverflowHeight}; lines=$lines"
            assertEquals("Text must actually exercise 200% font: $detail", 2f, input.density.fontScale, 0.001f)
            // Material can measure a paragraph in the full cell then report an intrinsic label
            // width. didOverflowWidth compares those allocations; inspect painted lines instead.
            (0 until layout.lineCount).forEach { line ->
                assertTrue("Text line must fit horizontally: $detail", layout.getLineLeft(line) >= -1f &&
                    layout.getLineRight(line) <= layout.size.width + 1f)
                assertFalse("Text must not be ellipsized: $detail", layout.isLineEllipsized(line))
                assertTrue("Text line must fit vertically: $detail", layout.getLineTop(line) >= -1f &&
                    layout.getLineBottom(line) <= layout.size.height + 1f)
            }
            assertEquals("Every character must be laid out: $detail", input.text.length, layout.getLineEnd(layout.lineCount - 1))
            assertFalse("Text height must not overflow: $detail", layout.didOverflowHeight)
        }
    }

    /** Apply a real emulator configuration before launching Compose, then restore exact settings. */
    private class NarrowLargeFontRule : TestRule {
        var displayHeightDp: Int = 640
            private set

        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
                val oldFont = shell("settings get system font_scale")
                require(oldFont == "null" || oldFont.toFloatOrNull() != null) { "Unexpected saved font scale: $oldFont" }
                val oldDensity = Regex("Override density:\\s*(\\d+)").find(shell("wm density"))?.groupValues?.get(1)
                val oldSize = Regex("Override size:\\s*(\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
                try {
                    shell("settings put system font_scale 2.0")
                    val height = if (description.methodName.endsWith("OnShortViewport")) 480 else 640
                    displayHeightDp = height
                    shell("wm size 320x$height")
                    shell("wm density 160")
                    automation.waitForIdle(100, 5000)
                    val appliedSize = shell("wm size")
                    val effectiveSize = Regex("Override size:\\s*(\\d+x\\d+)").find(appliedSize)?.groupValues?.get(1)
                        ?: Regex("Physical size:\\s*(\\d+x\\d+)").find(appliedSize)?.groupValues?.get(1)
                    assertEquals("The real display must match the requested viewport: $appliedSize", "320x$height", effectiveSize)
                    val appliedDensity = shell("wm density")
                    val effectiveDensity = Regex("Override density:\\s*(\\d+)").find(appliedDensity)?.groupValues?.get(1)
                        ?: Regex("Physical density:\\s*(\\d+)").find(appliedDensity)?.groupValues?.get(1)
                    assertEquals("The real display must use mdpi: $appliedDensity", "160", effectiveDensity)
                    assertEquals("The real system must use 200% font", 2f,
                        shell("settings get system font_scale").toFloatOrNull() ?: Float.NaN, 0.001f)
                    base.evaluate()
                } finally {
                    try {
                        if (oldFont == "null") shell("settings delete system font_scale")
                        else shell("settings put system font_scale $oldFont")
                    } finally {
                        try {
                            if (oldDensity == null) shell("wm density reset") else shell("wm density $oldDensity")
                        } finally {
                            if (oldSize == null) shell("wm size reset") else shell("wm size $oldSize")
                            automation.waitForIdle(100, 5000)
                        }
                    }
                }
            }
        }
    }

    private fun attention(): CsvImportUiState {
        val row = ImportRow(2, listOf("bad", "-12.50", "expense", "Żółć 東京 دخل 😀", "Żywność 東京", "", "[\"東京\"]"),
            listOf(ValidationIssue("INVALID_DATE", "date", 2)))
        return CsvImportUiState(phase = CsvImportPhase.ATTENTION, headers = headers, mapping = mapping,
            report = CsvImportReport(listOf(CsvImportRowResult(row, CsvImportRowStatus.INVALID)), ImportSummary(1, 0, 1, row.issues), listOf(row)),
            total = 1, processed = 1, attention = 1,
            categories = listOf(Category("food", "synthetic", "Żywność 東京", authorId = "author", updatedById = "author")))
    }
}
