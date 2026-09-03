package io.nekohasekai.sfa.utils

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class AppHttpTransportTest {
    @Test
    fun `puts IPv4 first when host has A and AAAA records`() {
        val ipv6 = InetAddress.getByName("2606:4700:3035::ac43:d175")
        val ipv4 = InetAddress.getByName("104.21.23.63")

        assertEquals(listOf(ipv4, ipv6), AppHttpTransport.preferIPv4("example.com", listOf(ipv6, ipv4)))
    }

    @Test
    fun `keeps NAT64 or IPv6 result when no IPv4 result exists`() {
        val ipv6 = InetAddress.getByName("2606:4700:3035::ac43:d175")

        assertEquals(listOf(ipv6), AppHttpTransport.preferIPv4("ipv6-only.example", listOf(ipv6)))
    }

    @Test
    fun `rejects an empty DNS result`() {
        assertThrows(UnknownHostException::class.java) {
            AppHttpTransport.preferIPv4("missing.example", emptyList())
        }
    }

    @Test
    fun `local proxy route requires an explicit loopback proxy port`() {
        val request = Request.Builder().url("https://example.com").build()

        assertThrows(IllegalArgumentException::class.java) {
            AppHttpTransport.execute(
                request = request,
                networkRoute = AppHttpTransport.NetworkRoute.LocalProxy,
            )
        }
    }

    @Test
    fun `underlying route rejects a local proxy port`() {
        val request = Request.Builder().url("https://example.com").build()

        assertThrows(IllegalArgumentException::class.java) {
            AppHttpTransport.execute(
                request = request,
                networkRoute = AppHttpTransport.NetworkRoute.Underlying,
                localHttpProxyPort = 7890,
            )
        }
    }

    @Test
    fun `underlying route fails closed without a selected physical network`() {
        val request = Request.Builder().url("https://example.com").build()

        assertThrows(IllegalStateException::class.java) {
            AppHttpTransport.execute(request)
        }
    }
}
