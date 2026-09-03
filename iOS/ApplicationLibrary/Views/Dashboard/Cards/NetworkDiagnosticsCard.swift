import Foundation
import Libbox
import Library
import Network
import SwiftUI

fileprivate enum NetworkDiagnosticProbe {
    case physical
    case vpn
}

@MainActor
public final class NetworkDiagnosticsViewModel: ObservableObject {
    @Published public private(set) var upstream: UnderlyingNetworkStatus?
    @Published public private(set) var physicalAddress: String?
    @Published public private(set) var physicalError: String?
    @Published public private(set) var physicalLoading = false
    @Published public private(set) var physicalObservedAt: Date?
    @Published public private(set) var vpnAddress: String?
    @Published public private(set) var vpnError: String?
    @Published public private(set) var vpnLoading = false
    @Published public private(set) var vpnObservedAt: Date?
    @Published public private(set) var refreshRequired = true

    private var pathMonitor: NWPathMonitor?
    private var appPathStatus: UnderlyingNetworkStatus?
    private var isRemote = false
    private var serviceAvailable = false
    private var generation = 0
    private var directSession: LibboxSTUNTestSession?
    private var vpnSession: LibboxSTUNTestSession?
    private var standaloneTest: LibboxSTUNTest?
    private var directStartTask: Task<Void, Never>?
    private var vpnStartTask: Task<Void, Never>?
    private var providerReloadTask: Task<Void, Never>?

    public init() {}

    public func activate(isRemote: Bool, serviceAvailable: Bool) {
        updateContext(isRemote: isRemote, serviceAvailable: serviceAvailable)
        guard !isRemote, pathMonitor == nil else { return }

        let monitor = NWPathMonitor()
        pathMonitor = monitor
        monitor.pathUpdateHandler = { [weak self] path in
            let status = UnderlyingNetworkStatus.capture(from: path)
            DispatchQueue.main.async {
                self?.handlePathUpdate(status)
            }
        }
        monitor.start(queue: DispatchQueue(label: "com.clashnl.network-diagnostics.path"))
        scheduleProviderSnapshotReload()
    }

    public func deactivate() {
        pathMonitor?.cancel()
        pathMonitor = nil
        providerReloadTask?.cancel()
        providerReloadTask = nil
        generation += 1
        cancelProbes()
        clearResults()
        refreshRequired = true
    }

    public func updateContext(isRemote: Bool, serviceAvailable: Bool) {
        let changed = self.isRemote != isRemote || self.serviceAvailable != serviceAvailable
        self.isRemote = isRemote
        self.serviceAvailable = serviceAvailable
        if changed {
            invalidate()
        }
        updateDisplayedUpstream()
        if serviceAvailable, !isRemote {
            scheduleProviderSnapshotReload()
        } else {
            providerReloadTask?.cancel()
            providerReloadTask = nil
        }
    }

    public func refresh() {
        generation += 1
        cancelProbes()
        clearResults()
        refreshRequired = false
        updateDisplayedUpstream()
        if serviceAvailable, !isRemote {
            scheduleProviderSnapshotReload()
        }

        let currentGeneration = generation
        Task { [weak self] in
            guard let self else { return }
            let configuredServer = await SharedPreferences.stunServer.get()
            guard self.generation == currentGeneration else { return }
            let server = configuredServer.isEmpty ? LibboxSTUNDefaultServer : configuredServer

            if self.serviceAvailable {
                // These are deliberately two independent sessions. `direct`
                // measures the underlying Internet path for Clash-converted
                // profiles; an empty tag resolves to the core's default/final
                // outbound and measures the VPN-selected path.
                self.physicalLoading = true
                self.vpnLoading = true
                self.startCoreProbe(.physical, server: server, outboundTag: "direct", generation: currentGeneration)
                self.startCoreProbe(.vpn, server: server, outboundTag: "", generation: currentGeneration)
            } else {
                // Without a running packet tunnel there is no second outbound
                // to compare. This raw STUN result is the system's current
                // direct Internet path only.
                self.physicalLoading = true
                self.vpnError = String(localized: "VPN is not connected")
                self.vpnObservedAt = Date()
                let handler = ProbeHandler(viewModel: self, probe: .physical, generation: currentGeneration)
                let test = LibboxNewSTUNTest()!
                self.standaloneTest = test
                test.start(server, handler: handler)
            }
        }
    }

    public var isRefreshing: Bool {
        physicalLoading || vpnLoading
    }

    private func startCoreProbe(_ probe: NetworkDiagnosticProbe, server: String, outboundTag: String, generation: Int) {
        let handler = ProbeHandler(viewModel: self, probe: probe, generation: generation)
        let task = Task { [weak self] in
            do {
                let session = try await Task.detached {
                    try CommandTarget.standaloneClient().startSTUNTest(server, outboundTag: outboundTag, handler: handler)
                }.value
                guard let self,
                      self.generation == generation,
                      self.isLoading(probe)
                else {
                    Self.closeInBackground(session)
                    return
                }
                self.setSession(session, for: probe)
            } catch {
                self?.finish(probe, generation: generation, error: error.localizedDescription)
            }
        }
        switch probe {
        case .physical:
            directStartTask = task
        case .vpn:
            vpnStartTask = task
        }
    }

    private func handlePathUpdate(_ status: UnderlyingNetworkStatus?) {
        let previousFingerprint = appPathStatus?.routeFingerprint
        appPathStatus = status
        updateDisplayedUpstream()
        if routeChangeShouldInvalidate(
            previousFingerprint: previousFingerprint,
            currentFingerprint: status?.routeFingerprint
        ) {
            invalidate()
        }
        if serviceAvailable {
            scheduleProviderSnapshotReload()
        }
    }

    private func scheduleProviderSnapshotReload() {
        providerReloadTask?.cancel()
        providerReloadTask = Task { [weak self] in
            // The extension can publish a fraction after the app observes the
            // path/service transition. Retry for a bounded 1.75 seconds rather
            // than polling continuously.
            for delayMilliseconds in [250, 500, 1000] {
                try? await Task.sleep(nanoseconds: UInt64(delayMilliseconds) * NSEC_PER_MSEC)
                guard !Task.isCancelled,
                      let self,
                      self.serviceAvailable,
                      !self.isRemote
                else { return }

                let previousFingerprint = self.upstream?.routeFingerprint
                let snapshot = UnderlyingNetworkStatusStore.read()
                self.upstream = snapshot
                if self.routeChangeShouldInvalidate(
                    previousFingerprint: previousFingerprint,
                    currentFingerprint: snapshot?.routeFingerprint
                ) {
                    self.invalidate()
                }
                if snapshot != nil {
                    return
                }
            }
        }
    }

    private func updateDisplayedUpstream() {
        guard !isRemote else {
            upstream = nil
            return
        }
        if serviceAvailable {
            // While our tunnel is active only the packet-tunnel extension can
            // authoritatively name its underlying physical path. Falling back
            // to the container app's NWPath may describe utun instead.
            upstream = UnderlyingNetworkStatusStore.read()
        } else {
            upstream = appPathStatus
        }
    }

    private func routeChangeShouldInvalidate(previousFingerprint: String?, currentFingerprint: String?) -> Bool {
        guard previousFingerprint != currentFingerprint else { return false }
        // The first monitor callback should also invalidate a probe that was
        // started before path identity became available.
        return previousFingerprint != nil || hasProbeState
    }

    private var hasProbeState: Bool {
        !refreshRequired ||
            physicalLoading || vpnLoading ||
            physicalAddress != nil || vpnAddress != nil ||
            physicalError != nil || vpnError != nil ||
            physicalObservedAt != nil || vpnObservedAt != nil
    }

    private func invalidate() {
        generation += 1
        cancelProbes()
        clearResults()
        refreshRequired = true
    }

    private func clearResults() {
        physicalAddress = nil
        physicalError = nil
        physicalLoading = false
        physicalObservedAt = nil
        vpnAddress = nil
        vpnError = nil
        vpnLoading = false
        vpnObservedAt = nil
    }

    private func cancelProbes() {
        directStartTask?.cancel()
        directStartTask = nil
        vpnStartTask?.cancel()
        vpnStartTask = nil
        let directSession = self.directSession
        self.directSession = nil
        let vpnSession = self.vpnSession
        self.vpnSession = nil
        let standaloneTest = self.standaloneTest
        self.standaloneTest = nil
        if let directSession {
            Self.closeInBackground(directSession)
        }
        if let vpnSession {
            Self.closeInBackground(vpnSession)
        }
        if let standaloneTest {
            Self.cancelInBackground(standaloneTest)
        }
    }

    private func setSession(_ session: LibboxSTUNTestSession, for probe: NetworkDiagnosticProbe) {
        switch probe {
        case .physical:
            directSession = session
        case .vpn:
            vpnSession = session
        }
    }

    private func isLoading(_ probe: NetworkDiagnosticProbe) -> Bool {
        switch probe {
        case .physical:
            return physicalLoading
        case .vpn:
            return vpnLoading
        }
    }

    fileprivate func finish(_ probe: NetworkDiagnosticProbe, generation: Int, externalAddress: String? = nil, error: String? = nil) {
        guard self.generation == generation else { return }
        let address = externalAddress.flatMap(Self.publicAddress(from:))
        // A completion time is useful for both a successful observation and a
        // failed path probe; the two paths intentionally finish independently.
        let observedAt = Date()
        switch probe {
        case .physical:
            physicalAddress = address
            physicalError = address == nil ? (error ?? String(localized: "STUN returned no external address")) : nil
            physicalObservedAt = observedAt
            physicalLoading = false
            if let directSession {
                Self.closeInBackground(directSession)
            }
            directSession = nil
            standaloneTest = nil
            directStartTask = nil
        case .vpn:
            vpnAddress = address
            vpnError = address == nil ? (error ?? String(localized: "STUN returned no external address")) : nil
            vpnObservedAt = observedAt
            vpnLoading = false
            if let vpnSession {
                Self.closeInBackground(vpnSession)
            }
            vpnSession = nil
            vpnStartTask = nil
        }
    }

    private static func closeInBackground(_ session: LibboxSTUNTestSession) {
        Task.detached {
            try? session.close()
        }
    }

    private static func cancelInBackground(_ test: LibboxSTUNTest) {
        Task.detached {
            test.cancel()
        }
    }

    private static func publicAddress(from mappedEndpoint: String) -> String? {
        let value = mappedEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }
        if value.first == "[", let closingBracket = value.firstIndex(of: "]") {
            return String(value[value.index(after: value.startIndex)..<closingBracket])
        }
        if value.filter({ $0 == ":" }).count == 1, let separator = value.lastIndex(of: ":") {
            return String(value[..<separator])
        }
        return value
    }

    private final class ProbeHandler: NSObject, LibboxSTUNTestHandlerProtocol, @unchecked Sendable {
        private weak var viewModel: NetworkDiagnosticsViewModel?
        private let probe: NetworkDiagnosticProbe
        private let generation: Int

        init(viewModel: NetworkDiagnosticsViewModel, probe: NetworkDiagnosticProbe, generation: Int) {
            self.viewModel = viewModel
            self.probe = probe
            self.generation = generation
        }

        func onProgress(_: LibboxSTUNTestProgress?) {}

        func onResult(_ result: LibboxSTUNTestResult?) {
            let externalAddress = result?.externalAddr
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.viewModel?.finish(self.probe, generation: self.generation, externalAddress: externalAddress)
            }
        }

        func onError(_ message: String?) {
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.viewModel?.finish(
                    self.probe,
                    generation: self.generation,
                    error: message ?? String(localized: "STUN test failed")
                )
            }
        }
    }
}

@MainActor
public struct NetworkDiagnosticsCard: View {
    @ObservedObject private var commandClient: CommandClient
    @StateObject private var viewModel = NetworkDiagnosticsViewModel()
    private let isRemote: Bool
    private let serviceAvailable: Bool

    public init(commandClient: CommandClient, isRemote: Bool, serviceAvailable: Bool) {
        _commandClient = ObservedObject(wrappedValue: commandClient)
        self.isRemote = isRemote
        self.serviceAvailable = serviceAvailable
    }

    public var body: some View {
        DashboardCardView(title: "", isHalfWidth: false) {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    DashboardCardHeader(icon: "network", title: "Network Paths", accent: .cyan)
                    Spacer()
                    Button {
                        viewModel.refresh()
                    } label: {
                        if viewModel.isRefreshing {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                    .buttonStyle(.borderless)
                    .disabled(viewModel.isRefreshing)
                    .accessibilityLabel(String(localized: "Refresh network paths"))
                }

                DashboardCardLine(
                    String(localized: "Target"),
                    isRemote ? String(localized: "Remote service") : String(localized: "This device")
                )

                upstreamContent

                Divider()

                HStack(alignment: .top, spacing: 12) {
                    exitColumn(
                        title: physicalExitTitle,
                        path: "direct",
                        address: viewModel.physicalAddress,
                        error: viewModel.physicalError,
                        loading: viewModel.physicalLoading,
                        observedAt: viewModel.physicalObservedAt
                    )
                    Divider()
                    exitColumn(
                        title: isRemote ? String(localized: "Remote default egress") : String(localized: "VPN default egress"),
                        path: String(localized: "default/final"),
                        address: viewModel.vpnAddress,
                        error: viewModel.vpnError,
                        loading: viewModel.vpnLoading,
                        observedAt: viewModel.vpnObservedAt
                    )
                }

                if viewModel.refreshRequired {
                    Label("Refresh required after a network or service change.", systemImage: "arrow.clockwise.circle")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Text("STUN observes a UDP NAT address only. It does not prove the HTTP/TCP or every-rule exit, and failure can mean that the selected outbound does not support UDP. Clash-converted profiles provide the direct outbound; other sing-box profiles may not.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .onAppear {
            viewModel.activate(isRemote: isRemote, serviceAvailable: serviceAvailable)
        }
        .onDisappear {
            viewModel.deactivate()
        }
        .onChangeCompat(of: serviceAvailable) { available in
            viewModel.updateContext(isRemote: isRemote, serviceAvailable: available)
        }
        .onChangeCompat(of: isRemote) { remote in
            viewModel.updateContext(isRemote: remote, serviceAvailable: serviceAvailable)
        }
    }

    private var physicalExitTitle: String {
        if isRemote {
            return String(localized: "Remote direct egress")
        }
        return serviceAvailable
            ? String(localized: "Physical direct egress")
            : String(localized: "System egress")
    }

    @ViewBuilder
    private var upstreamContent: some View {
        if isRemote {
            DashboardCardLine(String(localized: "Preferred upstream"), String(localized: "Not exposed by remote API"))
        } else if let upstream = viewModel.upstream {
            DashboardCardLine(
                String(localized: "Preferred upstream"),
                "\(interfaceKindName(upstream.interfaceKind)) · \(upstream.interfaceName)"
            )
            DashboardCardLine(
                String(localized: "Local interface address"),
                upstream.localAddresses.isEmpty ? String(localized: "Unavailable") : upstream.localAddresses.joined(separator: ", ")
            )
        } else {
            DashboardCardLine(String(localized: "Preferred upstream"), String(localized: "Unavailable"))
            DashboardCardLine(String(localized: "Local interface address"), String(localized: "Unavailable"))
        }
    }

    private func exitColumn(
        title: String,
        path: String,
        address: String?,
        error: String?,
        loading: Bool,
        observedAt: Date?
    ) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.subheadline.weight(.semibold))
            Text(path)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
            if loading {
                ProgressView()
                    .controlSize(.small)
                    .padding(.vertical, 4)
            } else if let address {
                Text(address)
                    .font(.system(.subheadline, design: .monospaced))
                    .lineLimit(2)
                if let observedAt {
                    Text(observedAt.formatted(date: .omitted, time: .standard))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            } else if let error {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
                if let observedAt {
                    Text(observedAt.formatted(date: .omitted, time: .standard))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            } else {
                Text("Tap refresh")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func interfaceKindName(_ kind: UnderlyingNetworkStatus.InterfaceKind) -> String {
        switch kind {
        case .wifi:
            return String(localized: "Wi-Fi")
        case .cellular:
            return String(localized: "Cellular")
        case .wiredEthernet:
            return String(localized: "Ethernet")
        case .other:
            return String(localized: "Other")
        }
    }
}
