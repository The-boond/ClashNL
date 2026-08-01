import Foundation
import Library
import SwiftUI

public enum NavigationPage: Int, CaseIterable, Identifiable {
    public var id: Self {
        self
    }

    case dashboard
    #if os(iOS)
        case subscriptions
    #endif
    #if os(macOS)
        case groups
        case connections
    #endif
    case logs
    case tools
    case settings

    public static var allCases: [NavigationPage] {
        #if os(iOS)
            return [.subscriptions, .logs, .dashboard, .tools, .settings]
        #elseif os(macOS)
            return [.dashboard, .groups, .connections, .logs, .tools, .settings]
        #else
            return [.dashboard, .logs, .tools, .settings]
        #endif
    }
}

public extension NavigationPage {
    init?(snapshotValue: String) {
        switch snapshotValue.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        #if os(iOS)
            case "subscriptions":
                self = .subscriptions
        #endif
        case "dashboard":
            self = .dashboard
        case "logs":
            self = .logs
        case "tools":
            self = .tools
        case "settings":
            self = .settings
        #if os(macOS)
            case "groups":
                self = .groups
            case "connections":
                self = .connections
        #endif
        default:
            return nil
        }
    }

    #if os(macOS)
        static var macosDefaultPages: [NavigationPage] {
            [.logs, .tools, .settings]
        }
    #endif

    var label: some View {
        Label(title, systemImage: iconImage)
            .tint(.textColor)
    }

    var title: String {
        switch self {
        case .dashboard:
            return String(localized: "Dashboard")
        #if os(iOS)
            case .subscriptions:
                return String(localized: "Subscriptions")
        #endif
        #if os(macOS)
            case .groups:
                return String(localized: "Groups")
            case .connections:
                return String(localized: "Connections")
        #endif
        case .logs:
            return String(localized: "Logs")
        case .tools:
            return String(localized: "Tools")
        case .settings:
            return String(localized: "Settings")
        }
    }

    private var iconImage: String {
        switch self {
        case .dashboard:
            return "gauge"
        #if os(iOS)
            case .subscriptions:
                return "cloud.fill"
        #endif
        #if os(macOS)
            case .groups:
                return "rectangle.3.group.fill"
            case .connections:
                return "list.bullet.rectangle.portrait.fill"
        #endif
        case .logs:
            return "list.bullet.rectangle"
        case .tools:
            return "terminal.fill"
        case .settings:
            return "gear.circle.fill"
        }
    }

    @MainActor
    var contentView: some View {
        Group {
            switch self {
            case .dashboard:
                DashboardView()
            #if os(iOS)
                case .subscriptions:
                    SubscriptionsView()
            #endif
            #if os(macOS)
                case .groups:
                    GroupListView()
                case .connections:
                    ConnectionListView()
            #endif
            case .logs:
                LogView()
            case .tools:
                ToolsView()
            case .settings:
                SettingView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        #if os(iOS)
            .background(Color(uiColor: .systemGroupedBackground))
        #endif
    }

    #if os(macOS)
        @MainActor
        func visible(_ profile: ExtensionProfile?) -> Bool {
            switch self {
            case .groups, .connections:
                return profile?.status.isConnectedStrict == true
            default:
                return true
            }
        }
    #endif
}
