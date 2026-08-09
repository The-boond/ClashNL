#if os(iOS)

    import Library
    import SwiftUI

    struct SubscriptionDetailView: View {
        let profile: ProfilePreview
        let isUpdating: Bool
        let onUpdate: () -> Void

        private var hasSubscriptionMetadata: Bool {
            profile.type == .remote && (
                profile.subscriptionTotal != nil ||
                    profile.subscriptionUpload != nil ||
                    profile.subscriptionDownload != nil ||
                    profile.subscriptionExpireAt != nil
            )
        }

        private var description: LocalizedStringKey {
            switch profile.type {
            case .remote:
                return "This remote profile is managed by its subscription source."
            case .icloud:
                return "This profile is stored in iCloud and shared across your Apple devices."
            case .local:
                return "This profile is stored locally on this device."
            }
        }

        var body: some View {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    overviewCard
                    informationCard
                    actionsCard
                }
                .padding()
            }
        }

        private var overviewCard: some View {
            DashboardCardView(title: "") {
                HStack(spacing: 12) {
                    Image(systemName: profile.type.presentationSymbol)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.blue)
                        .frame(width: 44, height: 44)
                        .background(Color.blue.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                    VStack(alignment: .leading, spacing: 4) {
                        Text(profile.name)
                            .font(.title3)
                            .fontWeight(.semibold)
                            .lineLimit(2)
                        Text(profile.type.presentationLabel)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Spacer(minLength: 0)
                }
            }
        }

        private var informationCard: some View {
            DashboardCardView(title: "") {
                VStack(alignment: .leading, spacing: 12) {
                    sectionLabel("Description")
                    Text(description)
                        .foregroundStyle(.secondary)

                    Divider()

                    sectionLabel("Usage and validity")
                    if hasSubscriptionMetadata {
                        SubscriptionInfoView(profile: profile)
                    } else {
                        Text("No subscription metadata available.")
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }

        private var actionsCard: some View {
            DashboardCardView(title: "") {
                VStack(alignment: .leading, spacing: 12) {
                    sectionLabel("Actions")
                    if profile.type == .remote {
                        Button(action: onUpdate) {
                            Label {
                                Text(isUpdating ? "Updating..." : "Update subscription")
                            } icon: {
                                Image(systemName: "arrow.clockwise")
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .disabled(isUpdating)
                    } else {
                        Text("This profile is managed locally and has no remote update action.")
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }

        private func sectionLabel(_ title: LocalizedStringKey) -> some View {
            Text(title)
                .font(.subheadline)
                .fontWeight(.semibold)
                .foregroundStyle(.secondary)
        }
    }

#endif
