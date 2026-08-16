package io.nekohasekai.sfa.utils

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * HTTP transport used by account and subscription traffic.
 *
 * Some mobile networks advertise a working IPv6 route but reset Cloudflare
 * HTTPS streams after the handshake. Prefer IPv4 whenever an A record exists,
 * while retaining IPv6 for IPv6-only hosts. When the local core is running,
 * subscription downloads first use its SOCKS listener and then fall back to
 * the direct IPv4-preferred transport.
 */
object AppHttpTransport {
    private const val LOCAL_SOCKS_PORT = 2333

    private val ipv4PreferredDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = preferIPv4(Dns.SYSTEM.lookup(hostname))
    }

    private val directClient = baseBuilder()
        .dns(ipv4PreferredDns)
        .build()

    private val localSocksClient = baseBuilder()
        .proxy(
            Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved("127.0.0.1", LOCAL_SOCKS_PORT),
            ),
        )
        .build()

    fun execute(request: Request, preferLocalSocks: Boolean = false): Response {
        if (preferLocalSocks) {
            runCatching { return localSocksClient.newCall(request).execute() }
        }
        return directClient.newCall(request).execute()
    }

    internal fun preferIPv4(addresses: List<InetAddress>): List<InetAddress> {
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        return if (ipv4.isNotEmpty()) ipv4 else addresses
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
