package pl.bargor.thesaurus.testfixtures

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.bargor.thesaurus.data.firebase.FirebaseAuthFactory
import pl.bargor.thesaurus.data.firebase.FirebaseFirestoreFactory

/** Owns only one named test app/cache and one UI store. No global clears or default Firebase app. */
internal class FirebaseIntegrationFixture private constructor(
    private val namedApp: FirebaseApp,
    private val namedAuth: FirebaseAuth,
    private val namedFirestore: FirebaseFirestore,
) {
    val appName: String = namedApp.name
    private val viewModels = ViewModelStore()
    var isClosed = false
        private set
    var cleanupFailures: List<Throwable> = emptyList()
        private set
    private var scenarioFailure: Throwable? = null
    val app: FirebaseApp get() = active { namedApp }
    val auth: FirebaseAuth get() = active { namedAuth }
    val firestore: FirebaseFirestore get() = active { namedFirestore }
    val store: ViewModelStore get() = active { viewModels }

    private inline fun <T> active(block: () -> T): T {
        check(!isClosed) { "Fixture $appName is closed" }
        return block()
    }

    suspend fun <T> operation(stage: String, timeoutMillis: Long = 15_000, block: suspend CoroutineScope.() -> T): T {
        check(!isClosed) { "Fixture $appName is closed: $stage" }
        require(stage.isNotBlank() && timeoutMillis in 1..90_000)
        val finished = withTimeoutOrNull(timeoutMillis) { Finished(block()) }
        currentCoroutineContext().ensureActive()
        if (finished == null) throw FixtureTimeoutException("$appName: $stage timed out after ${timeoutMillis}ms")
        return finished.value
    }

    suspend fun <T> scenario(stage: String, block: suspend CoroutineScope.() -> T): T = try {
        operation(stage, 90_000) { coroutineScope(block) }
    } catch (error: Throwable) {
        scenarioFailure = error
        throw error
    }

    suspend fun <T> awaitFlow(stage: String, flow: Flow<T>, predicate: (T) -> Boolean): T =
        operation(stage) { flow.first(predicate) }

    suspend fun disableNetwork() = operation("disable network") { namedFirestore.disableNetwork().await() }
    suspend fun enableNetwork() = operation("enable network") { namedFirestore.enableNetwork().await() }

    suspend fun close(originalFailure: Throwable? = scenarioFailure) {
        if (isClosed) return
        isClosed = true
        cleanupFailures = cleanupFixtureStages(listOf(
            // scenario() owns its CoroutineScope; a failed scenario cancels/joins its own
            // awaiters before reaching close(). Never cancel an unrelated caller's children.
            FixtureCleanupStage("$appName clear ViewModels") { withContext(Dispatchers.Main) { viewModels.clear() } },
            FixtureCleanupStage("$appName restore network") { namedFirestore.enableNetwork().await() },
            FixtureCleanupStage("$appName finish pending writes") { namedFirestore.waitForPendingWrites().await() },
            FixtureCleanupStage("$appName sign out named Auth") { namedAuth.signOut() },
            FixtureCleanupStage("$appName terminate named Firestore") { namedFirestore.terminate().await() },
            FixtureCleanupStage("$appName clear named cache") { namedFirestore.clearPersistence().await() },
        ), originalFailure)
        // Firebase Auth exposes no worker shutdown/join API. Deleting FirebaseApp can race
        // its queued Auth callbacks even after user Tasks complete. Keep only this signed-out
        // named registration until the instrumentation process exits; never retain active stores,
        // listeners or a Firestore network connection, and never delete another app's state.
        if (originalFailure == null && cleanupFailures.isNotEmpty()) {
            throw AssertionError("$appName cleanup failed").also { failure -> cleanupFailures.forEach(failure::addSuppressed) }
        }
    }

    companion object {
        suspend fun open(label: String, policy: FirebaseIntegrationPolicy = FirebaseIntegrationPolicy()): FirebaseIntegrationFixture {
            val name = policy.appName(label, UUID.randomUUID().toString())
            var app: FirebaseApp? = null
            var auth: FirebaseAuth? = null
            var firestore: FirebaseFirestore? = null
            try {
                val finished = withTimeoutOrNull(10_000) {
                    val created = FirebaseApp.initializeApp(InstrumentationRegistry.getInstrumentation().targetContext,
                        FirebaseOptions.Builder().setProjectId(policy.projectId).setApiKey(policy.apiKey)
                            .setApplicationId(policy.applicationId).build(), name)
                    app = created
                    val createdAuth = FirebaseAuthFactory.create(created)
                    auth = createdAuth
                    FirebaseAuthFactory.connectToLocalEmulator(createdAuth, host = policy.host, port = policy.authPort)
                    val createdFirestore = FirebaseFirestoreFactory.create(created, emulatorHost = policy.host, emulatorPort = policy.firestorePort)
                    firestore = createdFirestore
                    FirebaseIntegrationFixture(created, createdAuth, createdFirestore)
                }
                currentCoroutineContext().ensureActive()
                return finished ?: throw FixtureTimeoutException("$name setup timed out after 10000ms")
            } catch (error: Throwable) {
                cleanupFixtureStages(listOf(
                    FixtureCleanupStage("$name failed setup Auth") { auth?.signOut() },
                    FixtureCleanupStage("$name failed setup Firestore termination") {
                        val partial = firestore ?: app?.let { FirebaseFirestore.getInstance(it) }
                        partial?.terminate()?.await()
                    },
                    FixtureCleanupStage("$name failed setup cache") {
                        val partial = firestore ?: app?.let { FirebaseFirestore.getInstance(it) }
                        partial?.clearPersistence()?.await()
                    },
                ), error)
                throw error
            }
        }
    }

    private data class Finished<T>(val value: T)
}

internal class FixtureTimeoutException(message: String) : AssertionError(message)
