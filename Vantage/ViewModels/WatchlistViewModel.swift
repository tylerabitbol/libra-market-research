import Foundation
import SwiftUI
import SwiftData
import OSLog

/// One watchlist row's live state.
struct WatchlistRow: Identifiable, Sendable {
    var id: String { symbol }
    let symbol: String
    let name: String
    var quote: QuoteDTO?
    /// The last reading recorded on disk. Shown until a live quote arrives,
    /// and kept on screen if none does — a row that has a price from an hour
    /// ago is more useful than a row showing only an error.
    var stored: QuoteSnapshot?
    var error: APIError?

    var last: Double? { quote?.last ?? stored?.last }
    var changePercent: Double? { quote?.changePercent ?? stored?.changePercent }

    /// True when what is on screen came from disk rather than this refresh.
    var isStoredCopy: Bool { quote == nil && stored != nil }
    var asOf: Date? { quote == nil ? stored?.observedAt : nil }
    var hasValue: Bool { last != nil }
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

    /// Section 15's sort options, minus the ones that need signals not yet
    /// built. "Most unusual" and "highest research signal" arrive with the
    /// Phase 6 detectors; offering them now would mean two menu entries
    /// producing identical orderings, which is worse than one honest entry.
    enum SortOrder: String, CaseIterable, Identifiable {
        case symbol, biggestChange
        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .symbol: "Symbol"
            case .biggestChange: "Biggest change"
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
        }
    }

    func setSort(_ order: SortOrder) { sort = order }

    nonisolated static let logger = Logger(
        subsystem: "com.tylerabitbol.vantage", category: "watchlist"
    )

    func load(
        entries: [WatchlistEntry],
        registry: ProviderRegistry,
        snapshots: SnapshotStore? = nil,
        force: Bool = false
    ) {
        let members = entries.compactMap(\.security).map { ($0.symbol, $0.name) }
        // Show names from disk immediately; prices follow.
        rows = members.map { WatchlistRow(symbol: $0.0, name: $0.1) }
        guard !members.isEmpty else { return }

        if !force, case .fresh = freshness { return }
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            await self?.hydrate(from: snapshots)
            await self?.refreshQuotes(registry: registry)
            await self?.persist(using: snapshots)
        }
    }

    /// Fills each row with the last price recorded on disk, before any request.
    ///
    /// The watchlist already rendered names from disk while prices loaded; this
    /// extends the same idea to the prices themselves, so the list is readable
    /// offline and shows a stale number rather than a column of errors.
    private func hydrate(from snapshots: SnapshotStore?) async {
        guard let snapshots else { return }
        var stored: [String: QuoteSnapshot] = [:]
        for symbol in rows.map(\.symbol) {
            stored[symbol] = try? await snapshots.lastQuote(symbol: symbol, before: .now)
        }
        guard !Task.isCancelled else { return }
        rows = rows.map { row in
            var updated = row
            updated.stored = stored[row.symbol]
            return updated
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

    /// Records each quote so the watchlist accrues history simply by being
    /// opened — which is what gives Phase 6 something to compare against.
    private func persist(using snapshots: SnapshotStore?) async {
        guard let snapshots else { return }
        for row in rows {
            guard let quote = row.quote else { continue }
            do {
                try await snapshots.record(quote: quote, symbol: row.symbol)
            } catch {
                Self.logger.error("Persist failed for \(row.symbol, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }
        }
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
