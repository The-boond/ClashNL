package io.nekohasekai.sfa.utils

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
}
