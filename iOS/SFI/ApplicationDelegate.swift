import ApplicationLibrary
import FileProvider
import Foundation
import Libbox
import Library
import Network
import UIKit
import UserNotifications

class ApplicationDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private enum HomeScreenShortcutAction: String, CaseIterable {
        case toggle = "clashnl.shortcut.toggle"
        case start = "clashnl.shortcut.start"
        case pause = "clashnl.shortcut.pause"

        var title: String {
            switch self {
            case .toggle:
                return String(localized: "Toggle")
            case .start:
                return String(localized: "Start")
            case .pause:
                return String(localized: "Pause")
            }
        }
    }

    private var profileServer: ProfileServer?
    private var reportTransferServer: ReportTransferServer?
    private var activated = false
    private var pendingHomeScreenShortcut: HomeScreenShortcutAction?

    func application(_: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        if let shortcutItem = launchOptions?[.shortcutItem] as? UIApplicationShortcutItem {
            pendingHomeScreenShortcut = HomeScreenShortcutAction(rawValue: shortcutItem.type)
        }
        LibboxPrepareCrashSignalHandlers()
        NativeCrashReporter.installForCurrentProcess()
        LibboxReinstallCrashSignalHandlers()
        NSLog("Here I stand")
        let options = LibboxSetupOptions()
        options.basePath = FilePath.sharedDirectory.relativePath
        options.workingPath = FilePath.workingDirectory.relativePath
        options.tempPath = FilePath.cacheDirectory.relativePath
        options.crashReportSource = "Application"
        var setupError: NSError?
        LibboxSetup(options, &setupError)
        if let setupError {
            NSLog("setup service error: \(setupError.localizedDescription)")
        }
        do {
            try ApplicationLocale.apply()
        } catch {
            NSLog("failed to set locale: \(error)")
        }
        registerHomeScreenShortcuts()
        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.setNotificationCategories([
            UNNotificationCategory(
                identifier: "OPEN_URL",
                actions: [
                    UNNotificationAction(identifier: "COPY_URL", title: "Copy URL", options: .foreground, icon: UNNotificationActionIcon(systemImageName: "clipboard.fill")),
                    UNNotificationAction(identifier: "OPEN_URL", title: "Open", options: .foreground, icon: UNNotificationActionIcon(systemImageName: "safari.fill")),
                ],
                intentIdentifiers: []
            ),
        ])
        notificationCenter.delegate = self
        #if JAILBREAK
            // usernotificationsd only registers the live BulletinBoard data provider that gates
            // delivery once requestAuthorization runs from the host app; it never reaches that path
            // for a section that is already authorized, so the call must not be skipped.
            Task {
                do {
                    _ = try await notificationCenter.requestAuthorization(options: [.alert, .sound])
                } catch {
                    NSLog("request notification authorization error: \(error.localizedDescription)")
                }
            }
        #endif
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(activateAfterPrivacyDisclosure),
            name: .clashNlPrivacyDisclosureAccepted,
            object: nil
        )
        if UserDefaults.standard.bool(forKey: "privacyDisclosureAccepted") {
            activateAfterPrivacyDisclosure()
        }
        return true
    }

    func application(_: UIApplication, performActionFor shortcutItem: UIApplicationShortcutItem, completionHandler: @escaping (Bool) -> Void) {
        guard let action = HomeScreenShortcutAction(rawValue: shortcutItem.type) else {
            completionHandler(false)
            return
        }
        guard UserDefaults.standard.bool(forKey: "privacyDisclosureAccepted") else {
            pendingHomeScreenShortcut = action
            completionHandler(true)
            return
        }
        performHomeScreenShortcut(action, completionHandler: completionHandler)
    }

    func userNotificationCenter(_: UNUserNotificationCenter, willPresent _: UNNotification) async -> UNNotificationPresentationOptions {
        .banner
    }

    func userNotificationCenter(_: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if let url = response.notification.request.content.userInfo["OPEN_URL"] as? String {
            switch response.actionIdentifier {
            case "COPY_URL":
                UIPasteboard.general.string = url
            default:
                await UIApplication.shared.open(URL(string: url)!)
            }
        }
    }

    @objc private func activateAfterPrivacyDisclosure() {
        guard !activated else {
            return
        }
        activated = true
        do {
            try UIProfileUpdateTask.configure()
            NSLog("setup background task success")
        } catch {
            NSLog("setup background task error: \(error.localizedDescription)")
        }
        Task {
            if UIDevice.current.userInterfaceIdiom == .phone {
                await requestNetworkPermission()
            }
            await setupBackground()
        }
        if let pendingHomeScreenShortcut {
            self.pendingHomeScreenShortcut = nil
            performHomeScreenShortcut(pendingHomeScreenShortcut) { success in
                if !success {
                    NSLog("home screen shortcut action did not complete")
                }
            }
        }
    }

    private func registerHomeScreenShortcuts() {
        UIApplication.shared.shortcutItems = HomeScreenShortcutAction.allCases.map { action in
            UIApplicationShortcutItem(
                type: action.rawValue,
                localizedTitle: action.title,
                localizedSubtitle: nil,
                icon: nil,
                userInfo: nil
            )
        }
    }

    private func performHomeScreenShortcut(_ action: HomeScreenShortcutAction, completionHandler: @escaping (Bool) -> Void) {
        Task { @MainActor in
            do {
                guard let profile = try await ExtensionProfile.load() else {
                    completionHandler(false)
                    return
                }
                switch action {
                case .toggle:
                    if profile.status.isConnected {
                        try await profile.stop()
                    } else {
                        try await profile.start()
                    }
                case .start:
                    if !profile.status.isConnected {
                        try await profile.start()
                    }
                case .pause:
                    if profile.status.isConnected {
                        try await profile.stop()
                    }
                }
                completionHandler(true)
            } catch {
                NSLog("home screen shortcut action \(action.rawValue) error: \(error.localizedDescription)")
                completionHandler(false)
            }
        }
    }

    private nonisolated func setupBackground() async {
        if #available(iOS 16.0, *) {
            do {
                let profileServer = try ProfileServer()
                profileServer.start()
                await MainActor.run {
                    self.profileServer = profileServer
                }
                NSLog("started profile server")
            } catch {
                NSLog("setup profile server error: \(error.localizedDescription)")
            }
            do {
                let reportTransferServer = try ReportTransferServer()
                reportTransferServer.start()
                await MainActor.run {
                    self.reportTransferServer = reportTransferServer
                }
                NSLog("started report transfer server")
            } catch {
                NSLog("setup report transfer server error: \(error.localizedDescription)")
            }
            registerFileProviderDomain()
        }
    }

    @available(iOS 16.0, *)
    private nonisolated func registerFileProviderDomain() {
        let domain = NSFileProviderDomain(
            identifier: NSFileProviderDomainIdentifier(AppConfiguration.fileProviderDomainID),
            displayName: "ClashNl"
        )
        NSFileProviderManager.add(domain) { error in
            if let error {
                NSLog("Failed to add file provider domain: \(error)")
            }
        }
    }

    private nonisolated func requestNetworkPermission() async {
        if await SharedPreferences.networkPermissionRequested.get() {
            return
        }
        if !DeviceCensorship.isChinaDevice() {
            await SharedPreferences.networkPermissionRequested.set(true)
            return
        }
        URLSession.shared.dataTask(with: URL(string: "http://captive.apple.com")!) { _, response, _ in
            if let response = response as? HTTPURLResponse {
                if response.statusCode == 200 {
                    Task {
                        await SharedPreferences.networkPermissionRequested.set(true)
                    }
                }
            }
        }.resume()
    }
}

extension Notification.Name {
    static let clashNlPrivacyDisclosureAccepted = Notification.Name("ClashNlPrivacyDisclosureAccepted")
}
