package pl.bargor.thesaurus.ui.entries

import java.time.LocalDate

/** Presentation rows for consecutive accounting dates, preserving the supplied visible order. */
sealed interface EntryListRow {
    val key: String

    data class DateHeading(val date: LocalDate, val firstEntryId: String) : EntryListRow {
        override val key: String get() = "date-$firstEntryId"
    }

    data class Entry(val item: EntryListItem) : EntryListRow {
        override val key: String get() = "entry-${item.entry.id}"
    }
}

fun entryListRows(visibleEntries: List<EntryListItem>): List<EntryListRow> = buildList {
    var previousDate: LocalDate? = null
    visibleEntries.forEach { item ->
        val date = item.entry.date
        if (date != previousDate) add(EntryListRow.DateHeading(date, item.entry.id))
        add(EntryListRow.Entry(item))
        previousDate = date
    }
}
