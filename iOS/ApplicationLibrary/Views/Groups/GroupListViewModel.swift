import Libbox
import Library
import SwiftUI

@MainActor
public class GroupListViewModel: BaseViewModel {
    @Published public var groups: [OutboundGroup] = []

    private var selectionTasks: [String: Task<Void, Never>] = [:]

    override public init() {
        super.init()
        isLoading = true
    }

    public func connect() {
        if Variant.screenshotMode {
            groups = [
                OutboundGroup(tag: "my_group", type: "selector", selected: "server", selectable: true, isExpand: true, items: [
                    OutboundGroupItem(tag: "server", type: "Shadowsocks", urlTestTime: .now, urlTestDelay: 10),
                    OutboundGroupItem(tag: "server2", type: "WireGuard", urlTestTime: .now, urlTestDelay: 20),
                    OutboundGroupItem(tag: "auto", type: "URLTest", urlTestTime: .now, urlTestDelay: 30),
                ]),
                OutboundGroup(tag: "Auto", type: "urltest", selected: "Tokyo", selectable: true, isExpand: false, items: [
                    OutboundGroupItem(tag: "Tokyo", type: "Shadowsocks", urlTestTime: .now, urlTestDelay: 10),
                    OutboundGroupItem(tag: "Singapore", type: "VMess", urlTestTime: .now, urlTestDelay: 20),
                    OutboundGroupItem(tag: "Hong Kong", type: "Trojan", urlTestTime: .now, urlTestDelay: 15),
                ]),
            ]
            isLoading = false
        }
    }

    public func setGroups(_ goGroups: [LibboxOutboundGroup]?) {
        guard let goGroups else {
            selectionTasks.values.forEach { $0.cancel() }
            selectionTasks.removeAll()
            // Retain the rows backing an open detail view, but never retain
            // its old checkmark across a disconnected/replaced core session.
            for index in groups.indices {
                groups[index].selected = ""
            }
            isLoading = true
            return
        }

        let existingGroups = Dictionary(uniqueKeysWithValues: groups.map { ($0.tag, $0) })

        var newGroups = [OutboundGroup]()
        for goGroup in goGroups {
            var items = [OutboundGroupItem]()
            let itemIterator = goGroup.getItems()!
            while itemIterator.hasNext() {
                items.append(OutboundGroupItem(itemIterator.next()!))
            }

            let isExpand = existingGroups[goGroup.tag]?.isExpand ?? goGroup.isExpand

            newGroups.append(OutboundGroup(
                tag: goGroup.tag,
                type: goGroup.type,
                selected: goGroup.selected,
                selectable: goGroup.selectable,
                isExpand: isExpand,
                items: items
            ))
        }
        groups = newGroups
        isLoading = false
    }

    public func selectOutbound(groupTag: String, outboundTag: String) {
        guard !isLoading else { return }
        // Keep the last core-confirmed selection visible. A failed or delayed
        // request must not leave this page claiming a node that is not in use.
        // Serialize clicks in each group so an earlier request cannot win last.
        let previous = selectionTasks[groupTag]
        selectionTasks[groupTag] = Task {
            await previous?.value
            guard !Task.isCancelled, !isLoading else { return }
            await doSelectOutbound(groupTag: groupTag, outboundTag: outboundTag)
        }
    }

    private nonisolated func doSelectOutbound(groupTag: String, outboundTag: String) async {
        do {
            try await CommandTarget.standaloneClient().selectOutbound(groupTag, outboundTag: outboundTag)
        } catch {
            guard !Task.isCancelled else { return }
            await MainActor.run {
                alert = AlertState(action: "select outbound", error: error)
            }
        }
    }

    public func toggleExpand(groupTag: String) {
        guard let index = groups.firstIndex(where: { $0.tag == groupTag }) else { return }
        groups[index].isExpand.toggle()
        let isExpand = groups[index].isExpand
        Task {
            await setGroupExpand(tag: groupTag, isExpand: isExpand)
        }
    }

    private nonisolated func setGroupExpand(tag: String, isExpand: Bool) async {
        do {
            try await CommandTarget.standaloneClient().setGroupExpand(tag, isExpand: isExpand)
        } catch {
            await MainActor.run {
                alert = AlertState(action: "update group expansion", error: error)
            }
        }
    }

    public func performURLTest(_ tag: String) {
        Task {
            await doURLTest(tag: tag)
        }
    }

    private nonisolated func doURLTest(tag: String) async {
        do {
            try await CommandTarget.standaloneClient().urlTest(tag)
        } catch {
            await MainActor.run {
                alert = AlertState(action: "run URL test", error: error)
            }
        }
    }
}
