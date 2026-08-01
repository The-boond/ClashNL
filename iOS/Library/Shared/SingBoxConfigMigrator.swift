import CoreFoundation
import Foundation

/// Converts configuration fields accepted by sing-box 1.13 and older to the
/// explicit schema required by sing-box 1.14.
public enum SingBoxConfigMigrator {
    public enum Migration: String, Hashable {
        case legacyDNSServers
        case legacyDNSRules
        case legacyInboundFields
        case legacyOutbounds
        case legacyTUNFields
    }

    public struct Result {
        public let content: String
        public let migrations: Set<Migration>

        public var changed: Bool {
            !migrations.isEmpty
        }
    }

    private typealias JSONObject = [String: Any]

    private static let defaultResolverTag = "__clashnl_default__"
    private static let legacyServerKeys: Set<String> = [
        "address",
        "address_resolver",
        "address_strategy",
        "address_fallback_delay",
        "strategy",
        "client_subnet",
    ]
    private static let resolverKeys: Set<String> = [
        "server",
        "timeout",
        "strategy",
        "disable_cache",
        "disable_optimistic_cache",
        "rewrite_ttl",
        "client_subnet",
    ]

    /// Returns nil when the source is not a JSON object. A result is returned
    /// for valid JSON objects even when no migration is required, allowing the
    /// caller to keep native JSON errors out of the Clash YAML fallback.
    public static func migrate(_ source: String) -> Result? {
        guard let data = source.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(
                  with: data,
                  options: [.json5Allowed]
              ),
              var root = parsed as? JSONObject
        else {
            return nil
        }

        var migrations = Set<Migration>()
        if var dns = root["dns"] as? JSONObject {
            migrateDNS(&dns, root: &root, migrations: &migrations)
            root["dns"] = dns
        }
        migrateOutbounds(root: &root, migrations: &migrations)
        migrateInbounds(root: &root, migrations: &migrations)

        guard JSONSerialization.isValidJSONObject(root),
              let migratedData = try? JSONSerialization.data(
                  withJSONObject: root,
                  options: [.sortedKeys, .withoutEscapingSlashes]
              ),
              let content = String(data: migratedData, encoding: .utf8)
        else {
            return nil
        }
        return Result(content: content, migrations: migrations)
    }

    private static func migrateDNS(
        _ dns: inout JSONObject,
        root: inout JSONObject,
        migrations: inout Set<Migration>
    ) {
        let fakeIP = dns.removeValue(forKey: "fakeip") as? JSONObject
        var rcodeByTag = [String: String]()
        var strategyByTag = [String: Any]()
        var clientSubnetByTag = [String: Any]()

        if let servers = dns["servers"] as? [Any] {
            var migratedServers = [Any]()
            for (index, element) in servers.enumerated() {
                guard let server = element as? JSONObject,
                      let address = server["address"] as? String
                else {
                    migratedServers.append(element)
                    continue
                }

                migrations.insert(.legacyDNSServers)
                let tag = server["tag"] as? String
                let parsed = parseLegacyAddress(address)
                if parsed.type == "rcode" {
                    rcodeByTag[tag ?? "__clashnl_rcode_\(index)"] =
                        (parsed.host ?? "SERVFAIL").uppercased()
                    continue
                }

                var migrated: JSONObject = ["type": parsed.type]
                for (key, value) in server where !legacyServerKeys.contains(key) {
                    migrated[key] = value
                }

                switch parsed.type {
                case "udp", "tcp", "tls", "quic", "https", "h3":
                    if let host = parsed.host, !host.isEmpty {
                        migrated["server"] = host
                    }
                    if let port = parsed.port {
                        migrated["server_port"] = port
                    }
                    if let path = parsed.path, !path.isEmpty, path != "/dns-query" {
                        migrated["path"] = path
                    }
                case "dhcp":
                    if let interface = parsed.host, !interface.isEmpty, interface != "auto" {
                        migrated["interface"] = interface
                    }
                case "fakeip":
                    if let range = fakeIP?["inet4_range"] {
                        migrated["inet4_range"] = range
                    }
                    if let range = fakeIP?["inet6_range"] {
                        migrated["inet6_range"] = range
                    }
                default:
                    break
                }

                if let resolver = server["address_resolver"] as? String {
                    if let strategy = server["address_strategy"] {
                        migrated["domain_resolver"] = [
                            "server": resolver,
                            "strategy": strategy,
                        ] as JSONObject
                    } else {
                        migrated["domain_resolver"] = resolver
                    }
                }
                if let fallbackDelay = server["address_fallback_delay"] {
                    migrated["fallback_delay"] = fallbackDelay
                }

                let strategyKey = tag ?? defaultResolverTag
                if let strategy = server["strategy"] {
                    strategyByTag[strategyKey] = strategy
                }
                if let clientSubnet = server["client_subnet"] {
                    clientSubnetByTag[strategyKey] = clientSubnet
                }
                migratedServers.append(migrated)
            }
            dns["servers"] = migratedServers
        }

        if let oldRules = dns["rules"] as? [Any] {
            var migratedRules = [Any]()
            for element in oldRules {
                guard let rule = element as? JSONObject else {
                    migratedRules.append(element)
                    continue
                }

                let server = rule["server"] as? String
                if let server, let rcode = rcodeByTag[server] {
                    var rewritten = rule
                    rewritten.removeValue(forKey: "server")
                    rewritten["action"] = "predefined"
                    rewritten["rcode"] = rcode
                    migratedRules.append(rewritten)
                    migrations.insert(.legacyDNSServers)
                    continue
                }

                if let server,
                   let outbound = rule["outbound"],
                   isSimpleOutboundRule(rule)
                {
                    let resolver = buildResolver(
                        rule: rule,
                        server: server,
                        strategyByTag: strategyByTag,
                        clientSubnetByTag: clientSubnetByTag
                    )
                    let outboundTags = stringList(outbound)
                    if outboundTags.contains("any") {
                        var route = root["route"] as? JSONObject ?? [:]
                        if route["default_domain_resolver"] == nil {
                            route["default_domain_resolver"] = resolver
                        }
                        root["route"] = route
                    } else if !outboundTags.isEmpty,
                              let outbounds = root["outbounds"] as? [Any]
                    {
                        root["outbounds"] = outbounds.map { outboundElement in
                            guard var outboundObject = outboundElement as? JSONObject,
                                  let tag = outboundObject["tag"] as? String,
                                  outboundTags.contains(tag)
                            else {
                                return outboundElement
                            }
                            outboundObject["domain_resolver"] = resolver
                            return outboundObject
                        }
                    }
                    migrations.insert(.legacyDNSRules)
                    continue
                }

                var rewritten = rule
                let strategyKey = server ?? defaultResolverTag
                var changed = false
                if rewritten["strategy"] == nil, let strategy = strategyByTag[strategyKey] {
                    rewritten["strategy"] = strategy
                    changed = true
                }
                if rewritten["client_subnet"] == nil,
                   let clientSubnet = clientSubnetByTag[strategyKey]
                {
                    rewritten["client_subnet"] = clientSubnet
                    changed = true
                }
                if changed {
                    migrations.insert(.legacyDNSRules)
                }
                migratedRules.append(rewritten)
            }
            dns["rules"] = migratedRules
        }

        if let defaultStrategy = strategyByTag[defaultResolverTag] {
            dns["strategy"] = defaultStrategy
            migrations.insert(.legacyDNSRules)
        }
        if fakeIP != nil {
            migrations.insert(.legacyDNSServers)
        }
    }

    private static func migrateInbounds(
        root: inout JSONObject,
        migrations: inout Set<Migration>
    ) {
        guard let inbounds = root["inbounds"] as? [Any] else {
            return
        }

        var route = root["route"] as? JSONObject ?? [:]
        var legacyRules = [Any]()
        var migratedInbounds = [Any]()
        var existingTags = Set(
            inbounds.compactMap { ($0 as? JSONObject)?["tag"] as? String }
        )

        for (index, element) in inbounds.enumerated() {
            guard var inbound = element as? JSONObject else {
                migratedInbounds.append(element)
                continue
            }

            let hasLegacyInboundFields = [
                "sniff",
                "sniff_timeout",
                "sniff_override_destination",
                "domain_strategy",
                "udp_disable_domain_unmapping",
            ].contains { inbound[$0] != nil }
            let hasLegacyTUNFields =
                inbound["endpoint_independent_nat"] != nil ||
                inbound["gso"] != nil ||
                [
                    "inet4_address",
                    "inet6_address",
                    "inet4_route_address",
                    "inet6_route_address",
                    "inet4_route_exclude_address",
                    "inet6_route_exclude_address",
                ].contains { inbound[$0] != nil }

            if hasLegacyInboundFields {
                let existingTag = inbound["tag"] as? String
                let inboundTag: String
                if let existingTag, !existingTag.isEmpty {
                    inboundTag = existingTag
                } else {
                    var suffix = 0
                    var generatedTag: String
                    repeat {
                        generatedTag = suffix == 0
                            ? "clashnl-in-\(index)"
                            : "clashnl-in-\(index)-\(suffix)"
                        suffix += 1
                    } while existingTags.contains(generatedTag)
                    inboundTag = generatedTag
                    inbound["tag"] = generatedTag
                }
                existingTags.insert(inboundTag)

                if let strategy = inbound.removeValue(forKey: "domain_strategy") {
                    legacyRules.append([
                        "inbound": inboundTag,
                        "action": "resolve",
                        "strategy": strategy,
                    ] as JSONObject)
                }
                let sniffEnabled = boolean(inbound.removeValue(forKey: "sniff")) == true
                let sniffTimeout = inbound.removeValue(forKey: "sniff_timeout")
                inbound.removeValue(forKey: "sniff_override_destination")
                if sniffEnabled {
                    var rule: JSONObject = [
                        "inbound": inboundTag,
                        "action": "sniff",
                    ]
                    if let sniffTimeout {
                        rule["timeout"] = sniffTimeout
                    }
                    legacyRules.append(rule)
                }
                if boolean(inbound.removeValue(forKey: "udp_disable_domain_unmapping")) == true {
                    legacyRules.append([
                        "inbound": inboundTag,
                        "action": "route-options",
                        "udp_disable_domain_unmapping": true,
                    ] as JSONObject)
                }
                migrations.insert(.legacyInboundFields)
            }

            if hasLegacyTUNFields {
                inbound.removeValue(forKey: "endpoint_independent_nat")
                inbound.removeValue(forKey: "gso")
                mergeLegacyListField(in: &inbound, oldKey: "inet4_address", newKey: "address")
                mergeLegacyListField(in: &inbound, oldKey: "inet6_address", newKey: "address")
                mergeLegacyListField(in: &inbound, oldKey: "inet4_route_address", newKey: "route_address")
                mergeLegacyListField(in: &inbound, oldKey: "inet6_route_address", newKey: "route_address")
                mergeLegacyListField(
                    in: &inbound,
                    oldKey: "inet4_route_exclude_address",
                    newKey: "route_exclude_address"
                )
                mergeLegacyListField(
                    in: &inbound,
                    oldKey: "inet6_route_exclude_address",
                    newKey: "route_exclude_address"
                )
                migrations.insert(.legacyTUNFields)
            }

            migratedInbounds.append(inbound)
        }

        root["inbounds"] = migratedInbounds
        if !legacyRules.isEmpty {
            let existingRules = route["rules"] as? [Any] ?? []
            route["rules"] = legacyRules + existingRules
            root["route"] = route
        }
    }

    private static func migrateOutbounds(
        root: inout JSONObject,
        migrations: inout Set<Migration>
    ) {
        guard let outbounds = root["outbounds"] as? [Any] else {
            return
        }

        var dnsTags = Set<String>()
        var directOverrides = [String: JSONObject]()
        var defaultDirectOverrideTag: String?
        var migratedOutbounds = [Any]()

        for (index, element) in outbounds.enumerated() {
            guard var outbound = element as? JSONObject,
                  let type = outbound["type"] as? String
            else {
                migratedOutbounds.append(element)
                continue
            }

            if type == "dns" {
                if let tag = nonemptyString(outbound["tag"]) {
                    dnsTags.insert(tag)
                }
                migrations.insert(.legacyOutbounds)
                continue
            }

            if type == "direct" {
                var overrideOptions = JSONObject()
                if let value = outbound.removeValue(forKey: "override_address") {
                    overrideOptions["override_address"] = value
                }
                if let value = outbound.removeValue(forKey: "override_port") {
                    overrideOptions["override_port"] = value
                }
                outbound.removeValue(forKey: "proxy_protocol")

                if !overrideOptions.isEmpty {
                    let tag: String
                    if let existingTag = nonemptyString(outbound["tag"]) {
                        tag = existingTag
                    } else {
                        tag = uniqueOutboundTag(
                            base: "clashnl-direct-\(index)",
                            outbounds: outbounds
                        )
                        outbound["tag"] = tag
                    }
                    directOverrides[tag] = overrideOptions
                    if index == 0 {
                        defaultDirectOverrideTag = tag
                    }
                    migrations.insert(.legacyOutbounds)
                }
            }
            migratedOutbounds.append(outbound)
        }

        if !dnsTags.isEmpty {
            migratedOutbounds = migratedOutbounds.map { element in
                guard var outbound = element as? JSONObject,
                      let members = outbound["outbounds"] as? [Any]
                else {
                    return element
                }
                outbound["outbounds"] = members.filter { member in
                    guard let tag = member as? String else {
                        return true
                    }
                    return !dnsTags.contains(tag)
                }
                return outbound
            }
        }
        root["outbounds"] = migratedOutbounds

        guard !dnsTags.isEmpty || !directOverrides.isEmpty else {
            return
        }

        var route = root["route"] as? JSONObject ?? [:]
        let existingRules = route["rules"] as? [Any] ?? []
        var rewrittenRules = rewriteRouteRules(
            existingRules,
            dnsTags: dnsTags,
            directOverrides: directOverrides
        )

        if let final = nonemptyString(route["final"]),
           let overrideOptions = directOverrides[final]
        {
            rewrittenRules.append(
                routeRule(outbound: final, overrideOptions: overrideOptions)
            )
        } else if route["final"] == nil,
                  let tag = defaultDirectOverrideTag,
                  let overrideOptions = directOverrides[tag]
        {
            route["final"] = tag
            rewrittenRules.append(
                routeRule(outbound: tag, overrideOptions: overrideOptions)
            )
        }

        if let final = nonemptyString(route["final"]), dnsTags.contains(final) {
            route.removeValue(forKey: "final")
            rewrittenRules.append(["action": "hijack-dns"] as JSONObject)
        }
        route["rules"] = rewrittenRules
        root["route"] = route
    }

    private static func rewriteRouteRules(
        _ rules: [Any],
        dnsTags: Set<String>,
        directOverrides: [String: JSONObject]
    ) -> [Any] {
        rules.map { element in
            guard var rule = element as? JSONObject else {
                return element
            }
            if let nestedRules = rule["rules"] as? [Any] {
                rule["rules"] = rewriteRouteRules(
                    nestedRules,
                    dnsTags: dnsTags,
                    directOverrides: directOverrides
                )
            }
            guard let outbound = rule["outbound"] as? String else {
                return rule
            }
            if dnsTags.contains(outbound) {
                rule.removeValue(forKey: "outbound")
                rule["action"] = "hijack-dns"
                return rule
            }
            if let overrideOptions = directOverrides[outbound] {
                for (key, value) in overrideOptions where rule[key] == nil {
                    rule[key] = value
                }
            }
            return rule
        }
    }

    private static func routeRule(
        outbound: String,
        overrideOptions: JSONObject
    ) -> JSONObject {
        var rule: JSONObject = [
            "action": "route",
            "outbound": outbound,
        ]
        for (key, value) in overrideOptions {
            rule[key] = value
        }
        return rule
    }

    private static func uniqueOutboundTag(
        base: String,
        outbounds: [Any]
    ) -> String {
        let existingTags = Set(
            outbounds.compactMap { ($0 as? JSONObject)?["tag"] as? String }
        )
        var candidate = base
        var suffix = 1
        while existingTags.contains(candidate) {
            candidate = "\(base)-\(suffix)"
            suffix += 1
        }
        return candidate
    }

    private static func nonemptyString(_ value: Any?) -> String? {
        guard let string = value as? String else {
            return nil
        }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func mergeLegacyListField(
        in target: inout JSONObject,
        oldKey: String,
        newKey: String
    ) {
        guard let oldValue = target.removeValue(forKey: oldKey) else {
            return
        }
        let oldValues = oldValue as? [Any] ?? [oldValue]
        let currentValues: [Any]
        if let values = target[newKey] as? [Any] {
            currentValues = values
        } else if let value = target[newKey] {
            currentValues = [value]
        } else {
            currentValues = []
        }
        target[newKey] = currentValues + oldValues
    }

    private static func buildResolver(
        rule: JSONObject,
        server: String,
        strategyByTag: [String: Any],
        clientSubnetByTag: [String: Any]
    ) -> Any {
        var resolver: JSONObject = ["server": server]
        for key in resolverKeys where key != "server" {
            if let value = rule[key] {
                resolver[key] = value
            }
        }
        if resolver["strategy"] == nil, let strategy = strategyByTag[server] {
            resolver["strategy"] = strategy
        }
        if resolver["client_subnet"] == nil, let clientSubnet = clientSubnetByTag[server] {
            resolver["client_subnet"] = clientSubnet
        }
        return resolver.count == 1 ? server : resolver
    }

    private static func isSimpleOutboundRule(_ rule: JSONObject) -> Bool {
        let allowedKeys = resolverKeys.union(["outbound"])
        return Set(rule.keys).isSubset(of: allowedKeys)
    }

    private static func stringList(_ value: Any) -> [String] {
        if let value = value as? String {
            return [value]
        }
        return (value as? [Any])?.compactMap { $0 as? String } ?? []
    }

    private static func boolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID()
        else {
            return nil
        }
        return number.boolValue
    }

    private struct LegacyAddress {
        let type: String
        let host: String?
        let port: Int?
        let path: String?
    }

    private static func parseLegacyAddress(_ address: String) -> LegacyAddress {
        if address == "local" {
            return LegacyAddress(type: "local", host: nil, port: nil, path: nil)
        }
        if address == "fakeip" {
            return LegacyAddress(type: "fakeip", host: nil, port: nil, path: nil)
        }

        let hasScheme = address.contains("://")
        let components = hasScheme ? URLComponents(string: address) : nil
        let type = components?.scheme?.lowercased()
            ?? (hasScheme ? address.components(separatedBy: "://")[0].lowercased() : "udp")
        if type == "rcode" {
            let value = components?.host
                ?? components?.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            return LegacyAddress(type: type, host: value ?? "SERVFAIL", port: nil, path: nil)
        }
        if type == "dhcp" {
            let interface = components?.host
                ?? components?.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            return LegacyAddress(type: type, host: interface, port: nil, path: nil)
        }

        let defaultPort: Int
        switch type {
        case "tls", "quic":
            defaultPort = 853
        case "https", "h3":
            defaultPort = 443
        default:
            defaultPort = 53
        }

        let hostAndPort: (String, Int)
        if let componentHost = components?.host {
            hostAndPort = (componentHost, components?.port ?? defaultPort)
        } else {
            let authority = hasScheme
                ? address.components(separatedBy: "://").dropFirst().joined(separator: "://")
                    .components(separatedBy: "/")[0]
                : address
            hostAndPort = splitHostPort(authority, defaultPort: defaultPort)
        }
        return LegacyAddress(
            type: type,
            host: hostAndPort.0,
            port: hostAndPort.1 == defaultPort ? nil : hostAndPort.1,
            path: components?.path
        )
    }

    private static func splitHostPort(_ value: String, defaultPort: Int) -> (String, Int) {
        let authority = value.components(separatedBy: "@").last ?? value
        if authority.hasPrefix("["),
           let end = authority.firstIndex(of: "]")
        {
            let host = String(authority[authority.index(after: authority.startIndex) ..< end])
            let remainder = authority[authority.index(after: end)...]
            let port = Int(remainder.trimmingCharacters(in: CharacterSet(charactersIn: ":")))
                ?? defaultPort
            return (host, port)
        }

        if let colon = authority.lastIndex(of: ":"),
           authority.firstIndex(of: ":") == colon,
           let port = Int(authority[authority.index(after: colon)...])
        {
            return (String(authority[..<colon]), port)
        }
        return (authority, defaultPort)
    }
}
