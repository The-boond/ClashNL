package io.nekohasekai.sfa.utils

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * HTTP transport used by account and subscription traffic.
 *
 * Some mobile networks advertise a working IPv6 route but cannot complete
 * Cloudflare HTTPS requests over it. Account and subscription requests are
 * therefore resolved and connected over IPv4 only. IPv6-only destinations
 * fail explicitly instead of silently falling back to IPv6.
 */
object AppHttpTransport {
    private val ipv4OnlyDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = ipv4Only(hostname, Dns.SYSTEM.lookup(hostname))
    }

    private val directClient = baseBuilder()
        .dns(ipv4OnlyDns)
        .build()

    fun execute(request: Request): Response = directClient.newCall(request).execute()

    internal fun ipv4Only(hostname: String, addresses: List<InetAddress>): List<InetAddress> {
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        if (ipv4.isEmpty()) {
            throw UnknownHostException("No IPv4 address for $hostname")
        }
        return ipv4
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
