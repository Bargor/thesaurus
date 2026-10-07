package pl.bargor.thesaurus.testfixtures

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class FixtureCleanupStage(val label: String, val action: suspend () -> Unit)

/** A failed stage cannot skip later releases or replace the original scenario failure. */
internal suspend fun cleanupFixtureStages(
    stages: List<FixtureCleanupStage>,
    originalFailure: Throwable?,
    timeoutMillis: Long = 10_000,
): List<Throwable> = withContext(NonCancellable) {
    require(timeoutMillis in 1..10_000)
    val failures = mutableListOf<Throwable>()
    for (stage in stages) {
        try {
            val finished = withTimeoutOrNull(timeoutMillis) { stage.action(); true }
            check(finished == true) { "Cleanup timed out: ${stage.label}" }
        } catch (error: Throwable) {
            val failure = AssertionError("Cleanup failed: ${stage.label}", error)
            failures += failure
            originalFailure?.addSuppressed(failure)
        }
    }
    failures
}
