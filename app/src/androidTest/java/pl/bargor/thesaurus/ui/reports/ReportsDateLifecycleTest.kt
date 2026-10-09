package pl.bargor.thesaurus.ui.reports

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.ReportsRoute
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

/** Real route + retained VM, with synthetic data only: no Firebase, auth or device date changes. */
@RunWith(AndroidJUnit4::class)
class ReportsDateLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var owner: ReportTestLifecycle
    private lateinit var vm: ReportsViewModel
    private lateinit var clock: ReportTestClock
    private lateinit var repository: ReportDateRepository
    private var visible by mutableStateOf(true)
    private var household by mutableStateOf("home")

    @After fun clearRetainedViewModel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = false
            if (::owner.isInitialized) owner.moveTo(Lifecycle.State.DESTROYED)
            store.clear()
        }
    }

    @Test fun resumeRefreshesSameViewModelWithoutChangingFiltersSortOrHistoricalSelection() {
        openRoute("2026-02-15T12:00:00Z")
        compose.runOnIdle {
            vm.selectCategory("food")
            vm.selectMembers(setOf("actor"))
            vm.selectSort(ReportEntrySort.AMOUNT)
            vm.toggleSortDirection()
            vm.openFilters()
            vm.selectSort(ReportEntrySort.DATE)
        }
        val retained = vm
        val before = vm.state.value
        assertScope(1_250, listOf("today"))
        compose.runOnIdle { owner.moveTo(Lifecycle.State.CREATED) }
        clock.setInstant("2026-02-16T12:00:00Z")
        assertEquals(LocalDate.of(2026, 2, 15), vm.state.value.today)
        compose.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
        compose.waitUntil(5_000) { vm.state.value.today == LocalDate.of(2026, 2, 16) }
        assertSame(retained, vm)
        assertEquals(before.month, vm.state.value.month)
        assertEquals(before.selectedCategoryId, vm.state.value.selectedCategoryId)
        assertEquals(before.selectedMemberIds, vm.state.value.selectedMemberIds)
        assertEquals(before.sort, vm.state.value.sort)
        assertEquals(before.direction, vm.state.value.direction)
        assertEquals(before.filterDraft, vm.state.value.filterDraft)
        assertScope(3_050, listOf("tomorrow", "today"))
        // A deliberately selected past month survives another background/resume cycle.
        compose.runOnIdle { vm.dismissFilters(); vm.previousPeriod() }
        val historical = vm.state.value
        compose.runOnIdle { owner.moveTo(Lifecycle.State.CREATED) }
        clock.setInstant("2026-03-01T12:00:00Z")
        compose.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
        compose.waitUntil(5_000) { vm.state.value.today == LocalDate.of(2026, 3, 1) }
        assertSame(retained, vm)
        assertEquals(historical.month, vm.state.value.month)
        assertEquals(historical.entries, vm.state.value.entries)
        assertEquals(historical.aggregation, vm.state.value.aggregation)
        assertEquals(before.selectedCategoryId, vm.state.value.selectedCategoryId)
        assertEquals(before.sort, vm.state.value.sort)
        assertEquals(before.direction, vm.state.value.direction)
        assertEquals(1, repository.starts.get()) // Resume must not replace the source subscription.
    }

    @Test fun foregroundMidnightUpdatesTotalsChartsAndListAndProducesPaintedEvidence() {
        // Initial time fixes the delay to two seconds. Only the injected clock crosses midnight.
        openRoute("2026-02-15T23:59:58Z")
        assertScope(1_250, listOf("today"))
        compose.onNodeWithTag("reports-expense", useUnmergedTree = true).assertTextEquals("12,50\u00a0zł")
        capture("before-midnight", "reports-expense")
        val retained = vm
        clock.setInstant("2026-02-16T00:00:00Z")
        // Bounded polling waits for the production midnight job, not a manual VM refresh.
        compose.waitUntil(8_000) { vm.state.value.today == LocalDate.of(2026, 2, 16) }
        assertSame(retained, vm)
        assertScope(3_050, listOf("tomorrow", "today"))
        compose.onNodeWithTag("reports-expense", useUnmergedTree = true).assertTextEquals("30,50\u00a0zł")
        capture("after-midnight", "reports-expense")
        compose.onNodeWithTag("reports-balance-total").performScrollTo().assertIsDisplayed()
        capture("after-midnight-balance", "reports-balance-total")
        compose.onNodeWithTag("report-entry-tomorrow").performScrollTo().assertIsDisplayed()
        capture("after-midnight-new-entry", "report-entry-tomorrow")
        compose.onNodeWithTag("report-entry-later").assertDoesNotExist()
        assertEquals(1, repository.starts.get())
    }

    @Test fun pauseStopAndDisposalCancelMidnightWorkAndHouseholdSwitchCancelsOldSource() {
        openRoute("2026-02-15T23:59:59Z")
        compose.runOnIdle { owner.moveTo(Lifecycle.State.STARTED) } // ON_PAUSE alone must cancel.
        clock.setInstant("2026-02-16T00:00:00Z")
        assertNoClockReadForScheduledMidnight()
        assertEquals(LocalDate.of(2026, 2, 15), vm.state.value.today)

        clock.setInstant("2026-02-16T23:59:59Z")
        compose.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
        compose.waitUntil(5_000) { vm.state.value.today == LocalDate.of(2026, 2, 16) }
        compose.runOnIdle { owner.moveTo(Lifecycle.State.CREATED) } // ON_STOP.
        clock.setInstant("2026-02-17T00:00:00Z")
        assertNoClockReadForScheduledMidnight()
        assertEquals(LocalDate.of(2026, 2, 16), vm.state.value.today)

        clock.setInstant("2026-02-17T23:59:59Z")
        compose.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
        compose.runOnIdle { household = "other" }
        compose.waitUntil(5_000) { repository.starts.get() == 2 && repository.cancellations.get() == 1 }
        assertEquals(1, repository.active.get())
        compose.runOnIdle { visible = false }
        clock.setInstant("2026-02-18T00:00:00Z")
        assertNoClockReadForScheduledMidnight()
        assertEquals(LocalDate.of(2026, 2, 17), vm.state.value.today)
        compose.runOnIdle { store.clear() }
        compose.waitUntil(5_000) { repository.active.get() == 0 }
        assertEquals(2, repository.cancellations.get())
    }

    private fun openRoute(instant: String) {
        clock = ReportTestClock(Instant.parse(instant))
        repository = ReportDateRepository()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = ReportTestLifecycle().apply { moveTo(Lifecycle.State.RESUMED) }
            vm = ReportsViewModel(repository, repository, clock, SavedStateHandle(), repository)
            store.put("reports", vm)
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ThesaurusTheme(darkTheme = false) {
                    Surface {
                        Box(Modifier.fillMaxSize().testTag("reports-date-proof")) {
                            if (visible) ReportsRoute(household, {}, vm)
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) { !vm.state.value.isLoading && vm.state.value.balanceTrend != null }
    }

    private fun assertScope(expense: Int, ids: List<String>) {
        compose.runOnIdle {
            val state = vm.state.value
            assertEquals(ids, state.entries.map { it.entry.id })
            assertTrue(state.entries.all { it.entry.date <= state.today })
            assertEquals(expense.toBigInteger(), state.aggregation.totals.expenseGrosze)
            assertEquals((-expense).toBigInteger(), state.aggregation.totals.netGrosze)
            assertEquals(expense.toBigInteger(), state.aggregation.categories.single().amountGrosze)
            assertEquals((-expense).toBigInteger(), state.aggregation.trend.fold(java.math.BigInteger.ZERO) { sum, value -> sum + value.amountGrosze })
            val balance = requireNotNull(state.balanceTrend)
            // January history contributes to absolute balance, but never to February period totals.
            assertEquals(10_000.toBigInteger(), balance.startBalanceGrosze)
            assertEquals((10_000 - expense).toBigInteger(), balance.endBalanceGrosze)
            assertTrue(balance.buckets.all { it.to <= state.today })
        }
    }

    private fun assertNoClockReadForScheduledMidnight() {
        // Negative timer assertion: wait beyond its one-second deadline without sleeping the UI.
        val read = CountDownLatch(1)
        clock.onRead = { read.countDown() }
        try {
            assertFalse("A cancelled midnight timer still read the clock", read.await(1_500, TimeUnit.MILLISECONDS))
        } finally {
            clock.onRead = null
        }
    }

    private fun capture(name: String, paintedTag: String) {
        compose.waitForIdle()
        val text = compose.onNodeWithTag(paintedTag, useUnmergedTree = true).captureToImage().asAndroidBitmap()
        // Check actual glyph pixels; a blank capture with valid semantics fails this assertion.
        var darkPixels = 0
        for (y in 0 until text.height) for (x in 0 until text.width) {
            val pixel = text.getPixel(x, y)
            if (Color.alpha(pixel) > 200 && Color.red(pixel) < 110 && Color.green(pixel) < 110 && Color.blue(pixel) < 110) darkPixels++
        }
        assertTrue("No painted foreground in $name ($darkPixels pixels)", darkPixels > 30)
        val bitmap = compose.onNodeWithTag("reports-date-proof").captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ThesaurusTestEvidence/issue97/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(collection, values))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}

private class ReportTestLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun moveTo(state: Lifecycle.State) { registry.currentState = state }
}

private class ReportTestClock(@Volatile private var value: Instant) : Clock() {
    @Volatile var onRead: (() -> Unit)? = null
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = object : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this@ReportTestClock.withZone(zone)
        override fun instant(): Instant = this@ReportTestClock.instant()
    }
    override fun instant(): Instant { onRead?.invoke(); return value }
    fun setInstant(instant: String) { value = Instant.parse(instant) }
}

private class ReportDateRepository : LedgerRepository, TaxonomyRepository, HouseholdRepository {
    val starts = AtomicInteger()
    val cancellations = AtomicInteger()
    val active = AtomicInteger()
    private val food = Category("food", "home", "Jedzenie", color = "amber", authorId = "actor", updatedById = "actor")
    private fun entry(id: String, amount: Long, date: String) = LedgerEntry(id, "home", amount,
        LocalDate.parse(date), title = when (id) { "today" -> "Zakupy dzisiaj"; "tomorrow" -> "Zakupy jutro"; else -> id },
        categoryId = "food", authorId = "actor", updatedById = "actor")
    private val entries = MutableStateFlow(SyncObservation(listOf(
        entry("history", 10_000, "2026-01-12"),
        entry("today", -1_250, "2026-02-15"),
        entry("tomorrow", -1_800, "2026-02-16"),
        entry("later", -99_999, "2026-02-20"),
    ), SyncState.SYNCED))

    override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = flow {
        starts.incrementAndGet(); active.incrementAndGet()
        try {
            emitAll(entries.map { snapshot -> snapshot.copy(value = snapshot.value?.map { it.copy(householdId = householdId) }) })
        } finally { active.decrementAndGet(); cancellations.incrementAndGet() }
    }
    override fun observeCategories(householdId: String) = flowOf(SyncObservation(listOf(food.copy(householdId = householdId)), SyncState.SYNCED))
    override fun observeSubcategories(householdId: String, categoryId: String) = flowOf(SyncObservation(emptyList<Subcategory>(), SyncState.SYNCED))
    override fun observeHousehold(householdId: String) = flowOf(SyncObservation(Household(householdId, "Dom testowy", "actor", openingBalanceGrosze = 0), SyncState.SYNCED))
    override fun observeMembers(householdId: String) = flowOf(SyncObservation(emptyList<Member>(), SyncState.SYNCED))
    override suspend fun save(entry: LedgerEntry): Unit = error("Read-only synthetic fixture")
    override suspend fun tombstone(householdId: String, entryId: String, actorId: String): Unit = error("Read-only synthetic fixture")
    override suspend fun save(category: Category): Unit = error("Read-only synthetic fixture")
    override suspend fun save(subcategory: Subcategory): Unit = error("Read-only synthetic fixture")
    override suspend fun saveHousehold(household: Household): Unit = error("Read-only synthetic fixture")
    override suspend fun removeMember(householdId: String, memberId: String): Unit = error("Read-only synthetic fixture")
}
