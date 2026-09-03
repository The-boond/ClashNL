package io.nekohasekai.sfa.bg

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UnderlyingTransport(val key: String) {
    WiFi("wifi"),
    Cellular("cellular"),
    Ethernet("ethernet"),
    Usb("usb"),
    Bluetooth("bluetooth"),
    Satellite("satellite"),
    Other("other"),
}

data class UnderlyingNetworkSnapshot(
    val network: Network,
    val networkHandle: Long,
    val generation: Long,
    val transport: UnderlyingTransport,
    val interfaceName: String?,
    val interfaceAddresses: List<String>,
    val validated: Boolean,
)

internal data class UnderlyingNetworkCandidate<T>(
    val value: T,
    val stableId: Long,
    val platformPreferred: Boolean,
    val active: Boolean,
    val validated: Boolean,
    val current: Boolean,
)

/** Deterministic fallback used until Android's selected-network callback reports a value. */
internal fun <T> selectUnderlyingNetwork(candidates: List<UnderlyingNetworkCandidate<T>>): T? = candidates
    .sortedWith(
        compareByDescending<UnderlyingNetworkCandidate<T>> { it.platformPreferred }
            .thenByDescending { it.active }
            .thenByDescending { it.validated }
            .thenByDescending { it.current }
            .thenBy { it.stableId },
    ).firstOrNull()
    ?.value

/**
 * Process-wide source of truth for Android's physical, non-VPN upstream.
 *
 * Android 12+ can report the best matching non-VPN network directly. Older
 * releases use a passive matching-network callback so an active app VPN cannot
 * hide its physical upstream and the tracker never brings up a metered network.
 * The synchronous scan is only a startup/failure fallback.
 */
object UnderlyingNetworkTracker {
    private const val TAG = "UnderlyingNetwork"

    private data class ObservedNetwork(
        val capabilities: NetworkCapabilities? = null,
        val linkProperties: LinkProperties? = null,
    )

    private val lock = Any()
    private val _snapshots = MutableStateFlow<UnderlyingNetworkSnapshot?>(null)
    val snapshots = _snapshots.asStateFlow()

    private var connectivity: ConnectivityManager? = null
    private val observedNetworks = linkedMapOf<Network, ObservedNetwork>()
    private var sessionRequestCallback: ConnectivityManager.NetworkCallback? = null
    private var platformPreferredNetwork: Network? = null
    private var generation: Long = 0

    fun start(context: Context) {
        synchronized(lock) {
            if (connectivity != null) return
            val manager =
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivity = manager
            seedFromSystemLocked(manager)

            val networkCallback = createNetworkCallback(markPreferred = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            val handler = Handler(Looper.getMainLooper())
            runCatching {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        manager.registerBestMatchingNetworkCallback(networkRequest(), networkCallback, handler)

                    else -> manager.registerNetworkCallback(networkRequest(), networkCallback)
                }
            }.onFailure {
                Log.w(TAG, "Unable to monitor the physical network", it)
            }
        }
    }

    /**
     * Keeps the platform's currently preferred non-VPN network identified while
     * a VPN session is active on Android 6-11. The request is released when the
     * VPN stops, so it cannot keep a mobile network up for the app's lifetime.
     */
    fun beginVpnSession(context: Context) {
        start(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        synchronized(lock) {
            if (sessionRequestCallback != null) return
            val manager = connectivity ?: return
            val networkCallback = createNetworkCallback(markPreferred = true, sessionScoped = true)
            sessionRequestCallback = networkCallback
            runCatching {
                manager.requestNetwork(networkRequest(), networkCallback)
            }.onFailure {
                sessionRequestCallback = null
                Log.w(TAG, "Unable to request the preferred physical network", it)
            }
        }
    }

    fun endVpnSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        synchronized(lock) {
            val manager = connectivity ?: return
            val networkCallback = sessionRequestCallback ?: return
            sessionRequestCallback = null
            runCatching { manager.unregisterNetworkCallback(networkCallback) }
                .onFailure { Log.w(TAG, "Unable to release the preferred physical network request", it) }
            platformPreferredNetwork = null
            recomputeLocked(manager)
        }
    }

    fun current(refresh: Boolean = false): UnderlyingNetworkSnapshot? = synchronized(lock) {
        val manager = connectivity ?: return@synchronized _snapshots.value
        if (refresh) seedFromSystemLocked(manager)
        _snapshots.value
    }

    fun refresh(): UnderlyingNetworkSnapshot? = current(refresh = true)

    private fun createNetworkCallback(
        markPreferred: Boolean,
        sessionScoped: Boolean = false,
    ): ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
        private fun isActive(): Boolean = !sessionScoped || sessionRequestCallback === this

        override fun onAvailable(network: Network) {
            synchronized(lock) {
                if (!isActive()) return
                observedNetworks.putIfAbsent(network, ObservedNetwork())
                if (markPreferred) platformPreferredNetwork = network
                connectivity?.let(::recomputeLocked)
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (!isActive()) return
                if (!sessionScoped) observedNetworks.remove(network)
                if (platformPreferredNetwork == network) platformPreferredNetwork = null
                connectivity?.let(::recomputeLocked)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            synchronized(lock) {
                if (!isActive()) return
                val previous = observedNetworks[network] ?: ObservedNetwork()
                observedNetworks[network] = previous.copy(capabilities = networkCapabilities)
                connectivity?.let(::recomputeLocked)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            synchronized(lock) {
                if (!isActive()) return
                val previous = observedNetworks[network] ?: ObservedNetwork()
                observedNetworks[network] = previous.copy(linkProperties = linkProperties)
                connectivity?.let(::recomputeLocked)
            }
        }
    }

    private fun networkRequest(): NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
        }.build()

    private fun seedFromSystemLocked(manager: ConnectivityManager) {
        val available = manager.allNetworks.toSet()
        observedNetworks.keys.retainAll(available)
        available.forEach { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            if (capabilities == null) {
                observedNetworks.remove(network)
            } else {
                observedNetworks[network] =
                    ObservedNetwork(
                        capabilities = capabilities,
                        linkProperties = manager.getLinkProperties(network),
                    )
            }
        }
        recomputeLocked(manager)
    }

    private fun recomputeLocked(manager: ConnectivityManager) {
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) manager.activeNetwork else null
        val current = _snapshots.value?.network
        val preferred = platformPreferredNetwork
        val candidates =
            observedNetworks.mapNotNull { (network, observed) ->
                val capabilities = observed.capabilities ?: return@mapNotNull null
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                if (!capabilities.isNonVpn()) return@mapNotNull null
                UnderlyingNetworkCandidate(
                    value = network,
                    stableId = network.stableId(),
                    platformPreferred = network == preferred,
                    active = network == active,
                    validated = capabilities.isValidated(),
                    current = network == current,
                )
            }
        val selected = selectUnderlyingNetwork(candidates)
        val next = selected?.let { network ->
            observedNetworks[network]?.let { observed -> snapshotOf(network, observed) }
        }
        val previous = _snapshots.value
        if (next == null) {
            if (previous != null) _snapshots.value = null
        } else if (previous == null || !next.sameNetworkDetails(previous)) {
            generation += 1
            _snapshots.value = next.copy(generation = generation)
        }
    }

    private fun snapshotOf(network: Network, observed: ObservedNetwork): UnderlyingNetworkSnapshot? {
        val capabilities = observed.capabilities ?: return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        if (!capabilities.isNonVpn()) return null
        val linkProperties = observed.linkProperties
        val addresses =
            linkProperties
                ?.linkAddresses
                ?.map { it.address }
                ?.filterNot {
                    it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isMulticastAddress
                }
                ?.sortedBy { address -> if (address.address.size == 4) 0 else 1 }
                ?.mapNotNull { it.hostAddress?.substringBefore('%') }
                ?.distinct()
                .orEmpty()
        return UnderlyingNetworkSnapshot(
            network = network,
            networkHandle = network.stableId(),
            generation = 0,
            transport = capabilities.toUnderlyingTransport(),
            interfaceName = linkProperties?.interfaceName?.takeIf(String::isNotBlank),
            interfaceAddresses = addresses,
            validated = capabilities.isValidated(),
        )
    }

    private fun UnderlyingNetworkSnapshot.sameNetworkDetails(other: UnderlyingNetworkSnapshot): Boolean = network == other.network &&
        networkHandle == other.networkHandle &&
        transport == other.transport &&
        interfaceName == other.interfaceName &&
        interfaceAddresses == other.interfaceAddresses &&
        validated == other.validated

    private fun Network.stableId(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        networkHandle
    } else {
        hashCode().toLong()
    }

    private fun NetworkCapabilities.isNonVpn(): Boolean = !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))

    private fun NetworkCapabilities.isValidated(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun NetworkCapabilities.toUnderlyingTransport(): UnderlyingTransport = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UnderlyingTransport.WiFi
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UnderlyingTransport.Cellular
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> UnderlyingTransport.Ethernet
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasTransport(NetworkCapabilities.TRANSPORT_USB) ->
            UnderlyingTransport.Usb

        hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> UnderlyingTransport.Bluetooth
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> UnderlyingTransport.Satellite

        else -> UnderlyingTransport.Other
    }
}
