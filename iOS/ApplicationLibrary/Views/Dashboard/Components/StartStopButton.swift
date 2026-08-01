import Library
import NetworkExtension
import SwiftUI

@MainActor
public struct StartStopButton: View {
    public enum Presentation: Equatable {
        case compact
        case prominent
    }

    @EnvironmentObject private var environments: ExtensionEnvironments
    private let showsRuntimeDuration: Bool
    private let presentation: Presentation

    public init(
        showsRuntimeDuration: Bool = false,
        presentation: Presentation = .compact
    ) {
        self.showsRuntimeDuration = showsRuntimeDuration
        self.presentation = presentation
    }

    public var body: some View {
        Group {
            if let profile = environments.extensionProfile {
                ToggleConnectionButton(
                    showsRuntimeDuration: showsRuntimeDuration,
                    presentation: presentation
                )
                    .environmentObject(profile)
            } else {
                Button {} label: {
                    #if os(tvOS)
                        Image(systemName: "play.fill")
                    #else
                        Label("Start", systemImage: "play.fill")
                    #endif
                }
                .labelStyle(.iconOnly)
                .disabled(true)
            }
        }
        .disabled(environments.emptyProfiles)
    }

    private struct ToggleConnectionButton: View {
        @EnvironmentObject private var environments: ExtensionEnvironments
        @EnvironmentObject private var profile: ExtensionProfile
        @State private var alert: AlertState?
        @State private var currentTime = Date()
        @State private var isStarting = false
        let showsRuntimeDuration: Bool
        let presentation: Presentation

        private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

        var body: some View {
            Button {
                Task {
                    await switchProfile(!profile.status.isConnected)
                }
            } label: {
                #if os(iOS)
                    Group {
                        if presentation == .prominent {
                            HStack(spacing: 10) {
                                if isTransitioning {
                                    ProgressView()
                                        .tint(.white)
                                } else {
                                    Image(systemName: profile.status.isConnected ? "stop.fill" : "play.fill")
                                }
                                Text(prominentTitle)
                                    .fontWeight(.semibold)
                                if showsRuntimeDuration,
                                   profile.status.isConnectedStrict,
                                   let duration = runtimeDuration
                                {
                                    Text(duration)
                                        .font(.caption)
                                        .monospacedDigit()
                                }
                            }
                            .frame(maxWidth: .infinity, minHeight: 50)
                        } else {
                            HStack(spacing: 8) {
                                if showsRuntimeDuration,
                                   profile.status.isConnectedStrict,
                                   let duration = runtimeDuration
                                {
                                    Text(duration)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .monospacedDigit()
                                        .fixedSize()
                                        .transition(.asymmetric(
                                            insertion: .move(edge: .trailing).combined(with: .opacity),
                                            removal: .move(edge: .trailing).combined(with: .opacity)
                                        ))
                                }

                                if !profile.status.isConnected {
                                    Label("Start", systemImage: "play.fill")
                                        .padding(.horizontal, 12)
                                } else {
                                    Label("Stop", systemImage: "stop.fill")
                                }
                            }
                        }
                    }
                    .animation(.spring(response: 0.35, dampingFraction: 0.75), value: profile.status.isConnectedStrict)
                #elseif os(tvOS)
                    if !profile.status.isConnected {
                        Image(systemName: "play.fill")
                    } else {
                        Image(systemName: "stop.fill")
                    }
                #else
                    HStack(spacing: 8) {
                        if profile.status.isConnectedStrict, let duration = runtimeDuration {
                            Text(duration)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                                .fixedSize()
                                .transition(.asymmetric(
                                    insertion: .move(edge: .trailing).combined(with: .opacity),
                                    removal: .move(edge: .trailing).combined(with: .opacity)
                                ))
                        }

                        if !profile.status.isConnected {
                            Label("Start", systemImage: "play.fill")
                        } else {
                            Label("Stop", systemImage: "stop.fill")
                        }
                    }
                    .animation(.spring(response: 0.35, dampingFraction: 0.75), value: profile.status.isConnectedStrict)
                #endif
            }
            .modifier(
                StartStopPresentationModifier(
                    presentation: presentation,
                    isConnected: profile.status.isConnected
                )
            )
            .disabled(!profile.status.isEnabled)
            .alert($alert)
            .onReceive(timer) { _ in
                guard !Variant.screenshotMode else { return }
                Task { @MainActor in
                    currentTime = Date()
                }
            }
            .onChangeCompat(of: profile.status) { status in
                Task { @MainActor in
                    if isStarting {
                        if status == .disconnected {
                            isStarting = false
                            if #available(iOS 16.0, macOS 13.0, tvOS 17.0, *) {
                                await checkStartupError()
                            }
                        } else if status.isConnectedStrict {
                            isStarting = false
                            environments.commandClient.connect()
                        }
                    }
                }
            }
        }

        private var isTransitioning: Bool {
            switch profile.status {
            case .connecting, .disconnecting, .reasserting:
                return true
            default:
                return false
            }
        }

        private var prominentTitle: LocalizedStringKey {
            switch profile.status {
            case .connecting:
                return "Starting"
            case .disconnecting:
                return "Stopping"
            case .reasserting:
                return "Reasserting"
            default:
                return profile.status.isConnected ? "Stop" : "Start"
            }
        }

        private var runtimeDuration: String? {
            guard let connectedDate = profile.connectedDate else { return nil }
            let interval: TimeInterval
            if Variant.screenshotMode {
                interval = 3600
            } else {
                interval = currentTime.timeIntervalSince(connectedDate)
            }
            guard interval >= 0 else { return nil }

            let hours = Int(interval) / 3600
            let minutes = Int(interval) / 60 % 60
            let seconds = Int(interval) % 60

            if hours > 0 {
                return String(format: "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                return String(format: "%d:%02d", minutes, seconds)
            }
        }

        @available(iOS 16.0, macOS 13.0, tvOS 17.0, *)
        private func checkStartupError() async {
            if let alertState = await profile.checkLastDisconnectError() {
                alert = alertState
            }
        }

        private nonisolated func switchProfile(_ isEnabled: Bool) async {
            do {
                if isEnabled {
                    await MainActor.run { isStarting = true }
                    try await profile.start()
                } else {
                    try await profile.stop()
                }
            } catch {
                await MainActor.run {
                    isStarting = false
                    let action = isEnabled ? "start service" : "stop service"
                    alert = AlertState(action: action, error: error)
                }
            }
        }
    }
}

private struct StartStopPresentationModifier: ViewModifier {
    let presentation: StartStopButton.Presentation
    let isConnected: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if presentation == .prominent {
            content
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(isConnected ? .red : .accentColor)
        } else {
            content
                .labelStyle(.iconOnly)
            #if os(iOS)
                .modifier(PrimaryTintModifier())
            #endif
        }
    }
}

#if os(iOS)
    private struct PrimaryTintModifier: ViewModifier {
        func body(content: Content) -> some View {
            if #available(iOS 26.0, *), !Variant.debugNoIOS26 {
                content.tint(.primary)
            } else {
                content
            }
        }
    }
#endif
