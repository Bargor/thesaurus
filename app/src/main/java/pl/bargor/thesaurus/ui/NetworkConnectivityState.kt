package pl.bargor.thesaurus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import pl.bargor.thesaurus.data.connectivity.NetworkMonitor

@Composable
internal fun rememberNetworkOnline(monitor: NetworkMonitor): State<Boolean> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(true, monitor, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            monitor.isOnline.collect { value = it }
        }
    }
}
