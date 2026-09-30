package pl.bargor.thesaurus.data.connectivity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class NetworkMonitorTest {
    @Test fun validatedInternetIsOnline() { assertTrue(hasInternetConnection(true, true)) }
    @Test fun captivePortalIsOffline() { assertFalse(hasInternetConnection(true, false)) }
    @Test fun localNetworkWithoutInternetIsOffline() { assertFalse(hasInternetConnection(false, true)) }
    @Test fun missingNetworkIsOffline() { assertFalse(hasInternetConnection(false, false)) }

    @Test fun handoverIgnoresLateEventsFromPreviousDefaultNetwork() {
        val states = mutableListOf<Boolean>()
        val tracker = DefaultNetworkTracker<String> { states += it }
        tracker.initialSnapshot("wifi", true)
        tracker.available("mobile")
        tracker.capabilitiesChanged("mobile", true)
        tracker.lost("wifi")
        tracker.capabilitiesChanged("wifi", false)
        tracker.capabilitiesChanged("mobile", false)
        tracker.capabilitiesChanged("mobile", true)
        tracker.lost("mobile")
        assertEquals(listOf(true, false, true, false, true, false), states)
    }

    @Test fun callbackWinsOverRacingInitialSnapshot() {
        val states = mutableListOf<Boolean>()
        val tracker = DefaultNetworkTracker<String> { states += it }
        tracker.available("new-network")
        tracker.capabilitiesChanged("new-network", true)
        tracker.initialSnapshot("old-network", false)
        assertEquals(listOf(false, true), states)
    }

    @Test fun availabilityOfInitialDefaultDoesNotInvalidateItsKnownCapabilities() {
        val states = mutableListOf<Boolean>()
        val tracker = DefaultNetworkTracker<String> { states += it }
        tracker.initialSnapshot("wifi", true)
        tracker.available("wifi")
        assertEquals(listOf(true), states)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun emitsCurrentStateThenTransitionsWithoutDuplicatesAndUnregistersOnCancellation() = runTest {
        var listener: ((Boolean) -> Unit)? = null
        var registered = 0
        var unregistered = 0
        val monitor = connectivityFlow { callback ->
            registered++
            listener = callback
            callback(false)
            val cleanup: () -> Unit = { unregistered++; listener = null }
            cleanup
        }
        val states = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { monitor.collect { states += it } }
        listener!!(false)
        listener!!(true)
        listener!!(true)
        listener!!(false)
        testScheduler.runCurrent()
        assertEquals(listOf(false, true, false), states)
        assertEquals(1, registered)
        job.cancel()
        testScheduler.runCurrent()
        assertEquals(1, unregistered)
        assertEquals(null, listener)
    }
}
