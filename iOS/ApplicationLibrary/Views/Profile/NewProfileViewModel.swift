import Foundation
import Libbox
import Library
import SwiftUI

@MainActor
public final class NewProfileViewModel: BaseViewModel {
    @Published public var isSaving = false
    @Published public var createSucceeded = false
    @Published public var profileName = ""
    #if !os(tvOS)
        @Published public var profileType = ProfileType.local
    #else
        @Published public var profileType = ProfileType.remote
    #endif
    @Published public var fileImport = false
    @Published public var fileURL: URL?
    @Published public var remotePath = ""
    @Published public var autoUpdate = true
    @Published public var autoUpdateInterval: Int32 = 60
    @Published public var pickerPresented = false

    public let isImport: Bool

    public init(
        importRequest: NewProfileView.ImportRequest? = nil,
        localImportRequest: NewProfileView.LocalImportRequest? = nil,
        initialProfileType: ProfileType? = nil
    ) {
        isImport = importRequest != nil
        super.init()
        if let importRequest {
            profileType = .remote
            remotePath = importRequest.url.trimmingCharacters(in: .whitespacesAndNewlines)
            let requestedName = importRequest.name.trimmingCharacters(in: .whitespacesAndNewlines)
            profileName = requestedName.isEmpty
                ? RemoteProfileURLPolicy.suggestedName(for: remotePath)
                : requestedName
        } else if let localImportRequest {
            profileName = localImportRequest.name
            profileType = .local
            fileImport = true
            fileURL = localImportRequest.fileURL
        } else if let initialProfileType {
            profileType = initialProfileType
        }
    }

    public func resetFields() {
        profileName = ""
        profileType = .local
        fileImport = false
        fileURL = nil
        remotePath = ""
    }

    public func createProfile(
        environments: ExtensionEnvironments,
        dismiss: DismissAction? = nil,
        onSuccess: ((Profile) async -> Void)? = nil,
        sendUpdateNotification: Bool = true
    ) async {
        isSaving = true
        defer { isSaving = false }

        alert = nil
        profileName = profileName.trimmingCharacters(in: .whitespacesAndNewlines)
        remotePath = remotePath.trimmingCharacters(in: .whitespacesAndNewlines)

        if profileType == .icloud, remotePath.isEmpty {
            alert = AlertState(errorMessage: String(localized: "Missing path"))
            return
        }

        if profileType == .remote, remotePath.isEmpty {
            alert = AlertState(errorMessage: String(localized: "Missing URL"))
            return
        }

        if profileType == .remote {
            guard let normalizedURL = RemoteProfileURLPolicy.normalize(remotePath) else {
                alert = AlertState(errorMessage: String(localized: "Only HTTP and HTTPS URLs are allowed"))
                return
            }
            remotePath = normalizedURL
            if profileName.isEmpty {
                profileName = RemoteProfileURLPolicy.suggestedName(for: normalizedURL)
            }
        }

        guard !profileName.isEmpty else {
            alert = AlertState(errorMessage: String(localized: "Missing profile name"))
            return
        }

        let createdProfile: Profile
        do {
            createdProfile = try await createProfileBackground()
        } catch {
            alert = AlertState(action: "create profile", error: error)
            return
        }

        if let onSuccess {
            await onSuccess(createdProfile)
        }
        if sendUpdateNotification {
            environments.profileUpdate.send()
        }
        createSucceeded = true
        dismiss?()

        #if os(macOS)
            resetFields()
        #endif
    }

    private nonisolated func createProfileBackground() async throws -> Profile {
        let nextProfileID = try await ProfileManager.nextID()

        var savePath = ""
        var remoteURL: String?
        var lastUpdated: Date?
        var subscriptionMetadata: SubscriptionMetadata?

        let profileName = await profileName
        let profileType = await profileType
        let fileImport = await fileImport
        let fileURL = await fileURL
        let remotePath = await remotePath
        let autoUpdate = await autoUpdate
        let autoUpdateInterval = await autoUpdateInterval

        if profileType == .local {
            let profileConfigDirectory = FilePath.sharedDirectory.appendingPathComponent("configs", isDirectory: true)
            let profileConfig = profileConfigDirectory.appendingPathComponent("config_\(nextProfileID).json")
            try await BlockingIO.run {
                let importedContent: String
                if fileImport {
                    guard let fileURL else {
                        throw NSError(domain: "NewProfileViewModel", code: 0, userInfo: [NSLocalizedDescriptionKey: String(localized: "Missing file")])
                    }
                    importedContent = try fileURL.withRequiredSecurityScopedAccess(
                        or: NSError(domain: "NewProfileViewModel", code: 0, userInfo: [NSLocalizedDescriptionKey: String(localized: "Missing access to selected file")])
                    ) {
                        try String(contentsOf: fileURL)
                    }
                } else {
                    importedContent = "{}"
                }
                let configContent = try ProfileContentNormalizer.normalize(importedContent)
                try FileManager.default.createDirectory(at: profileConfigDirectory, withIntermediateDirectories: true)
                try configContent.write(to: profileConfig, atomically: true, encoding: .utf8)
            }
            savePath = "configs/config_\(nextProfileID).json"
        } else if profileType == .icloud {
            let iCloudDirectory = FilePath.iCloudDirectory
            try await BlockingIO.run {
                if !FileManager.default.fileExists(atPath: iCloudDirectory.path) {
                    try FileManager.default.createDirectory(at: iCloudDirectory, withIntermediateDirectories: true)
                }
                let saveURL = iCloudDirectory.appendingPathComponent(remotePath, isDirectory: false)
                do {
                    _ = try String(contentsOf: saveURL)
                } catch {
                    try "{}".write(to: saveURL, atomically: true, encoding: .utf8)
                }
            }
            savePath = remotePath
        } else if profileType == .remote {
            let response = try await HTTPClient.getSubscriptionAsync(remotePath)
            subscriptionMetadata = SubscriptionMetadataParser.parse(headers: response.headers, body: response.content)
            let remoteContent = try await BlockingIO.run {
                try ProfileContentNormalizer.normalize(response.content)
            }
            let profileConfigDirectory = FilePath.sharedDirectory.appendingPathComponent("configs", isDirectory: true)
            let profileConfig = profileConfigDirectory.appendingPathComponent("config_\(nextProfileID).json")
            try await BlockingIO.run {
                try FileManager.default.createDirectory(at: profileConfigDirectory, withIntermediateDirectories: true)
                try remoteContent.write(to: profileConfig, atomically: true, encoding: .utf8)
            }
            savePath = "configs/config_\(nextProfileID).json"
            remoteURL = remotePath
            lastUpdated = .now
        }

        let uniqueProfileName = try await ProfileManager.uniqueName(profileName)

        // Create Profile object - GRDB will set its ID after insertion
        let profile = Profile(
            name: uniqueProfileName,
            type: profileType,
            path: savePath,
            remoteURL: remoteURL,
            autoUpdate: autoUpdate,
            autoUpdateInterval: autoUpdateInterval,
            lastUpdated: lastUpdated,
            subscriptionUpload: subscriptionMetadata?.upload,
            subscriptionDownload: subscriptionMetadata?.download,
            subscriptionTotal: subscriptionMetadata?.total,
            subscriptionExpireAt: subscriptionMetadata?.expireAt,
            subscriptionUpdateInterval: subscriptionMetadata?.updateIntervalMinutes ?? 0,
            subscriptionWebPageURL: subscriptionMetadata?.webPageURL
        )
        try await ProfileManager.create(profile)

        if profileType == .remote {
            #if os(iOS) || os(tvOS)
                try UIProfileUpdateTask.configure()
            #else
                try await ProfileUpdateTask.configure()
            #endif
        }

        // Return the profile object which now has its ID set by GRDB
        return profile
    }
}
