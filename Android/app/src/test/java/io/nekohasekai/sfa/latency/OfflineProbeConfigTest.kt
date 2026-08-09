package io.nekohasekai.sfa.latency

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineProbeConfigTest {
    @Test
    fun temporaryConfigRemovesTunRoutesAndSystemProxyControls() {
        val content = """
            {
              "dns": {"servers": [{"tag": "local", "address": "local"}]},
              "inbounds": [{"type": "tun", "auto_route": true, "strict_route": true}],
              "outbounds": [{"type": "direct", "tag": "NODE"}],
              "route": {"auto_detect_interface": true, "auto_route": true},
              "system_proxy": {"enabled": true},
              "experimental": {"cache_file": {"enabled": true}}
            }
        """.trimIndent()

        val root = Json.parseToJsonElement(
            OfflineProbeConfig.stripVpnIntegration(content),
        ).jsonObject

        assertFalse(root.containsKey("inbounds"))
        assertFalse(root.containsKey("system_proxy"))
        assertTrue(root.containsKey("dns"))
        assertTrue(root.containsKey("outbounds"))
        assertEquals(true, root["route"]?.jsonObject?.get("auto_detect_interface")?.jsonPrimitive?.content?.toBoolean())
        assertFalse(root["route"]?.jsonObject?.containsKey("auto_route") == true)
    }
}
