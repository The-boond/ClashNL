import ApplicationLibrary
import Foundation
import Library
import SwiftUI

@main
struct Application: App {
    @UIApplicationDelegateAdaptor private var appDelegate: ApplicationDelegate
    @StateObject private var environments = ExtensionEnvironments()
    @StateObject private var peerStore = TailscaleSSHPeerStore()
    @AppStorage("privacyDisclosureAccepted") private var privacyDisclosureAccepted = false

    init() {
        Task { @MainActor in
            ImportedFontStore.shared.bootstrap()
        }
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if privacyDisclosureAccepted {
                    MainView()
                } else {
                    PrivacyDisclosureView {
                        privacyDisclosureAccepted = true
                        NotificationCenter.default.post(name: .clashNlPrivacyDisclosureAccepted, object: nil)
                    }
                }
            }
            .environmentObject(environments)
            .environmentObject(peerStore)
        }
    }
}
