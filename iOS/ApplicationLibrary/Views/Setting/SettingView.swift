import Library
import SwiftUI

#if os(macOS)
    private struct SettingsNavigationPathKey: EnvironmentKey {
        static let defaultValue: Binding<NavigationPath>? = nil
    }

    public extension EnvironmentValues {
        var settingsNavigationPath: Binding<NavigationPath>? {
            get { self[SettingsNavigationPathKey.self] }
            set { self[SettingsNavigationPathKey.self] = newValue }
        }
    }
#endif

public extension Notification.Name {
    static let navigateToSettingsPage = Notification.Name("navigateToSettingsPage")
}

public enum SettingsPage: Hashable {
    case app, tools
    case core, packetTunnel, onDemandRules, profileOverride, remoteControl, sponsors
}

public struct SettingView: View {
    @EnvironmentObject private var environments: ExtensionEnvironments

    private enum Tabs: Int, CaseIterable, Identifiable {
        var id: Self {
            self
        }

        case app, core, packetTunnel, onDemandRules, profileOverride, remoteControl, sponsors

        #if os(macOS)
            var page: SettingsPage {
                switch self {
                case .app:
                    return .app
                case .core:
                    return .core
                case .packetTunnel:
                    return .packetTunnel
                case .onDemandRules:
                    return .onDemandRules
                case .profileOverride:
                    return .profileOverride
                case .remoteControl:
                    return .remoteControl
                case .sponsors:
                    return .sponsors
                }
            }
        #endif

        var label: some View {
            Label(title, systemImage: iconImage)
        }

        var title: String {
            switch self {
            case .app:
                return String(localized: "App")
            case .core:
                return String(localized: "Core")
            case .packetTunnel:
                return String(localized: "Packet Tunnel")
            case .onDemandRules:
                return String(localized: "On Demand Rules")
            case .profileOverride:
                return String(localized: "Profile Override")
            case .remoteControl:
                return String(localized: "Remote Control")
            case .sponsors:
                return String(localized: "Sponsors")
            }
        }

        private var iconImage: String {
            switch self {
            case .app:
                return "app.badge.fill"
            case .core:
                return "shippingbox.fill"
            case .packetTunnel:
                return "aspectratio.fill"
            case .onDemandRules:
                return "filemenu.and.selection"
            case .profileOverride:
                return "square.dashed.inset.filled"
            case .remoteControl:
                return "antenna.radiowaves.left.and.right"
            case .sponsors:
                return "heart.fill"
            }
        }

        @MainActor
        var contentView: some View {
            Group {
                switch self {
                case .app:
                    AppView()
                case .core:
                    CoreView()
                case .packetTunnel:
                    PacketTunnelView()
                case .onDemandRules:
                    OnDemandRulesView()
                case .profileOverride:
                    ProfileOverrideView()
                case .remoteControl:
                    RemoteControlView()
                case .sponsors:
                    SponsorsView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
            #if os(iOS)
                .background(Color(uiColor: .systemGroupedBackground))
            #endif
        }

        @MainActor
        var navigationLink: some View {
            #if os(macOS)
                FormNavigationLink(value: page) {
                    label
                }
            #else
                FormNavigationLink {
                    contentView
                } label: {
                    label
                }
            #endif
        }
    }

    #if os(macOS)
        @MainActor
        private static func destinationView(for page: SettingsPage) -> some View {
            Group {
                switch page {
                case .app:
                    AppView()
                case .tools:
                    ToolsView()
                case .core:
                    CoreView()
                case .packetTunnel:
                    PacketTunnelView()
                case .onDemandRules:
                    OnDemandRulesView()
                case .profileOverride:
                    ProfileOverrideView()
                case .remoteControl:
                    RemoteControlView()
                case .sponsors:
                    SponsorsView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        }
    #endif

    #if os(iOS)
        @State private var showRemoteControl = false
        @State private var showTools = false
    #endif

    public init() {}
    public var body: some View {
        FormView {
            Section {
                Tabs.app.navigationLink
                Tabs.core.navigationLink
                #if !os(tvOS)
                    Tabs.packetTunnel.navigationLink
                #endif
                Tabs.onDemandRules.navigationLink
                Tabs.profileOverride.navigationLink
                #if !os(tvOS)
                    remoteControlLink
                #endif
                #if JAILBREAK
                    FormNavigationLink {
                        JailbreakView()
                    } label: {
                        Label("Jailbreak", systemImage: "lock.shield.fill")
                    }
                #endif
            }
            #if os(iOS)
                Section("Activity & Diagnostics") {
                    FormNavigationLink {
                        GroupListView()
                            .navigationTitle("Proxy Groups")
                    } label: {
                        Label("Proxy Groups", systemImage: "point.3.connected.trianglepath.dotted")
                    }
                    FormNavigationLink {
                        ConnectionListView()
                            .navigationTitle("Connections")
                    } label: {
                        Label("Connections", systemImage: "arrow.left.arrow.right")
                    }
                    FormNavigationLink {
                        LogView()
                            .navigationTitle("Logs")
                            .navigationBarTitleDisplayMode(.inline)
                    } label: {
                        Label("Logs", systemImage: "text.alignleft")
                    }
                    NavigationLink(isActive: $showTools) {
                        ToolsView()
                            .navigationTitle("Diagnostics")
                    } label: {
                        Label("Diagnostics", systemImage: "wrench.and.screwdriver.fill")
                            .badge(
                                environmentsUnreadReportCount
                            )
                    }
                    .onReceive(NotificationCenter.default.publisher(for: .navigateToSettingsPage)) { notification in
                        guard let page = notification.object as? SettingsPage, page == .tools else { return }
                        showTools = true
                    }
                }
            #endif
            #if !os(tvOS)
                Section("About") {
                    #if os(iOS)
                        NavigationLink {
                            PrivacyDisclosureView()
                        } label: {
                            Label("Data & Privacy", systemImage: "hand.raised.fill")
                        }
                    #endif
                    Link(destination: URL(string: String(localized: "https://sing-box.sagernet.org/"))!) {
                        Label("Documentation", systemImage: "doc.on.doc.fill")
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(.accentColor)
                    .contextMenu {
                        Link(destination: URL(string: String(localized: "https://sing-box.sagernet.org/changelog/"))!) {
                            Text("Changelog")
                        }
                        Link(destination: URL(string: String(localized: "https://sing-box.sagernet.org/configuration/"))!) {
                            Text("Configuration")
                        }
                    }
                    Link(destination: URL(string: String("https://github.com/The-boond/ClashNL"))!) {
                        Label("ClashNL Source Code", systemImage: "chevron.left.forwardslash.chevron.right")
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(.accentColor)
                    .contextMenu {
                        Link(destination: URL(string: String("https://github.com/The-boond/ClashNL/releases"))!) {
                            Text("Releases")
                        }
                    }
                    RequestReviewButton {
                        Label("Rate on the App Store", systemImage: "text.bubble.fill")
                    }
                    #if os(macOS)
                        if Variant.useSystemExtension {
                            Tabs.sponsors.navigationLink
                        }
                    #endif
                }
            #endif
        }
        #if os(macOS)
        .formNavigationDestination(for: SettingsPage.self) { page in
            Self.destinationView(for: page)
        }
        #endif
    }

    private var environmentsUnreadReportCount: Int {
        environments.totalUnreadReportCount
    }

    #if !os(tvOS)
        private var remoteControlLink: some View {
            #if os(iOS)
                NavigationLink(isActive: $showRemoteControl) {
                    Tabs.remoteControl.contentView
                } label: {
                    Tabs.remoteControl.label
                }
                .onReceive(NotificationCenter.default.publisher(for: .navigateToSettingsPage)) { notification in
                    guard let page = notification.object as? SettingsPage, page == .remoteControl else { return }
                    Task {
                        // Wait for the tab switch to install this view before pushing.
                        try? await Task.sleep(nanoseconds: NSEC_PER_MSEC * 300)
                        showRemoteControl = true
                    }
                }
            #else
                Tabs.remoteControl.navigationLink
            #endif
        }
    #endif
}
