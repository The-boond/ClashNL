import Libbox
import Library
import SwiftUI

struct SubscriptionInfoView: View {
    let profile: ProfilePreview

    private var hasUsage: Bool {
        profile.subscriptionTotal != nil &&
            (profile.subscriptionUpload != nil || profile.subscriptionDownload != nil)
    }

    private var hasExpiration: Bool {
        profile.subscriptionExpireAt != nil
    }

    var body: some View {
        if profile.type == .remote, hasUsage || hasExpiration {
            VStack(alignment: .leading, spacing: 2) {
                if hasUsage {
                    let upload = max(profile.subscriptionUpload ?? 0, 0)
                    let download = max(profile.subscriptionDownload ?? 0, 0)
                    let (sum, overflow) = upload.addingReportingOverflow(download)
                    let used = overflow ? Int64.max : sum
                    HStack(spacing: 4) {
                        Image(systemName: "chart.bar.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                        Text("Subscription Usage")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(verbatim: "\(LibboxFormatBytes(used)) / \(LibboxFormatBytes(profile.subscriptionTotal ?? 0))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                }

                if let expiration = profile.subscriptionExpireAt {
                    HStack(spacing: 4) {
                        Image(systemName: "calendar")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                        if expiration <= Date() {
                            Text("Expired")
                                .font(.caption)
                                .foregroundStyle(.red)
                        } else {
                            Text("Expires \(expiration.relativeFormat)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }
}
