import Foundation

@main
struct RuntimeProxyRoutingTests {
    static var checks = 0

    static func equal<T: Equatable>(_ actual: T, _ expected: T, _ message: String) {
        checks += 1
        guard actual == expected else { fatalError(message) }
    }

    static func parse(_ source: String) -> RuntimeProxyRouting {
        guard let routing = RuntimeProxyRouting.parse(source) else { fatalError("Fixture failed to parse") }
        return routing
    }

    static func main() throws {
        let config = """
        {
          "outbounds": [
            {"type":"selector","tag":"Service","outbounds":["Node-A","Node-B"]},
            {"type":"selector","tag":"GLOBAL","outbounds":["Node-A","Node-B"]},
            {"type":"selector","tag":"Main","outbounds":["Auto","Node-C"]},
            {"type":"urltest","tag":"Auto","outbounds":["Node-A","Node-B"]},
            {"type":"socks","tag":"Node-A","server":"192.0.2.1","password":"fixture-secret"},
            {"type":"direct","tag":"Node-B"},
            {"type":"direct","tag":"Node-C"},
            {"type":"direct","tag":"direct"}
          ],
          "route": {"final":"Main","rules":[
            {"domain_suffix":["example.com"],"outbound":"Service"},
            {"clash_mode":"global","outbound":"GLOBAL"},
            {"clash_mode":"direct","action":"route","outbound":"direct"}
          ]}
        }
        """
        let routing = parse(config)
        var live = ["Service":"Node-B", "GLOBAL":"Node-A", "Main":"Node-C", "Auto":"Node-A"]

        equal(routing.selection(mode: "rule", selections: live)?.node, "Node-C", "Unrelated first group must not replace default route")
        equal(routing.selection(mode: "rule", selections: live)?.group, "Main", "Read actual route.final")
        equal(routing.selection(mode: "", selections: live), nil, "Wait for the core mode instead of guessing during startup")
        live["Service"] = "Node-A"
        equal(routing.selection(mode: "rule", selections: live)?.node, "Node-C", "A service-group click must not hijack dashboard selection")
        equal(routing.selection(mode: "global", selections: live)?.node, "Node-A", "Global mode must follow its explicit mode rule")
        equal(routing.selection(mode: "direct", selections: live)?.node, "direct", "Direct mode must follow its actual route")

        live["Main"] = "Auto"
        equal(routing.selection(mode: "rule", selections: live)?.node, "Node-A", "Resolve nested automatic groups")
        live["Auto"] = "Node-B"
        equal(routing.selection(mode: "rule", selections: live)?.node, "Node-B", "A core automatic-group update changes the leaf")
        equal(routing.selection(mode: "rule", selections: [:]), nil, "Never display configured defaults before the first live snapshot")
        live["Main"] = "Removed"
        equal(routing.selection(mode: "rule", selections: live), nil, "Reject a stale node outside the current group")
        live["Main"] = ""
        equal(routing.selection(mode: "rule", selections: live), nil, "Empty selections are not active nodes")

        let single = parse("""
        {"outbounds":[
          {"type":"selector","tag":"Only","outbounds":["Node"]},
          {"type":"direct","tag":"Node"}
        ],"route":{"final":"Only"}}
        """)
        equal(single.selection(mode: "rule", selections: [:])?.node, "Node", "Handle singleton groups omitted by the core stream")

        let cycle = parse("""
        {"outbounds":[
          {"type":"selector","tag":"A","outbounds":["B","direct"]},
          {"type":"selector","tag":"B","outbounds":["A","direct"]},
          {"type":"direct","tag":"direct"}
        ],"route":{"final":"A"}}
        """)
        equal(cycle.selection(mode: "rule", selections: ["A":"B", "B":"A"]), nil, "Cycles never report a group as a node")

        let json5 = parse("""
        {
          // A native sing-box profile can contain comments and trailing commas.
          "outbounds":[{"type":"direct","tag":"Actual"},{"type":"direct","tag":"GLOBAL"},],
        }
        """)
        equal(json5.selection(mode: "global", selections: [:])?.node, "Actual", "Do not invent a global route from the group's name")
        equal(json5.selection(mode: "direct", selections: [:])?.node, "Actual", "Do not invent a direct override that the configuration does not implement")

        let orderedRules = parse("""
        {"outbounds":[{"type":"direct","tag":"A"},{"type":"direct","tag":"B"}],
         "route":{"final":"B","rules":[{"outbound":"A"},{"clash_mode":"global","outbound":"B"}]}}
        """)
        equal(orderedRules.selection(mode: "global", selections: [:])?.node, "A", "Respect the first unconditional default route")
        equal(RuntimeProxyRouting.parse("not JSON"), nil, "Invalid configuration must not manufacture a route")
        equal(RuntimeProxyRouting.parse("{\"outbounds\":[]}"), nil, "Missing outbound information must stay unavailable")

        let encoded = try JSONEncoder().encode(routing)
        let metadata = String(decoding: encoded, as: UTF8.self)
        equal(metadata.contains("fixture-secret"), false, "Never send passwords in routing metadata")
        equal(metadata.contains("192.0.2.1"), false, "Never send server addresses in routing metadata")
        equal(try JSONDecoder().decode(RuntimeProxyRouting.self, from: encoded), routing, "Provider metadata must round-trip")

        var completions = 0
        let reply = RuntimeProxyRoutingReply { _ in completions += 1 }
        DispatchQueue.concurrentPerform(iterations: 32) { _ in reply.finish(nil) }
        equal(completions, 1, "Racing provider replies and timeouts resume exactly once")
        print("Runtime proxy routing: \(checks) checks passed")
    }
}
