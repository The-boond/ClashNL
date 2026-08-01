package io.nekohasekai.sfa.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigMigratorTest {
    private val json = Json

    @Test
    fun migratesLegacyDnsServersAndInboundFields() {
        val source =
            """
            {
              "dns": {
                "servers": [
                  {"tag":"remote","address":"https://dns.example/dns-query","detour":"direct"},
                  {"tag":"blocked","address":"rcode://refused"}
                ],
                "rules": [
                  {"domain":["blocked.example"],"server":"blocked"},
                  {"outbound":["any"],"server":"remote"}
                ]
              },
              "inbounds": [
                {
                  "type":"tun",
                  "sniff":true,
                  "sniff_timeout":"1s",
                  "domain_strategy":"prefer_ipv4",
                  "endpoint_independent_nat":true,
                  "inet4_address":"172.19.0.1/30"
                }
              ],
              "outbounds": [
                {"type":"direct","tag":"direct"}
              ]
            }
            """.trimIndent()

        val result = SingBoxConfigMigrator.migrate(source)!!
        val root = json.parseToJsonElement(result.content).jsonObject
        val dns = root.getValue("dns").jsonObject
        val server = dns.getValue("servers").jsonArray.first().jsonObject
        val rcodeRule = dns.getValue("rules").jsonArray.first().jsonObject
        val route = root.getValue("route").jsonObject
        val inbound = root.getValue("inbounds").jsonArray.first().jsonObject

        assertTrue(result.changed)
        assertTrue(result.migrations.contains(SingBoxConfigMigrator.Migration.LEGACY_DNS_SERVERS))
        assertEquals("https", server.getValue("type").jsonPrimitive.content)
        assertEquals("dns.example", server.getValue("server").jsonPrimitive.content)
        assertFalse(server.containsKey("detour"))
        assertEquals("predefined", rcodeRule.getValue("action").jsonPrimitive.content)
        assertEquals("REFUSED", rcodeRule.getValue("rcode").jsonPrimitive.content)
        assertEquals("remote", route.getValue("default_domain_resolver").jsonPrimitive.content)
        assertEquals("172.19.0.1/30", inbound.getValue("address").jsonArray.first().jsonPrimitive.content)
        assertFalse(inbound.containsKey("sniff"))
        assertFalse(inbound.containsKey("domain_strategy"))
        assertFalse(inbound.containsKey("endpoint_independent_nat"))

        val routeRules = route.getValue("rules").jsonArray
        assertEquals("resolve", routeRules.first().jsonObject.getValue("action").jsonPrimitive.content)
        assertEquals("sniff", routeRules[1].jsonObject.getValue("action").jsonPrimitive.content)
    }

    @Test
    fun keepsModernJsonUntouched() {
        val source =
            """
            {
              "dns": {
                "servers": [{"type":"local","tag":"local"}]
              },
              "inbounds": [{"type":"tun","tag":"tun","address":["172.19.0.1/30"]}]
            }
            """.trimIndent()

        val result = SingBoxConfigMigrator.migrate(source)!!

        assertFalse(result.changed)
        assertTrue(result.migrations.isEmpty())
    }

    @Test
    fun supportsBareAndIpv6DnsAddresses() {
        val source =
            """
            {
              "dns": {
                "servers": [
                  {"address":"1.1.1.1:5353"},
                  {"address":"[2001:db8::1]"}
                ]
              }
            }
            """.trimIndent()

        val root =
            json.parseToJsonElement(
                SingBoxConfigMigrator.migrate(source)!!.content,
            ).jsonObject
        val servers = root.getValue("dns").jsonObject.getValue("servers").jsonArray

        assertEquals("udp", servers[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(5353, servers[0].jsonObject.getValue("server_port").jsonPrimitive.int)
        assertEquals("2001:db8::1", servers[1].jsonObject.getValue("server").jsonPrimitive.content)
    }

    @Test
    fun migratesLegacyDnsOutboundToRouteAction() {
        val source =
            """
            {
              "outbounds": [
                {"type":"vless","tag":"proxy","server":"example.com","server_port":443,"uuid":"11111111-1111-1111-1111-111111111111"},
                {"type":"dns","tag":"dns-out"},
                {"type":"direct","tag":"direct"}
              ],
              "route": {
                "rules": [
                  {"protocol":"dns","outbound":"dns-out"},
                  {
                    "type":"logical",
                    "mode":"or",
                    "rules":[{"protocol":"dns","outbound":"dns-out"}],
                    "outbound":"proxy"
                  }
                ],
                "final":"proxy"
              }
            }
            """.trimIndent()

        val result = SingBoxConfigMigrator.migrate(source)!!
        val root = json.parseToJsonElement(result.content).jsonObject
        val outbounds = root.getValue("outbounds").jsonArray
        val rules = root.getValue("route").jsonObject.getValue("rules").jsonArray
        val dnsRule = rules.first().jsonObject
        val nestedDnsRule =
            rules[1]
                .jsonObject
                .getValue("rules")
                .jsonArray
                .first()
                .jsonObject

        assertTrue(result.changed)
        assertTrue(result.migrations.contains(SingBoxConfigMigrator.Migration.LEGACY_DNS_OUTBOUND))
        assertFalse(
            outbounds.any {
                it.jsonObject["type"]?.jsonPrimitive?.content == "dns"
            },
        )
        assertEquals("hijack-dns", dnsRule.getValue("action").jsonPrimitive.content)
        assertFalse(dnsRule.containsKey("outbound"))
        assertEquals("hijack-dns", nestedDnsRule.getValue("action").jsonPrimitive.content)
        assertEquals("proxy", rules[1].jsonObject.getValue("outbound").jsonPrimitive.content)
    }

    @Test
    fun migratesRuntimeOnlyDnsAndRuleSetCompatibilityFields() {
        val source =
            """
            {
              "dns": {
                "servers": [
                  {"type":"https","tag":"local","server":"dns.example","detour":"direct"},
                  {"type":"https","tag":"remote","server":"dns.example","detour":"proxy"},
                  {"type":"https","tag":"bound","server":"dns.example","detour":"bound-direct"}
                ]
              },
              "outbounds": [
                {"type":"direct","tag":"direct"},
                {"type":"direct","tag":"bound-direct","bind_interface":"wlan0"},
                {"type":"vless","tag":"proxy","server":"example.com","server_port":443,"uuid":"11111111-1111-1111-1111-111111111111"}
              ],
              "route": {
                "rule_set": [
                  {
                    "type":"remote",
                    "tag":"remote-rules",
                    "format":"binary",
                    "url":"https://rules.example/rules.srs",
                    "download_detour":"proxy"
                  },
                  {
                    "type":"remote",
                    "tag":"custom-client-rules",
                    "format":"binary",
                    "url":"https://rules.example/custom.srs",
                    "download_detour":"proxy",
                    "http_client":"custom-client"
                  }
                ]
              }
            }
            """.trimIndent()

        val result = SingBoxConfigMigrator.migrate(source)!!
        val root = json.parseToJsonElement(result.content).jsonObject
        val servers = root.getValue("dns").jsonObject.getValue("servers").jsonArray
        val ruleSets = root.getValue("route").jsonObject.getValue("rule_set").jsonArray
        val migratedRuleSet = ruleSets.first().jsonObject
        val customClientRuleSet = ruleSets[1].jsonObject

        assertTrue(result.migrations.contains(SingBoxConfigMigrator.Migration.EMPTY_DIRECT_DNS_DETOUR))
        assertTrue(
            result.migrations.contains(
                SingBoxConfigMigrator.Migration.LEGACY_RULE_SET_DOWNLOAD_DETOUR,
            ),
        )
        assertFalse(servers[0].jsonObject.containsKey("detour"))
        assertEquals("proxy", servers[1].jsonObject.getValue("detour").jsonPrimitive.content)
        assertEquals("bound-direct", servers[2].jsonObject.getValue("detour").jsonPrimitive.content)
        assertFalse(migratedRuleSet.containsKey("download_detour"))
        assertEquals(
            "proxy",
            migratedRuleSet
                .getValue("http_client")
                .jsonObject
                .getValue("detour")
                .jsonPrimitive
                .content,
        )
        assertFalse(customClientRuleSet.containsKey("download_detour"))
        assertEquals(
            "custom-client",
            customClientRuleSet.getValue("http_client").jsonPrimitive.content,
        )
    }
}
