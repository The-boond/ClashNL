package io.nekohasekai.sfa.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * Converts configuration fields that were accepted by sing-box 1.13 and older
 * to the explicit 1.14 format.
 *
 * Subscription providers often keep one profile template for several client
 * versions. Keeping this conversion at the import boundary lets the stored
 * profile use the same libbox version as the app, without modifying the
 * provider's URL or exposing credentials.
 */
object SingBoxConfigMigrator {
    enum class Migration {
        LEGACY_DNS_SERVERS,
        LEGACY_DNS_RULES,
        LEGACY_DNS_OUTBOUND,
        EMPTY_DIRECT_DNS_DETOUR,
        LEGACY_RULE_SET_DOWNLOAD_DETOUR,
        LEGACY_INBOUND_FIELDS,
        LEGACY_TUN_FIELDS,
    }

    data class Result(
        val content: String,
        val migrations: Set<Migration>,
    ) {
        val changed: Boolean
            get() = migrations.isNotEmpty()
    }

    private val json =
        Json {
            explicitNulls = false
            prettyPrint = false
        }

    private val legacyServerKeys =
        setOf(
            "address",
            "address_resolver",
            "address_strategy",
            "address_fallback_delay",
            "strategy",
            "client_subnet",
        )

    private val resolverKeys =
        setOf(
            "server",
            "timeout",
            "strategy",
            "disable_cache",
            "disable_optimistic_cache",
            "rewrite_ttl",
            "client_subnet",
        )

    /**
     * Returns null when [source] is not a JSON object. A JSON object is
     * returned even when no legacy fields are present, which allows callers
     * to avoid incorrectly treating a native JSON error as a YAML error.
     */
    fun migrate(source: String): Result? {
        val root =
            runCatching {
                json.parseToJsonElement(source.trim()).jsonObject
            }.getOrNull() ?: return null

        val migrations = linkedSetOf<Migration>()
        val rootMap = root.toMutableMap()
        val dnsElement = rootMap["dns"]?.takeIf { it is JsonObject }
        if (dnsElement != null) {
            val dns = dnsElement.jsonObject.toMutableMap()
            migrateDns(dns, rootMap, migrations)
            rootMap["dns"] = JsonObject(dns)
        }

        migrateEmptyDirectDnsDetour(rootMap, migrations)
        migrateLegacyDnsOutbound(rootMap, migrations)
        migrateLegacyRuleSetDownloadDetour(rootMap, migrations)
        migrateInbounds(rootMap, migrations)

        val migratedRoot = JsonObject(rootMap)
        return Result(
            content = json.encodeToString(JsonElement.serializer(), migratedRoot),
            migrations = migrations,
        )
    }

    private fun migrateDns(
        dns: MutableMap<String, JsonElement>,
        root: MutableMap<String, JsonElement>,
        migrations: MutableSet<Migration>,
    ) {
        val fakeIp = dns.remove("fakeip")?.jsonObjectOrNull()
        val rcodeByTag = linkedMapOf<String, String>()
        val strategyByTag = linkedMapOf<String, JsonElement>()
        val clientSubnetByTag = linkedMapOf<String, JsonElement>()

        val servers = dns["servers"]?.jsonArrayOrNull()
        if (servers != null) {
            val migratedServers = buildList {
                servers.forEachIndexed { index, element ->
                    val server = element.jsonObjectOrNull()
                    val address = server?.get("address")?.jsonPrimitive?.contentOrNull
                    if (server == null || address == null) {
                        add(element)
                        return@forEachIndexed
                    }

                    migrations += Migration.LEGACY_DNS_SERVERS
                    val tag = server["tag"]?.jsonPrimitive?.contentOrNull
                    val parsed = parseLegacyAddress(address)
                    if (parsed.type == "rcode") {
                        val rcodeTag = tag ?: "__clashnl_rcode_$index"
                        rcodeByTag[rcodeTag] = (parsed.host ?: "SERVFAIL").uppercase()
                        return@forEachIndexed
                    }

                    val migrated = linkedMapOf<String, JsonElement>()
                    migrated["type"] = JsonPrimitive(parsed.type)
                    server.forEach { (key, value) ->
                        if (key !in legacyServerKeys) {
                            migrated[key] = value
                        }
                    }

                    when (parsed.type) {
                        "udp", "tcp", "tls", "quic", "https", "h3" -> {
                            parsed.host?.takeIf { it.isNotBlank() }?.let {
                                migrated["server"] = JsonPrimitive(it)
                            }
                            parsed.port?.let {
                                migrated["server_port"] = JsonPrimitive(it)
                            }
                            parsed.path?.takeIf { it.isNotBlank() && it != "/dns-query" }?.let {
                                migrated["path"] = JsonPrimitive(it)
                            }
                        }

                        "dhcp" -> {
                            parsed.host
                                ?.takeIf { it.isNotBlank() && it != "auto" }
                                ?.let { migrated["interface"] = JsonPrimitive(it) }
                        }

                        "fakeip" -> {
                            fakeIp?.get("inet4_range")?.let { migrated["inet4_range"] = it }
                            fakeIp?.get("inet6_range")?.let { migrated["inet6_range"] = it }
                        }
                    }

                    server["address_resolver"]?.jsonPrimitive?.contentOrNull?.let { resolver ->
                        val resolverObject = linkedMapOf<String, JsonElement>()
                        resolverObject["server"] = JsonPrimitive(resolver)
                        server["address_strategy"]?.let {
                            resolverObject["strategy"] = it
                        }
                        migrated["domain_resolver"] =
                            if (resolverObject.size == 1) {
                                JsonPrimitive(resolver)
                            } else {
                                JsonObject(resolverObject)
                            }
                    }
                    server["address_fallback_delay"]?.let {
                        migrated["fallback_delay"] = it
                    }

                    val strategyKey = tag ?: "__clashnl_default__"
                    server["strategy"]?.let { strategyByTag[strategyKey] = it }
                    server["client_subnet"]?.let { clientSubnetByTag[strategyKey] = it }
                    add(JsonObject(migrated))
                }
            }
            dns["servers"] = JsonArray(migratedServers)
        }

        val oldRules = dns["rules"]?.jsonArrayOrNull()
        if (oldRules != null) {
            val migratedRules = mutableListOf<JsonElement>()
            oldRules.forEach { element ->
                val rule = element.jsonObjectOrNull()
                if (rule == null) {
                    migratedRules += element
                    return@forEach
                }

                val server = rule["server"]?.jsonPrimitive?.contentOrNull
                if (server != null && rcodeByTag.containsKey(server)) {
                    migrations += Migration.LEGACY_DNS_SERVERS
                    val rewritten = rule.toMutableMap()
                    rewritten.remove("server")
                    rewritten["action"] = JsonPrimitive("predefined")
                    rewritten["rcode"] = JsonPrimitive(rcodeByTag.getValue(server))
                    migratedRules += JsonObject(rewritten)
                    return@forEach
                }

                val outbound = rule["outbound"]
                if (outbound != null && server != null && isSimpleOutboundRule(rule)) {
                    val resolver =
                        buildResolver(
                            rule = rule,
                            server = server,
                            strategyByTag = strategyByTag,
                            clientSubnetByTag = clientSubnetByTag,
                        )
                    val outboundTags =
                        outbound.jsonPrimitiveOrNull()?.contentOrNull?.let { listOf(it) }
                            ?: outbound.jsonArrayOrNull()?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
                    if (outboundTags?.any { it == "any" } == true) {
                        val route = (root["route"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
                        if (route["default_domain_resolver"] == null) {
                            route["default_domain_resolver"] = resolver
                        }
                        root["route"] = JsonObject(route)
                    } else if (!outboundTags.isNullOrEmpty()) {
                        val outbounds = root["outbounds"]?.jsonArrayOrNull()
                        if (outbounds != null) {
                            root["outbounds"] =
                                JsonArray(
                                    outbounds.map { outboundElement ->
                                        val outboundObject = outboundElement.jsonObjectOrNull()
                                        if (
                                            outboundObject != null &&
                                            outboundTags.contains(
                                                outboundObject["tag"]?.jsonPrimitive?.contentOrNull,
                                            )
                                        ) {
                                            JsonObject(
                                                outboundObject.toMutableMap().apply {
                                                    put("domain_resolver", resolver)
                                                },
                                            )
                                        } else {
                                            outboundElement
                                        }
                                    },
                                )
                        }
                    }
                    migrations += Migration.LEGACY_DNS_RULES
                    return@forEach
                }

                val rewritten = rule.toMutableMap()
                val strategyKey = server ?: "__clashnl_default__"
                strategyByTag[strategyKey]?.let { strategy ->
                    if (rewritten["strategy"] == null) {
                        rewritten["strategy"] = strategy
                    }
                }
                clientSubnetByTag[strategyKey]?.let { subnet ->
                    if (rewritten["client_subnet"] == null) {
                        rewritten["client_subnet"] = subnet
                    }
                }
                if (rewritten != rule) {
                    migrations += Migration.LEGACY_DNS_RULES
                }
                migratedRules += JsonObject(rewritten)
            }
            dns["rules"] = JsonArray(migratedRules)
        }

        strategyByTag["__clashnl_default__"]?.let {
            dns["strategy"] = it
            migrations += Migration.LEGACY_DNS_RULES
        }

        // The old fakeip object is consumed into the fakeip server above.
        if (fakeIp != null) {
            migrations += Migration.LEGACY_DNS_SERVERS
        }
    }

    private fun migrateInbounds(
        root: MutableMap<String, JsonElement>,
        migrations: MutableSet<Migration>,
    ) {
        val inbounds = root["inbounds"]?.jsonArrayOrNull() ?: return
        val route = (root["route"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
        val legacyRules = mutableListOf<JsonElement>()
        val migratedInbounds = mutableListOf<JsonElement>()
        val existingTags =
            inbounds.mapNotNull { it.jsonObjectOrNull()?.get("tag")?.jsonPrimitive?.contentOrNull }
                .toMutableSet()

        inbounds.forEachIndexed { index, element ->
            val inbound = element.jsonObjectOrNull()?.toMutableMap()
            if (inbound == null) {
                migratedInbounds += element
                return@forEachIndexed
            }

            val hasLegacy =
                listOf(
                    "sniff",
                    "sniff_timeout",
                    "sniff_override_destination",
                    "domain_strategy",
                    "udp_disable_domain_unmapping",
                ).any(inbound::containsKey)
            val tunLegacy =
                inbound.containsKey("endpoint_independent_nat") ||
                    inbound.containsKey("gso") ||
                    listOf(
                        "inet4_address",
                        "inet6_address",
                        "inet4_route_address",
                        "inet6_route_address",
                        "inet4_route_exclude_address",
                        "inet6_route_exclude_address",
                    ).any(inbound::containsKey)

            if (hasLegacy) {
                var tag = inbound["tag"]?.jsonPrimitive?.contentOrNull
                if (tag.isNullOrBlank()) {
                    var generatedTag = "clashnl-in-$index"
                    while (!existingTags.add(generatedTag)) {
                        generatedTag = "$generatedTag-$index"
                    }
                    tag = generatedTag
                    inbound["tag"] = JsonPrimitive(generatedTag)
                }
                val inboundTag = tag

                inbound.remove("domain_strategy")?.let { strategy ->
                    legacyRules +=
                        buildJsonObject {
                            put("inbound", JsonPrimitive(inboundTag))
                            put("action", JsonPrimitive("resolve"))
                            put("strategy", strategy)
                        }
                }
                val sniffEnabled = inbound.remove("sniff")?.jsonPrimitive?.booleanOrNull == true
                val sniffTimeout = inbound.remove("sniff_timeout")
                inbound.remove("sniff_override_destination")
                if (sniffEnabled) {
                    legacyRules +=
                        buildJsonObject {
                            put("inbound", JsonPrimitive(inboundTag))
                            put("action", JsonPrimitive("sniff"))
                            sniffTimeout?.let { put("timeout", it) }
                        }
                }
                inbound.remove("udp_disable_domain_unmapping")?.let { enabled ->
                    if (enabled.jsonPrimitive.booleanOrNull == true) {
                        legacyRules +=
                            buildJsonObject {
                                put("inbound", JsonPrimitive(inboundTag))
                                put("action", JsonPrimitive("route-options"))
                                put("udp_disable_domain_unmapping", JsonPrimitive(true))
                            }
                    }
                }
                migrations += Migration.LEGACY_INBOUND_FIELDS
            }

            if (tunLegacy) {
                // endpoint_independent_nat is the default behavior in the new
                // UDP mapping enum; dropping the removed flag preserves it.
                inbound.remove("endpoint_independent_nat")
                inbound.remove("gso")
                mergeLegacyListField(inbound, "inet4_address", "address")
                mergeLegacyListField(inbound, "inet6_address", "address")
                mergeLegacyListField(inbound, "inet4_route_address", "route_address")
                mergeLegacyListField(inbound, "inet6_route_address", "route_address")
                mergeLegacyListField(inbound, "inet4_route_exclude_address", "route_exclude_address")
                mergeLegacyListField(inbound, "inet6_route_exclude_address", "route_exclude_address")
                migrations += Migration.LEGACY_TUN_FIELDS
            }

            migratedInbounds += JsonObject(inbound)
        }

        root["inbounds"] = JsonArray(migratedInbounds)
        if (legacyRules.isNotEmpty()) {
            val existingRouteRules = route["rules"]?.jsonArrayOrNull().orEmpty()
            route["rules"] = JsonArray(legacyRules + existingRouteRules)
            root["route"] = JsonObject(route)
        }
    }

    private fun migrateLegacyDnsOutbound(
        root: MutableMap<String, JsonElement>,
        migrations: MutableSet<Migration>,
    ) {
        val outbounds = root["outbounds"]?.jsonArrayOrNull() ?: return
        val dnsTags = linkedSetOf<String>()
        var removed = false
        val migratedOutbounds =
            outbounds.filterNot { element ->
                val outbound = element.jsonObjectOrNull()
                val isLegacyDns =
                    outbound
                        ?.get("type")
                        ?.jsonPrimitiveOrNull()
                        ?.contentOrNull == "dns"
                if (isLegacyDns) {
                    removed = true
                    outbound
                        .get("tag")
                        ?.jsonPrimitiveOrNull()
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let(dnsTags::add)
                }
                isLegacyDns
            }
        if (!removed) return

        root["outbounds"] = JsonArray(migratedOutbounds)
        val route = root["route"]?.jsonObjectOrNull()?.toMutableMap()
        val routeRules = route?.get("rules")?.jsonArrayOrNull()
        if (route != null && routeRules != null && dnsTags.isNotEmpty()) {
            route["rules"] =
                JsonArray(
                    routeRules.map { migrateLegacyDnsRouteRule(it, dnsTags) },
                )
            root["route"] = JsonObject(route)
        }
        migrations += Migration.LEGACY_DNS_OUTBOUND
    }

    private fun migrateLegacyDnsRouteRule(
        element: JsonElement,
        dnsTags: Set<String>,
    ): JsonElement {
        val rule = element.jsonObjectOrNull() ?: return element
        val migrated = rule.toMutableMap()
        migrated["rules"]?.jsonArrayOrNull()?.let { nestedRules ->
            migrated["rules"] =
                JsonArray(
                    nestedRules.map { migrateLegacyDnsRouteRule(it, dnsTags) },
                )
        }
        val outbound =
            migrated["outbound"]
                ?.jsonPrimitiveOrNull()
                ?.contentOrNull
        if (outbound in dnsTags) {
            migrated.remove("outbound")
            migrated["action"] = JsonPrimitive("hijack-dns")
        }
        return JsonObject(migrated)
    }

    private fun migrateEmptyDirectDnsDetour(
        root: MutableMap<String, JsonElement>,
        migrations: MutableSet<Migration>,
    ) {
        val emptyDirectTags =
            root["outbounds"]
                ?.jsonArrayOrNull()
                .orEmpty()
                .mapNotNull { element ->
                    val outbound = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val isEmptyDirect =
                        outbound["type"]
                            ?.jsonPrimitiveOrNull()
                            ?.contentOrNull == "direct" &&
                            outbound.keys.all { it == "type" || it == "tag" }
                    if (!isEmptyDirect) return@mapNotNull null
                    outbound["tag"]
                        ?.jsonPrimitiveOrNull()
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                }.toSet()
        if (emptyDirectTags.isEmpty()) return

        val dns = root["dns"]?.jsonObjectOrNull()?.toMutableMap() ?: return
        val servers = dns["servers"]?.jsonArrayOrNull() ?: return
        var changed = false
        val migratedServers =
            servers.map { element ->
                val server = element.jsonObjectOrNull() ?: return@map element
                val detour =
                    server["detour"]
                        ?.jsonPrimitiveOrNull()
                        ?.contentOrNull
                if (detour !in emptyDirectTags) return@map element

                changed = true
                JsonObject(server.toMutableMap().apply { remove("detour") })
            }
        if (!changed) return

        dns["servers"] = JsonArray(migratedServers)
        root["dns"] = JsonObject(dns)
        migrations += Migration.EMPTY_DIRECT_DNS_DETOUR
    }

    private fun migrateLegacyRuleSetDownloadDetour(
        root: MutableMap<String, JsonElement>,
        migrations: MutableSet<Migration>,
    ) {
        val route = root["route"]?.jsonObjectOrNull()?.toMutableMap() ?: return
        val ruleSets = route["rule_set"]?.jsonArrayOrNull() ?: return
        var changed = false
        val migratedRuleSets =
            ruleSets.map { element ->
                val ruleSet = element.jsonObjectOrNull() ?: return@map element
                if ("download_detour" !in ruleSet) return@map element

                val migrated = ruleSet.toMutableMap()
                val detour =
                    migrated
                        .remove("download_detour")
                        ?.jsonPrimitiveOrNull()
                        ?.contentOrNull
                if ("http_client" !in migrated && !detour.isNullOrBlank()) {
                    migrated["http_client"] =
                        buildJsonObject {
                            put("detour", JsonPrimitive(detour))
                        }
                }
                changed = true
                JsonObject(migrated)
            }
        if (!changed) return

        route["rule_set"] = JsonArray(migratedRuleSets)
        root["route"] = JsonObject(route)
        migrations += Migration.LEGACY_RULE_SET_DOWNLOAD_DETOUR
    }

    private fun mergeLegacyListField(
        target: MutableMap<String, JsonElement>,
        oldKey: String,
        newKey: String,
    ) {
        val old = target.remove(oldKey) ?: return
        val oldValues = old.jsonArrayOrNull()?.toList() ?: listOf(old)
        val current = target[newKey]
        val currentValues = current?.jsonArrayOrNull()?.toList() ?: current?.let { listOf(it) }.orEmpty()
        target[newKey] = JsonArray(currentValues + oldValues)
    }

    private fun buildResolver(
        rule: JsonObject,
        server: String,
        strategyByTag: Map<String, JsonElement>,
        clientSubnetByTag: Map<String, JsonElement>,
    ): JsonElement {
        val resolver = linkedMapOf<String, JsonElement>("server" to JsonPrimitive(server))
        resolverKeys
            .filter { it != "server" }
            .forEach { key ->
                rule[key]?.let { resolver[key] = it }
            }
        strategyByTag[server]?.let { resolver.putIfAbsent("strategy", it) }
        clientSubnetByTag[server]?.let { resolver.putIfAbsent("client_subnet", it) }
        return if (resolver.size == 1) JsonPrimitive(server) else JsonObject(resolver)
    }

    private fun isSimpleOutboundRule(rule: JsonObject): Boolean = rule.keys.all { it == "outbound" || it == "server" || it in resolverKeys }

    private data class LegacyAddress(
        val type: String,
        val host: String? = null,
        val port: Int? = null,
        val path: String? = null,
    )

    private fun parseLegacyAddress(address: String): LegacyAddress {
        if (address == "local") return LegacyAddress("local")
        if (address == "fakeip") return LegacyAddress("fakeip")

        val hasScheme = "://" in address
        val uri =
            if (hasScheme) {
                runCatching { URI(address) }.getOrNull()
            } else {
                null
            }
        val type = uri?.scheme?.lowercase() ?: if (hasScheme) "udp" else "udp"
        if (type == "rcode") {
            val value = uri?.host ?: uri?.rawAuthority ?: uri?.path?.trimStart('/')
            return LegacyAddress("rcode", host = value ?: "SERVFAIL")
        }
        if (type == "dhcp") {
            val interfaceName = uri?.host ?: uri?.path?.trimStart('/')
            return LegacyAddress("dhcp", host = interfaceName)
        }

        val defaultPort =
            when (type) {
                "tls", "quic" -> 853
                "https", "h3" -> 443
                else -> 53
            }
        val authority = uri?.rawAuthority ?: address
        val (host, port) = splitHostPort(authority, defaultPort)
        return LegacyAddress(
            type = type,
            host = host,
            port = port.takeIf { it != defaultPort },
            path = uri?.path,
        )
    }

    private fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int> {
        val authority = value.substringAfterLast('@')
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end > 0) {
                val host = authority.substring(1, end)
                val port =
                    authority
                        .substring(end + 1)
                        .removePrefix(":")
                        .toIntOrNull()
                        ?: defaultPort
                return host to port
            }
        }
        val colon = authority.lastIndexOf(':')
        if (colon > 0 && authority.indexOf(':') == colon) {
            val port = authority.substring(colon + 1).toIntOrNull()
            if (port != null) {
                return authority.substring(0, colon) to port
            }
        }
        return authority to defaultPort
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
}
