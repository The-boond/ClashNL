package io.nekohasekai.sfa.utils

import android.net.Network
import android.net.NetworkCapabilities
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.DefaultNetworkMonitor
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
 * HTTP transport used by account and subscription traffic.
 *
 * Account and subscription requests prefer IPv4, but retain the platform's
 * IPv6/NAT64 result when the physical network has no usable IPv4 result.
 * While the core is running, app traffic first uses its local SOCKS listener
 * so account, subscription, and exit-IP requests follow the selected proxy.
 * The underlying non-VPN network remains the fallback for stopped or
 * unavailable core service states.
 */
object AppHttpTransport {
    private const val LOCAL_SOCKS_PORT = 2333

    fun execute(
        request: Request,
        preferLocalSocks: Boolean = false,
    ): Response {
        if (preferLocalSocks) {
            runCatching {
                return baseBuilder()
                    .proxy(
                        Proxy(
                            Proxy.Type.SOCKS,
                            InetSocketAddress.createUnresolved("127.0.0.1", LOCAL_SOCKS_PORT),
                        ),
                    ).build()
                    .newCall(request)
                    .execute()
            }
        }

        val network = underlyingNetwork()
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses =
                    network?.let { selected ->
                        runCatching { selected.getAllByName(hostname).toList() }.getOrNull()
                    } ?: Dns.SYSTEM.lookup(hostname)
                return preferIPv4(hostname, addresses)
            }
        }
        val builder = baseBuilder().dns(dns)
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

    private fun baseBuilder() = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
}
