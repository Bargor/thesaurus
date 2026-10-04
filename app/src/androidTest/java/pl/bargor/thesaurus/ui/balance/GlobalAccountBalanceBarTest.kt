package pl.bargor.thesaurus.ui.balance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.Destination
import pl.bargor.thesaurus.HouseholdApp
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.ui.entry.EntryFormScreen
import pl.bargor.thesaurus.ui.entry.EntryFormUiState
import pl.bargor.thesaurus.ui.settings.OpeningBalanceScreen
import pl.bargor.thesaurus.ui.settings.OpeningBalanceUiState

class GlobalAccountBalanceBarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun signedPositiveNegativeAndZeroHaveCompleteAccessibleMeaning() {
        var amount by mutableStateOf(12345.toBigInteger())
        compose.setContent { Viewport { GlobalAccountBalanceBar(ready(amount)) } }
        for ((value, status) in listOf(12345 to "Saldo dodatnie", -12345 to "Saldo ujemne", 0 to "Saldo zerowe")) {
            compose.runOnIdle { amount = value.toBigInteger() }
            assertAmount(amount)
            val description = description()
            assertTrue(description.contains("Saldo konta")); assertTrue(description.contains(currency(amount)))
            assertTrue(description.contains(status))
            assertEquals(status, compose.onNodeWithTag("global-account-balance").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        }
    }

    @Test fun loadingAndErrorNeverRenderFakeZeroAndRetryIsActionable() {
        var state by mutableStateOf(GlobalAccountBalanceUiState())
        var retries = 0
        compose.setContent { Viewport { GlobalAccountBalanceBar(state) { retries++ } } }
        compose.onNodeWithTag("global-account-balance-loading", true).assertIsDisplayed()
        compose.onNodeWithTag("global-account-balance-amount", true).assertDoesNotExist()
        compose.runOnIdle { state = GlobalAccountBalanceUiState(isLoading = false, hasError = true, syncState = SyncState.ERROR) }
        compose.onNodeWithTag("global-account-balance-error", true).assertIsDisplayed()
        compose.onNodeWithTag("global-account-balance-amount", true).assertDoesNotExist()
        compose.onNodeWithTag("global-account-balance-retry", true).performClick()
        compose.runOnIdle { assertEquals(1, retries); state = ready(BigInteger.ZERO) }
        assertAmount(BigInteger.ZERO)
    }

    @Test fun pendingAndOfflineMetadataAreAccessibleAlongsideFullAmount() {
        var state by mutableStateOf(ready((-80).toBigInteger()).copy(syncState = SyncState.PENDING))
        compose.setContent { Viewport { GlobalAccountBalanceBar(state) } }
        assertAmount((-80).toBigInteger()); assertTrue(description().contains("oczekujące na synchronizację"))
        compose.runOnIdle { state = state.copy(syncState = SyncState.OFFLINE) }
        assertAmount((-80).toBigInteger()); assertTrue(description().contains("zapisanych na urządzeniu"))
    }

    @Test fun largeAmountsRemainOneLineAcrossNarrowLargeFontDensityAndLandscapeChanges() {
        var width by mutableStateOf(320)
        var fontScale by mutableStateOf(1.8f)
        var density by mutableStateOf(1f)
        var amount by mutableStateOf(123_456_789.toBigInteger())
        compose.setContent { Viewport(width, fontScale, density) { GlobalAccountBalanceBar(ready(amount)) } }
        for (viewport in listOf(320, 720)) for (scale in listOf(1f, 1.8f)) for (display in listOf(1f, 2f)) for (sign in listOf(1, -1)) {
            compose.runOnIdle { width = viewport; fontScale = scale; density = display; amount = (123_456_789L * sign).toBigInteger() }
            assertAmount(amount)
            val value = compose.onNodeWithTag("global-account-balance-amount", true).getUnclippedBoundsInRoot()
            val footer = compose.onNodeWithTag("global-account-balance", true).getUnclippedBoundsInRoot()
            assertTrue("Ordinary large amount must fit in footer", value.left >= footer.left && value.right <= footer.right && value.bottom <= footer.bottom)
            compose.onNodeWithTag("global-account-balance-amount-scroll", true).assertDoesNotExist()
        }
    }

    @Test fun arbitraryPrecisionAmountExposesFullReadableScrollableValueWithoutEllipsis() {
        val amount = BigInteger.TEN.pow(80)
        compose.setContent { Viewport(320, 1.8f) { GlobalAccountBalanceBar(ready(amount)) } }
        assertAmount(amount)
        assertTrue(description().contains(currency(amount)))
        val scroll = compose.onNodeWithTag("global-account-balance-amount-scroll", true)
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(before.maxValue() > 0f)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
        compose.waitForIdle()
        val after = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(after.maxValue(), after.value(), 1f)
    }

    @Test fun horizontalSafeAreaKeepsFullLabelAndAmountInsideSyntheticCutoutMargins() {
        compose.setContent { Viewport {
            GlobalAccountBalanceBar(ready(12345.toBigInteger()), horizontalInsets = WindowInsets(left = 12.dp, right = 18.dp))
        } }
        assertAmount(12345.toBigInteger())
        val footer = compose.onNodeWithTag("global-account-balance", true).getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText("Saldo konta", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val amount = compose.onNodeWithTag("global-account-balance-amount", true).getUnclippedBoundsInRoot()
        for (text in listOf(label, amount)) {
            assertTrue("Text must clear left safe area plus content padding", text.left >= footer.left + 28.dp)
            assertTrue("Text must clear right safe area plus content padding", text.right <= footer.right - 34.dp)
        }
    }

    @Test fun sharedFooterIsCompactFixedAboveNavigationOnTabsNestedRoutesAndScrolledLastAction() {
        var state by mutableStateOf(ready(12345.toBigInteger()))
        compose.setContent { Viewport(320, height = 600) {
            HouseholdApp(
                balanceState = state,
                entriesContent = { settings, add, family, edit ->
                    LazyColumn(Modifier.fillMaxSize().testTag("entry-list-fixture")) {
                        item { Button(onClick = settings, modifier = Modifier.testTag("settings-fixture")) { Text("Ustawienia") } }
                        item { Button(onClick = family, modifier = Modifier.testTag("family-fixture")) { Text("Rodzina") } }
                        items(30) { Text("Wpis $it", Modifier.height(48.dp)) }
                        item { Button(onClick = add, modifier = Modifier.testTag("last-add-fixture")) { Text("Dodaj") } }
                        item { Button(onClick = { edit("fixture-42") }, modifier = Modifier.testTag("last-edit-fixture")) { Text("Edytuj") } }
                    }
                },
                summaryContent = { Text("Podsumowanie testowe") },
                browseContent = { open -> Button(onClick = { open("browse-42") }, modifier = Modifier.testTag("browse-edit-fixture")) { Text("Przegląd testowy") } },
                reportsContent = { Text("Raporty testowe") },
                taxonomyContent = { back -> Button(onClick = back, modifier = Modifier.testTag("settings-back-fixture")) { Text("Kategorie testowe") } },
                entryFormContent = { id, _ -> Text("Formularz testowy: $id", Modifier.testTag("form-fixture")) },
                familyContent = { back -> Button(onClick = back, modifier = Modifier.testTag("family-back-fixture")) { Text("Rodzina testowa") } },
            )
        } }
        val initial = compose.onNodeWithTag("global-account-balance", true).getUnclippedBoundsInRoot()
        assertTrue("Normal footer stays compact", (initial.bottom - initial.top).value in 26f..36f)
        fun assertFooter() {
            assertAmount(state.amountGrosze!!)
            val footer = compose.onNodeWithTag("global-account-balance", true).getUnclippedBoundsInRoot()
            val navigation = compose.onNodeWithTag("bottom-navigation", true).getUnclippedBoundsInRoot()
            assertEquals(initial.top.value, footer.top.value, 1f)
            assertEquals("Footer directly adjoins navigation", footer.bottom.value, navigation.top.value, 1f)
        }
        for (destination in Destination.entries) {
            compose.onNodeWithTag(destination.navigationTestTag).performClick(); assertFooter()
        }
        compose.onNodeWithTag(Destination.Entries.navigationTestTag).performClick()
        compose.onNodeWithTag("settings-fixture").performClick(); assertFooter()
        compose.onNodeWithTag("settings-categories").performClick(); compose.onNodeWithText("Kategorie testowe").assertIsDisplayed(); assertFooter()
        compose.onNodeWithTag("settings-back-fixture").performClick()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag("family-fixture").performClick(); compose.onNodeWithText("Rodzina testowa").assertIsDisplayed(); assertFooter()
        compose.onNodeWithTag("family-back-fixture").performClick()
        compose.onNodeWithTag("entry-list-fixture").performScrollToNode(hasTestTag("last-add-fixture"))
        compose.onNodeWithTag("last-add-fixture").assertIsDisplayed()
        val action = compose.onNodeWithTag("last-add-fixture").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("global-account-balance", true).getUnclippedBoundsInRoot()
        assertTrue("Last add action must remain above footer", action.bottom <= footer.top)
        assertFooter(); compose.onNodeWithTag("last-add-fixture").performClick()
        compose.onNodeWithText("Formularz testowy: null").assertIsDisplayed(); assertFooter()
        compose.onNodeWithTag(Destination.Browse.navigationTestTag).performClick()
        compose.onNodeWithTag("browse-edit-fixture").performClick()
        compose.onNodeWithText("Formularz testowy: browse-42").assertIsDisplayed(); assertFooter()
        compose.runOnIdle { state = ready((-50).toBigInteger()).copy(syncState = SyncState.PENDING) }
        assertFooter()
    }

    @Test fun realNestedFormKeepsFooterAndScrollableSaveAboveVisibleKeyboard() {
        val originalImeSetting = shell("settings get secure show_ime_with_hard_keyboard")
        check(originalImeSetting in listOf("null", "0", "1"))
        val window = compose.activity.window
        val originalSoftInputMode = window.attributes.softInputMode
        @Suppress("DEPRECATION") val originalUiFlags = window.decorView.systemUiVisibility
        try {
        shell("settings put secure show_ime_with_hard_keyboard 1")
        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        var form by mutableStateOf(EntryFormUiState(isLoading = false))
        var imeBottom = 0
        var keyboard: SoftwareKeyboardController? = null
        lateinit var composeView: android.view.View
        compose.setContent { ThesaurusTheme {
            composeView = LocalView.current
            keyboard = LocalSoftwareKeyboardController.current
            imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
            HouseholdApp(
                balanceState = ready(12345.toBigInteger()),
                entriesContent = { _, add, _, _ -> Button(onClick = add, modifier = Modifier.testTag("real-form-add")) { Text("Dodaj") } },
                summaryContent = {}, reportsContent = {}, browseContent = {},
                entryFormContent = { _, _ ->
                    EntryFormScreen(form,
                        onAmountChange = { form = form.copy(amount = it) }, onTitleChange = {}, onTagsChange = {},
                        onDateChange = {}, onTypeChange = {}, onCategorySelected = {}, onSubcategorySelected = {},
                        onSave = {}, onBack = {}, modifier = Modifier.fillMaxSize())
                },
            )
        } }
        compose.onNodeWithTag("real-form-add").performClick()
        compose.onNodeWithTag("entry-amount").performClick().performTextInput("12,50")
        compose.onNodeWithTag("entry-amount").assertIsFocused()
        compose.runOnIdle { keyboard?.show() }
        try { compose.waitUntil(10_000) { imeBottom > 0 } } catch (failure: ComposeTimeoutException) {
            val frame = android.graphics.Rect()
            compose.runOnUiThread { composeView.getWindowVisibleDisplayFrame(frame) }
            throw AssertionError("Focused amount did not publish visible IME: imeBottom=$imeBottom, visibleFrame=$frame, " +
                "viewHeight=${composeView.height}, rootHeight=${composeView.rootView.height}, softInputMode=${window.attributes.softInputMode}", failure)
        }
        assertAmount(12345.toBigInteger())
        compose.onNodeWithTag("entry-save").performScrollTo().assertIsDisplayed()
        val save = compose.onNodeWithTag("entry-save").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("global-account-balance", true).getUnclippedBoundsInRoot()
        val navigation = compose.onNodeWithTag("bottom-navigation", true).getUnclippedBoundsInRoot()
        assertTrue("Form save action must remain reachable above shared footer", save.bottom <= footer.top)
        assertEquals(footer.bottom.value, navigation.top.value, 1f)
        var imeTopInRoot = 0
        compose.runOnIdle {
            val visibleFrame = android.graphics.Rect()
            composeView.getWindowVisibleDisplayFrame(visibleFrame)
            val location = IntArray(2)
            composeView.getLocationOnScreen(location)
            imeTopInRoot = visibleFrame.bottom - location[1]
            assertTrue("This assertion requires a visible IME, not only input focus", imeBottom > 0)
        }
        val navigationPixels = compose.onNodeWithTag("bottom-navigation", true).fetchSemanticsNode().boundsInRoot
        assertTrue("Bottom navigation and footer must sit above the keyboard", navigationPixels.bottom <= imeTopInRoot + 1f)
        } finally {
            compose.runOnUiThread {
                // ComponentActivity test host starts with decor fitting system windows.
                WindowCompat.setDecorFitsSystemWindows(window, true)
                window.setSoftInputMode(originalSoftInputMode)
                restoreSystemUiVisibility(window, originalUiFlags)
            }
            shell(if (originalImeSetting == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $originalImeSetting")
        }
    }

    @Test fun openingBalanceSignAndSaveStayReachableWithTheRealSoftwareKeyboardVisible() {
        val originalImeSetting = shell("settings get secure show_ime_with_hard_keyboard")
        check(originalImeSetting in listOf("null", "0", "1"))
        val window = compose.activity.window
        val originalSoftInputMode = window.attributes.softInputMode
        @Suppress("DEPRECATION") val originalUiFlags = window.decorView.systemUiVisibility
        var keyboard: SoftwareKeyboardController? = null
        var imeBottom = 0
        var form by mutableStateOf(OpeningBalanceUiState(loading = false, isOwner = true))
        var saves = 0
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(window, false) }
            compose.setContent { ThesaurusTheme {
                keyboard = LocalSoftwareKeyboardController.current
                imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                HouseholdApp(
                    balanceState = ready(25_000.toBigInteger()),
                    entriesContent = { settings, _, _, _ ->
                        Button(onClick = settings, modifier = Modifier.testTag("real-balance-settings")) { Text("Ustawienia") }
                    },
                    summaryContent = {}, reportsContent = {}, browseContent = {},
                    openingBalanceContent = { back ->
                        OpeningBalanceScreen(form,
                            onAmountChange = { form = form.copy(amount = it) },
                            onModeChange = { form = form.copy(mode = it) },
                            onSave = { saves++ }, onRetry = {}, onBack = back)
                    },
                )
            } }
            compose.onNodeWithTag("real-balance-settings").performClick()
            compose.onNodeWithTag("settings-opening-balance").performClick()
            compose.onNodeWithTag("opening-balance-amount").performScrollTo().performClick().performTextInput("123,45")
            compose.onNodeWithTag("opening-balance-amount").assertIsFocused()
            compose.runOnIdle { keyboard?.show() }
            compose.waitUntil(10_000) { imeBottom > 0 }
            compose.onNodeWithTag("opening-balance-sign").performScrollTo().assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("opening-balance-amount").assertTextContains("-123,45")
            compose.runOnIdle { assertTrue("Sign action is tested with a visible real IME", imeBottom > 0) }
            val sign = compose.onNodeWithTag("opening-balance-sign").getUnclippedBoundsInRoot()
            val footer = compose.onNodeWithTag("global-account-balance", true).getUnclippedBoundsInRoot()
            assertTrue("Explicit minus target must stay above the shared footer", sign.bottom <= footer.top)
            compose.onNodeWithTag("opening-balance-save").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(1, saves); assertTrue(imeBottom > 0) }
            val save = compose.onNodeWithTag("opening-balance-save").getUnclippedBoundsInRoot()
            assertTrue("Balance save must stay above the shared footer with the IME visible", save.bottom <= footer.top)
            assertAmount(25_000.toBigInteger())
        } finally {
            compose.runOnIdle { keyboard?.hide() }
            compose.runOnUiThread {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                window.setSoftInputMode(originalSoftInputMode)
                restoreSystemUiVisibility(window, originalUiFlags)
            }
            shell(if (originalImeSetting == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $originalImeSetting")
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreSystemUiVisibility(window: android.view.Window, flags: Int) {
        window.decorView.systemUiVisibility = flags
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText().trim() }

    @Composable private fun Viewport(width: Int = 320, fontScale: Float = 1f, density: Float = 1f, height: Int = 600, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            ThesaurusTheme { Box(Modifier.requiredWidth(width.dp).height(height.dp)) { content() } }
        }
    }
    private fun ready(value: BigInteger) = GlobalAccountBalanceUiState(amountGrosze = value, isLoading = false)
    private fun description() = compose.onNodeWithTag("global-account-balance", true).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
    private fun currency(value: BigInteger) = (if (value.signum() > 0) "+" else "") + NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL")).format(BigDecimal(value, 2))
    private fun assertAmount(value: BigInteger) {
        val expected = currency(value)
        compose.onNodeWithTag("global-account-balance-amount", true).assertTextEquals(expected)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(action(layouts)); assertTrue(layouts.isNotEmpty())
                layouts.forEach {
                    val diagnostics = "amount=$expected, size=${it.size}, paragraphWidth=${it.multiParagraph.width}, " +
                        "lineRight=${it.getLineRight(0)}, constraints=${it.layoutInput.constraints}, density=${it.layoutInput.density}, " +
                        "font=${it.layoutInput.style.fontSize}, lineCount=${it.lineCount}"
                    assertEquals(diagnostics, 1, it.lineCount)
                    assertFalse("Amount clips horizontally: $diagnostics", it.didOverflowWidth)
                    assertFalse("Amount clips vertically: $diagnostics", it.didOverflowHeight)
                    assertFalse("Amount uses ellipsis: $diagnostics", it.isLineEllipsized(0))
                    assertEquals(diagnostics, expected.length, it.getLineEnd(0))
                    assertTrue("Keep a readable body font", it.layoutInput.style.fontSize.value >= 14f)
                }
            }
    }
}
