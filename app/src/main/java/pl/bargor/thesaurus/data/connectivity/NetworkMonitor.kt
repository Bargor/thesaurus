package pl.bargor.thesaurus.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Internet connectivity is independent of cached snapshots and pending Firestore writes. */
interface NetworkMonitor {
    val isOnline: Flow<Boolean>
}

internal fun hasInternetConnection(internet: Boolean, validated: Boolean): Boolean = internet && validated

internal fun connectivityFlow(register: ((Boolean) -> Unit) -> (() -> Unit)): Flow<Boolean> = callbackFlow {
    val unregister = register { trySend(it) }
    awaitClose { unregister() }
}.distinctUntilChanged()

/** Callback data wins over an initial snapshot; late events from the previous default are ignored. */
internal class DefaultNetworkTracker<N>(private val emit: (Boolean) -> Unit) {
    private var currentNetwork: N? = null
    private var receivedCallback = false

    @Synchronized fun initialSnapshot(network: N?, online: Boolean) {
        if (!receivedCallback) {
            currentNetwork = network
            emit(online)
        }
    }

    @Synchronized fun available(network: N) {
        receivedCallback = true
        if (currentNetwork != network) {
            currentNetwork = network
            // onCapabilitiesChanged supplies the new network's validated state next.
            emit(false)
        }
    }

    @Synchronized fun capabilitiesChanged(network: N, online: Boolean) {
        receivedCallback = true
        if (currentNetwork == network) emit(online)
    }

    @Synchronized fun lost(network: N) {
        receivedCallback = true
        if (currentNetwork == network) {
            currentNetwork = null
            emit(false)
        }
    }
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val isOnline: Flow<Boolean> = connectivityFlow { emit ->
        val tracker = DefaultNetworkTracker<Network>(emit)
        fun NetworkCapabilities?.online() = hasInternetConnection(
            this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = tracker.available(network)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                tracker.capabilitiesChanged(network, capabilities.online())
            override fun onLost(network: Network) = tracker.lost(network)
        }
        manager.registerDefaultNetworkCallback(callback)
        val initialNetwork = manager.activeNetwork
        tracker.initialSnapshot(initialNetwork, manager.getNetworkCapabilities(initialNetwork).online())
        val unregister: () -> Unit = { manager.unregisterNetworkCallback(callback) }
        unregister
    }
}
