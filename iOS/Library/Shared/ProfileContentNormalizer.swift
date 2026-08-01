import Foundation
import Libbox

public enum ProfileContentNormalizer {
    public static func normalize(_ content: String) throws -> String {
        let trimmedContent = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedContent.isEmpty else {
            throw NSError(
                domain: "ProfileContentNormalizer",
                code: 3,
                userInfo: [NSLocalizedDescriptionKey: String(localized: "The profile is empty")]
            )
        }

        var nativeError: NSError?
        LibboxCheckConfig(trimmedContent, &nativeError)
        if nativeError == nil {
            return trimmedContent
        }

        if let migration = SingBoxConfigMigrator.migrate(trimmedContent) {
            var migratedError: NSError?
            LibboxCheckConfig(migration.content, &migratedError)
            if migratedError == nil {
                return migration.content
            }
            let detail = migratedError?.localizedDescription
                ?? nativeError?.localizedDescription
                ?? String(localized: "Unknown configuration error")
            throw NSError(
                domain: "ProfileContentNormalizer",
                code: 4,
                userInfo: [
                    NSLocalizedDescriptionKey:
                        String(localized: "The sing-box configuration is incompatible with this app: \(detail)"),
                ]
            )
        }

        var conversionError: NSError?
        let converted = LibboxConvertClashConfig(trimmedContent, &conversionError)
        if let conversionError {
            let nativeDetail = nativeError?.localizedDescription
                ?? String(localized: "Unknown configuration error")
            throw NSError(
                domain: "ProfileContentNormalizer",
                code: 5,
                userInfo: [
                    NSLocalizedDescriptionKey:
                        "Configuration is neither valid sing-box JSON nor supported Clash/Mihomo YAML. sing-box: \(nativeDetail); Clash/Mihomo: \(conversionError.localizedDescription)",
                ]
            )
        }
        guard let convertedContent = converted?.value else {
            throw NSError(
                domain: "ProfileContentNormalizer",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Clash profile conversion returned no content"]
            )
        }

        var validationError: NSError?
        LibboxCheckConfig(convertedContent, &validationError)
        if let validationError {
            throw validationError
        }
        return convertedContent
    }

    public static func looksLikeClashYAML(_ content: String) -> Bool {
        let lowercased = content.lowercased()
        return lowercased.contains("\nproxies:") ||
            lowercased.hasPrefix("proxies:") ||
            lowercased.contains("\nproxy-groups:") ||
            lowercased.hasPrefix("proxy-groups:")
    }
}
