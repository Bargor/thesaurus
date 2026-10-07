package pl.bargor.thesaurus.testfixtures

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FixtureCleanupTest {
    @Test fun failureAndTimeoutStillAttemptEveryReleaseAndRemainSuppressedOnScenarioFailure() = runTest {
        val attempted = mutableListOf<String>()
        val original = AssertionError("scenario failure")
        val failure = IllegalStateException("release failure")
        val failures = cleanupFixtureStages(listOf(
            FixtureCleanupStage("first") { attempted += "first"; throw failure },
            FixtureCleanupStage("stuck") { attempted += "stuck"; awaitCancellation() },
            FixtureCleanupStage("last") { attempted += "last" },
        ), original, timeoutMillis = 20)
        assertEquals(listOf("first", "stuck", "last"), attempted)
        assertEquals(2, failures.size)
        assertEquals(failures, original.suppressed.toList())
        assertSame(failure, failures.first().cause)
    }

    @Test fun cancelledCallerStillFinishesOwnedCleanupWithoutTouchingSiblingWork() = runTest {
        var cleaned = false
        var siblingCancelled = false
        val sibling = backgroundScope.launch { try { awaitCancellation() } finally { siblingCancelled = true } }
        val owner = launch {
            try { awaitCancellation() } catch (error: CancellationException) {
                val failures = cleanupFixtureStages(listOf(FixtureCleanupStage("release") {
                    delay(1); cleaned = true
                }), error)
                assertTrue(failures.isEmpty())
                throw error
            }
        }
        runCurrent()
        owner.cancelAndJoin()
        assertTrue(cleaned)
        assertTrue(sibling.isActive)
        assertEquals(false, siblingCancelled)
    }
}
