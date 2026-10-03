package pl.bargor.thesaurus.ui.reports

import androidx.lifecycle.SavedStateHandle
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
class ReportsScopeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun totalsCategoryChartTrendAndEntriesConsumeOneScopedDataset() = runTest {
        val ledger = Ledger(listOf(entry("shop-expense", -1200, "shop"), entry("shop-income", 200, "shop"), entry("cafe", -500, "cafe"), entry("car", -300, category = "car")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        assertEquals(4, vm.state.value.aggregation.totals.entryCount)
        vm.selectCategory("food")
        assertEquals(3, vm.state.value.aggregation.totals.entryCount)
        vm.selectSubcategory("shop")
        val scoped = vm.state.value
        assertEquals(setOf("shop-expense", "shop-income"), scoped.entries.map { it.entry.id }.toSet())
        assertEquals(200.toBigInteger(), scoped.aggregation.totals.incomeGrosze)
        assertEquals(1200.toBigInteger(), scoped.aggregation.totals.expenseGrosze)
        assertEquals((-1000).toBigInteger(), scoped.aggregation.totals.netGrosze)
        assertEquals(listOf(ReportCategoryValue("food", 1400.toBigInteger())), scoped.aggregation.categories)
        assertEquals((-1000).toBigInteger(), scoped.aggregation.trend.sumOfBig { it.amountGrosze })
        assertTrue(scoped.entries.all { it.subcategoryName == "Sklep" && it.categoryColor == "rose" })
        vm.selectType(ReportTypeFilter.EXPENSE)
        assertEquals(listOf("shop-expense"), vm.state.value.entries.map { it.entry.id })
        assertEquals(1200.toBigInteger(), vm.state.value.aggregation.trend.single().amountGrosze)
        assertEquals((-1200).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        vm.selectPeriodMode(ReportPeriodMode.YEAR)
        assertEquals(LocalDate.of(2026, 9, 1), vm.state.value.aggregation.trend.single().date)
        assertEquals(1, vm.state.value.aggregation.totals.entryCount)
    }

    @Test fun dependentChoicesRejectForeignSubcategoryAndChangingParentOrClearingResetsScope() = runTest {
        val vm = ReportsViewModel(Ledger(listOf(entry("food", -100, "shop"), entry("car", -200, "fuel", "car"))), Taxonomy(), clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.selectSubcategory("shop")
        assertNull(vm.state.value.selectedSubcategoryId)
        vm.selectCategory("food"); vm.selectSubcategory("shop")
        assertEquals(listOf("cafe", "shop"), vm.state.value.subcategories.map { it.id })
        vm.selectCategory("car")
        assertNull(vm.state.value.selectedSubcategoryId)
        assertEquals(listOf("fuel"), vm.state.value.subcategories.map { it.id })
        vm.selectSubcategory("shop")
        assertNull(vm.state.value.selectedSubcategoryId)
        vm.selectSubcategory("fuel"); vm.selectSort(ReportEntrySort.AMOUNT); vm.toggleSortDirection()
        vm.openFilters(); vm.resetFilters(); vm.applyFilters(); vm.clearControls()
        assertFalse(vm.state.value.hasActiveFilters)
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        assertEquals(ReportSortDirection.DESCENDING, vm.state.value.direction)
        assertTrue(vm.state.value.subcategories.isEmpty())
        assertEquals(2, vm.state.value.entries.size)
    }

    @Test fun disappearingTaxonomyRetainsAppliedHistoricalScopeAndArchivedHistoryRemainsSelectable() = runTest {
        val taxonomy = Taxonomy()
        taxonomy.categories.value = SyncObservation(listOf(category("food").copy(archived = true), category("car")), SyncState.SYNCED)
        val vm = ReportsViewModel(Ledger(listOf(entry("food", -100, "shop"))), taxonomy, clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.selectCategory("food"); vm.selectSubcategory("shop")
        assertTrue(vm.state.value.categories.single { it.id == "food" }.archived)
        taxonomy.subs.getValue("food").value = SyncObservation(emptyList(), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals("food", vm.state.value.selectedCategoryId)
        assertEquals("shop", vm.state.value.selectedSubcategoryId)
        taxonomy.categories.value = SyncObservation(listOf(category("car")), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals("food", vm.state.value.selectedCategoryId)
        assertEquals("shop", vm.state.value.selectedSubcategoryId)
        assertTrue(vm.state.value.subcategories.isEmpty())
        assertEquals(1, vm.state.value.entries.size)
    }

    @Test fun cachedScopeSurvivesErrorsRetryAndPendingUpdatesButCannotLeakAcrossHouseholds() = runTest {
        val ledger = Ledger(listOf(entry("cached", -100, "shop")))
        val taxonomy = Taxonomy()
        val vm = ReportsViewModel(ledger, taxonomy, clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.selectCategory("food"); vm.selectSubcategory("shop")
        ledger.home.value = SyncObservation(listOf(entry("cached", -100, "shop"), entry("local", -50, "shop"), entry("deleted", 900, "shop").copy(deleted = true, deletedById = "actor"), entry("foreign", 800, "shop").copy(householdId = "other")), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(SyncState.PENDING, vm.state.value.syncState)
        assertEquals((-150).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        ledger.home.value = SyncObservation(state = SyncState.OFFLINE)
        taxonomy.subs.getValue("food").value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("offline"))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasError)
        vm.retry(); advanceUntilIdle()
        assertEquals("shop", vm.state.value.selectedSubcategoryId)
        assertEquals(2, vm.state.value.entries.size)
        taxonomy.subs.getValue("food").value = SyncObservation(listOf(sub("shop", "food", "Sklep")), SyncState.SYNCED)
        ledger.home.value = SyncObservation(listOf(entry("new", -25, "shop")), SyncState.SYNCED)
        advanceUntilIdle()
        assertFalse(vm.state.value.hasError)
        assertEquals((-25).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        vm.start("other")
        assertTrue(vm.state.value.isLoading)
        assertNull(vm.state.value.selectedCategoryId)
        assertNull(vm.state.value.selectedSubcategoryId)
        assertTrue(vm.state.value.entries.isEmpty())
        advanceUntilIdle()
        assertTrue(vm.state.value.aggregation.totals.isEmpty)
    }

    @Test fun obsoleteRestoredTagsAndSortValuesCannotRestrictDataWhileValidSortRoundTrips() = runTest {
        val saved = SavedStateHandle(mapOf("reports.selectedTag" to "dom", "reports.filterTag" to "dom", "reports.sort" to "SUBCATEGORY", "reports.direction" to "invalid"))
        val ledger = Ledger(listOf(entry("a", -100, "shop").copy(tags = listOf("dom")), entry("b", 300, "shop")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, saved, ReportHouseholds())
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        assertEquals(ReportSortDirection.DESCENDING, vm.state.value.direction)
        vm.start("home"); advanceUntilIdle()
        assertEquals(2, vm.state.value.entries.size)
        assertFalse(saved.keys().any { "tag" in it.lowercase() })
        vm.selectSort(ReportEntrySort.AMOUNT); vm.toggleSortDirection()
        assertEquals(listOf("a", "b"), vm.state.value.entries.map { it.entry.id })
        val restored = ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), ReportHouseholds())
        assertEquals(ReportEntrySort.AMOUNT, restored.state.value.sort)
        assertEquals(ReportSortDirection.ASCENDING, restored.state.value.direction)
    }

    @Test fun customDraftWaitsForApplyEvenAcrossMetadataUpdatesAndAllPeriodsRespectCategoryScope() = runTest {
        val ledger = Ledger(listOf(entry("aug", -200, "shop", date = "2026-08-31"), entry("sept", 300, "shop"), entry("other", -999, "fuel", "car")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.selectCategory("food"); vm.selectSubcategory("shop")
        vm.selectPeriodMode(ReportPeriodMode.CUSTOM)
        vm.updateCustomFrom("2026-08-01"); vm.updateCustomTo("2026-08-31")
        ledger.home.value = SyncObservation(ledger.home.value.value, SyncState.OFFLINE)
        advanceUntilIdle()
        assertEquals(listOf("sept"), vm.state.value.entries.map { it.entry.id })
        vm.applyCustomPeriod()
        assertEquals(listOf("aug"), vm.state.value.entries.map { it.entry.id })
        assertEquals((-200).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        vm.selectPeriodMode(ReportPeriodMode.YEAR)
        assertEquals(100.toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        assertEquals(2, vm.state.value.aggregation.trend.size)
        vm.selectPeriodMode(ReportPeriodMode.MONTH); vm.previousPeriod()
        assertEquals(listOf("aug"), vm.state.value.entries.map { it.entry.id })
    }

    @Test fun validEmptyCategoryAndSubcategoryRemainSelectedWithZeroReportUntilCleared() = runTest {
        val taxonomy = Taxonomy()
        taxonomy.categories.value = SyncObservation(listOf(category("food"), category("empty")), SyncState.SYNCED)
        taxonomy.subs.getValue("food").value = SyncObservation(listOf(sub("shop", "food", "Sklep"), sub("empty-shop", "food", "Pusty sklep")), SyncState.SYNCED)
        taxonomy.subs["empty"] = MutableStateFlow(SyncObservation(listOf(sub("empty-branch", "empty", "Pusta podkategoria")), SyncState.SYNCED))
        val vm = ReportsViewModel(Ledger(listOf(entry("existing", -100, "shop"))), taxonomy, clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.selectCategory("food"); vm.selectSubcategory("empty-shop")
        assertEquals("food", vm.state.value.selectedCategoryId)
        assertEquals("empty-shop", vm.state.value.selectedSubcategoryId)
        assertTrue(vm.state.value.hasActiveFilters)
        assertEquals(ReportTotals(), vm.state.value.aggregation.totals)
        assertTrue(vm.state.value.entries.isEmpty())
        assertTrue(vm.state.value.aggregation.categories.isEmpty())
        assertTrue(vm.state.value.aggregation.trend.isEmpty())
        vm.selectCategory("empty"); vm.selectSubcategory("empty-branch")
        assertEquals("empty", vm.state.value.selectedCategoryId)
        assertEquals("empty-branch", vm.state.value.selectedSubcategoryId)
        assertEquals(ReportTotals(), vm.state.value.aggregation.totals)
        assertTrue(vm.state.value.entries.isEmpty())
        vm.openFilters(); vm.resetFilters(); vm.applyFilters(); vm.clearControls()
        assertNull(vm.state.value.selectedCategoryId)
        assertNull(vm.state.value.selectedSubcategoryId)
        assertEquals(listOf("existing"), vm.state.value.entries.map { it.entry.id })
        assertEquals((-100).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
    }

    @Test fun dialogDraftAppliesAtomicallyAndCancelResetAndRestorationKeepAppliedScope() = runTest {
        val saved = SavedStateHandle()
        val ledger = Ledger(listOf(entry("shop", -100, "shop"), entry("car", 200, "fuel", "car")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, saved, ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.openFilters(); vm.selectCategory("food"); vm.selectSubcategory("shop")
        vm.selectMembers(setOf("actor")); vm.selectType(ReportTypeFilter.EXPENSE)
        vm.selectPeriodMode(ReportPeriodMode.YEAR)
        assertEquals(2, vm.state.value.entries.size)
        assertNull(vm.state.value.selectedCategoryId)
        assertEquals("shop", vm.state.value.filterDraft!!.selectedSubcategoryId)
        vm.applyFilters()
        assertNull(vm.state.value.filterDraft)
        assertEquals(listOf("shop"), vm.state.value.entries.map { it.entry.id })
        assertEquals(ReportPeriodMode.YEAR, vm.state.value.mode)
        vm.openFilters(); vm.selectCategory("car"); vm.selectMembers(emptySet()); vm.dismissFilters()
        assertEquals("food", vm.state.value.selectedCategoryId)
        vm.openFilters(); vm.resetFilters()
        assertNull(vm.state.value.filterDraft!!.selectedMemberIds)
        assertEquals("food", vm.state.value.selectedCategoryId)
        vm.dismissFilters()
        val restored = ReportsViewModel(ledger, Taxonomy(), clock,
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), ReportHouseholds())
        restored.start("home"); advanceUntilIdle()
        assertNull(restored.state.value.filterDraft)
        assertEquals(setOf("actor"), restored.state.value.selectedMemberIds)
        assertEquals("shop", restored.state.value.selectedSubcategoryId)
        assertEquals(listOf("shop"), restored.state.value.entries.map { it.entry.id })
        vm.openFilters(); vm.resetFilters(); vm.applyFilters()
        assertFalse(vm.state.value.hasActiveFilters)
        assertEquals(2, vm.state.value.entries.size)
    }

    @Test fun invalidCustomDraftCannotApplyAndValidRangeIsInclusive() = runTest {
        val vm = ReportsViewModel(Ledger(listOf(entry("aug", -100, date = "2026-08-31"),
            entry("sept", 200))), Taxonomy(), clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle(); vm.openFilters(); vm.selectPeriodMode(ReportPeriodMode.CUSTOM)
        for ((from, to) in listOf("bad" to "2026-09-30", "2026-09-30" to "2026-09-01",
            "2026-09-01" to "2026-10-01")) {
            vm.updateCustomFrom(from); vm.updateCustomTo(to); vm.applyFilters()
            assertTrue(vm.state.value.filterDraft!!.customDateError)
            assertEquals(ReportPeriodMode.MONTH, vm.state.value.mode)
            assertEquals(listOf("sept"), vm.state.value.entries.map { it.entry.id })
        }
        vm.updateCustomFrom("2026-08-31"); vm.updateCustomTo("2026-09-12"); vm.applyFilters()
        assertNull(vm.state.value.filterDraft)
        assertEquals(2, vm.state.value.entries.size)
        assertEquals(100.toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
    }

    @Test fun memberIdsUnionWithinMembersAndIntersectOtherFiltersIncludingNoneAndAll() = runTest {
        val households = ReportHouseholds()
        households.home.value = SyncObservation(listOf(Member("actor", "a@example.test", "Same", MemberRole.OWNER),
            Member("second", "b@example.test", "Same", MemberRole.MEMBER)), SyncState.SYNCED)
        val ledger = Ledger(listOf(entry("a", -100, "shop"),
            entry("b", -200, "shop").copy(authorId = "second"),
            entry("former", -300, "shop").copy(authorId = "departed"),
            entry("income", 400, "shop").copy(authorId = "second"),
            entry("car", -500, "fuel", "car").copy(authorId = "second"),
            entry("foreign", -999, "shop").copy(householdId = "other", authorId = "foreign"),
            entry("deleted", -999, "shop").copy(deleted = true, deletedById = "actor", authorId = "deleted")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle(), households)
        vm.start("home"); advanceUntilIdle()
        assertEquals(setOf("actor", "second", "departed"), vm.state.value.members.map { it.id }.toSet())
        assertTrue(vm.state.value.members.single { it.id == "departed" }.former)
        vm.openFilters(); vm.selectCategory("food"); vm.selectSubcategory("shop")
        vm.selectType(ReportTypeFilter.EXPENSE); vm.selectMembers(setOf("actor", "second")); vm.applyFilters()
        val scoped = vm.state.value
        assertEquals(setOf("a", "b"), scoped.entries.map { it.entry.id }.toSet())
        assertEquals(300.toBigInteger(), scoped.aggregation.totals.expenseGrosze)
        assertEquals(listOf(ReportCategoryValue("food", 300.toBigInteger())), scoped.aggregation.categories)
        assertEquals(300.toBigInteger(), scoped.aggregation.trend.sumOfBig { it.amountGrosze })
        vm.openFilters(); vm.selectMembers(setOf("departed")); vm.applyFilters()
        assertEquals(listOf("former"), vm.state.value.entries.map { it.entry.id })
        vm.openFilters(); vm.selectMembers(setOf("foreign")); vm.applyFilters()
        assertEquals(emptySet<String>(), vm.state.value.selectedMemberIds)
        vm.openFilters(); vm.selectMembers(setOf("departed")); vm.applyFilters()
        ledger.home.value = SyncObservation(ledger.home.value.value!!.map { if (it.id == "former") it.copy(deleted = true, deletedById = "actor") else it }, SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals(setOf("departed"), vm.state.value.selectedMemberIds)
        assertTrue(vm.state.value.members.any { it.id == "departed" && it.former })
        ledger.home.value = SyncObservation(ledger.home.value.value!!.map { if (it.id == "former") it.copy(deleted = false, deletedById = null) else it }, SyncState.SYNCED)
        advanceUntilIdle()
        households.home.value = SyncObservation(emptyList(), SyncState.OFFLINE)
        ledger.home.value = SyncObservation(ledger.home.value.value, SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(setOf("departed"), vm.state.value.selectedMemberIds)
        assertEquals(SyncState.PENDING, vm.state.value.syncState)
        assertEquals(listOf("former"), vm.state.value.entries.map { it.entry.id })
        vm.openFilters(); vm.selectMembers(emptySet()); vm.applyFilters()
        assertTrue(vm.state.value.entries.isEmpty())
        assertTrue(vm.state.value.aggregation.totals.isEmpty)
        assertTrue(vm.state.value.aggregation.categories.isEmpty())
        assertTrue(vm.state.value.aggregation.trend.isEmpty())
        vm.openFilters(); vm.selectMembers(null); vm.applyFilters()
        assertEquals(setOf("a", "b", "former"), vm.state.value.entries.map { it.entry.id }.toSet())
        vm.openFilters(); vm.selectMembers(setOf("actor")); vm.start("other")
        assertNull(vm.state.value.filterDraft)
        assertNull(vm.state.value.selectedMemberIds)
        assertTrue(vm.state.value.members.isEmpty())
        advanceUntilIdle()
        ledger.home.value = SyncObservation(listOf(entry("stale", -999)), SyncState.SYNCED)
        households.home.value = SyncObservation(listOf(Member("leaked", "leaked@example.test", "Leak", MemberRole.MEMBER)), SyncState.SYNCED)
        advanceUntilIdle()
        assertTrue(vm.state.value.entries.isEmpty())
        assertTrue(vm.state.value.members.isEmpty())
        assertFalse(vm.state.value.hasActiveFilters)
    }

    @Test fun previousMonthAndIndependentYearNavigationRoundTrip() = runTest {
        val saved = SavedStateHandle()
        val ledger = Ledger(listOf(entry("aug", -100, date = "2026-08-31"),
            entry("sept", -200), entry("last-year", -300, date = "2025-12-31")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, saved, ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.openFilters(); vm.previousPeriod(); vm.applyFilters()
        val monthRestored = ReportsViewModel(ledger, Taxonomy(), clock, copied(saved), ReportHouseholds())
        monthRestored.start("home"); advanceUntilIdle()
        assertEquals(ReportPeriodMode.MONTH, monthRestored.state.value.mode)
        assertEquals("2026-08", monthRestored.state.value.month.toString())
        assertEquals(listOf("aug"), monthRestored.state.value.entries.map { it.entry.id })
        vm.openFilters(); vm.selectPeriodMode(ReportPeriodMode.YEAR); vm.previousPeriod(); vm.applyFilters()
        val yearRestored = ReportsViewModel(ledger, Taxonomy(), clock, copied(saved), ReportHouseholds())
        yearRestored.start("home"); advanceUntilIdle()
        assertEquals(ReportPeriodMode.YEAR, yearRestored.state.value.mode)
        assertEquals("2025", yearRestored.state.value.year.toString())
        assertEquals("2026-08", yearRestored.state.value.month.toString())
        assertEquals(listOf("last-year"), yearRestored.state.value.entries.map { it.entry.id })
        yearRestored.openFilters(); yearRestored.selectPeriodMode(ReportPeriodMode.MONTH); yearRestored.applyFilters()
        assertEquals(listOf("aug"), yearRestored.state.value.entries.map { it.entry.id })
    }

    @Test fun customRangeTypeAndExplicitNoMembersSurviveRestoration() = runTest {
        val saved = SavedStateHandle()
        val ledger = Ledger(listOf(entry("expense", -100, date = "2026-08-31"),
            entry("income", 200), entry("outside", -300, date = "2026-09-30")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, saved, ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.openFilters(); vm.selectPeriodMode(ReportPeriodMode.CUSTOM)
        vm.updateCustomFrom("2026-08-31"); vm.updateCustomTo("2026-09-12")
        vm.selectType(ReportTypeFilter.EXPENSE); vm.selectMembers(emptySet()); vm.applyFilters()
        val restored = ReportsViewModel(ledger, Taxonomy(), clock, copied(saved), ReportHouseholds())
        restored.start("home"); advanceUntilIdle()
        val state = restored.state.value
        assertEquals(ReportPeriodMode.CUSTOM, state.mode)
        assertEquals("2026-08-31", state.customFromInput)
        assertEquals("2026-09-12", state.customToInput)
        assertEquals(SummaryPeriod(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 12)), state.period())
        assertEquals(ReportTypeFilter.EXPENSE, state.typeFilter)
        assertEquals(emptySet<String>(), state.selectedMemberIds)
        assertTrue(state.hasActiveFilters)
        assertTrue(state.entries.isEmpty())
        assertEquals(ReportTotals(), state.aggregation.totals)
        restored.openFilters(); restored.selectMembers(null); restored.applyFilters()
        assertEquals(listOf("expense"), restored.state.value.entries.map { it.entry.id })
        assertEquals(100.toBigInteger(), restored.state.value.aggregation.totals.expenseGrosze)
    }

    @Test fun abandonedOpenDraftDoesNotRestoreOrOverwriteAppliedSelection() = runTest {
        val saved = SavedStateHandle()
        val ledger = Ledger(listOf(entry("shop", -100, "shop"), entry("car", 200, "fuel", "car")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, saved, ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.openFilters(); vm.selectCategory("food"); vm.selectSubcategory("shop")
        vm.selectMembers(setOf("actor")); vm.selectType(ReportTypeFilter.EXPENSE); vm.applyFilters()
        vm.openFilters(); vm.selectCategory("car"); vm.selectPeriodMode(ReportPeriodMode.YEAR)
        vm.previousPeriod(); vm.selectMembers(emptySet()); vm.selectType(ReportTypeFilter.INCOME)
        assertNotNull(vm.state.value.filterDraft)
        val restored = ReportsViewModel(ledger, Taxonomy(), clock, copied(saved), ReportHouseholds())
        restored.start("home"); advanceUntilIdle()
        assertNull(restored.state.value.filterDraft)
        assertEquals(ReportPeriodMode.MONTH, restored.state.value.mode)
        assertEquals("2026-09", restored.state.value.month.toString())
        assertEquals("food", restored.state.value.selectedCategoryId)
        assertEquals("shop", restored.state.value.selectedSubcategoryId)
        assertEquals(setOf("actor"), restored.state.value.selectedMemberIds)
        assertEquals(ReportTypeFilter.EXPENSE, restored.state.value.typeFilter)
        assertEquals(listOf("shop"), restored.state.value.entries.map { it.entry.id })
    }

    @Test fun restoredCategoryAndSubcategoryRemainAppliedWhileMetadataArrivesLater() = runTest {
        val saved = SavedStateHandle()
        val ledger = Ledger(listOf(entry("shop", -100, "shop"), entry("cafe", -200, "cafe")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, saved, ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.openFilters(); vm.selectCategory("food"); vm.selectSubcategory("shop"); vm.applyFilters()
        val delayed = Taxonomy()
        delayed.categories.value = SyncObservation(state = SyncState.SYNCED)
        delayed.subs.getValue("food").value = SyncObservation(state = SyncState.SYNCED)
        val restored = ReportsViewModel(ledger, delayed, clock, copied(saved), ReportHouseholds())
        restored.start("home")
        assertEquals("food", restored.state.value.selectedCategoryId)
        assertEquals("shop", restored.state.value.selectedSubcategoryId)
        advanceUntilIdle()
        assertEquals(listOf("shop"), restored.state.value.entries.map { it.entry.id })
        assertTrue(restored.state.value.categories.isEmpty())
        assertTrue(restored.state.value.subcategories.isEmpty())
        delayed.categories.value = SyncObservation(listOf(category("food")), SyncState.SYNCED)
        delayed.subs.getValue("food").value = SyncObservation(listOf(sub("shop", "food", "Late shop")), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals("food", restored.state.value.selectedCategoryId)
        assertEquals("shop", restored.state.value.selectedSubcategoryId)
        assertEquals(listOf("shop"), restored.state.value.entries.map { it.entry.id })
        assertEquals("Late shop", restored.state.value.entries.single().subcategoryName)
        assertEquals((-100).toBigInteger(), restored.state.value.aggregation.totals.netGrosze)
    }

    @Test fun draftNavigationBoundsAndResetUseTodayWithoutChangingAppliedReport() = runTest {
        val vm = ReportsViewModel(Ledger(listOf(entry("sept", -100))), Taxonomy(), clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle(); vm.openFilters()
        vm.nextPeriod()
        assertEquals("2026-09", vm.state.value.filterDraft!!.month.toString())
        vm.previousPeriod()
        assertEquals("2026-08", vm.state.value.filterDraft!!.month.toString())
        assertEquals("2026-09", vm.state.value.month.toString())
        vm.selectPeriodMode(ReportPeriodMode.YEAR); vm.nextPeriod()
        assertEquals("2026", vm.state.value.filterDraft!!.year.toString())
        vm.previousPeriod()
        assertEquals("2025", vm.state.value.filterDraft!!.year.toString())
        vm.selectPeriodMode(ReportPeriodMode.CUSTOM); vm.updateCustomFrom("2025-01-01"); vm.resetFilters()
        val draft = vm.state.value.filterDraft!!
        assertEquals(ReportPeriodMode.MONTH, draft.mode)
        assertEquals("2026-09", draft.month.toString())
        assertEquals("2026", draft.year.toString())
        assertEquals("2026-09-01", draft.customFromInput)
        assertEquals("2026-09-30", draft.customToInput)
        assertEquals(listOf("sept"), vm.state.value.entries.map { it.entry.id })
        vm.applyFilters()
        assertEquals(LocalDate.of(2026, 9, 30), vm.state.value.period().to)
        assertFalse(vm.state.value.hasActiveFilters)
    }

    @Test fun pendingLedgerUpdatesUseAppliedDatasetWhileConflictingDraftRemainsOpen() = runTest {
        val ledger = Ledger(listOf(entry("cached", -100, "shop"), entry("other", 300, "cafe")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.openFilters(); vm.selectCategory("food"); vm.selectSubcategory("shop")
        vm.selectMembers(setOf("actor")); vm.selectType(ReportTypeFilter.EXPENSE); vm.applyFilters()
        vm.openFilters(); vm.selectSubcategory("cafe"); vm.selectType(ReportTypeFilter.INCOME)
        ledger.home.value = SyncObservation(listOf(entry("cached", -100, "shop"),
            entry("local", -50, "shop"), entry("draft-only", 999, "cafe")), SyncState.PENDING)
        advanceUntilIdle()
        val pending = vm.state.value
        assertEquals(SyncState.PENDING, pending.syncState)
        assertEquals("cafe", pending.filterDraft!!.selectedSubcategoryId)
        assertEquals(ReportTypeFilter.INCOME, pending.filterDraft!!.typeFilter)
        assertEquals(setOf("cached", "local"), pending.entries.map { it.entry.id }.toSet())
        assertEquals(ReportTotals(BigInteger.ZERO, 150.toBigInteger(), 2), pending.aggregation.totals)
        assertEquals(listOf(ReportCategoryValue("food", 150.toBigInteger())), pending.aggregation.categories)
        assertEquals(150.toBigInteger(), pending.aggregation.trend.sumOfBig { it.amountGrosze })
        ledger.home.value = SyncObservation(state = SyncState.OFFLINE)
        advanceUntilIdle()
        assertEquals(pending.entries, vm.state.value.entries)
        assertEquals(pending.aggregation, vm.state.value.aggregation)
        vm.dismissFilters()
        assertEquals(pending.aggregation, vm.state.value.aggregation)
    }

    @Test fun formerAuthorSelectedOnlyInDraftSurvivesLastEntryTombstoneAndApply() = runTest {
        val ledger = Ledger(listOf(entry("former", -100).copy(authorId = "departed")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.openFilters(); vm.selectMembers(setOf("departed"))
        assertNull(vm.state.value.selectedMemberIds)
        ledger.home.value = SyncObservation(listOf(entry("former", -100).copy(authorId = "departed",
            deleted = true, deletedById = "actor")), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(setOf("departed"), vm.state.value.filterDraft!!.selectedMemberIds)
        assertTrue(vm.state.value.members.single { it.id == "departed" }.former)
        vm.applyFilters(); vm.openFilters()
        assertEquals(setOf("departed"), vm.state.value.selectedMemberIds)
        assertEquals(setOf("departed"), vm.state.value.filterDraft!!.selectedMemberIds)
        assertTrue(vm.state.value.members.single { it.id == "departed" }.former)
        assertTrue(vm.state.value.entries.isEmpty())
        assertEquals(ReportTotals(), vm.state.value.aggregation.totals)
    }

    @Test fun missingTaxonomyOptionsRemainValidWhileSelectedOnlyInDraft() = runTest {
        val taxonomy = Taxonomy()
        val vm = ReportsViewModel(Ledger(listOf(entry("shop", -100, "shop"))),
            taxonomy, clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.openFilters(); vm.selectCategory("food"); vm.selectSubcategory("shop")
        taxonomy.subs.getValue("food").value = SyncObservation(emptyList(), SyncState.SYNCED)
        taxonomy.categories.value = SyncObservation(emptyList(), SyncState.SYNCED)
        advanceUntilIdle()
        assertNull(vm.state.value.selectedCategoryId)
        assertEquals("food", vm.state.value.filterDraft!!.selectedCategoryId)
        assertEquals("shop", vm.state.value.filterDraft!!.selectedSubcategoryId)
        vm.selectSubcategory("shop")
        assertEquals("shop", vm.state.value.filterDraft!!.selectedSubcategoryId)
        vm.applyFilters()
        assertEquals("food", vm.state.value.selectedCategoryId)
        assertEquals("shop", vm.state.value.selectedSubcategoryId)
        assertEquals(listOf("shop"), vm.state.value.entries.map { it.entry.id })
        vm.openFilters(); vm.selectCategory("food")
        assertEquals("food", vm.state.value.filterDraft!!.selectedCategoryId)
        assertNull(vm.state.value.filterDraft!!.selectedSubcategoryId)
        vm.dismissFilters()
        assertEquals("shop", vm.state.value.selectedSubcategoryId)
    }

    @Test fun missingCategoryCanBeReselectedBeforeItWasEverApplied() = runTest {
        val taxonomy = Taxonomy()
        val vm = ReportsViewModel(Ledger(listOf(entry("shop", -100, "shop"))),
            taxonomy, clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        vm.openFilters(); vm.selectCategory("food"); vm.selectSubcategory("shop")
        taxonomy.categories.value = SyncObservation(emptyList(), SyncState.SYNCED)
        advanceUntilIdle()
        vm.selectCategory("food")
        assertNull(vm.state.value.selectedCategoryId)
        assertEquals("food", vm.state.value.filterDraft!!.selectedCategoryId)
        assertNull(vm.state.value.filterDraft!!.selectedSubcategoryId)
        vm.applyFilters()
        assertEquals("food", vm.state.value.selectedCategoryId)
        assertEquals(listOf("shop"), vm.state.value.entries.map { it.entry.id })
    }

    @Test fun allSortDirectionsKeepMembershipAggregationsAndEqualKeyTiesStable() = runTest {
        val ledger = Ledger(listOf(entry("z", -100, date = "2026-09-12"),
            entry("a", -100, date = "2026-09-12"), entry("early", 300, date = "2026-09-01"),
            entry("late", -200, date = "2026-09-25")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        val original = vm.state.value
        val orders = listOf(
            Triple(ReportEntrySort.DATE, ReportSortDirection.ASCENDING, listOf("early", "a", "z", "late")),
            Triple(ReportEntrySort.DATE, ReportSortDirection.DESCENDING, listOf("late", "a", "z", "early")),
            Triple(ReportEntrySort.AMOUNT, ReportSortDirection.ASCENDING, listOf("late", "a", "z", "early")),
            Triple(ReportEntrySort.AMOUNT, ReportSortDirection.DESCENDING, listOf("early", "a", "z", "late")))
        for ((sort, direction, expected) in orders) {
            vm.openFilters(); vm.selectSort(sort)
            if (vm.state.value.filterDraft!!.direction != direction) vm.toggleSortDirection()
            assertEquals(original.aggregation, vm.state.value.aggregation)
            vm.applyFilters()
            assertEquals(expected, vm.state.value.entries.map { it.entry.id })
            assertEquals(original.entries.map { it.entry.id }.toSet(), vm.state.value.entries.map { it.entry.id }.toSet())
            assertEquals(original.aggregation, vm.state.value.aggregation)
        }
        ledger.home.value = SyncObservation(ledger.home.value.value!!.reversed(), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(listOf("early", "a", "z", "late"), vm.state.value.entries.map { it.entry.id })
    }

    @Test fun sortingDraftCancelResetAndRestoreRetainOnlyAppliedSort() = runTest {
        val saved = SavedStateHandle()
        val ledger = Ledger(listOf(entry("a", -100), entry("b", 200)))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, saved, ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        val original = vm.state.value
        vm.openFilters(); vm.selectSort(ReportEntrySort.AMOUNT); vm.toggleSortDirection()
        assertEquals(original.entries, vm.state.value.entries)
        assertEquals(original.aggregation, vm.state.value.aggregation)
        assertFalse(vm.state.value.hasActiveFilters)
        assertEquals("DATE", saved.get<String>("reports.sort"))
        vm.dismissFilters(); vm.openFilters()
        assertEquals(ReportEntrySort.DATE, vm.state.value.filterDraft!!.sort)
        assertEquals(ReportSortDirection.DESCENDING, vm.state.value.filterDraft!!.direction)
        vm.selectSort(ReportEntrySort.AMOUNT); vm.toggleSortDirection(); vm.applyFilters()
        assertTrue(vm.state.value.hasActiveFilters)
        assertEquals(listOf("a", "b"), vm.state.value.entries.map { it.entry.id })
        vm.openFilters(); vm.resetFilters()
        assertEquals(ReportEntrySort.DATE, vm.state.value.filterDraft!!.sort)
        assertEquals(ReportSortDirection.DESCENDING, vm.state.value.filterDraft!!.direction)
        assertEquals(ReportEntrySort.AMOUNT, vm.state.value.sort)
        assertEquals(ReportSortDirection.ASCENDING, vm.state.value.direction)
        val restored = ReportsViewModel(ledger, Taxonomy(), clock, copied(saved), ReportHouseholds())
        restored.start("home"); advanceUntilIdle()
        assertNull(restored.state.value.filterDraft)
        assertEquals(ReportEntrySort.AMOUNT, restored.state.value.sort)
        assertEquals(ReportSortDirection.ASCENDING, restored.state.value.direction)
        assertEquals(vm.state.value.entries, restored.state.value.entries)
        vm.dismissFilters(); vm.openFilters()
        assertEquals(ReportEntrySort.AMOUNT, vm.state.value.filterDraft!!.sort)
        vm.resetFilters(); vm.applyFilters()
        assertFalse(vm.state.value.hasActiveFilters)
        vm.openFilters(); vm.toggleSortDirection(); vm.applyFilters()
        assertTrue("Ascending date alone activates the funnel", vm.state.value.hasActiveFilters)
    }

    @Test fun offlineSortAndFiltersCommitTogetherAndInvalidRangeKeepsAppliedReport() = runTest {
        val ledger = Ledger(listOf(entry("expense", -100, "shop"), entry("income", 200, "shop"),
            entry("car", -300, "fuel", "car")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle(), ReportHouseholds())
        vm.start("home"); advanceUntilIdle()
        ledger.home.value = SyncObservation(state = SyncState.OFFLINE); advanceUntilIdle()
        val original = vm.state.value
        vm.openFilters(); vm.selectSort(ReportEntrySort.AMOUNT); vm.toggleSortDirection()
        vm.selectCategory("food"); vm.selectSubcategory("shop"); vm.selectMembers(setOf("actor"))
        vm.selectPeriodMode(ReportPeriodMode.CUSTOM); vm.updateCustomFrom("bad")
        vm.applyFilters()
        assertTrue(vm.state.value.filterDraft!!.customDateError)
        assertEquals(original.entries, vm.state.value.entries)
        assertEquals(original.aggregation, vm.state.value.aggregation)
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        vm.updateCustomFrom("2026-09-01"); vm.updateCustomTo("2026-09-30"); vm.applyFilters()
        assertNull(vm.state.value.filterDraft)
        assertEquals(SyncState.OFFLINE, vm.state.value.syncState)
        assertEquals(listOf("expense", "income"), vm.state.value.entries.map { it.entry.id })
        assertEquals(100.toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        assertEquals(listOf(ReportCategoryValue("food", 300.toBigInteger())), vm.state.value.aggregation.categories)
        assertEquals(100.toBigInteger(), vm.state.value.aggregation.trend.sumOfBig { it.amountGrosze })
        assertEquals(setOf("actor"), vm.state.value.selectedMemberIds)
        assertEquals(ReportEntrySort.AMOUNT, vm.state.value.sort)
        assertEquals(ReportSortDirection.ASCENDING, vm.state.value.direction)
    }

    private fun copied(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun <T> List<T>.sumOfBig(value: (T) -> BigInteger) = fold(BigInteger.ZERO) { sum, item -> sum + value(item) }
    private fun entry(id: String, amount: Long, subcategory: String? = null, category: String = "food", date: String = "2026-09-12") = LedgerEntry(id, "home", amount, LocalDate.parse(date), categoryId = category, subcategoryId = subcategory, authorId = "actor", updatedById = "actor")
    private fun category(id: String) = Category(id, "home", id, color = "rose", authorId = "actor", updatedById = "actor")
    private fun sub(id: String, parent: String, name: String) = Subcategory(id, "home", parent, name, authorId = "actor", updatedById = "actor")
    private inner class Taxonomy : TaxonomyRepository {
        val categories = MutableStateFlow(SyncObservation(listOf(category("food"), category("car")), SyncState.SYNCED))
        val subs = mutableMapOf("food" to MutableStateFlow(SyncObservation(listOf(sub("shop", "food", "Sklep"), sub("cafe", "food", "Kawiarnia")), SyncState.SYNCED)), "car" to MutableStateFlow(SyncObservation(listOf(sub("fuel", "car", "Paliwo")), SyncState.SYNCED)))
        override fun observeCategories(householdId: String) = categories
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> = subs.getOrPut(categoryId) { MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED)) }
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
    }
    private class Ledger(entries: List<LedgerEntry>) : LedgerRepository {
        val home = MutableStateFlow(SyncObservation(entries, SyncState.SYNCED))
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = if (householdId == "home") home else MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED))
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
    }
}
