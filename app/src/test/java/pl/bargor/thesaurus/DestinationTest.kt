package pl.bargor.thesaurus

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationTest {
    @Test
    fun destinationsProvideTheFourMainSections() {
        assertEquals(
            listOf(
                R.string.navigation_entries,
                R.string.navigation_summary,
                R.string.navigation_reports,
                R.string.navigation_browse,
            ),
            Destination.entries.map(Destination::labelRes),
        )
    }

    @Test
    fun destinationsProvideUniqueStableRoutes() {
        assertEquals(
            listOf("entries", "summary", "reports", "browse"),
            Destination.entries.map(Destination::route),
        )
    }

    @Test
    fun compactNavigationPreservesStableTagsAndDistinctLabelsForEveryTab() {
        assertEquals(
            listOf("navigation-entries", "navigation-summary", "navigation-reports", "navigation-browse"),
            Destination.entries.map(Destination::navigationTestTag),
        )
        assertEquals(4, Destination.entries.map(Destination::labelRes).distinct().size)
    }
}
