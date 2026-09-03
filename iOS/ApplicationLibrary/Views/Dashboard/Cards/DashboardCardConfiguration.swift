import Foundation
import Library

@MainActor
public final class DashboardCardConfiguration: ObservableObject {
    @Published public private(set) var enabledCards: [DashboardCard] = []
    @Published public private(set) var cardOrder: [DashboardCard] = []
    @Published public private(set) var isLoading = true

    public init() {
        Task {
            await reload()
        }
    }

    public func reload() async {
        isLoading = true
        enabledCards = await loadEnabledCards()
        cardOrder = await loadCardOrder()
        isLoading = false
    }

    public func isEnabled(_ card: DashboardCard) -> Bool {
        enabledCards.contains(card)
    }

    public func toggleCard(_ card: DashboardCard) {
        #if !os(iOS)
            guard card != .profile else { return }
        #endif

        // Update state synchronously so UI reflects change immediately
        if enabledCards.contains(card) {
            enabledCards.removeAll { $0 == card }
        } else {
            enabledCards = insertInOrder(card, into: enabledCards)
        }

        // Save asynchronously in background
        Task {
            await saveEnabledCards()
        }
    }

    public func moveCard(from source: IndexSet, to destination: Int) async {
        cardOrder.move(fromOffsets: source, toOffset: destination)
        await saveCardOrder()
    }

    public func resetToDefault() async {
        await SharedPreferences.enabledDashboardCards.set([])
        await SharedPreferences.dashboardCardOrder.set([])
        await reload()
    }

    public var orderedEnabledCards: [DashboardCard] {
        cardOrder.filter { enabledCards.contains($0) }
    }

    private func loadEnabledCards() async -> [DashboardCard] {
        let saved = await SharedPreferences.enabledDashboardCards.get()
        #if os(iOS)
            let needsNetworkPathsMigration = !(await SharedPreferences.dashboardNetworkPathsMigrationCompleted.get())
            if saved == [Self.noEnabledCardsMarker] {
                // Respect an explicit "no cards" choice while completing the
                // one-time migration so it is not reconsidered next launch.
                if needsNetworkPathsMigration {
                    await SharedPreferences.dashboardNetworkPathsMigrationCompleted.set(true)
                }
                return []
            }
        #endif
        guard !saved.isEmpty else {
            #if os(iOS)
                if needsNetworkPathsMigration {
                    await SharedPreferences.dashboardNetworkPathsMigrationCompleted.set(true)
                }
            #endif
            return DashboardCard.defaultCards
        }

        var cards = saved.compactMap { migrateCardName($0) }.compactMap { DashboardCard(rawValue: $0) }
        #if os(iOS)
            // The card did not exist when older custom lists were saved. Add it
            // once; after this marker is set, a user disabling it is preserved.
            if needsNetworkPathsMigration {
                if !cards.contains(.networkPaths) {
                    cards.append(.networkPaths)
                }
                await SharedPreferences.dashboardNetworkPathsMigrationCompleted.set(true)
            }
        #endif
        #if !os(iOS)
            if !cards.contains(.profile) {
                cards.append(.profile)
            }
        #endif
        await SharedPreferences.enabledDashboardCards.set(cards.map(\.rawValue))
        return cards
    }

    private func loadCardOrder() async -> [DashboardCard] {
        let saved = await SharedPreferences.dashboardCardOrder.get()
        guard !saved.isEmpty else { return DashboardCard.defaultOrder }

        var order = saved.compactMap { migrateCardName($0) }.compactMap { DashboardCard(rawValue: $0) }
        let existingSet = Set(order)
        var newCards = DashboardCard.allCases.filter { !existingSet.contains($0) }
        if let networkPathsIndex = newCards.firstIndex(of: .networkPaths) {
            newCards.remove(at: networkPathsIndex)
            let insertionIndex = order.firstIndex(of: .profile).map { $0 + 1 } ?? 0
            order.insert(.networkPaths, at: insertionIndex)
        }
        order.append(contentsOf: newCards)
        await SharedPreferences.dashboardCardOrder.set(order.map(\.rawValue))
        return order
    }

    private func migrateCardName(_ name: String) -> String {
        switch name {
        case "traffic":
            return "uploadTraffic"
        case "trafficTotal":
            return "downloadTraffic"
        default:
            return name
        }
    }

    private func saveEnabledCards() async {
        #if os(iOS)
            let storedCards = enabledCards.isEmpty
                ? [Self.noEnabledCardsMarker]
                : enabledCards.map(\.rawValue)
            await SharedPreferences.enabledDashboardCards.set(storedCards)
        #else
            await SharedPreferences.enabledDashboardCards.set(enabledCards.map(\.rawValue))
        #endif
    }

    private func saveCardOrder() async {
        await SharedPreferences.dashboardCardOrder.set(cardOrder.map(\.rawValue))
    }

    private func insertInOrder(_ card: DashboardCard, into cards: [DashboardCard]) -> [DashboardCard] {
        guard let cardIndex = cardOrder.firstIndex(of: card) else {
            return cards + [card]
        }

        let insertIndex = cards.filter { enabledCard in
            guard let enabledIndex = cardOrder.firstIndex(of: enabledCard) else { return false }
            return enabledIndex < cardIndex
        }.count

        var result = cards
        result.insert(card, at: insertIndex)
        return result
    }

    #if os(iOS)
        private static let noEnabledCardsMarker = "__none__"
    #endif
}
