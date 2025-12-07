import Foundation
import Library
import SwiftUI

public struct OnDemandRulesView: View {
    @EnvironmentObject private var environments: ExtensionEnvironments
    @State private var isLoading = true
    @State private var alert: AlertState?
    @State private var alwaysOn = false

    public init() {}
    public var body: some View {
        Group {
            if isLoading {
                ProgressView().onAppear {
                    Task.detached {
                        await loadSettings()
                    }
                }
            } else {
                FormView {
                    FormToggle("Always On", """
                    Implement always-on via on-demand rules.

                    This should not be an intended use of the API, so you cannot disable VPN in system settings. To stop the service manually, use the in-app interface or simply delete the VPN profile.
                    """, $alwaysOn) { newValue in
                        await SharedPreferences.alwaysOn.set(newValue)
                        await restartService()
                    }

                    FormButton {
                        Task {
                            await SharedPreferences.resetOnDemandRules()
                            await restartService()
                            isLoading = true
                        }
                    } label: {
                        Label("Reset", systemImage: "eraser.fill")
                    }
                    .foregroundColor(.red)
                }
            }
        }
        .navigationTitle("On Demand Rules")
        .alert($alert)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private func restartService() async {
        guard let profile = environments.extensionProfile, profile.status.isConnected else {
            return
        }
        do {
            try await profile.restart()
        } catch {
            alert = AlertState(error: error)
        }
    }

    private func loadSettings() async {
        alwaysOn = await SharedPreferences.alwaysOn.get()
        isLoading = false
    }
}
