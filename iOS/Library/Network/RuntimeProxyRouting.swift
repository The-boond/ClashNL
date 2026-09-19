import Foundation

/// Routing metadata from the configuration accepted by the running tunnel.
/// Contains only outbound tags and group relationships, never server credentials.
public struct RuntimeProxyRouting: Codable, Equatable, Sendable {
    public static let request = Data("clashnl.runtime-proxy-routing.v1".utf8)

    public struct ModeRoute: Codable, Equatable, Sendable {
        public let mode: String?
        public let inverted: Bool
        public let outbound: String
    }

    public struct Selection: Equatable, Sendable {
        public let group: String
        public let node: String
    }

    public let finalOutbound: String
    public let outboundTags: Set<String>
    public let groupMembers: [String: [String]]
    public let modeRoutes: [ModeRoute]

    public static func parse(_ content: String) -> RuntimeProxyRouting? {
        guard let root = try? JSONSerialization.jsonObject(
            with: Data(content.utf8), options: [.json5Allowed]
        ) as? [String: Any] else { return nil }

        let outbounds = root["outbounds"] as? [[String: Any]] ?? []
        let endpoints = root["endpoints"] as? [[String: Any]] ?? []
        let tags = Set((outbounds + endpoints).compactMap { $0["tag"] as? String })
        let route = root["route"] as? [String: Any] ?? [:]
        guard let final = (route["final"] as? String).flatMap({ $0.isEmpty ? nil : $0 })
            ?? outbounds.first?["tag"] as? String,
            tags.contains(final)
        else { return nil }

        var members: [String: [String]] = [:]
        for outbound in outbounds {
            guard let tag = outbound["tag"] as? String,
                  let type = outbound["type"] as? String,
                  type == "selector" || type == "urltest"
            else { continue }
            members[tag] = outbound["outbounds"] as? [String] ?? []
        }

        // sing-box modes affect routing through explicit clash_mode rules.
        // A group named GLOBAL, or a service-specific rule, is not a default route.
        let modeOnlyKeys: Set<String> = ["type", "clash_mode", "invert", "action", "outbound"]
        let modeRoutes = (route["rules"] as? [[String: Any]] ?? []).compactMap { rule -> ModeRoute? in
            guard Set(rule.keys).isSubset(of: modeOnlyKeys),
                  (rule["type"] as? String ?? "default") == "default",
                  (rule["action"] as? String ?? "route") == "route",
                  let outbound = rule["outbound"] as? String,
                  tags.contains(outbound)
            else { return nil }
            return ModeRoute(
                mode: rule["clash_mode"] as? String,
                inverted: rule["invert"] as? Bool ?? false,
                outbound: outbound
            )
        }
        return RuntimeProxyRouting(
            finalOutbound: final, outboundTags: tags,
            groupMembers: members, modeRoutes: modeRoutes
        )
    }

    public func selection(mode: String, selections: [String: String]) -> Selection? {
        guard !mode.isEmpty || modeRoutes.allSatisfy({ $0.mode == nil }) else { return nil }
        let root = modeRoutes.first { rule in
            let matches = rule.mode.map { $0 == mode } ?? true
            return rule.inverted ? !matches : matches
        }?.outbound ?? finalOutbound

        var node = root
        var visited = Set<String>()
        while let members = groupMembers[node] {
            guard visited.insert(node).inserted else { return nil }
            // The core omits groups with fewer than two members from its stream.
            // A single-member group has exactly one possible live selection.
            guard let selected = selections[node] ?? (members.count == 1 ? members.first : nil),
                  members.contains(selected)
            else { return nil }
            node = selected
        }
        guard outboundTags.contains(node) else { return nil }
        return Selection(group: root, node: node)
    }
}

/// A provider reply may race its timeout. Resume the waiting task exactly once.
public final class RuntimeProxyRoutingReply: @unchecked Sendable {
    private let lock = NSLock()
    private var completion: ((Data?) -> Void)?

    public init(_ completion: @escaping (Data?) -> Void) {
        self.completion = completion
    }

    public func finish(_ data: Data?) {
        lock.lock()
        let callback = completion
        completion = nil
        lock.unlock()
        callback?(data)
    }
}
