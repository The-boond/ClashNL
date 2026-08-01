import Foundation

public struct SubscriptionMetadata: Equatable, Sendable {
    public let upload: Int64?
    public let download: Int64?
    public let total: Int64?
    public let expireAt: Date?
    public let updateIntervalMinutes: Int32?
    public let webPageURL: String?

    public init(
        upload: Int64? = nil,
        download: Int64? = nil,
        total: Int64? = nil,
        expireAt: Date? = nil,
        updateIntervalMinutes: Int32? = nil,
        webPageURL: String? = nil
    ) {
        self.upload = upload
        self.download = download
        self.total = total
        self.expireAt = expireAt
        self.updateIntervalMinutes = updateIntervalMinutes
        self.webPageURL = webPageURL
    }

    public var isEmpty: Bool {
        upload == nil &&
            download == nil &&
            total == nil &&
            expireAt == nil &&
            updateIntervalMinutes == nil &&
            webPageURL == nil
    }
}

public enum SubscriptionMetadataParser {
    private static let supportedKeys = [
        "subscription-userinfo",
        "profile-update-interval",
        "profile-web-page-url",
    ]
    private static let maximumCommentLines = 64

    public static func parse(headers: [String: String], body: String? = nil) -> SubscriptionMetadata? {
        var metadataValues = parseCommentMetadata(body)
        for (key, value) in headers {
            let normalizedKey = key.lowercased()
            let normalizedValue = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if supportedKeys.contains(normalizedKey), !normalizedValue.isEmpty {
                metadataValues[normalizedKey] = normalizedValue
            }
        }

        var userInfoValues = [String: String]()
        for item in metadataValues["subscription-userinfo", default: ""].split(separator: ";") {
            guard let separator = item.firstIndex(of: "=") else {
                continue
            }
            let key = item[..<separator].trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let value = item[item.index(after: separator)...].trimmingCharacters(in: .whitespacesAndNewlines)
            if !key.isEmpty {
                userInfoValues[key] = value
            }
        }

        let metadata = SubscriptionMetadata(
            upload: nonnegativeInt64(userInfoValues["upload"]),
            download: nonnegativeInt64(userInfoValues["download"]),
            total: nonnegativeInt64(userInfoValues["total"]),
            expireAt: expirationDate(userInfoValues["expire"]),
            updateIntervalMinutes: updateIntervalMinutes(metadataValues["profile-update-interval"]),
            webPageURL: safeWebPageURL(metadataValues["profile-web-page-url"])
        )
        return metadata.isEmpty ? nil : metadata
    }

    private static func parseCommentMetadata(_ body: String?) -> [String: String] {
        guard let body, !body.isEmpty else {
            return [:]
        }
        var result = [String: String]()
        for line in body.split(omittingEmptySubsequences: false, whereSeparator: \.isNewline).prefix(maximumCommentLines) {
            let trimmedLine = line.trimmingCharacters(in: .whitespaces)
            guard trimmedLine.hasPrefix("#") else {
                continue
            }
            let comment = trimmedLine.dropFirst().trimmingCharacters(in: .whitespaces)
            guard let separator = comment.firstIndex(of: ":") else {
                continue
            }
            let key = comment[..<separator].trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let value = comment[comment.index(after: separator)...].trimmingCharacters(in: .whitespacesAndNewlines)
            if supportedKeys.contains(key), !value.isEmpty {
                result[key] = value
            }
        }
        return result
    }

    private static func nonnegativeInt64(_ value: String?) -> Int64? {
        guard let value, let parsed = Int64(value), parsed >= 0 else {
            return nil
        }
        return parsed
    }

    private static func expirationDate(_ value: String?) -> Date? {
        guard let seconds = nonnegativeInt64(value), seconds > 0 else {
            return nil
        }
        let timestamp = TimeInterval(seconds)
        guard timestamp.isFinite else {
            return nil
        }
        return Date(timeIntervalSince1970: timestamp)
    }

    private static func updateIntervalMinutes(_ value: String?) -> Int32? {
        guard let value, let hours = Int64(value.trimmingCharacters(in: .whitespacesAndNewlines)), hours > 0 else {
            return nil
        }
        let maximumHours = Int64(Int32.max) / 60
        if hours > maximumHours {
            return Int32.max
        }
        return Int32(max(15, hours * 60))
    }

    private static func safeWebPageURL(_ value: String?) -> String? {
        guard let value else {
            return nil
        }
        let normalizedValue = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            let components = URLComponents(string: normalizedValue),
            let scheme = components.scheme?.lowercased(),
            scheme == "http" || scheme == "https",
            components.host?.isEmpty == false,
            components.user == nil,
            components.password == nil
        else {
            return nil
        }
        return normalizedValue
    }
}
