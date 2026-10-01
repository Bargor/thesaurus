package pl.bargor.thesaurus.ui.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.time.*
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
import pl.bargor.thesaurus.data.model.*

@RunWith(AndroidJUnit4::class)
class SummaryRepositoryIntegrationTest {
    @get:Rule val networkPermission = LocalNetworkPermissionRule()
    @Test fun realCachedOverviewAndDetailStayConsistentAcrossOfflineRenamePendingWriteAndTombstone() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(instrumentation.targetContext,
            FirebaseOptions.Builder().setApplicationId("1:1234567890:android:test").setApiKey("fake-api-key")
                .setProjectId("demo-thesaurus").build(), "summary-$suffix")
        val auth = FirebaseAuthFactory.create(app)
        FirebaseAuthFactory.connectToLocalEmulator(auth)
        val firestore = FirebaseFirestoreFactory.create(app, emulatorHost = "10.0.2.2")
        val store = ViewModelStore()
        try {
            withTimeout(90_000) {
                val email = "summary-$suffix@example.test"
                val uid = auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
                val repository = FirestoreRepositories(firestore)
                val home = (repository.createFirstHousehold(OnboardingIdentity(uid, email, "Ala"), "Dom podsumowania") as FirstHouseholdResult.Created).householdId
                val food = repository.observeCategories(home).first { it.state == SyncState.SYNCED && it.value.orEmpty().isNotEmpty() }.value!!.single { it.id == "jedzenie" }
                fun entry(id: String, amount: Long, date: String = "2026-09-12") = LedgerEntry("$id-$suffix", home, amount, LocalDate.parse(date), categoryId = food.id, authorId = uid, updatedById = uid)
                val expense = entry("expense", -1000); val income = entry("income", 3000)
                repository.save(expense); repository.save(income)
                repository.save(entry("august", -500, "2026-08-31")); repository.save(entry("future", -999, "2026-09-16"))
                lateinit var vm: SummaryViewModel
                instrumentation.runOnMainSync {
                    vm = SummaryViewModel(repository, repository, repository, Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle())
                    store.put("summary", vm); vm.start(home, uid)
                }
                suspend fun state(predicate: (SummaryUiState) -> Boolean) = withTimeout(15_000) { vm.state.first(predicate) }
                val initial = state { !it.isLoading && it.syncState == SyncState.SYNCED && it.cards.lastOrNull()?.entries?.size == 2 && it.cards.last().highestExpenseCategory != null }
                assertEquals(9, initial.cards.size)
                val key = initial.cards.last().key
                instrumentation.runOnMainSync { vm.openPeriod(key) }
                val detail = state { it.detailEntries.size == 2 }
                assertEquals(setOf(expense.id, income.id), detail.detailEntries.map { it.entry.id }.toSet())
                assertEquals(detail.cards.last().totals, detail.detailCard!!.totals)
                firestore.disableNetwork().await(); state { it.syncState == SyncState.OFFLINE }
                val local = entry("local", -500)
                val writes = listOf(async { repository.save(local) }, async { repository.save(food.copy(name = "Żywność lokalna", updatedById = uid)) })
                try {
                    val pending = state { it.syncState == SyncState.PENDING && it.detailEntries.size == 3 && it.detailEntries.all { item -> item.categoryName == "Żywność lokalna" } }
                    assertEquals(1500.toBigInteger(), pending.detailCard!!.totals.netGrosze)
                    assertEquals(pending.cards.last().totals, pending.detailCard!!.totals)
                    assertEquals("Żywność lokalna", pending.cards.last().highestExpenseCategory!!.name)
                    instrumentation.runOnMainSync { vm.closePeriod(); vm.selectPeriodMode(SummaryPeriodMode.YEAR) }
                    val annual = state { it.mode == SummaryPeriodMode.YEAR && it.cards.singleOrNull()?.entries?.size == 4 }
                    assertEquals(1000.toBigInteger(), annual.cards.single().totals.netGrosze)
                    instrumentation.runOnMainSync { vm.selectPeriodMode(SummaryPeriodMode.MONTH); vm.openPeriod(key) }
                    state { it.detailEntries.size == 3 }
                    firestore.enableNetwork().await()
                    withTimeout(15_000) { writes.forEach { it.await() } }
                    state { it.syncState == SyncState.SYNCED && it.detailEntries.size == 3 }
                } finally { writes.filter { it.isActive }.forEach { it.cancel() } }
                repository.tombstone(home, local.id, uid)
                val deleted = state { it.syncState == SyncState.SYNCED && it.detailEntries.size == 2 && it.detailEntries.none { item -> item.entry.id == local.id } }
                assertEquals(2000.toBigInteger(), deleted.detailCard!!.totals.netGrosze)
                assertEquals(deleted.cards.last().entries.map { it.id }.toSet(), deleted.detailEntries.map { it.entry.id }.toSet())
                assertEquals(deleted.cards.last().totals, deleted.detailCard!!.totals)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            runCatching { withTimeout(10_000) { firestore.enableNetwork().await() } }
            runCatching { withTimeout(10_000) { firestore.terminate().await() } }
            // Named fake app remains registered until exit, avoiding Auth worker races.
        }
    }
}
