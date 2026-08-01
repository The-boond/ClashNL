import Foundation
import GRDB
import Libbox

public extension Profile {
    nonisolated func updateRemoteProfile() async throws {
        if type != .remote {
            return
        }
        guard let url = remoteURL,
              let normalizedURL = RemoteProfileURLPolicy.normalize(url)
        else {
            throw NSError(
                domain: "RemoteProfileURLPolicy",
                code: 1,
                userInfo: [
                    NSLocalizedDescriptionKey: String(localized: "Only HTTP and HTTPS URLs are allowed"),
                ]
            )
        }
        let response = try await HTTPClient.getSubscriptionAsync(normalizedURL)
        let metadata = SubscriptionMetadataParser.parse(headers: response.headers, body: response.content)
        let remoteContent = try await BlockingIO.run {
            try ProfileContentNormalizer.normalize(response.content)
        }
        let contentChanged: Bool
        do {
            let oldContent = try await readAsync()
            contentChanged = oldContent != remoteContent
        } catch {
            contentChanged = true
        }
        if contentChanged {
            try await writeAsync(remoteContent)
        }
        await MainActor.run {
            remoteURL = normalizedURL
            if let metadata {
                replaceSubscriptionMetadata(metadata)
            }
            lastUpdated = Date()
        }
        try await ProfileManager.update(self)
        if contentChanged {
            try await onProfileUpdated()
        }
    }

    nonisolated func onProfileUpdated() async throws {
        if await SharedPreferences.selectedProfileID.get() == id {
            if let profile = try? await ExtensionProfile.load() {
                if await profile.status == .connected {
                    try await profile.reloadService()
                }
            }
        }
    }
}

public extension Profile {
    @MainActor
    func replaceSubscriptionMetadata(_ metadata: SubscriptionMetadata?) {
        guard let metadata else {
            return
        }
        subscriptionUpload = metadata.upload ?? subscriptionUpload
        subscriptionDownload = metadata.download ?? subscriptionDownload
        subscriptionTotal = metadata.total ?? subscriptionTotal
        subscriptionExpireAt = metadata.expireAt ?? subscriptionExpireAt
        subscriptionUpdateInterval =
            metadata.updateIntervalMinutes ?? subscriptionUpdateInterval
        subscriptionWebPageURL = metadata.webPageURL ?? subscriptionWebPageURL
    }
}
