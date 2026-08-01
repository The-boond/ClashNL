import Foundation
import SwiftUI

public enum AppAppearanceMode: String, CaseIterable, Codable, Identifiable {
    case system
    case light
    case dark

    public var id: String {
        rawValue
    }

    public init(storedValue: String) {
        self = Self(rawValue: storedValue) ?? .system
    }

    public var title: LocalizedStringKey {
        switch self {
        case .system:
            return "System Default"
        case .light:
            return "Light Theme"
        case .dark:
            return "Dark Theme"
        }
    }

    public var preferredColorScheme: ColorScheme? {
        switch self {
        case .system:
            return nil
        case .light:
            return .light
        case .dark:
            return .dark
        }
    }
}

public extension Notification.Name {
    static let appAppearanceModeDidChange = Notification.Name("appAppearanceModeDidChange")
}
