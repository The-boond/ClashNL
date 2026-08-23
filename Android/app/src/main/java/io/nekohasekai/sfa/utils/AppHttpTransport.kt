package io.nekohasekai.sfa.utils

import android.net.Network
import android.net.NetworkCapabilities
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.DefaultNetworkMonitor
import io.nekohasekai.sfa.repository.RemoteProfileUrlPolicy
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * HTTP transport used by subscription traffic.
 *
 * Subscription requests prefer IPv4, but retain the platform's
 * IPv6/NAT64 result when the physical network has no usable IPv4 result.
 * Requests bind directly to Android's underlying non-VPN network. Mihomo
 * intentionally exposes no local HTTP/SOCKS inbound, so probing the old
 * sing-box port would only add a failed connection before every request.
 */
object AppHttpTransport {
    enum class NetworkRoute {
        /** Bypass an active VPN. Used for subscription and update bootstrap traffic. */
        Underlying,

        /** Follow Android's current default route, including this app's VPN. */
        Active,
    }

    private const val DEFAULT_CALL_TIMEOUT_SECONDS = 35L

    @Suppress("UNUSED_PARAMETER")
    fun execute(
        request: Request,
        // Retained temporarily for source compatibility with vendor flavors.
        preferLocalSocks: Boolean = false,
        callTimeoutSeconds: Long = DEFAULT_CALL_TIMEOUT_SECONDS,
        networkRoute: NetworkRoute = NetworkRoute.Underlying,
    ): Response {
        require(callTimeoutSeconds >= 0) { "callTimeoutSeconds must not be negative" }
        val network = underlyingNetwork().takeIf { networkRoute == NetworkRoute.Underlying }
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses =
                    network?.let { selected ->
                        runCatching { selected.getAllByName(hostname).toList() }.getOrNull()
                    } ?: Dns.SYSTEM.lookup(hostname)
                return preferIPv4(hostname, addresses)
            }
        }
        val builder = baseBuilder(callTimeoutSeconds).dns(dns)
        network?.socketFactory?.let(builder::socketFactory)
        return builder.build().newCall(request).execute()
    }

    internal fun preferIPv4(hostname: String, addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty()) {
            throw UnknownHostException("No address for $hostname")
        }
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        return if (ipv4.isEmpty()) addresses else ipv4 + addresses.filterNot { it is Inet4Address }
    }

    private fun underlyingNetwork(): Network? {
        val connectivity = Application.connectivity
        val candidates = buildList {
            DefaultNetworkMonitor.defaultNetwork?.let(::add)
            connectivity.activeNetwork?.let(::add)
            connectivity.allNetworks.forEach(::add)
        }.distinct()
        return candidates
            .mapNotNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                network to capabilities
            }
            .sortedByDescending { (_, capabilities) ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            .firstOrNull()
            ?.first
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
