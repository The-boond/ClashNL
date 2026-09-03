import Darwin
import Foundation
import Network

/// A snapshot of the network path used underneath the packet tunnel.
///
/// `localAddresses` are addresses assigned to the interface. They are not the
/// public address observed after Wi-Fi, carrier, or CGNAT translation.
public struct UnderlyingNetworkStatus: Codable, Equatable, Sendable {
    public enum InterfaceKind: String, Codable, Sendable {
        case wifi
        case cellular
        case wiredEthernet
        case other
    }

    public let interfaceName: String
    public let interfaceIndex: Int
    public let interfaceKind: InterfaceKind
    public let localAddresses: [String]
    public let isExpensive: Bool
    public let isConstrained: Bool
    public let observedAt: Date

    public init(
        interfaceName: String,
        interfaceIndex: Int,
        interfaceKind: InterfaceKind,
        localAddresses: [String],
        isExpensive: Bool,
        isConstrained: Bool,
        observedAt: Date = Date()
    ) {
        self.interfaceName = interfaceName
        self.interfaceIndex = interfaceIndex
        self.interfaceKind = interfaceKind
        self.localAddresses = localAddresses
        self.isExpensive = isExpensive
        self.isConstrained = isConstrained
        self.observedAt = observedAt
    }

    public static func capture(from path: NWPath, preferredInterface: NWInterface? = nil) -> UnderlyingNetworkStatus? {
        guard path.status == .satisfied,
              let interface = preferredInterface ?? Self.preferredInterface(from: path)
        else {
            return nil
        }
        return UnderlyingNetworkStatus(
            interfaceName: interface.name,
            interfaceIndex: interface.index,
            interfaceKind: kind(for: interface.type),
            localAddresses: addresses(for: interface.name),
            isExpensive: path.isExpensive,
            isConstrained: path.isConstrained
        )
    }

    /// `availableInterfaces` may contain both a tunnel and its physical carrier.
    /// Prefer an interface type that the evaluated path says it actually uses,
    /// and prefer physical carrier types over `.other` (which commonly includes
    /// a virtual interface). Array order is only the final tie-breaker.
    public static func preferredInterface(from path: NWPath) -> NWInterface? {
        let usedInterfaces = path.availableInterfaces.filter { path.usesInterfaceType($0.type) }
        return usedInterfaces.first(where: { isPhysical($0.type) })
            ?? usedInterfaces.first
            ?? path.availableInterfaces.first(where: { isPhysical($0.type) })
            ?? path.availableInterfaces.first
    }

    /// Excludes the observation time so routine refreshes do not look like a
    /// route change to dashboard invalidation logic.
    public var routeFingerprint: String {
        [
            interfaceName,
            String(interfaceIndex),
            interfaceKind.rawValue,
            localAddresses.joined(separator: ","),
            isExpensive ? "1" : "0",
            isConstrained ? "1" : "0",
        ].joined(separator: "|")
    }

    private static func kind(for type: NWInterface.InterfaceType) -> InterfaceKind {
        switch type {
        case .wifi:
            return .wifi
        case .cellular:
            return .cellular
        case .wiredEthernet:
            return .wiredEthernet
        default:
            return .other
        }
    }

    private static func isPhysical(_ type: NWInterface.InterfaceType) -> Bool {
        switch type {
        case .wifi, .cellular, .wiredEthernet:
            return true
        default:
            return false
        }
    }

    private static func addresses(for interfaceName: String) -> [String] {
        var firstAddress: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&firstAddress) == 0, let firstAddress else {
            return []
        }
        defer { freeifaddrs(firstAddress) }

        var values: [String] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = firstAddress
        while let current = cursor {
            let record = current.pointee
            cursor = record.ifa_next
            guard let name = record.ifa_name,
                  String(cString: name) == interfaceName,
                  let address = record.ifa_addr
            else {
                continue
            }

            let family = Int32(address.pointee.sa_family)
            let addressLength: socklen_t
            switch family {
            case AF_INET:
                addressLength = socklen_t(MemoryLayout<sockaddr_in>.size)
            case AF_INET6:
                addressLength = socklen_t(MemoryLayout<sockaddr_in6>.size)
            default:
                continue
            }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = host.withUnsafeMutableBufferPointer { buffer in
                getnameinfo(
                    address,
                    addressLength,
                    buffer.baseAddress,
                    socklen_t(buffer.count),
                    nil,
                    0,
                    NI_NUMERICHOST
                )
            }
            guard result == 0 else { continue }

            var value = String(cString: host)
            if let zone = value.firstIndex(of: "%") {
                value = String(value[..<zone])
            }
            guard value != "127.0.0.1", value != "::1" else { continue }
            values.append(value)
        }

        return Array(Set(values)).sorted { lhs, rhs in
            let lhsRank = addressRank(lhs)
            let rhsRank = addressRank(rhs)
            return lhsRank == rhsRank ? lhs < rhs : lhsRank < rhsRank
        }
    }

    private static func addressRank(_ value: String) -> Int {
        if !value.contains(":") { return 0 }
        if value.lowercased().hasPrefix("fe80:") { return 2 }
        return 1
    }
}

/// Cross-process handoff from the packet-tunnel extension to its containing
/// app. Atomic replacement prevents the app from reading a partial snapshot.
public enum UnderlyingNetworkStatusStore {
    public static let defaultMaxAge: TimeInterval = 120

    private static var fileURL: URL {
        FilePath.cacheDirectory.appendingPathComponent("underlying-network-status.json")
    }

    public static func write(_ status: UnderlyingNetworkStatus) {
        do {
            try FileManager.default.createDirectory(at: FilePath.cacheDirectory, withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(status)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            // Interface monitoring must never prevent the tunnel from starting.
        }
    }

    public static func read(maxAge: TimeInterval = defaultMaxAge, now: Date = Date()) -> UnderlyingNetworkStatus? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        guard let status = try? JSONDecoder().decode(UnderlyingNetworkStatus.self, from: data) else { return nil }
        let age = now.timeIntervalSince(status.observedAt)
        // Reject stale snapshots left by an abnormal extension termination, as
        // well as implausibly future-dated snapshots after a clock adjustment.
        guard age >= -60, age <= maxAge else { return nil }
        return status
    }

    public static func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }
}
