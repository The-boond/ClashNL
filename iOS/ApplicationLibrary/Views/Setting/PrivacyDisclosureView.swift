#if os(iOS)
    import Foundation
    import SwiftUI

    public struct PrivacyDisclosureView: View {
        private let onContinue: (() -> Void)?

        public init(onContinue: (() -> Void)? = nil) {
            self.onContinue = onContinue
        }

        private var usesChinese: Bool {
            Locale.preferredLanguages.first?.hasPrefix("zh") == true
        }

        public var body: some View {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        Image(systemName: "lock.shield.fill")
                            .font(.system(size: 44))
                            .foregroundStyle(.tint)
                        Text(usesChinese ? "使用 ClashNl 前" : "Before using ClashNl")
                            .font(.largeTitle.bold())
                        Text(usesChinese ? "数据与隐私说明" : "Data & Privacy Disclosure")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }

                    disclosureCard(
                        title: usesChinese ? "ClashNl 收集的数据" : "Data collected by ClashNl",
                        body: usesChinese
                            ? "无。ClashNl 不收集、出售或向第三方披露浏览记录、DNS 查询、流量内容、IP 地址、设备标识符、分析数据或配置凭据。应用不含广告与追踪 SDK。"
                            : "None. ClashNl does not collect, sell, or disclose browsing history, DNS queries, traffic content, IP addresses, device identifiers, analytics, or profile credentials. The app contains no advertising or tracking SDK."
                    )

                    disclosureCard(
                        title: usesChinese ? "保存在设备上的数据" : "Data stored on your device",
                        body: usesChinese
                            ? "你导入的配置、节点选择、运行日志与崩溃报告保存在应用容器中。只有你主动启用 iCloud 配置时，相应文件才会同步到你自己的 iCloud 容器。"
                            : "Imported profiles, outbound selections, runtime logs, and crash reports remain in the app container. Profile files sync only when you explicitly use the iCloud profile option, through your own iCloud container."
                    )

                    disclosureCard(
                        title: usesChinese ? "网络连接" : "Network connections",
                        body: usesChinese
                            ? "订阅更新会直接请求你提供的订阅地址。启用 VPN 后，流量按所选配置发送到其中指定的 DNS 与代理服务器；这些服务由其各自的隐私政策约束。"
                            : "Subscription updates contact the URL you provide. When the VPN is enabled, traffic is sent to the DNS and proxy servers named by the selected profile; those services are governed by their own privacy policies."
                    )

                    Text(
                        usesChinese
                            ? "继续即表示你已阅读以上说明。你可随时删除配置、日志或整个应用数据。"
                            : "By continuing, you acknowledge this disclosure. You can delete profiles, logs, or all app data at any time."
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                    if let onContinue {
                        Button(action: onContinue) {
                            Text(usesChinese ? "同意并继续" : "Acknowledge and Continue")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                    }
                }
                .padding(24)
                .frame(maxWidth: 640, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(usesChinese ? "数据与隐私" : "Data & Privacy")
        }

        private func disclosureCard(title: String, body: String) -> some View {
            VStack(alignment: .leading, spacing: 8) {
                Text(title)
                    .font(.headline)
                Text(body)
                    .font(.body)
                    .foregroundStyle(.secondary)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
        }
    }
#endif
