package io.nekohasekai.sfa.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileNodeSelectionTest {
    private val source =
        """
        {
          "log": {"level": "info"},
          "outbounds": [
            {"type": "vless", "tag": "node-a"},
            {"type": "hysteria2", "tag": "node-b"},
            {"type": "selector", "tag": "Proxy", "outbounds": ["node-a", "node-b"]}
          ]
        }
        """.trimIndent()

    @Test
    fun readsNodesBeforeAnExplicitSelectionExists() {
        val group = ProfileNodeSelection.read(source).single()

        assertEquals("Proxy", group.tag)
        assertEquals("node-a", group.selected)
        assertFalse(group.hasExplicitSelection)
        assertEquals(listOf("node-a", "node-b"), group.items.map { it.tag })
        assertEquals(listOf("vless", "hysteria2"), group.items.map { it.type })
    }

    @Test
    fun selectedNodeBecomesTheSelectorDefault() {
        val updated = ProfileNodeSelection.select(source, "Proxy", "node-b")
        val group = ProfileNodeSelection.read(updated).single()
        val root = Json.parseToJsonElement(updated).jsonObject

        assertEquals("node-b", group.selected)
        assertTrue(group.hasExplicitSelection)
        assertEquals("info", root.getValue("log").jsonObject.getValue("level").jsonPrimitive.content)
    }

    @Test
    fun subscriptionRefreshKeepsAStillAvailableSelection() {
        val previous = ProfileNodeSelection.select(source, "Proxy", "node-b")
        val refreshed =
            source.replace(
                """{"type": "vless", "tag": "node-a"}""",
                """{"type": "vless", "tag": "node-a", "server_port": 443}""",
            )

        val merged = ProfileNodeSelection.preserveSelections(previous, refreshed)

        assertEquals("node-b", ProfileNodeSelection.read(merged).single().selected)
    }

    @Test
    fun subscriptionRefreshDropsASelectionThatNoLongerExists() {
        val previous = ProfileNodeSelection.select(source, "Proxy", "node-b")
        val refreshed =
            """
            {
              "outbounds": [
                {"type": "vless", "tag": "node-a"},
                {"type": "selector", "tag": "Proxy", "outbounds": ["node-a"]}
              ]
            }
            """.trimIndent()

        val merged = ProfileNodeSelection.preserveSelections(previous, refreshed)
        val group = ProfileNodeSelection.read(merged).single()

        assertEquals("node-a", group.selected)
        assertFalse(group.hasExplicitSelection)
    }
}
