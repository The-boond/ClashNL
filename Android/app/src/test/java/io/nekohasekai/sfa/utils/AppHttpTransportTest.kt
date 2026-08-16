package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class AppHttpTransportTest {
    @Test
    fun `keeps only IPv4 when host has A and AAAA records`() {
        val ipv6 = InetAddress.getByName("2606:4700:3035::ac43:d175")
        val ipv4 = InetAddress.getByName("104.21.23.63")

        assertEquals(listOf(ipv4), AppHttpTransport.ipv4Only("example.com", listOf(ipv6, ipv4)))
    }

    @Test
    fun `rejects IPv6-only host`() {
        val ipv6 = InetAddress.getByName("2606:4700:3035::ac43:d175")

        assertThrows(UnknownHostException::class.java) {
            AppHttpTransport.ipv4Only("ipv6-only.example", listOf(ipv6))
        }
    }
}
