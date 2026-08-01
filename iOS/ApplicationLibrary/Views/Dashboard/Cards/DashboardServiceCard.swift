#if os(iOS)

    import Foundation
    import Library
    import NetworkExtension
    import SwiftUI

    @MainActor
    public struct DashboardServiceCard: View {
        @EnvironmentObject private var profile: ExtensionProfile
        @ObservedObject private var commandClient: CommandClient

        @Binding private var profileList: [ProfilePreview]
        @Binding private var selectedProfileID: Int64

        public init(
            profileList: Binding<[ProfilePreview]>,
            selectedProfileID: Binding<Int64>,
            commandClient: CommandClient
        ) {
            _profileList = profileList
            _selectedProfileID = selectedProfileID
            _commandClient = ObservedObject(wrappedValue: commandClient)
        }

        private var selectedProfile: ProfilePreview? {
            profileList.first { $0.id == selectedProfileID }
        }

        private var currentNode: String {
            guard profile.status.isConnected else {
                return String(localized: "No active node")
            }
            return commandClient.groups?
                .first(where: { $0.selectable && !$0.selected.isEmpty })?
                .selected ?? String(localized: "No active node")
        }

        private var currentMode: String {
            let mode = commandClient.clashMode
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return mode.isEmpty ? String(localized: "Not available") : mode
        }

        public var body: some View {
            DashboardCardView(title: "") {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(alignment: .center, spacing: 12) {
                        DashboardCardHeader(
                            icon: profile.status.isConnected ? "checkmark.shield.fill" : "shield.fill",
                            title: "VPN Control",
                            accent: statusColor
                        )
                        Spacer()
                        statusBadge
                    }

                    Text(statusDescription)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)

                    VStack(spacing: 10) {
                        informationRow(
                            icon: "cloud.fill",
                            label: String(localized: "Subscription"),
                            value: selectedProfile?.name ?? String(localized: "Not available"),
                            accent: .blue
                        )
                        informationRow(
                            icon: "network",
                            label: String(localized: "Current Node"),
                            value: currentNode,
                            accent: .green
                        )
                        informationRow(
                            icon: "arrow.triangle.branch",
                            label: String(localized: "Mode"),
                            value: currentMode,
                            accent: .purple
                        )
                    }

                    StartStopButton(
                        showsRuntimeDuration: true,
                        presentation: .prominent
                    )
                }
            }
        }

        private var statusBadge: some View {
            Text(statusLabel)
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundStyle(statusColor)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(statusColor.opacity(0.12))
                .clipShape(Capsule())
        }

        private func informationRow(
            icon: String,
            label: String,
            value: String,
            accent: Color
        ) -> some View {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(accent)
                    .frame(width: 28, height: 28)
                    .background(accent.opacity(0.1))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                Text(label)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 12)
                Text(value)
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .lineLimit(1)
            }
        }

        private var statusColor: Color {
            switch profile.status {
            case .connected:
                return .green
            case .connecting, .reasserting:
                return .orange
            case .disconnecting:
                return .orange
            case .disconnected:
                return .blue
            default:
                return .red
            }
        }

        private var statusLabel: LocalizedStringKey {
            switch profile.status {
            case .connected:
                return "Started"
            case .connecting:
                return "Starting"
            case .reasserting:
                return "Reasserting"
            case .disconnecting:
                return "Stopping"
            case .disconnected:
                return "Stopped"
            default:
                return "Unknown"
            }
        }

        private var statusDescription: LocalizedStringKey {
            switch profile.status {
            case .connected:
                return "VPN protection is active. Your network traffic is using the selected subscription."
            case .connecting, .reasserting:
                return "Preparing the VPN tunnel and applying the selected configuration."
            case .disconnecting:
                return "Stopping the VPN tunnel."
            default:
                return "Tap Start to enable VPN protection with the selected subscription."
            }
        }
    }

#endif
