package pl.bargor.thesaurus.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.connectivity.NetworkMonitor

class NetworkConnectivityStateTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fakeMonitorDrivesIndicatorAndCollectionStopsOnBackgroundAndDisposal() {
        val online = MutableStateFlow(true)
        var activeCollectors = 0
        val monitor = object : NetworkMonitor {
            override val isOnline = flow {
                activeCollectors++
                try { emitAll(online) } finally { activeCollectors-- }
            }
        }
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        var composed by mutableStateOf(true)
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        rule.setContent {
            if (composed) CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val isOnline by rememberNetworkOnline(monitor)
                ThesaurusTheme { OfflineStatusHost(isOnline, "entries") {} }
            }
        }
        rule.waitUntil { activeCollectors == 1 }
        rule.onNodeWithTag("offline-indicator").assertDoesNotExist()
        rule.runOnIdle { online.value = false }
        rule.onNodeWithTag("offline-indicator").assertIsDisplayed()
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.waitUntil { activeCollectors == 0 }
        rule.runOnIdle { online.value = true }
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        rule.waitUntil { activeCollectors == 1 }
        rule.onNodeWithTag("offline-indicator").assertDoesNotExist()
        rule.runOnIdle { composed = false }
        rule.waitUntil { activeCollectors == 0 }
    }
}
