package pl.bargor.thesaurus.ui.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.data.firebase.*
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncState

@RunWith(AndroidJUnit4::class)
class ReportsRepositoryIntegrationTest {
    @get:Rule val localNetworkPermissionRule = LocalNetworkPermissionRule()
    @Test fun scopedCachedReportsUpdateOfflineTaxonomyAndPendingLedgerThenRecoverAndRemoveTombstones() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(instrumentation.targetContext,
            FirebaseOptions.Builder().setApplicationId("1:1234567890:android:test").setApiKey("fake-api-key")
                .setProjectId("demo-thesaurus").build(), "reports-$suffix")
        val auth = FirebaseAuthFactory.create(app)
        FirebaseAuthFactory.connectToLocalEmulator(auth)
        val firestore = FirebaseFirestoreFactory.create(app, emulatorHost = "10.0.2.2")
        val store = ViewModelStore()
        try {
            withTimeout(90_000) {
                val email = "reports-$suffix@example.test"
                val uid = auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
                val repository = FirestoreRepositories(firestore)
                val home = (repository.createFirstHousehold(OnboardingIdentity(uid, email, "Ala"), "Dom raportów") as FirstHouseholdResult.Created).householdId
                val categories = repository.observeCategories(home).first { it.state == SyncState.SYNCED && it.value.orEmpty().isNotEmpty() }.value!!
                val food = categories.single { it.id == "jedzenie" }
                val income = categories.single { it.id == "wplywy" }
                val subs = repository.observeSubcategories(home, food.id).first { it.state == SyncState.SYNCED && it.value.orEmpty().isNotEmpty() }.value!!
                val shop = subs.single { it.id == "supermarket" }
                val restaurant = subs.single { it.id == "restauracja" }
                fun entry(id: String, amount: Long, category: String = food.id, sub: String? = shop.id, date: String = "2026-09-12") = LedgerEntry(
                    "$id-$suffix", home, amount, LocalDate.parse(date), categoryId = category, subcategoryId = sub, authorId = uid, updatedById = uid)
                val expense = entry("expense", -1000)
                val refund = entry("refund", 100)
                val excluded = entry("restaurant", -400, sub = restaurant.id)
                val paycheck = entry("income", 10_000, income.id, null)
                repository.save(expense); repository.save(refund); repository.save(excluded); repository.save(paycheck)
                repository.save(entry("august", -900, date = "2026-08-31"))
                lateinit var vm: ReportsViewModel
                instrumentation.runOnMainSync {
                    vm = ReportsViewModel(repository, repository, Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle())
                    store.put("reports", vm); vm.start(home)
                }
                suspend fun state(predicate: (ReportsUiState) -> Boolean) = withTimeout(15_000) { vm.state.first(predicate) }
                state { !it.isLoading && it.syncState == SyncState.SYNCED && it.entries.size == 4 && it.categories.isNotEmpty() }
                instrumentation.runOnMainSync { vm.selectCategory(food.id) }
                state { it.selectedCategoryId == food.id && it.subcategories.any { sub -> sub.id == shop.id } }
                instrumentation.runOnMainSync { vm.selectSubcategory(shop.id) }
                val scoped = state { it.selectedSubcategoryId == shop.id && it.entries.size == 2 }
                assertEquals((-900).toBigInteger(), scoped.aggregation.totals.netGrosze)
                assertEquals(1100.toBigInteger(), scoped.aggregation.categories.single().amountGrosze)
                assertEquals(setOf(expense.id, refund.id), scoped.entries.map { it.entry.id }.toSet())
                firestore.disableNetwork().await()
                state { it.syncState == SyncState.OFFLINE }
                val local = entry("local", -500)
                val writes = listOf(async { repository.save(local) }, async { repository.save(food.copy(name = "Jedzenie lokalne", updatedById = uid)) }, async { repository.save(shop.copy(name = "Sklep lokalny", updatedById = uid)) })
                try {
                    val pending = state { it.syncState == SyncState.PENDING && it.entries.size == 3 && it.entries.all { item -> item.categoryName == "Jedzenie lokalne" && item.subcategoryName == "Sklep lokalny" } }
                    assertEquals((-1400).toBigInteger(), pending.aggregation.totals.netGrosze)
                    assertEquals(1600.toBigInteger(), pending.aggregation.categories.single().amountGrosze)
                    assertEquals(shop.id, pending.selectedSubcategoryId)
                    instrumentation.runOnMainSync { vm.selectPeriodMode(ReportPeriodMode.YEAR) }
                    val annual = state { it.mode == ReportPeriodMode.YEAR && it.entries.size == 4 }
                    assertEquals((-2300).toBigInteger(), annual.aggregation.totals.netGrosze)
                    assertEquals(2, annual.aggregation.trend.size)
                    instrumentation.runOnMainSync { vm.selectPeriodMode(ReportPeriodMode.MONTH) }
                    state { it.mode == ReportPeriodMode.MONTH && it.entries.size == 3 }
                    firestore.enableNetwork().await()
                    withTimeout(15_000) { writes.forEach { it.await() } }
                    state { it.syncState == SyncState.SYNCED && it.entries.size == 3 }
                } finally { writes.filter { it.isActive }.forEach { it.cancel() } }
                repository.tombstone(home, local.id, uid)
                val afterDelete = state { it.syncState == SyncState.SYNCED && it.entries.size == 2 && it.entries.none { item -> item.entry.id == local.id } }
                assertEquals((-900).toBigInteger(), afterDelete.aggregation.totals.netGrosze)
                assertEquals(1100.toBigInteger(), afterDelete.aggregation.categories.single().amountGrosze)
                instrumentation.runOnMainSync { vm.clearControls() }
                val all = state { !it.hasActiveFilters && it.entries.size == 4 }
                assertEquals(8700.toBigInteger(), all.aggregation.totals.netGrosze)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            runCatching { withTimeout(10_000) { firestore.enableNetwork().await() } }
            runCatching { withTimeout(10_000) { firestore.terminate().await() } }
            // Keep the named fake app registered until process exit to avoid Auth worker races.
        }
    }
}
