package pl.bargor.thesaurus.ui.summary

import androidx.lifecycle.SavedStateHandle
import java.time.Clock
import java.time.Instant
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryViewModelTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC)
    @Test fun startsWithCurrentMonthAndOnlyPeriodState() {
        val vm = SummaryViewModel(clock, SavedStateHandle())
        assertEquals(SummaryUiState(YearMonth.of(2026, 9), Year.of(2026)), vm.state.value)
    }
    @Test fun monthArrowsCrossYearBoundaries() {
        val vm = SummaryViewModel(clock, SavedStateHandle())
        repeat(4) { vm.nextMonth() }
        assertEquals(YearMonth.of(2027, 1), vm.state.value.month)
        assertEquals(Year.of(2027), vm.state.value.year)
        vm.previousMonth()
        assertEquals(YearMonth.of(2026, 12), vm.state.value.month)
        assertEquals(Year.of(2026), vm.state.value.year)
    }
    @Test fun modeToggleRetainsMonthAndYearArrowsKeepItsMonthNumber() {
        val vm = SummaryViewModel(clock, SavedStateHandle())
        vm.selectPeriodMode(SummaryPeriodMode.YEAR); vm.previousYear()
        assertEquals(Year.of(2025), vm.state.value.year)
        vm.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(YearMonth.of(2025, 9), vm.state.value.month)
        vm.selectPeriodMode(SummaryPeriodMode.YEAR); vm.nextYear(); vm.nextYear()
        vm.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(YearMonth.of(2027, 9), vm.state.value.month)
    }
    @Test fun freshHandleRestoresModeYearAndMonthAndIgnoresObsoleteFilters() {
        val saved = SavedStateHandle(mapOf("summary.selectedTag" to "dom", "summary.sort" to "CATEGORY"))
        val vm = SummaryViewModel(clock, saved)
        vm.nextMonth(); vm.selectPeriodMode(SummaryPeriodMode.YEAR); vm.previousYear()
        val restored = SummaryViewModel(clock, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(SummaryPeriodMode.YEAR, restored.state.value.mode)
        assertEquals(Year.of(2025), restored.state.value.year)
        restored.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(YearMonth.of(2025, 10), restored.state.value.month)
    }
    @Test fun leapFebruaryRemainsFebruaryWhenNavigatingYears() {
        val vm = SummaryViewModel(Clock.fixed(Instant.parse("2028-02-15T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle())
        vm.selectPeriodMode(SummaryPeriodMode.YEAR); vm.previousYear(); vm.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(YearMonth.of(2027, 2), vm.state.value.month)
    }
}
