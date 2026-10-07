package pl.bargor.thesaurus.ui.entries

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.data.firebase.FirstHouseholdResult
import pl.bargor.thesaurus.data.firebase.FirestoreRepositories
import pl.bargor.thesaurus.data.firebase.OnboardingIdentity
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceViewModel
import pl.bargor.thesaurus.ui.reports.ReportsViewModel
import pl.bargor.thesaurus.testfixtures.FirebaseIntegrationFixture

@RunWith(AndroidJUnit4::class)
class EntryListRepositoryIntegrationTest {
    @get:Rule val localNetworkPermissionRule = LocalNetworkPermissionRule()

    @Test fun localRevealSortAndLiveWritesUseCacheWithoutLimitingBalanceOrReports() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = UUID.randomUUID().toString()
        val fixture = FirebaseIntegrationFixture.open("EntryListRepositoryIntegrationTest")
        val auth = fixture.auth
        val firestore = fixture.firestore
        val store = fixture.store
        try {
            fixture.scenario("EntryListRepositoryIntegrationTest scenario") {
                val email = "entry-list-$suffix@example.test"
                val uid = auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
                val repository = FirestoreRepositories(firestore)
                val home = (repository.createFirstHousehold(OnboardingIdentity(uid, email, "Ala"), "Dom wpisów") as FirstHouseholdResult.Created).householdId
                val category = repository.observeCategories(home).first {
                    it.state == SyncState.SYNCED && it.value.orEmpty().isNotEmpty()
                }.value!!.single { it.id == "jedzenie" }
                val subcategory = repository.observeSubcategories(home, category.id).first {
                    it.state == SyncState.SYNCED && it.value.orEmpty().isNotEmpty()
                }.value!!.single { it.id == "supermarket" }
                val date = LocalDate.of(2026, 9, 16)
                fun entry(id: String) = LedgerEntry("$id-$suffix", home, -100, date,
                    categoryId = category.id, subcategoryId = subcategory.id, authorId = uid, updatedById = uid)
                val original = (0 until 25).map { entry("entry-${it.toString().padStart(2, '0')}") }
                original.forEach { repository.save(it) }
                val preference = object : EntryListSortPreference {
                    var sort = EntryListSort.ACCOUNTING_DATE
                    override fun read() = sort
                    override fun save(sort: EntryListSort) { this.sort = sort }
                }
                lateinit var vm: EntryListViewModel
                lateinit var balance: GlobalAccountBalanceViewModel
                lateinit var reports: ReportsViewModel
                instrumentation.runOnMainSync {
                    vm = EntryListViewModel(repository, repository, repository, preference)
                    balance = GlobalAccountBalanceViewModel(repository, repository)
                    reports = ReportsViewModel(repository, repository,
                        Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle(), repository)
                    store.put("entries", vm); store.put("balance", balance); store.put("reports", reports)
                    vm.start(home, uid); balance.start(home, uid); reports.start(home)
                }
                suspend fun state(predicate: (EntryListUiState) -> Boolean): EntryListUiState =
                    fixture.operation("EntryListRepositoryIntegrationTest wait 1") { vm.state.first(predicate) }
                suspend fun assertCompleteAggregates(expectedIds: Set<String> = original.map { it.id }.toSet(), editedTitle: String? = null) {
                    val balanceState = fixture.operation("EntryListRepositoryIntegrationTest wait 2") { balance.state.first {
                        !it.isLoading && !it.hasError && it.amountGrosze == (-2500).toBigInteger()
                    } }
                    val reportState = fixture.operation("EntryListRepositoryIntegrationTest wait 3") { reports.state.first {
                        !it.isLoading && !it.hasError && it.entries.map { row -> row.entry.id }.toSet() == expectedIds &&
                            it.aggregation.totals.netGrosze == (-2500).toBigInteger() &&
                            (editedTitle == null || it.entries.any { row -> row.entry.title == editedTitle })
                    } }
                    assertEquals((-2500).toBigInteger(), balanceState.amountGrosze)
                    assertEquals(25, reportState.aggregation.totals.entryCount)
                }
                val initial = state {
                    !it.isLoading && it.syncState == SyncState.SYNCED && it.entries.size == 25 &&
                        it.entries.all { row -> row.categoryName == category.name && row.subcategoryName == subcategory.name && row.authorName == "Ala" && row.canManage }
                }
                assertEquals(20, initial.visibleEntries.size)
                assertCompleteAggregates()
                fixture.disableNetwork()
                state { it.syncState == SyncState.OFFLINE }
                instrumentation.runOnMainSync { vm.revealMoreEntries() }
                assertEquals(25, vm.state.value.visibleEntries.size)
                assertFalse(vm.state.value.hasMore)
                instrumentation.runOnMainSync { vm.changeSort(EntryListSort.CREATION_ORDER) }
                assertEquals(20, vm.state.value.visibleEntries.size)
                assertEquals(initial.entries.associateBy { it.entry.id }, vm.state.value.entries.associateBy { it.entry.id })
                assertCompleteAggregates()

                // Recreate only the list while the other full-history consumers remain subscribed.
                instrumentation.runOnMainSync {
                    vm = EntryListViewModel(repository, repository, repository, preference)
                    store.put("entries", vm); vm.start(home, uid)
                }
                val cached = state { !it.isLoading && it.syncState == SyncState.OFFLINE && it.entries.size == 25 }
                assertEquals(EntryListSort.CREATION_ORDER, cached.sort)
                assertEquals(sortEntries(initial.entries.map { it.entry }, cached.sort), cached.entries.map { it.entry })
                instrumentation.runOnMainSync { vm.revealMoreEntries() }
                assertEquals(25, vm.state.value.visibleEntries.size)

                val local = entry("local")
                val edited = cached.entries.first().entry.copy(title = "Lokalna edycja", date = date.minusDays(1))
                val deleted = cached.entries.last().entry
                val mutatedIds = original.map { it.id }.toSet() - deleted.id + local.id
                val writes = listOf(async { repository.save(local) }, async { repository.save(edited) },
                    async { repository.tombstone(home, deleted.id, uid) })
                try {
                    val pending = state { it.syncState == SyncState.PENDING && it.entries.size == 25 &&
                        it.entries.any { row -> row.entry.id == local.id } &&
                        it.entries.any { row -> row.entry.id == edited.id && row.entry.title == edited.title } &&
                        it.entries.none { row -> row.entry.id == deleted.id }
                    }
                    assertEquals(local.id, pending.entries.first().entry.id)
                    assertEquals(25, pending.visibleEntries.size)
                    assertTrue(pending.entries.all { it.categoryColor == category.color && it.canManage })
                    instrumentation.runOnMainSync { vm.changeSort(EntryListSort.ACCOUNTING_DATE) }
                    assertEquals(20, vm.state.value.visibleEntries.size)
                    assertEquals(edited.id, vm.state.value.entries.last().entry.id)
                    assertCompleteAggregates(mutatedIds, edited.title)
                    fixture.enableNetwork()
                    fixture.operation("EntryListRepositoryIntegrationTest wait 4") { writes.forEach { it.await() } }
                    val synced = state { it.syncState == SyncState.SYNCED && it.entries.size == 25 &&
                        it.entries.any { row -> row.entry.id == local.id && row.entry.createdAt != null }
                    }
                    assertFalse(synced.entries.any { it.entry.id == deleted.id })
                    assertEquals(sortEntries(synced.entries.map { it.entry }, synced.sort), synced.entries.map { it.entry })
                    assertCompleteAggregates(mutatedIds, edited.title)
                } finally { writes.filter { it.isActive }.forEach { it.cancel() } }
            }
        } finally {
            fixture.close()
        }
    }
}
