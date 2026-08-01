import Foundation
import Libbox

public struct HTTPTextResponse: Sendable {
    public let content: String
    public let headers: [String: String]
}

public class HTTPClient {
    private static let subscriptionHeaderNames = [
        "subscription-userinfo",
        "profile-update-interval",
        "profile-web-page-url",
    ]

    private static var userAgent: String {
        var userAgent = Variant.applicationName
        userAgent += "/"
        userAgent += Bundle.main.version
        userAgent += " (Build "
        userAgent += Bundle.main.versionNumber
        userAgent += "; sing-box "
        userAgent += LibboxVersion()
        userAgent += "; language "
        userAgent += ApplicationLocale.preferredIdentifier
        userAgent += ")"
        return userAgent
    }

    private static var subscriptionUserAgent: String {
        "sing-box/\(LibboxVersion())"
    }

    private let client: any LibboxHTTPClientProtocol

    public init() {
        client = LibboxNewHTTPClient()!
        client.modernTLS()
    }

    public func get(
        _ url: String?,
        headers: [String: String] = [:],
        userAgent: String? = nil
    ) throws -> HTTPTextResponse {
        #if DEBUG
            precondition(!Thread.isMainThread, "HTTPClient.get(...) must not be called on the main thread")
        #endif
        let request = client.newRequest()!
        request.setUserAgent(userAgent ?? HTTPClient.userAgent)
        for (key, value) in headers {
            request.setHeader(key, value: value)
        }
        try request.setURL(url)
        let response = try request.execute()
        let content = try response.getContent()
        var responseHeaders = [String: String]()
        for key in Self.subscriptionHeaderNames {
            let value = response.getHeader(key)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !value.isEmpty {
                responseHeaders[key] = value
            }
        }
        return HTTPTextResponse(content: content.value, headers: responseHeaders)
    }

    public func getString(_ url: String?, headers: [String: String] = [:]) throws -> String {
        try get(url, headers: headers).content
    }

    public func getSubscription(_ url: String?) throws -> HTTPTextResponse {
        try get(url, userAgent: HTTPClient.subscriptionUserAgent)
    }

    public func getAsync(_ url: String?) async throws -> HTTPTextResponse {
        try await Self.getAsync(url)
    }

    public static func getAsync(_ url: String?) async throws -> HTTPTextResponse {
        try await BlockingIO.run {
            try HTTPClient().get(url)
        }
    }

    public static func getSubscriptionAsync(_ url: String?) async throws -> HTTPTextResponse {
        try await BlockingIO.run {
            try HTTPClient().getSubscription(url)
        }
    }

    public func getStringAsync(_ url: String?) async throws -> String {
        try await Self.getStringAsync(url)
    }

    public static func getStringAsync(_ url: String?) async throws -> String {
        try await BlockingIO.run {
            try HTTPClient().getString(url)
        }
    }

    public func writeTo(_ url: String?, path: String, progress: ((Int64, Int64) -> Void)? = nil) throws {
        #if DEBUG
            precondition(!Thread.isMainThread, "HTTPClient.writeTo(...) must not be called on the main thread")
        #endif
        let request = client.newRequest()!
        request.setUserAgent(HTTPClient.userAgent)
        try request.setURL(url)
        let response = try request.execute()
        if let progress {
            let handler = WriteToProgressHandler(progress)
            try response.writeTo(withProgress: path, handler: handler)
        } else {
            try response.write(to: path)
        }
    }

    public static func writeToAsync(_ url: String?, path: String, progress: ((Int64, Int64) -> Void)? = nil) async throws {
        try await BlockingIO.run {
            try HTTPClient().writeTo(url, path: path, progress: progress)
        }
    }

    deinit {
        client.close()
    }
}

private class WriteToProgressHandler: NSObject, LibboxHTTPResponseWriteToProgressHandlerProtocol {
    private let handler: (Int64, Int64) -> Void

    init(_ handler: @escaping (Int64, Int64) -> Void) {
        self.handler = handler
    }

    func update(_ progress: Int64, total: Int64) {
        handler(progress, total)
    }
}
