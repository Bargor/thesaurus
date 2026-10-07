package pl.bargor.thesaurus.ui.balance

import androidx.test.ext.junit.runners.AndroidJUnit4
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
class OpeningBalanceRepositoryIntegrationTest {
    @get:Rule val networkPermission = LocalNetworkPermissionRule()

    @Test fun settingsPersistOfflineAndCurrentBalanceBacktracksTheCompleteOwnedLedger() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val fixture = FirebaseIntegrationFixture.open("OpeningBalanceRepositoryIntegrationTest")
        val auth = fixture.auth
        val firestore = fixture.firestore
        val repository = FirestoreRepositories(firestore)
        try {
            fixture.scenario("OpeningBalanceRepositoryIntegrationTest scenario") {
                suspend fun newHome(label: String): Pair<String, String> {
                    val email = "opening-$label-$suffix@example.test"
                    val uid = auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
                    val home = (repository.createFirstHousehold(OnboardingIdentity(uid, email, "Ala"), "Dom $label")
                        as FirstHouseholdResult.Created).householdId
                    return uid to home
                }
                // An independent household in the same project must never affect backtracking.
                val (otherUid, otherHome) = newHome("other")
                repository.save(LedgerEntry("foreign-$suffix", otherHome, 900_000, LocalDate.of(2026, 9, 15),
                    categoryId = "jedzenie", authorId = otherUid, updatedById = otherUid))
                val (uid, home) = newHome("owned")
                suspend fun opening(expected: Long, sync: SyncState = SyncState.SYNCED): Household =
                    fixture.operation("OpeningBalanceRepositoryIntegrationTest wait 1") { repository.observeHousehold(home).first {
                        it.state == sync && it.value?.openingBalanceGrosze == expected
                    }.value!! }

                repository.saveCurrentBalance(home, 12_345)
                opening(12_345) // A synchronized empty ledger needs no adjustment.
                for (amount in listOf(-12_345L, 0L, 123_456_789_012L)) {
                    repository.saveOpeningBalance(home, amount)
                    opening(amount)
                }
                repository.saveOpeningBalance(home, 20_000)
                opening(20_000)
                fixture.disableNetwork()
                opening(20_000, SyncState.OFFLINE)
                val pending = async { repository.saveOpeningBalance(home, 25_000) }
                try {
                    opening(25_000, SyncState.PENDING)
                    // A recreated subscription uses the existing positive cached setting.
                    assertEquals(25_000L, opening(25_000, SyncState.PENDING).openingBalanceGrosze)
                    val failure = runCatching { repository.saveCurrentBalance(home, 30_000) }.exceptionOrNull()
                    assertNotNull("Current mode must reject unavailable server history", failure)
                    opening(25_000, SyncState.PENDING)
                    fixture.enableNetwork()
                    fixture.operation("OpeningBalanceRepositoryIntegrationTest wait 2") { pending.await() }
                    opening(25_000)
                } finally { if (pending.isActive) pending.cancel() }

                fun entry(id: String, amount: Long, date: String) = LedgerEntry("$id-$suffix", home, amount,
                    LocalDate.parse(date), categoryId = "jedzenie", authorId = uid, updatedById = uid)
                val old = entry("old", 5_000, "2001-01-01")
                val future = entry("future", -1_250, "2099-12-31")
                val deleted = entry("deleted", 99_999, "2026-09-15")
                repository.save(old); repository.save(future); repository.save(deleted)
                repository.tombstone(home, deleted.id, uid)
                for (current in listOf(10_000L, -500L, 0L, 123_456_789_012L)) {
                    repository.saveCurrentBalance(home, current)
                    val saved = opening(current - 3_750)
                    val ledger = fixture.operation("OpeningBalanceRepositoryIntegrationTest wait 3") { repository.observeEntries(home).first { it.state == SyncState.SYNCED }.value!! }
                    assertEquals(current.toBigInteger(), absoluteAccountBalance(saved.openingBalanceGrosze, ledger))
                    assertTrue(ledger.none { it.householdId == otherHome })
                }
                // Retry must read the ledger again after a subsequent edit, not reuse its first sum.
                val storedOld = fixture.operation("OpeningBalanceRepositoryIntegrationTest wait 4") { repository.observeEntries(home).first { it.state == SyncState.SYNCED }.value!! }
                    .first { it.id == old.id }
                repository.save(storedOld.copy(amountGrosze = 6_000))
                repository.saveCurrentBalance(home, 10_000)
                opening(5_250)
                val finalEntries = fixture.operation("OpeningBalanceRepositoryIntegrationTest wait 5") { repository.observeEntries(home, includeDeleted = true).first { it.state == SyncState.SYNCED }.value!! }
                assertEquals("Setting a balance must not create a ledger entry", setOf(old.id, future.id, deleted.id), finalEntries.map { it.id }.toSet())

                suspend fun rejectStalePreparedBalance(
                    mutation: suspend () -> Unit,
                    unchangedOpening: Long,
                    freshOpening: Long,
                ) {
                    val prepared = repository.prepareCurrentBalance(home, 10_000)
                    mutation()
                    val failure = runCatching {
                        fixture.operation("OpeningBalanceRepositoryIntegrationTest wait 6") { repository.commitCurrentBalance(prepared) }
                    }.exceptionOrNull()
                    assertNotNull("A changed ledger or setting must reject the prepared balance", failure)
                    opening(unchangedOpening)
                    // The public retry must fetch a new complete server snapshot.
                    repository.saveCurrentBalance(home, 10_000)
                    opening(freshOpening)
                }
                val racedCreation = entry("raced-creation", 1_000, "1999-01-01")
                rejectStalePreparedBalance({ repository.save(racedCreation) }, 5_250, 4_250)
                val storedFuture = fixture.operation("OpeningBalanceRepositoryIntegrationTest wait 7") { repository.observeEntries(home).first { it.state == SyncState.SYNCED }.value!! }
                    .first { it.id == future.id }
                rejectStalePreparedBalance({ repository.save(storedFuture.copy(amountGrosze = -2_250)) }, 4_250, 5_250)
                rejectStalePreparedBalance({ repository.tombstone(home, racedCreation.id, uid) }, 5_250, 6_250)
                rejectStalePreparedBalance({ repository.saveOpeningBalance(home, 7_777) }, 7_777, 6_250)
            }
        } finally {
            fixture.close()
        }
        Unit
    }
}
