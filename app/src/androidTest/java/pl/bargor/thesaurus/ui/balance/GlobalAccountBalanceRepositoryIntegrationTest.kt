package pl.bargor.thesaurus.ui.balance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.data.firebase.*
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.testfixtures.FirebaseIntegrationFixture

@RunWith(AndroidJUnit4::class)
class GlobalAccountBalanceRepositoryIntegrationTest {
    @get:Rule val networkPermission = LocalNetworkPermissionRule()

    @Test fun allTimeBalanceRehydratesPendingCacheAndReconcilesEditsAndTombstones() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = UUID.randomUUID().toString()
        // A named fake app and unique owned household bound every write to this fixture.
        // Never clear emulator data: the DEV household may contain the user's sample ledger.
        val fixture = FirebaseIntegrationFixture.open("GlobalAccountBalanceRepositoryIntegrationTest")
        val auth = fixture.auth
        val firestore = fixture.firestore
        val store = fixture.store
        try {
            fixture.scenario("GlobalAccountBalanceRepositoryIntegrationTest scenario") {
                val email = "global-balance-$suffix@example.test"
                val uid = auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
                val repository = FirestoreRepositories(firestore)
                val home = (repository.createFirstHousehold(OnboardingIdentity(uid, email, "Ala"), "Dom salda") as FirstHouseholdResult.Created).householdId
                fun entry(id: String, amount: Long, date: String = "2026-09-15") = LedgerEntry("$id-$suffix", home, amount, LocalDate.parse(date), categoryId = "jedzenie", authorId = uid, updatedById = uid)
                val income = entry("old-income", 3000, "2001-01-01")
                val expense = entry("future-expense", -1000, "2099-12-31")
                repository.save(income); repository.save(expense)
                lateinit var vm: GlobalAccountBalanceViewModel
                fun createVm() = instrumentation.runOnMainSync {
                    store.clear()
                    vm = GlobalAccountBalanceViewModel(repository, repository)
                    store.put("balance", vm); vm.start(home, uid)
                }
                suspend fun state(predicate: (GlobalAccountBalanceUiState) -> Boolean) = fixture.operation("GlobalAccountBalanceRepositoryIntegrationTest wait 1") { vm.state.first(predicate) }
                createVm()
                state { it.amountGrosze == 2000.toBigInteger() && it.syncState == SyncState.SYNCED }
                fixture.disableNetwork()
                state { it.amountGrosze == 2000.toBigInteger() && it.syncState == SyncState.OFFLINE }
                val local = entry("local", -500)
                val pending = async { repository.save(local) }
                try {
                    state { it.amountGrosze == 1500.toBigInteger() && it.syncState == SyncState.PENDING }
                    createVm()
                    val restored = state { it.amountGrosze == 1500.toBigInteger() && it.syncState == SyncState.PENDING }
                    assertFalse(restored.isLoading); assertFalse(restored.hasError)
                    fixture.enableNetwork(); fixture.operation("GlobalAccountBalanceRepositoryIntegrationTest wait 2") { pending.await() }
                    state { it.amountGrosze == 1500.toBigInteger() && it.syncState == SyncState.SYNCED }
                } finally { if (pending.isActive) pending.cancel() }
                val storedExpense = fixture.operation("GlobalAccountBalanceRepositoryIntegrationTest wait 3") {
                    repository.observeEntries(home).first { observation ->
                        observation.state == SyncState.SYNCED && observation.value.orEmpty().any {
                            it.id == expense.id && it.createdAt != null
                        }
                    }.value!!.first { it.id == expense.id }
                }
                repository.save(storedExpense.copy(amountGrosze = -1250))
                state { it.amountGrosze == 1250.toBigInteger() && it.syncState == SyncState.SYNCED }
                repository.tombstone(home, local.id, uid)
                state { it.amountGrosze == 1750.toBigInteger() && it.syncState == SyncState.SYNCED }
                repository.tombstone(home, income.id, uid)
                val negative = state { it.amountGrosze == (-1250).toBigInteger() && it.syncState == SyncState.SYNCED }
                assertEquals(home, negative.householdId); assertEquals(uid, negative.actorId)
                repository.tombstone(home, expense.id, uid)
                state { it.amountGrosze == 0.toBigInteger() && it.syncState == SyncState.SYNCED && !it.isLoading }
            }
        } finally {
            fixture.close()
        }
        Unit
    }
}
