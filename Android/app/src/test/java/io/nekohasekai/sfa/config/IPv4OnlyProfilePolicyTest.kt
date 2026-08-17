package io.nekohasekai.sfa.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class IPv4OnlyProfilePolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `forces the default node resolver to IPv4 only`() {
        val output =
            IPv4OnlyProfilePolicy.enforce(
                """
                {
                  "dns":{"servers":[{"type":"local","tag":"local"}],"strategy":"prefer_ipv6"},
                  "outbounds":[{"type":"vless","tag":"node","server":"node.example","server_port":443}],
                  "route":{"default_domain_resolver":"local","final":"node"}
                }
                """.trimIndent(),
            )

        val root = json.parseToJsonElement(output).jsonObject
        assertEquals("ipv4_only", root.getValue("dns").jsonObject.getValue("strategy").jsonPrimitive.content)
        assertEquals(
            "ipv4_only",
            root.getValue("route").jsonObject
                .getValue("default_domain_resolver").jsonObject
                .getValue("strategy").jsonPrimitive.content,
        )
    }

    @Test
    fun `overrides a node specific resolver strategy`() {
        val output =
            IPv4OnlyProfilePolicy.enforce(
                """
                {
                  "dns":{"servers":[{"type":"local","tag":"local"}]},
                  "outbounds":[{
                    "type":"vless",
                    "tag":"node",
                    "server":"node.example",
                    "server_port":443,
                    "domain_resolver":{"server":"local","strategy":"prefer_ipv6"}
                  }],
                  "route":{"final":"node"}
                }
                """.trimIndent(),
            )

        val root = json.parseToJsonElement(output).jsonObject
        val outbound = root.getValue("outbounds").toString()
        org.junit.Assert.assertTrue(outbound.contains("\"strategy\":\"ipv4_only\""))
        org.junit.Assert.assertFalse(outbound.contains("prefer_ipv6"))
    }
}
