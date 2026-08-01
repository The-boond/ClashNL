import Foundation

/// Normalizes and bounds URLs used for remote subscription profiles.
///
/// Subscription URLs are fetched by the Libbox HTTP client, so the UI and
/// background update path should agree on the accepted schemes and host form.
public enum RemoteProfileURLPolicy {
    public static func normalize(_ value: String) -> String? {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty,
              let components = URLComponents(string: normalized),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.host,
              !host.isEmpty,
              components.user == nil,
              components.password == nil
        else {
            return nil
        }
        return normalized
    }

    public static func suggestedName(for value: String) -> String {
        guard let components = URLComponents(string: value),
              let host = components.host?.trimmingCharacters(in: .whitespacesAndNewlines),
              !host.isEmpty
        else {
            return String(localized: "Subscription")
        }
        return host
    }
}
