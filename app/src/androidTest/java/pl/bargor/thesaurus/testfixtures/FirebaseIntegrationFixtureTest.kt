package pl.bargor.thesaurus.testfixtures

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.data.firebase.FirstHouseholdResult
import pl.bargor.thesaurus.data.firebase.FirestorePaths
import pl.bargor.thesaurus.data.firebase.FirestoreRepositories
import pl.bargor.thesaurus.data.firebase.OnboardingIdentity
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncState

@RunWith(AndroidJUnit4::class)
class FirebaseIntegrationFixtureTest {
    @get:Rule val localNetworkPermissionRule = LocalNetworkPermissionRule()

    @Test fun independentNamedFixturesHaveSeparateAuthCachesAndStores() = runBlocking<Unit> {
        val first = FirebaseIntegrationFixture.open("independent")
        var second: FirebaseIntegrationFixture? = null
        var originalFailure: Throwable? = null
        try {
            val other = FirebaseIntegrationFixture.open("independent")
            second = other
            assertNotEquals(first.appName, other.appName)
            assertNotEquals(FirebaseApp.DEFAULT_APP_NAME, first.appName)
            assertEquals(FirebaseIntegrationPolicy.PROJECT_ID, first.app.options.projectId)
            assertNotSame(first.auth, other.auth)
            assertNotSame(first.firestore, other.firestore)
            assertNotSame(first.store, other.store)
            val firstHome = first.scenario("create first independent home") { createHome(first) }.second
            val otherHome = other.scenario("create second independent home") { createHome(other) }.second
            assertNotEquals(firstHome, otherHome)
            assertNotEquals(first.auth.currentUser!!.uid, other.auth.currentUser!!.uid)
            other.scenario("second user cannot read first fixture home") {
                val failure = runCatching { other.firestore.collection(FirestorePaths.HOUSEHOLDS)
                    .document(firstHome).get(Source.SERVER).await() }.exceptionOrNull()
                assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    (failure as? FirebaseFirestoreException)?.code)
                assertEquals(otherHome, other.firestore.collection(FirestorePaths.HOUSEHOLDS)
                    .document(otherHome).get(Source.SERVER).await().id)
            }
        } catch (error: Throwable) {
            originalFailure = error
            throw error
        } finally {
            val secondCleanup = runCatching { second?.close(originalFailure) }
            try { first.close(originalFailure ?: secondCleanup.exceptionOrNull()) }
            finally { secondCleanup.getOrThrow() }
        }
        assertTrue(first.isClosed)
        assertTrue(requireNotNull(second).isClosed)
        assertThrows(IllegalStateException::class.java) { first.firestore }
    }

    @Test fun failedOfflineScenarioCancelsAwaitersFlushesItsWriteAndClearsOnlyOwnedViewModels() = runBlocking<Unit> {
        val fixture = FirebaseIntegrationFixture.open("failure-cleanup")
        val auth = fixture.auth
        val sentinel = ScenarioFailure(Any())
        var childCancelled = false
        var cleared = false
        var clearedOnMain = false
        try {
            fixture.scenario("failure after a queued offline write") {
                val (uid, home) = createHome(fixture)
                withContext(Dispatchers.Main) {
                    fixture.store.put("owned", object : ViewModel() {
                        override fun onCleared() {
                            cleared = true
                            clearedOnMain = Looper.myLooper() == Looper.getMainLooper()
                        }
                    })
                }
                val repository = FirestoreRepositories(fixture.firestore)
                fixture.disableNetwork()
                val entry = LedgerEntry("pending-${UUID.randomUUID()}", home, -100, LocalDate.of(2026, 9, 1),
                    categoryId = "jedzenie", authorId = uid, updatedById = uid)
                async { try { repository.save(entry) } finally { childCancelled = true } }
                fixture.awaitFlow("observe owned pending write", repository.observeEntries(home)) {
                    it.state == SyncState.PENDING && it.value.orEmpty().any { row -> row.id == entry.id }
                }
                throw sentinel
            }
        } catch (error: AssertionError) {
            assertSame(sentinel, error)
        } finally {
            fixture.close()
        }
        assertTrue(fixture.isClosed)
        assertTrue(childCancelled)
        assertTrue(cleared)
        assertTrue(clearedOnMain)
        assertNull(auth.currentUser)
        assertTrue(fixture.cleanupFailures.isEmpty())
        fixture.close() // Idempotent; cannot reopen the closed client or clear another store.
    }

    private suspend fun createHome(fixture: FirebaseIntegrationFixture): Pair<String, String> {
        val email = "fixture-${UUID.randomUUID()}@example.test"
        val uid = fixture.auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
        val repository = FirestoreRepositories(fixture.firestore)
        val home = (repository.createFirstHousehold(OnboardingIdentity(uid, email, "Fixture"), "Fixture home")
            as FirstHouseholdResult.Created).householdId
        fixture.awaitFlow("created household taxonomy reaches server", repository.observeCategories(home)) {
            it.state == SyncState.SYNCED && it.value.orEmpty().size == 10
        }
        return uid to home
    }

    // Keep the sentinel itself across coroutine stack-trace recovery, not only its message.
    private class ScenarioFailure(val identityMarker: Any) : AssertionError("expected scenario failure")
}
