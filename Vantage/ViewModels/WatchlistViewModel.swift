import Foundation
import SwiftUI
import SwiftData

/// One watchlist row's live state.
struct WatchlistRow: Identifiable, Sendable {
    var id: String { symbol }
    let symbol: String
    let name: String
    var quote: QuoteDTO?
    var error: APIError?

    var changePercent: Double? { quote?.changePercent }
}

/// Backs the watchlist (Section 15).
///
/// Membership is persisted in SwiftData; prices are fetched live. The two are
/// deliberately separate: the list must render instantly from disk and remain
/// usable when the network doesn't, with prices filling in as they arrive.
@Observable
@MainActor
final class WatchlistViewModel {
    private(set) var rows: [WatchlistRow] = []
    private(set) var isLoading = false
    private(set) var lastRefreshedAt: Date?
    private(set) var sort: SortOrder = .symbol

    private var loadTask: Task<Void, Never>?

    /// Section 15's sort options, minus the ones that need signals not yet built.
    enum SortOrder: String, CaseIterable, Identifiable {
        case symbol, biggestChange, mostUnusual
        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .symbol: "Symbol"
            case .biggestChange: "Biggest change"
            case .mostUnusual: "Most unusual"
            }
        }
    }

    var freshness: Freshness {
        if isLoading { return .refreshing(previous: lastRefreshedAt) }
        return StalenessPolicy.quote.evaluate(lastUpdated: lastRefreshedAt)
    }

    var sortedRows: [WatchlistRow] {
        switch sort {
        case .symbol:
            rows.sorted { $0.symbol < $1.symbol }
        case .biggestChange:
            // Absolute magnitude: a 5% fall is as notable as a 5% rise, and
            // this screen is about what moved, not about what went up.
            rows.sorted { abs($0.changePercent ?? 0) > abs($1.changePercent ?? 0) }
        case .mostUnusual:
            // Placeholder ordering until the event detectors land in Phase 6;
            // magnitude is the honest stand-in for "unusual" until then.
            rows.sorted { abs($0.changePercent ?? 0) > abs($1.changePercent ?? 0) }
        }
    }

    func setSort(_ order: SortOrder) { sort = order }

    func load(entries: [WatchlistEntry], registry: ProviderRegistry, force: Bool = false) {
        let members = entries.compactMap(\.security).map { ($0.symbol, $0.name) }
        // Show names from disk immediately; prices follow.
        rows = members.map { WatchlistRow(symbol: $0.0, name: $0.1) }
        guard !members.isEmpty else { return }

        if !force, case .fresh = freshness { return }
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            await self?.refreshQuotes(registry: registry)
        }
    }

    private func refreshQuotes(registry: ProviderRegistry) async {
        isLoading = true
        defer { isLoading = false }

        let symbols = rows.map(\.symbol)
        let provider = registry.marketData

        // Quotes only. Deliberately no history: the watchlist shows a daily
        // change, and fetching bars per row would exhaust Tiingo's hourly quota
        // on a list of any size.
        let results = await withTaskGroup(of: (String, Result<QuoteDTO, APIError>).self) { group in
            for symbol in symbols {
                group.addTask {
                    do {
                        return (symbol, .success(try await provider.quote(symbol: symbol)))
                    } catch let error as APIError {
                        return (symbol, .failure(error))
                    } catch {
                        return (symbol, .failure(.transport(.finnhub,
                                                            underlying: error.localizedDescription)))
                    }
                }
            }
            var collected: [String: Result<QuoteDTO, APIError>] = [:]
            for await (symbol, result) in group { collected[symbol] = result }
            return collected
        }

        guard !Task.isCancelled else { return }
        rows = rows.map { row in
            var updated = row
            switch results[row.symbol] {
            case .success(let quote): updated.quote = quote; updated.error = nil
            case .failure(let error): updated.error = error
            case nil: break
            }
            return updated
        }
        lastRefreshedAt = .now
    }
}

/// Symbol search for adding to the watchlist.
@Observable
@MainActor
final class SymbolSearchViewModel {
    private(set) var results: [CompanyProfileDTO] = []
    private(set) var isSearching = false
    private(set) var error: APIError?

    private var searchTask: Task<Void, Never>?

    func search(_ query: String, registry: ProviderRegistry) {
        searchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 1 else { results = []; return }

        searchTask = Task { [weak self] in
            // Debounce: search fires per keystroke, and each one is a request
            // against a rate-limited provider.
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            await self?.performSearch(trimmed, registry: registry)
        }
    }

    private func performSearch(_ query: String, registry: ProviderRegistry) async {
        isSearching = true
        defer { isSearching = false }
        do {
            results = try await registry.marketData.search(query: query)
            error = nil
        } catch let error as APIError {
            self.error = error
            results = []
        } catch {
            self.error = .transport(.finnhub, underlying: error.localizedDescription)
            results = []
        }
    }
}
