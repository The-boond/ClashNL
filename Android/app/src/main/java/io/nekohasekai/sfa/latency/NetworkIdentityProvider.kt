package io.nekohasekai.sfa.latency

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.DefaultNetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class NetworkIdentity(
    val key: String,
    val transport: String,
)

object NetworkIdentityProvider {
    private val registrations = ConcurrentHashMap<Any, ConnectivityManager.NetworkCallback>()
    private val _changes = MutableStateFlow(NetworkIdentity("unknown", "unknown"))
    val changes = _changes.asStateFlow()

    fun current(): NetworkIdentity {
        val connectivity = Application.connectivity
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivity.activeNetwork?.let(::add)
            }
            DefaultNetworkMonitor.defaultNetwork?.let(::add)
            connectivity.allNetworks.forEach(::add)
        }.distinct()

        val selected = candidates.firstNotNullOfOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstNotNullOfOrNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return@firstNotNullOfOrNull null
            }
            network to capabilities
        }

        if (selected == null) return NetworkIdentity("unknown", "unknown")
        val (network, capabilities) = selected
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
        val networkId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            network.networkHandle.toString()
        } else {
            connectivity.getLinkProperties(network)?.interfaceName ?: "unknown"
        }
        return NetworkIdentity("$transport:$networkId", transport)
    }

    fun start(owner: Any) {
        if (registrations.containsKey(owner)) return
        val connectivity = Application.connectivity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publishCurrent()

                override fun onLost(network: Network) = publishCurrent()

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = publishCurrent()
            }
            runCatching { connectivity.registerDefaultNetworkCallback(callback) }
                .onSuccess { registrations[owner] = callback }
        }
        publishCurrent()
    }

    fun stop(owner: Any) {
        val callback = registrations.remove(owner) ?: return
        runCatching { Application.connectivity.unregisterNetworkCallback(callback) }
    }

    private fun publishCurrent() {
        val identity = runCatching { current() }.getOrDefault(NetworkIdentity("unknown", "unknown"))
        if (_changes.value != identity) _changes.value = identity
    }
}
