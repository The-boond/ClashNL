import Library
import SwiftUI

public struct GroupListView: View {
    @EnvironmentObject private var environments: ExtensionEnvironments
    @StateObject private var viewModel = GroupListViewModel()

    public init() {}
    public var body: some View {
        content
        .environmentObject(viewModel)
        .alert($viewModel.alert)
        .onAppear {
            viewModel.connect()
        }
        .onReceive(environments.commandClient.$groups) { groups in
            Task { @MainActor in
                viewModel.setGroups(groups)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.isLoading {
            ProgressView("Loading proxy groups…")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if viewModel.groups.isEmpty {
            emptyState
        } else {
            #if os(iOS)
                List {
                    Section {
                        ForEach($viewModel.groups, id: \.tag) { $group in
                            NavigationLink {
                                IOSGroupDetailView(group: $group)
                                    .environmentObject(viewModel)
                            } label: {
                                IOSGroupRow(group: group)
                            }
                        }
                    } footer: {
                        Text("Open a proxy group to choose a node and run a latency test.")
                    }
                }
                .listStyle(.insetGrouped)
            #else
                ScrollView {
                    VStack {
                        ForEach($viewModel.groups, id: \.tag) { $group in
                            GroupView($group)
                        }
                    }
                    .padding()
                }
            #endif
        }
    }

    @ViewBuilder
    private var emptyState: some View {
        #if os(iOS)
            if #available(iOS 17.0, *) {
                ContentUnavailableView(
                    "No Proxy Groups",
                    systemImage: "point.3.connected.trianglepath.dotted",
                    description: Text("Connect the VPN to load the proxy groups in the active subscription.")
                )
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "point.3.connected.trianglepath.dotted")
                        .font(.largeTitle)
                    Text("No Proxy Groups")
                        .font(.headline)
                    Text("Connect the VPN to load the proxy groups in the active subscription.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(32)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        #else
            Text("Empty groups")
        #endif
    }
}

#if os(iOS)

    @MainActor
    private struct IOSGroupRow: View {
        let group: OutboundGroup

        var body: some View {
            HStack(spacing: 12) {
                Image(systemName: group.selectable ? "arrow.triangle.swap" : "bolt.horizontal.circle")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 34, height: 34)
                    .background(Color.accentColor.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                VStack(alignment: .leading, spacing: 3) {
                    Text(group.tag)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    Text(group.selected.isEmpty ? group.displayType : group.selected)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                Text("\(group.items.count)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
        }
    }

    @MainActor
    private struct IOSGroupDetailView: View {
        @EnvironmentObject private var viewModel: GroupListViewModel
        @Binding var group: OutboundGroup
        @State private var requestedInitialTest = false

        var body: some View {
            List {
                Section {
                    ForEach(group.items, id: \.tag) { item in
                        Button {
                            guard group.selectable, group.selected != item.tag else { return }
                            viewModel.selectOutbound(groupTag: group.tag, outboundTag: item.tag)
                        } label: {
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(item.tag)
                                        .foregroundStyle(.primary)
                                        .lineLimit(2)
                                    Text(item.displayType)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }

                                Spacer(minLength: 8)

                                if item.urlTestDelay > 0 {
                                    Text(item.delayString)
                                        .font(.caption.monospacedDigit().weight(.medium))
                                        .foregroundStyle(item.delayColor)
                                } else {
                                    Text("Not tested")
                                        .font(.caption)
                                        .foregroundStyle(.tertiary)
                                }

                                Image(systemName: group.selected == item.tag ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(group.selected == item.tag ? Color.accentColor : Color.secondary.opacity(0.35))
                            }
                            .contentShape(Rectangle())
                            .padding(.vertical, 5)
                        }
                        .buttonStyle(.plain)
                        .disabled(!group.selectable)
                    }
                } footer: {
                    Text("Latency: green up to 250 ms, blue up to 350 ms, orange up to 600 ms, and red above 600 ms.")
                }
            }
            .navigationTitle(group.tag)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        viewModel.performURLTest(group.tag)
                    } label: {
                        Label("Test latency", systemImage: "bolt.fill")
                    }
                }
            }
            .onAppear {
                guard !requestedInitialTest,
                      group.selectable,
                      group.items.contains(where: { $0.urlTestDelay == 0 })
                else { return }
                requestedInitialTest = true
                viewModel.performURLTest(group.tag)
            }
        }
    }

#endif
