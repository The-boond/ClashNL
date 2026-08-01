import Foundation
import Libbox
import Library
import SwiftUI

@MainActor
public final class OverviewViewModel: BaseViewModel {
    @Published public var reasserting = false

    public func switchProfile(_ profileID: Int64, profile: ExtensionProfile, environments: ExtensionEnvironments) async {
        guard !reasserting, profile.status.isSwitchable else {
            return
        }
        reasserting = true
        defer { reasserting = false }

        await SharedPreferences.selectedProfileID.set(profileID)
        environments.selectedProfileUpdate.send()

        if profile.status.isConnected {
            do {
                try await profile.reloadService()
            } catch {
                alert = AlertState(action: "reload service", error: error)
            }
        }
    }

    public nonisolated func setSystemProxyEnabled(_ enabled: Bool, profile: ExtensionProfile) async {
        do {
            await SharedPreferences.systemProxyEnabled.set(enabled)
            if enabled {
                try LibboxNewStandaloneCommandClient()!.setSystemProxyEnabled(enabled)
            } else {
                await MainActor.run { reasserting = true }
                try await profile.restart()
                await MainActor.run { reasserting = false }
            }
        } catch {
            await MainActor.run {
                reasserting = false
                alert = AlertState(action: "update system proxy settings", error: error)
            }
        }
    }
}
