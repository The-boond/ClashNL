package io.nekohasekai.sfa.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads and persists selector defaults directly in a normalized sing-box
 * profile. This keeps a node choice available before the VPN service starts.
 */
object ProfileNodeSelection {
    data class SelectorItem(
        val tag: String,
        val type: String,
    )

    data class SelectorGroup(
        val tag: String,
        val selected: String,
        val hasExplicitSelection: Boolean,
        val items: List<SelectorItem>,
    )

    private val json =
        Json {
            explicitNulls = false
            prettyPrint = false
        }

    fun read(content: String): List<SelectorGroup> {
        val root = parseRoot(content)
        val outbounds = root["outbounds"]?.jsonArray ?: return emptyList()
        val typesByTag =
            outbounds
                .mapNotNull { element ->
                    val outbound = element as? JsonObject ?: return@mapNotNull null
                    val tag = outbound.string("tag") ?: return@mapNotNull null
                    tag to outbound.string("type").orEmpty()
                }.toMap()

        return outbounds.mapNotNull { element ->
            val outbound = element as? JsonObject ?: return@mapNotNull null
            if (outbound.string("type") != "selector") return@mapNotNull null

            val tag = outbound.string("tag") ?: return@mapNotNull null
            val members =
                (outbound["outbounds"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            if (members.isEmpty()) return@mapNotNull null

            val configuredDefault = outbound.string("default")?.takeIf { it in members }
            SelectorGroup(
                tag = tag,
                selected = configuredDefault ?: members.first(),
                hasExplicitSelection = configuredDefault != null,
                items =
                members.map { member ->
                    SelectorItem(
                        tag = member,
                        type = typesByTag[member].orEmpty(),
                    )
                },
            )
        }
    }

    fun select(
        content: String,
        groupTag: String,
        itemTag: String,
    ): String {
        val root = parseRoot(content)
        var updated = false
        val outbounds =
            root["outbounds"]?.jsonArray?.map { element ->
                val outbound = element as? JsonObject ?: return@map element
                if (outbound.string("type") != "selector" || outbound.string("tag") != groupTag) {
                    return@map element
                }

                val members =
                    (outbound["outbounds"] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        .orEmpty()
                require(itemTag in members) {
                    "Node is not part of selector"
                }
                updated = true
                JsonObject(outbound.toMutableMap().apply { put("default", JsonPrimitive(itemTag)) })
            } ?: error("Profile has no outbounds")

        require(updated) {
            "Selector group was not found"
        }
        return encode(JsonObject(root.toMutableMap().apply { put("outbounds", JsonArray(outbounds)) }))
    }

    fun preserveSelections(
        previousContent: String,
        updatedContent: String,
    ): String {
        val previousSelections =
            read(previousContent)
                .filter { it.hasExplicitSelection }
                .associate { it.tag to it.selected }
        if (previousSelections.isEmpty()) return updatedContent

        val root = parseRoot(updatedContent)
        var changed = false
        val outbounds =
            root["outbounds"]?.jsonArray?.map { element ->
                val outbound = element as? JsonObject ?: return@map element
                if (outbound.string("type") != "selector") return@map element

                val groupTag = outbound.string("tag") ?: return@map element
                val selected = previousSelections[groupTag] ?: return@map element
                val members =
                    (outbound["outbounds"] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        .orEmpty()
                if (selected !in members || outbound.string("default") == selected) {
                    return@map element
                }

                changed = true
                JsonObject(outbound.toMutableMap().apply { put("default", JsonPrimitive(selected)) })
            } ?: return updatedContent

        if (!changed) return updatedContent
        return encode(JsonObject(root.toMutableMap().apply { put("outbounds", JsonArray(outbounds)) }))
    }

    private fun parseRoot(content: String): JsonObject = json.parseToJsonElement(content.trim()).jsonObject

    private fun encode(root: JsonObject): String = json.encodeToString(JsonElement.serializer(), root)

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
}
