package io.nekohasekai.sfa.compose.model

import io.nekohasekai.sfa.mihomo.MihomoConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class MihomoConnectionMappingTest {
    @Test
    fun mapsMihomoMetadataAndCumulativeTraffic() {
        val connection = Connection.from(
            MihomoConnection(
                id = "connection-id",
                host = "example.com",
                network = "tcp",
                chains = listOf("proxy-node", "selector"),
                upload = 1_024,
                download = 2_048,
                type = "HTTP",
                sourceIp = "10.0.0.2",
                destinationIp = "203.0.113.8",
                sourcePort = "54321",
                destinationPort = "443",
                inboundName = "TUN",
                inboundUser = "user",
                process = "com.example.app",
                processPath = "/data/app/com.example.app/base.apk",
                start = "2026-08-23T10:15:30Z",
                rule = "DOMAIN-SUFFIX",
                rulePayload = "example.com",
            ),
        )

        assertEquals("TUN", connection.inbound)
        assertEquals("10.0.0.2:54321", connection.source)
        assertEquals("example.com:443", connection.destination)
        assertEquals("proxy-node", connection.outbound)
        assertEquals(listOf("proxy-node", "selector"), connection.chain)
        assertEquals("DOMAIN-SUFFIX example.com", connection.rule)
        assertEquals(Instant.parse("2026-08-23T10:15:30Z").toEpochMilli(), connection.createdAt)
        assertEquals(0L, connection.upload)
        assertEquals(0L, connection.download)
        assertEquals(1_024L, connection.uploadTotal)
        assertEquals(2_048L, connection.downloadTotal)
        assertEquals("/data/app/com.example.app/base.apk", connection.processInfo?.processPath)
        assertNull(connection.closedAt)
    }

    @Test
    fun bracketsIpv6DestinationAndUsesStableDefaults() {
        val connection = Connection.from(
            MihomoConnection(
                id = "ipv6-id",
                host = "",
                network = "udp",
                chains = emptyList(),
                upload = 0,
                download = 0,
                destinationIp = "2001:db8::1",
                destinationPort = "53",
            ),
        )

        assertEquals(6, connection.ipVersion)
        assertEquals("[2001:db8::1]:53", connection.destination)
        assertEquals("mihomo", connection.inbound)
        assertEquals("", connection.outbound)
        assertNull(connection.processInfo)
        assertFalse(connection.performSearch("outbound:missing"))
    }
}
