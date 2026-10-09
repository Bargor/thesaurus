package pl.bargor.thesaurus.ui.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsDateRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test fun midnightRecalculatesCachedTotalsEntriesAndBothChartsWithoutRepositoryEmission() = runTest {
        val clock = MovingClock(testScheduler, "2026-09-12T23:59:59Z")
        val vm = model(clock, listOf(entry("income", 600, "2026-09-12"),
            entry("expense", -200, "2026-09-13"), entry("future", 99_999, "2026-09-14")))
        vm.start("home"); runCurrent()
        assertEquals(listOf("income"), vm.state.value.entries.map { it.entry.id })
        assertEquals(600.toBigInteger(), vm.state.value.balanceTrend!!.endBalanceGrosze)
        vm.setForeground("home", true); runCurrent()
        advanceTimeBy(999); runCurrent()
        assertEquals(LocalDate.parse("2026-09-12"), vm.state.value.today)
        advanceTimeBy(1); runCurrent()
        val state = vm.state.value
        assertEquals(LocalDate.parse("2026-09-13"), state.today)
        assertEquals(setOf("income", "expense"), state.entries.map { it.entry.id }.toSet())
        assertEquals(600.toBigInteger(), state.aggregation.totals.incomeGrosze)
        assertEquals(200.toBigInteger(), state.aggregation.totals.expenseGrosze)
        assertEquals(400.toBigInteger(), state.aggregation.totals.netGrosze)
        assertEquals(listOf(ReportCategoryValue("food", 800.toBigInteger())), state.aggregation.categories)
        assertEquals(listOf(LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-13")),
            state.aggregation.trend.map { it.date })
        assertEquals(400.toBigInteger(), state.balanceTrend!!.endBalanceGrosze)
        assertEquals(state.today, state.balanceTrend!!.buckets.last().to)
        vm.setForeground("home", false); advanceUntilIdle()
    }

    @Test fun retainedStartAndResumeRefreshDateAfterBackgroundWithoutFollowingSelectedMonth() = runTest {
        val clock = MovingClock(testScheduler, "2026-09-30T12:00:00Z")
        val vm = model(clock, listOf(entry("september", 100, "2026-09-30"), entry("october", 900, "2026-10-01")))
        vm.start("home"); runCurrent()
        clock.jumpTo("2026-10-01T12:00:00Z")
        vm.start("home"); runCurrent()
        assertEquals(LocalDate.parse("2026-10-01"), vm.state.value.today)
        assertEquals(YearMonth.of(2026, 9), vm.state.value.month)
        assertEquals(listOf("september"), vm.state.value.entries.map { it.entry.id })
        clock.jumpTo("2026-10-02T12:00:00Z")
        vm.setForeground("home", true); runCurrent()
        assertEquals(LocalDate.parse("2026-10-02"), vm.state.value.today)
        vm.nextPeriod()
        assertEquals(YearMonth.of(2026, 10), vm.state.value.month)
        assertEquals(listOf("october"), vm.state.value.entries.map { it.entry.id })
        vm.nextPeriod()
        assertEquals(YearMonth.of(2026, 10), vm.state.value.month)
        vm.setForeground("home", false); advanceUntilIdle()
    }

    @Test fun startingWithFixedClockWithoutForegroundDoesNotScheduleRecurringWork() = runTest {
        val vm = model(Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC))
        vm.start("home"); advanceUntilIdle()
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(LocalDate.parse("2026-09-12"), vm.state.value.today)
    }

    @Test fun foregroundTimerRearmsForFollowingMidnight() = runTest {
        val clock = MovingClock(testScheduler, "2026-09-12T23:59:59Z")
        val vm = model(clock)
        vm.start("home"); vm.setForeground("home", true); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(LocalDate.parse("2026-09-13"), vm.state.value.today)
        advanceTimeBy(86_400_000L); runCurrent()
        assertEquals(LocalDate.parse("2026-09-14"), vm.state.value.today)
        vm.setForeground("home", false); advanceUntilIdle()
    }

    @Test fun monthYearAndLeapDayRolloverKeepExplicitSelectionsAndUnlockNavigation() = runTest {
        for ((before, after) in listOf("2024-02-28" to "2024-02-29",
            "2024-02-29" to "2024-03-01", "2026-12-31" to "2027-01-01")) {
            val clock = MovingClock(testScheduler, "${before}T23:59:59Z")
            val vm = model(clock)
            vm.start("home"); runCurrent()
            vm.selectPeriodMode(ReportPeriodMode.YEAR)
            vm.setForeground("home", true); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertEquals(LocalDate.parse(after), vm.state.value.today)
            assertEquals(YearMonth.from(LocalDate.parse(before)), vm.state.value.month)
            assertEquals(Year.from(LocalDate.parse(before)), vm.state.value.year)
            vm.nextPeriod()
            assertEquals(Year.from(LocalDate.parse(after)), vm.state.value.year)
            vm.selectPeriodMode(ReportPeriodMode.MONTH); vm.nextPeriod()
            assertEquals(YearMonth.from(LocalDate.parse(after)), vm.state.value.month)
            vm.setForeground("home", false); runCurrent()
        }
        advanceUntilIdle()
    }

    @Test fun historicCustomScopeAndAppliedFiltersSurviveCalendarRefresh() = runTest {
        val clock = MovingClock(testScheduler, "2026-09-30T12:00:00Z")
        val vm = model(clock, listOf(entry("historic", -100, "2026-08-31"), entry("current", -900, "2026-09-30")))
        vm.start("home"); runCurrent()
        vm.openFilters(); vm.selectPeriodMode(ReportPeriodMode.CUSTOM)
        vm.updateCustomFrom("2026-08-01"); vm.updateCustomTo("2026-08-31")
        vm.selectCategory("food"); vm.selectMembers(setOf("actor")); vm.selectType(ReportTypeFilter.EXPENSE)
        vm.selectSort(ReportEntrySort.AMOUNT); vm.toggleSortDirection(); vm.applyFilters()
        val previous = vm.state.value
        clock.jumpTo("2027-01-01T12:00:00Z"); vm.refreshCalendar()
        assertEquals(previous.copy(today = LocalDate.parse("2027-01-01")), vm.state.value)
        assertEquals(listOf("historic"), vm.state.value.entries.map { it.entry.id })
    }

    @Test fun openDraftKeepsChoicesAndResetUsesNewDate() = runTest {
        val clock = MovingClock(testScheduler, "2026-12-31T12:00:00Z")
        val vm = model(clock)
        vm.start("home"); runCurrent()
        vm.openFilters(); vm.selectPeriodMode(ReportPeriodMode.CUSTOM)
        vm.updateCustomFrom("2026-08-01"); vm.updateCustomTo("2026-08-31")
        vm.selectCategory("food"); vm.selectMembers(setOf("actor")); vm.selectType(ReportTypeFilter.EXPENSE)
        vm.selectSort(ReportEntrySort.AMOUNT); vm.toggleSortDirection()
        val draft = vm.state.value.filterDraft
        clock.jumpTo("2027-01-01T12:00:00Z"); vm.refreshCalendar()
        assertEquals(draft, vm.state.value.filterDraft)
        vm.resetFilters()
        assertEquals(ReportFilterDraft(ReportPeriodMode.MONTH, YearMonth.of(2027, 1), Year.of(2027),
            customFromInput = "2027-01-01", customToInput = "2027-01-01"), vm.state.value.filterDraft)
        assertEquals(YearMonth.of(2026, 12), vm.state.value.month)
    }

    @Test fun previouslyFutureCustomDateBecomesValidAndTomorrowRemainsInvalidAfterResume() = runTest {
        val clock = MovingClock(testScheduler, "2026-09-12T12:00:00Z")
        val vm = model(clock, listOf(entry("new-day", 400, "2026-09-13")))
        vm.start("home"); runCurrent()
        vm.openFilters(); vm.selectPeriodMode(ReportPeriodMode.CUSTOM)
        vm.updateCustomFrom("2026-09-13"); vm.updateCustomTo("2026-09-13"); vm.applyFilters()
        assertTrue(vm.state.value.filterDraft!!.customDateError)
        clock.jumpTo("2026-09-13T12:00:00Z")
        vm.setForeground("home", true); runCurrent(); vm.applyFilters()
        assertNull(vm.state.value.filterDraft)
        assertEquals(listOf("new-day"), vm.state.value.entries.map { it.entry.id })
        vm.openFilters(); vm.updateCustomTo("2026-09-14"); vm.applyFilters()
        assertTrue(vm.state.value.filterDraft!!.customDateError)
        vm.dismissFilters(); vm.updateCustomTo("2026-09-14"); vm.applyCustomPeriod()
        assertTrue(vm.state.value.customDateError)
        vm.updateCustomTo("2026-09-13"); vm.applyCustomPeriod()
        assertFalse(vm.state.value.customDateError)
        vm.setForeground("home", false); advanceUntilIdle()
    }

    @Test fun stoppingForegroundCancelsTimerAndRestartImmediatelyCatchesUp() = runTest {
        val clock = MovingClock(testScheduler, "2026-09-12T23:59:59Z")
        val vm = model(clock)
        vm.start("home"); vm.setForeground("home", true); runCurrent()
        vm.setForeground("home", false); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(LocalDate.parse("2026-09-12"), vm.state.value.today)
        vm.setForeground("home", true); runCurrent()
        assertEquals(LocalDate.parse("2026-09-13"), vm.state.value.today)
        vm.setForeground("home", false); advanceUntilIdle()
    }

    @Test fun staleHouseholdForegroundCallbacksCannotStopNewHouseholdTimer() = runTest {
        val clock = MovingClock(testScheduler, "2026-09-12T23:59:59Z")
        val ledger = Ledger(listOf(entry("home", 100, "2026-09-12")))
        ledger.other.value = SyncObservation(listOf(entry("other", 900, "2026-09-13").copy(householdId = "other")), SyncState.SYNCED)
        val vm = model(clock, ledger = ledger)
        vm.start("home"); vm.setForeground("home", true); runCurrent()
        vm.setForeground("other", true); vm.start("other"); runCurrent()
        vm.setForeground("home", false); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(LocalDate.parse("2026-09-13"), vm.state.value.today)
        assertEquals(listOf("other"), vm.state.value.entries.map { it.entry.id })
        assertEquals(900.toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        vm.setForeground("other", false); advanceUntilIdle()
    }

    @Test fun clearingViewModelCancelsScheduledMidnightRefresh() = runTest {
        val clock = MovingClock(testScheduler, "2026-09-12T23:59:59Z")
        val vm = model(clock)
        vm.start("home"); vm.setForeground("home", true); runCurrent()
        stores.last().clear(); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(LocalDate.parse("2026-09-12"), vm.state.value.today)
        advanceUntilIdle()
    }

    @Test fun backwardsClockExcludesFutureSelectedMonthAndCustomRangeFromEntriesAndCharts() = runTest {
        val clock = MovingClock(testScheduler, "2026-10-01T12:00:00Z")
        val vm = model(clock, listOf(entry("october", 99_999, "2026-10-01"), entry("september", 100, "2026-09-30")))
        vm.start("home"); runCurrent()
        clock.jumpTo("2026-09-30T12:00:00Z"); vm.refreshCalendar()
        assertEquals(YearMonth.of(2026, 10), vm.state.value.month)
        assertTrue(vm.state.value.entries.isEmpty())
        assertTrue(vm.state.value.aggregation.totals.isEmpty)
        assertTrue(vm.state.value.aggregation.trend.isEmpty())
        assertNull(vm.state.value.balanceTrend)
        clock.jumpTo("2026-10-01T12:00:00Z"); vm.refreshCalendar()
        vm.selectPeriodMode(ReportPeriodMode.CUSTOM)
        vm.updateCustomFrom("2026-09-30"); vm.updateCustomTo("2026-10-01"); vm.applyCustomPeriod()
        clock.jumpTo("2026-09-30T12:00:00Z"); vm.refreshCalendar()
        assertEquals("2026-10-01", vm.state.value.customToInput)
        assertEquals(listOf("september"), vm.state.value.entries.map { it.entry.id })
        assertEquals(100.toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        assertEquals(LocalDate.parse("2026-09-30"), vm.state.value.balanceTrend!!.period.to)
    }

    @Test fun backwardsYearBoundaryKeepsFutureYearAndCustomSelectionButReturnsEmptyReports() = runTest {
        val clock = MovingClock(testScheduler, "2027-01-01T12:00:00Z")
        val vm = model(clock, listOf(entry("future", 99_999, "2027-01-01")))
        vm.start("home"); runCurrent(); vm.selectPeriodMode(ReportPeriodMode.YEAR)
        clock.jumpTo("2026-12-31T12:00:00Z"); vm.refreshCalendar()
        assertEquals(Year.of(2027), vm.state.value.year)
        assertTrue(vm.state.value.entries.isEmpty())
        assertTrue(vm.state.value.aggregation.totals.isEmpty)
        assertNull(vm.state.value.balanceTrend)
        clock.jumpTo("2027-01-01T12:00:00Z"); vm.refreshCalendar()
        vm.selectPeriodMode(ReportPeriodMode.CUSTOM)
        vm.updateCustomFrom("2027-01-01"); vm.updateCustomTo("2027-01-01"); vm.applyCustomPeriod()
        clock.jumpTo("2026-12-31T12:00:00Z"); vm.refreshCalendar()
        assertEquals("2027-01-01", vm.state.value.customFromInput)
        assertEquals("2027-01-01", vm.state.value.customToInput)
        assertTrue(vm.state.value.entries.isEmpty())
        assertTrue(vm.state.value.aggregation.categories.isEmpty())
        assertTrue(vm.state.value.aggregation.trend.isEmpty())
        assertNull(vm.state.value.balanceTrend)
    }

    @Test fun foregroundMidnightUsesTwentyThreeAndTwentyFiveHourLocalDays() = runTest {
        for ((instant, hours, nextDay) in listOf(
            Triple("2024-03-30T23:00:00Z", 23, "2024-04-01"),
            Triple("2024-10-26T22:00:00Z", 25, "2024-10-28"))) {
            val clock = MovingClock(testScheduler, instant, ZoneId.of("Europe/Warsaw"))
            val vm = model(clock)
            vm.start("home"); vm.setForeground("home", true); runCurrent()
            val today = vm.state.value.today
            advanceTimeBy(hours * 3_600_000L - 1); runCurrent()
            assertEquals(today, vm.state.value.today)
            advanceTimeBy(1); runCurrent()
            assertEquals(LocalDate.parse(nextDay), vm.state.value.today)
            vm.setForeground("home", false); runCurrent()
        }
        advanceUntilIdle()
    }

    private fun model(clock: Clock, entries: List<LedgerEntry> = emptyList(), ledger: Ledger = Ledger(entries)) =
        ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle(), ReportHouseholds()).also { vm ->
            stores += ViewModelStore().also { it.put("reports", vm) }
        }

    private class MovingClock(private val scheduler: TestCoroutineScheduler, instant: String,
        private val localZone: ZoneId = ZoneOffset.UTC) : Clock() {
        private var origin = Instant.parse(instant).minusMillis(scheduler.currentTime)
        fun jumpTo(instant: String) { origin = Instant.parse(instant).minusMillis(scheduler.currentTime) }
        override fun instant(): Instant = origin.plusMillis(scheduler.currentTime)
        override fun getZone(): ZoneId = localZone
        override fun withZone(zone: ZoneId): Clock = object : Clock() {
            override fun instant() = this@MovingClock.instant()
            override fun getZone() = zone
            override fun withZone(zone: ZoneId) = this@MovingClock.withZone(zone)
        }
    }

    private fun entry(id: String, amount: Long, date: String) = LedgerEntry(id, "home", amount,
        LocalDate.parse(date), categoryId = "food", authorId = "actor", updatedById = "actor")

    private class Ledger(entries: List<LedgerEntry>) : LedgerRepository {
        val home = MutableStateFlow(SyncObservation(entries, SyncState.SYNCED))
        val other = MutableStateFlow(SyncObservation(emptyList<LedgerEntry>(), SyncState.SYNCED))
        override fun observeEntries(householdId: String, includeDeleted: Boolean) = if (householdId == "home") home else other
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
    }

    private class Taxonomy : TaxonomyRepository {
        override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> =
            MutableStateFlow(SyncObservation(listOf(Category("food", householdId, "Food", authorId = "actor", updatedById = "actor")), SyncState.SYNCED))
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> =
            MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED))
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
    }
}
