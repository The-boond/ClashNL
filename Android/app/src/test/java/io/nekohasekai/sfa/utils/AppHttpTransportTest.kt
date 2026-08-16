package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class AppHttpTransportTest {
    @Test
    fun `prefers IPv4 when host has A and AAAA records`() {
        val ipv6 = InetAddress.getByName("2606:4700:3035::ac43:d175")
        val ipv4 = InetAddress.getByName("104.21.23.63")

        assertEquals(listOf(ipv4), AppHttpTransport.preferIPv4(listOf(ipv6, ipv4)))
    }

    @Test
    fun `keeps IPv6 for IPv6-only host`() {
        val ipv6 = InetAddress.getByName("2606:4700:3035::ac43:d175")

        assertEquals(listOf(ipv6), AppHttpTransport.preferIPv4(listOf(ipv6)))
    }
}
