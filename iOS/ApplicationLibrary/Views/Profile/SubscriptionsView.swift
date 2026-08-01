#if os(iOS)

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
                        accent: .blue
                    )
                    Spacer()
                    Text("\(coordinator.profileList.count)")
                        .font(.headline)
                        .foregroundStyle(.blue)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.blue.opacity(0.12))
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

#endif
