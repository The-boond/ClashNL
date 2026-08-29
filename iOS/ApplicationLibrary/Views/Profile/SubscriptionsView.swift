#if os(iOS)

    import Libbox
    import Library
    import SwiftUI

    @MainActor
    public struct SubscriptionsView: View {
        @EnvironmentObject private var environments: ExtensionEnvironments
        @StateObject private var coordinator = DashboardViewModel()
        @StateObject private var selectionCoordinator = OverviewViewModel()

        public init() {}

        public var body: some View {
            Group {
                if coordinator.isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            summaryCard
                            switchableProfileCard
                            ProxyGroupsShortcut(
                                commandClient: environments.commandClient,
                                isConnected: environments.extensionProfile?.status.isConnectedStrict == true
                            )
                        }
                        .padding()
                    }
                    .refreshable {
                        await coordinator.reload()
                    }
                }
            }
            .alert(combinedAlert)
            .onAppear {
                coordinator.setEnvironments(environments)
                environments.connect()
                Task {
                    await coordinator.reload()
                }
            }
            .onReceive(environments.profileUpdate) { _ in
                Task {
                    await coordinator.reload()
                }
            }
            .onReceive(environments.selectedProfileUpdate) { _ in
                Task {
                    await coordinator.updateSelectedProfile()
                }
            }
        }

        private var summaryCard: some View {
            DashboardCardView(title: "") {
                HStack(spacing: 12) {
                    DashboardCardHeader(
                        icon: "cloud.fill",
                        title: "Subscriptions",
                        accent: .accentColor
                    )
                    Spacer()
                    Text("\(coordinator.profileList.count)")
                        .font(.headline)
                        .foregroundStyle(Color.accentColor)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.accentColor.opacity(0.12))
                        .clipShape(Capsule())
                }
            }
        }

        private var combinedAlert: Binding<AlertState?> {
            Binding(
                get: {
                    selectionCoordinator.alert ?? coordinator.alert
                },
                set: { newValue in
                    guard case .none = newValue else {
                        return
                    }
                    selectionCoordinator.alert = nil
                    coordinator.alert = nil
                }
            )
        }

        private var selectedProfileBinding: Binding<Int64> {
            Binding(
                get: { coordinator.selectedProfileID },
                set: { profileID in
                    guard profileID != coordinator.selectedProfileID else {
                        return
                    }
                    if let profile = environments.extensionProfile {
                        guard profile.status.isSwitchable,
                              !selectionCoordinator.reasserting
                        else {
                            return
                        }
                    }
                    let previousProfileID = coordinator.selectedProfileID
                    coordinator.selectedProfileID = profileID
                    Task {
                        if let profile = environments.extensionProfile {
                            await selectionCoordinator.switchProfile(
                                profileID,
                                profile: profile,
                                environments: environments
                            )
                            if await SharedPreferences.selectedProfileID.get() != profileID {
                                coordinator.selectedProfileID = previousProfileID
                            }
                        } else {
                            await SharedPreferences.selectedProfileID.set(profileID)
                            environments.selectedProfileUpdate.send()
                        }
                    }
                }
            )
        }

        @ViewBuilder
        private var switchableProfileCard: some View {
            if let profile = environments.extensionProfile {
                SwitchableSubscriptionCard(
                    profile: profile,
                    selectionCoordinator: selectionCoordinator,
                    profileList: $coordinator.profileList,
                    selectedProfileID: selectedProfileBinding
                )
            } else {
                ProfileCard(
                    profileList: $coordinator.profileList,
                    selectedProfileID: selectedProfileBinding
                )
            }
        }
    }

    @MainActor
    private struct SwitchableSubscriptionCard: View {
        @ObservedObject var profile: ExtensionProfile
        @ObservedObject var selectionCoordinator: OverviewViewModel
        @Binding var profileList: [ProfilePreview]
        @Binding var selectedProfileID: Int64

        var body: some View {
            ProfileCard(
                profileList: $profileList,
                selectedProfileID: $selectedProfileID
            )
            .disabled(
                !profile.status.isSwitchable ||
                    selectionCoordinator.reasserting
            )
        }
    }

    @MainActor
    private struct ProxyGroupsShortcut: View {
        @ObservedObject var commandClient: CommandClient
        let isConnected: Bool

        private var selectableGroups: [LibboxOutboundGroup] {
            commandClient.groups?.filter(\.selectable) ?? []
        }

        var body: some View {
            Group {
                if isConnected {
                    NavigationLink {
                        GroupListView()
                            .navigationTitle("Proxy Groups")
                    } label: {
                        cardContent
                    }
                    .buttonStyle(.plain)
                } else {
                    cardContent
                        .opacity(0.72)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityHint(
                isConnected
                    ? Text("Opens the proxy groups and nodes in the active subscription.")
                    : Text("Connect the VPN to view proxy groups and nodes.")
            )
        }

        private var cardContent: some View {
            DashboardCardView(title: "") {
                HStack(spacing: 12) {
                    Image(systemName: "point.3.connected.trianglepath.dotted")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                        .frame(width: 40, height: 40)
                        .background(Color.accentColor.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))

                    VStack(alignment: .leading, spacing: 3) {
                        Text("Proxy Groups & Nodes")
                            .font(.headline)
                        Text(shortcutDescription)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }

                    Spacer(minLength: 8)

                    if isConnected {
                        Text("\(selectableGroups.count)")
                            .font(.caption.monospacedDigit().weight(.semibold))
                            .foregroundStyle(Color.accentColor)
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    } else {
                        Image(systemName: "lock.fill")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
            }
        }

        private var shortcutDescription: String {
            guard isConnected else {
                return String(localized: "Connect the VPN to load nodes")
            }
            if let group = selectableGroups.first,
               !group.selected.isEmpty
            {
                return "\(group.tag) · \(group.selected)"
            }
            return String(localized: "Choose a proxy group and node")
        }
    }

#endif
