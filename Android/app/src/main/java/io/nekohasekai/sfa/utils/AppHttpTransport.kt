package io.nekohasekai.sfa.utils

import android.net.Network
import io.nekohasekai.sfa.bg.UnderlyingNetworkTracker
import io.nekohasekai.sfa.repository.RemoteProfileUrlPolicy
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * HTTP transport used by subscription traffic.
 *
 * Subscription requests prefer IPv4, but retain the platform's
 * IPv6/NAT64 result when the physical network has no usable IPv4 result.
 * Subscription requests bind directly to Android's underlying non-VPN network.
 * Runtime diagnostics may instead opt into Mihomo's app-owned IPv4 loopback
 * HTTP proxy so they observe the selected proxy exit.
 */
object AppHttpTransport {
    enum class NetworkRoute {
        /** Bypass an active VPN. Used for subscription and update bootstrap traffic. */
        Underlying,

        /** Only used to reach Mihomo's explicit loopback HTTP proxy. */
        LocalProxy,
    }

    private const val DEFAULT_CALL_TIMEOUT_SECONDS = 35L

    @Suppress("UNUSED_PARAMETER")
    fun execute(
        request: Request,
        // Retained temporarily for source compatibility with vendor flavors.
        preferLocalSocks: Boolean = false,
        callTimeoutSeconds: Long = DEFAULT_CALL_TIMEOUT_SECONDS,
        networkRoute: NetworkRoute = NetworkRoute.Underlying,
        localHttpProxyPort: Int? = null,
        explicitNetwork: Network? = null,
    ): Response {
        require(callTimeoutSeconds >= 0) { "callTimeoutSeconds must not be negative" }
        require(localHttpProxyPort == null || localHttpProxyPort in 1..65_535) { "Invalid local HTTP proxy port" }
        require(networkRoute != NetworkRoute.LocalProxy || localHttpProxyPort != null) {
            "The process-default route is not a reliable VPN diagnostic; use the local Mihomo proxy"
        }
        require(networkRoute == NetworkRoute.LocalProxy || localHttpProxyPort == null) {
            "A local Mihomo proxy cannot be combined with an underlying-network request"
        }
        require(explicitNetwork == null || networkRoute == NetworkRoute.Underlying) {
            "An explicit network is only valid for underlying-network requests"
        }
        val network = when (networkRoute) {
            NetworkRoute.Underlying ->
                explicitNetwork
                    ?: UnderlyingNetworkTracker.current()?.network
                    ?: error("No physical network is available")

            NetworkRoute.LocalProxy -> null
        }
        val dns =
            network?.let { selected ->
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = preferIPv4(hostname, selected.getAllByName(hostname).toList())
                }
            } ?: Dns.SYSTEM
        val builder = baseBuilder(callTimeoutSeconds).dns(dns)
        network?.let { selected ->
            // Ignore Android's system ProxySelector: a physical-network
            // diagnostic must not be redirected into this app's VPN proxy.
            builder.proxy(Proxy.NO_PROXY)
            builder.socketFactory(selected.socketFactory)
        }
        localHttpProxyPort?.let { port ->
            // Mihomo deliberately binds its app-owned inbound to IPv4 loopback.
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        }
        return builder.build().newCall(request).execute()
    }

    internal fun preferIPv4(hostname: String, addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty()) {
            throw UnknownHostException("No address for $hostname")
        }
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        return if (ipv4.isEmpty()) addresses else ipv4 + addresses.filterNot { it is Inet4Address }
    }

    private fun baseBuilder(callTimeoutSeconds: Long) = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            RemoteProfileUrlPolicy.validate(chain.request().url.toString())
            chain.proceed(chain.request())
        }
}
