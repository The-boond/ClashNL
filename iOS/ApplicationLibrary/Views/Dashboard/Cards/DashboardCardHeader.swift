import SwiftUI

public struct DashboardCardHeader: View {
    private let icon: String
    private let title: LocalizedStringKey
    private let accent: Color

    public init(
        icon: String,
        title: LocalizedStringKey,
        accent: Color = .accentColor
    ) {
        self.icon = icon
        self.title = title
        self.accent = accent
    }

    public var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(accent)
                .frame(width: 34, height: 34)
                .background(accent.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            Text(title)
                .font(.headline)
                .fontWeight(.semibold)
        }
    }
}
