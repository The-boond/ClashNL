package io.nekohasekai.sfa.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Keeps subscription node endpoint resolution on IPv4.
 *
 * The account/subscription download transport has its own IPv4-only policy.
 * This policy applies to the imported sing-box runtime profile, so proxy server
 * hostnames do not reconnect through an AAAA result on unstable dual-stack
 * mobile networks.
 */
object IPv4OnlyProfilePolicy {
    private const val IPV4_ONLY = "ipv4_only"

    private val json =
        Json {
            explicitNulls = false
            prettyPrint = false
        }

    fun enforce(source: String): String {
        val root = json.parseToJsonElement(source).jsonObject.toMutableMap()

        val dns = (root["dns"] as? JsonObject)?.toMutableMap()
        if (dns != null) {
            dns["strategy"] = JsonPrimitive(IPV4_ONLY)
            root["dns"] = JsonObject(dns)
        }

        val route = (root["route"] as? JsonObject)?.toMutableMap()
        if (route != null) {
            route["default_domain_resolver"]?.let { resolver ->
                route["default_domain_resolver"] = ipv4OnlyResolver(resolver)
            }
            root["route"] = JsonObject(route)
        }

        rewriteDialers(root["outbounds"])?.let { root["outbounds"] = it }
        rewriteDialers(root["endpoints"])?.let { root["endpoints"] = it }

        return json.encodeToString(JsonElement.serializer(), JsonObject(root))
    }

    private fun rewriteDialers(element: JsonElement?): JsonElement? {
        val items = element as? JsonArray ?: return element
        return JsonArray(
            items.map { item ->
                val dialer = item as? JsonObject ?: return@map item
                val resolver = dialer["domain_resolver"] ?: return@map item
                JsonObject(
                    dialer.toMutableMap().apply {
                        put("domain_resolver", ipv4OnlyResolver(resolver))
                    },
                )
            },
        )
    }

    private fun ipv4OnlyResolver(resolver: JsonElement): JsonElement =
        when (resolver) {
            is JsonPrimitive ->
                JsonObject(
                    linkedMapOf(
                        "server" to resolver,
                        "strategy" to JsonPrimitive(IPV4_ONLY),
                    ),
                )

            is JsonObject ->
                JsonObject(
                    resolver.toMutableMap().apply {
                        put("strategy", JsonPrimitive(IPV4_ONLY))
                    },
                )

            else -> resolver
        }
}
