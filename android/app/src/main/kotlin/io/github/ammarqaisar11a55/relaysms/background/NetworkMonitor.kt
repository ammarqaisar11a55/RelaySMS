package io.github.ammarqaisar11a55.relaysms.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes whether the phone has a validated internet connection while this process is alive.
 * It does not keep the process alive; WorkManager's network constraint covers the dead-process
 * case.
 */
class NetworkMonitor(context: Context, private val onAvailable: () -> Unit) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val was = _online.value
            _online.value = usable
            if (usable && !was) onAvailable()
        }

        override fun onLost(network: Network) {
            _online.value = currentlyOnline()
        }
    }

    fun start() {
        try {
            manager?.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // Too many callbacks or a platform fault: fall back to WorkManager's constraint only.
        }
    }

    private fun currentlyOnline(): Boolean {
        val m = manager ?: return false
        val caps = m.getNetworkCapabilities(m.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
