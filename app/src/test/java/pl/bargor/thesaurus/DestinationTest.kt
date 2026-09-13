package pl.bargor.thesaurus

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationTest {
    @Test
    fun destinationsProvideTheThreeMainSections() {
        assertEquals(
            listOf(
                R.string.navigation_entries,
                R.string.navigation_summary,
                R.string.navigation_reports,
            ),
            Destination.entries.map(Destination::labelRes),
        )
    }

    @Test
    fun destinationsProvideUniqueStableRoutes() {
        assertEquals(
            listOf("entries", "summary", "reports"),
            Destination.entries.map(Destination::route),
        )
    }
}
