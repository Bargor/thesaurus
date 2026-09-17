package pl.bargor.thesaurus.ui.entries

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Small local preference: it intentionally contains no household or financial data. */
interface EntryListSortPreference {
    fun read(): EntryListSort
    fun save(sort: EntryListSort)
}

class SharedPreferencesEntryListSortPreference @Inject constructor(
    @ApplicationContext context: Context,
) : EntryListSortPreference {
    private val preferences = context.getSharedPreferences("entry_list", Context.MODE_PRIVATE)

    override fun read(): EntryListSort = when (preferences.getString(SORT_KEY, null)) {
        EntryListSort.CREATION_ORDER.name -> EntryListSort.CREATION_ORDER
        else -> EntryListSort.ACCOUNTING_DATE
    }

    override fun save(sort: EntryListSort) {
        preferences.edit().putString(SORT_KEY, sort.name).apply()
    }

    private companion object {
        const val SORT_KEY = "sort"
    }
}
