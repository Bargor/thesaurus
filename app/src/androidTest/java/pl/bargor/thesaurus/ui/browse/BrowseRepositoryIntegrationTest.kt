package pl.bargor.thesaurus.ui.browse

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
import pl.bargor.thesaurus.data.observation.HouseholdObservation
import pl.bargor.thesaurus.ui.summary.SummaryPeriodMode
import pl.bargor.thesaurus.testfixtures.FirebaseIntegrationFixture

/** Only a named fake app connected to Auth/Firestore emulators participates in this test. */
@RunWith(AndroidJUnit4::class)
class BrowseRepositoryIntegrationTest {
    @get:Rule val localNetworkPermissionRule = LocalNetworkPermissionRule()

    @Test fun cachedNestedBrowseTracksPendingWritesPeriodChangesAndSyncedTombstones() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = UUID.randomUUID().toString()
        val fixture = FirebaseIntegrationFixture.open("BrowseRepositoryIntegrationTest")
        val auth = fixture.auth
        val firestore = fixture.firestore
        val store = fixture.store
        try {
            fixture.scenario("BrowseRepositoryIntegrationTest scenario") {
                val email = "browse-$suffix@example.test"
                val uid = auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
                val repository = FirestoreRepositories(firestore)
                val household = (repository.createFirstHousehold(OnboardingIdentity(uid, email, "Ala"), "Dom Przeglądu") as FirstHouseholdResult.Created).householdId
                val categories = repository.observeCategories(household).first { it.state == SyncState.SYNCED && it.value.orEmpty().isNotEmpty() }.value!!
                val food = categories.single { it.id == "jedzenie" }
                val income = categories.single { it.id == "wplywy" }
                val foodSubs = repository.observeSubcategories(household, food.id).first { it.state == SyncState.SYNCED && it.value.orEmpty().isNotEmpty() }.value!!
                val shop = foodSubs.single { it.id == "supermarket" }
                fun entry(id: String, amount: Long, date: LocalDate, categoryId: String, subcategoryId: String? = null) = LedgerEntry(
                    id = "$id-$suffix", householdId = household, amountGrosze = amount, date = date,
                    categoryId = categoryId, subcategoryId = subcategoryId, authorId = uid, updatedById = uid)
                val septIncome = entry("income", 10_000, LocalDate.of(2026, 9, 1), income.id)
                val septExpense = entry("expense", -2_000, LocalDate.of(2026, 9, 30), food.id, shop.id)
                val august = entry("august", -700, LocalDate.of(2026, 8, 31), food.id)
                repository.save(septIncome); repository.save(septExpense); repository.save(august)
                lateinit var vm: BrowseViewModel
                instrumentation.runOnMainSync {
                    vm = BrowseViewModel(repository, repository, repository,
                        Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle())
                    store.put("browse", vm)
                    vm.start(household, uid)
                }
                suspend fun awaitState(predicate: (BrowseUiState) -> Boolean) = fixture.operation("BrowseRepositoryIntegrationTest wait 1") { vm.state.first(predicate) }
                val ready = awaitState { !it.isLoading && it.syncState == SyncState.SYNCED && it.entryItems.size == 2 && it.entryItems[septExpense.id]?.subcategoryName == shop.name }
                assertEquals((-2_000).toBigInteger(), ready.categories.single { it.categoryId == food.id }.netGrosze)
                assertEquals(10_000.toBigInteger(), ready.categories.single { it.categoryId == income.id }.netGrosze)
                assertFalse(ready.entryItems.containsKey(august.id))
                val shopKey = BrowseSubcategoryKey(food.id, shop.id)
                val incomeNone = BrowseSubcategoryKey(income.id, null)
                instrumentation.runOnMainSync {
                    vm.toggleCategory(food.id); vm.toggleSubcategory(shopKey)
                    vm.toggleCategory(income.id); vm.toggleSubcategory(incomeNone)
                }
                assertTrue(vm.state.value.expandedSubcategories.containsAll(listOf(shopKey, incomeNone)))
                fixture.disableNetwork()
                awaitState { it.syncState == SyncState.OFFLINE }
                val offlineExpense = entry("offline", -500, LocalDate.of(2026, 9, 14), food.id, shop.id)
                val pendingSave = async { repository.save(offlineExpense) }
                try {
                    val pending = awaitState { it.syncState == SyncState.PENDING && it.entryItems.containsKey(offlineExpense.id) }
                    assertEquals((-2_500).toBigInteger(), pending.categories.single { it.categoryId == food.id }.netGrosze)
                    assertTrue(shopKey in pending.expandedSubcategories)
                    instrumentation.runOnMainSync { vm.selectPeriodMode(SummaryPeriodMode.YEAR) }
                    val yearly = awaitState { it.mode == SummaryPeriodMode.YEAR && it.entryItems.size == 4 }
                    assertEquals((-3_200).toBigInteger(), yearly.categories.single { it.categoryId == food.id }.netGrosze)
                    instrumentation.runOnMainSync { vm.selectPeriodMode(SummaryPeriodMode.MONTH); vm.previousMonth() }
                    val cachedAugust = awaitState { it.month.monthValue == 8 && it.entryItems.size == 1 }
                    assertEquals(setOf(august.id), cachedAugust.entryItems.keys)
                    assertEquals((-700).toBigInteger(), cachedAugust.categories.single().netGrosze)
                    instrumentation.runOnMainSync { vm.nextMonth() }
                    awaitState { it.month.monthValue == 9 && it.entryItems.size == 3 }
                    fixture.enableNetwork()
                    fixture.operation("BrowseRepositoryIntegrationTest wait 2") { pendingSave.await() }
                    awaitState { it.syncState == SyncState.SYNCED && it.entryItems.size == 3 }
                } finally {
                    if (pendingSave.isActive) pendingSave.cancel()
                }
                repository.tombstone(household, offlineExpense.id, uid)
                val deleted = awaitState { it.syncState == SyncState.SYNCED && !it.entryItems.containsKey(offlineExpense.id) && it.entryItems.size == 2 }
                assertEquals((-2_000).toBigInteger(), deleted.categories.single { it.categoryId == food.id }.netGrosze)
                assertEquals(setOf(septExpense.id), deleted.categories.single { it.categoryId == food.id }.subcategories.single().entries.map { it.id }.toSet())
                // Archived taxonomy remains readable for historical ledger rows in the shared model.
                repository.save(shop.copy(name = "Historyczne zakupy", archived = true, updatedById = uid))
                val renamed = awaitState { it.syncState == SyncState.SYNCED &&
                    it.entryItems[septExpense.id]?.subcategoryName == "Historyczne zakupy" }
                assertEquals((-2_000).toBigInteger(), renamed.categories.single { it.categoryId == food.id }.netGrosze)
                val shared = fixture.operation("BrowseRepositoryIntegrationTest wait 3") {
                    HouseholdObservation(repository, repository, repository)
                        .observe(household, uid, includeHousehold = true).first { model ->
                            model.syncState == SyncState.SYNCED && model.household.hasSnapshot &&
                                model.entries.value.orEmpty().size == 3 &&
                                model.subcategoryValues[food.id].orEmpty().any {
                                    it.id == shop.id && it.archived && it.name == "Historyczne zakupy"
                                }
                        }
                }
                val sharedEntries = requireNotNull(shared.entries.value)
                assertEquals(setOf(septIncome.id, septExpense.id, august.id), sharedEntries.map { it.id }.toSet())
                assertEquals(-2_000L, sharedEntries.single { it.id == septExpense.id }.amountGrosze)
                assertEquals(household, requireNotNull(shared.household.value).id)
            }
        } finally {
            fixture.close()
        }
    }
}
