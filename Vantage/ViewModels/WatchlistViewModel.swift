import Foundation
import SwiftUI
import SwiftData
import OSLog

/// One watchlist row's live state.
struct WatchlistRow: Identifiable, Sendable {
    var id: String { symbol }
    let symbol: String
    let name: String
    /// The user's own ordering, from `WatchlistEntry.priority`. Lower first.
    var priority: Int = 0
    var quote: QuoteDTO?
    /// The last reading recorded on disk. Shown until a live quote arrives,
    /// and kept on screen if none does — a row that has a price from an hour
    /// ago is more useful than a row showing only an error.
    var stored: QuoteSnapshot?
    var error: APIError?

    /// The sector this security belongs to, from the stored profile. Nil until
    /// the detail page has been opened once and written one.
    var sector: String?
    /// The most recent change the detectors have recorded for this security.
    var latestEvent: DetectedEventDTO?
    /// The market's move for the same session, fetched once for the whole list.
    var marketPercent: Double?
    /// This row's sector's move, fetched once per distinct sector on the list.
    var sectorPercent: Double?

    var last: Double? { quote?.last ?? stored?.last }
    var changePercent: Double? { quote?.changePercent ?? stored?.changePercent }

    /// Difference in percentage points against the market and the sector.
    ///
    /// A plain difference, not beta-adjusted — that belongs on the detail page
    /// where a beta can be fitted and shown. Here it is labelled for what it
    /// is: how much more or less this moved than the benchmark today.
    var versusMarket: Double? {
        guard let changePercent, let marketPercent else { return nil }
        return changePercent - marketPercent
    }

    var versusSector: Double? {
        guard let changePercent, let sectorPercent else { return nil }
        return changePercent - sectorPercent
    }

    /// How unusual the most recent recorded change was, for sorting.
    var unusualness: Double { latestEvent?.unusualness ?? 0 }
    /// When the most recent recorded change occurred.
    var latestEventAt: Date? { latestEvent?.occurredAt }

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
        case symbol, biggestChange, mostUnusual, newestInformation, priority
        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .symbol: "Symbol"
            case .biggestChange: "Biggest change"
            case .mostUnusual: "Most unusual"
            case .newestInformation: "Newest information"
            case .priority: "Your priority"
            }
        }
    }

    var freshness: Freshness {
        if isLoading { return .refreshing(previous: lastRefreshedAt) }
        return StalenessPolicy.quote.evaluate(lastUpdated: lastRefreshedAt)
    }

    var sortedRows: [WatchlistRow] { Self.sorted(rows, by: sort) }

    /// The orderings, as a free function so tests exercise the shipped
    /// comparison rather than a copy of it. They used to re-implement it,
    /// which meant a change here would not have been caught there.
    /// Pure over `Sendable` values, so it carries no actor isolation and can be
    /// checked from anywhere.
    nonisolated static func sorted(_ rows: [WatchlistRow], by order: SortOrder) -> [WatchlistRow] {
        switch order {
        case .symbol:
            rows.sorted { $0.symbol < $1.symbol }
        case .biggestChange:
            // Absolute magnitude: a 5% fall is as notable as a 5% rise, and
            // this screen is about what moved, not about what went up.
            rows.sorted { abs($0.changePercent ?? 0) > abs($1.changePercent ?? 0) }
        case .mostUnusual:
            // Unusual relative to each security's own history, which is not
            // the same ordering as the biggest move: a 2% day can be extreme
            // for a utility and unremarkable for a small-cap biotech.
            rows.sorted { $0.unusualness > $1.unusualness }
        case .newestInformation:
            rows.sorted {
                ($0.latestEventAt ?? .distantPast) > ($1.latestEventAt ?? .distantPast)
            }
        case .priority:
            rows.sorted {
                $0.priority == $1.priority ? $0.symbol < $1.symbol : $0.priority < $1.priority
            }
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
        // Everything the store already knows, read before any request: names,
        // sectors and the user's own ordering all render immediately.
        let members = entries.compactMap { entry -> WatchlistRow? in
            guard let security = entry.security else { return nil }
            var row = WatchlistRow(symbol: security.symbol, name: security.name)
            row.sector = security.sector
            row.priority = entry.priority
            return row
        }
        rows = members
        guard !members.isEmpty else { return }

        if !force, case .fresh = freshness { return }
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            await self?.perform(registry: registry, snapshots: snapshots)
        }
    }

    /// Pull-to-refresh, which must not return until the work is done. `load`
    /// spawns and returns, so the control's spinner ended with the gesture
    /// while the quotes were still in flight.
    func refresh(entries: [WatchlistEntry], registry: ProviderRegistry,
                 snapshots: SnapshotStore? = nil) async {
        load(entries: entries, registry: registry, snapshots: snapshots, force: true)
        await loadTask?.value
    }

    private func perform(registry: ProviderRegistry, snapshots: SnapshotStore?) async {
        await hydrate(from: snapshots)
        await refreshQuotes(registry: registry)
        await loadBenchmarkMoves(registry: registry)
        await persist(using: snapshots, provider: registry.marketData.id)
    }

    /// Fills each row with the last price recorded on disk, before any request.
    ///
    /// The watchlist already rendered names from disk while prices loaded; this
    /// extends the same idea to the prices themselves, so the list is readable
    /// offline and shows a stale number rather than a column of errors.
    private func hydrate(from snapshots: SnapshotStore?) async {
        guard let snapshots else { return }
        var stored: [String: QuoteSnapshot] = [:]
        var events: [String: DetectedEventDTO] = [:]
        for symbol in rows.map(\.symbol) {
            stored[symbol] = try? await snapshots.lastQuote(symbol: symbol, before: .now)
            // The feed of recorded changes, read rather than recomputed. A
            // watchlist that re-ran detection per row would need every row's
            // history, which is the one thing this screen must never fetch.
            events[symbol] = (try? await snapshots.events(symbol: symbol, limit: 1))?.first
        }
        guard !Task.isCancelled else { return }
        rows = rows.map { row in
            var updated = row
            updated.stored = stored[row.symbol]
            updated.latestEvent = events[row.symbol]
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

    /// The market's move, and one move per distinct sector on the list.
    ///
    /// Bounded by the number of *sectors*, not by the number of rows: eleven
    /// sectors exist, so a hundred-row watchlist costs at most twelve extra
    /// quotes. A per-row benchmark would cost a hundred, which is the mistake
    /// that makes this screen unusable on a free tier.
    ///
    /// Quotes only, never history — the same rule the rows themselves follow.
    private func loadBenchmarkMoves(registry: ProviderRegistry) async {
        let sectors = Set(rows.compactMap { row in
            Benchmark.sector(matching: row.sector)?.etfSymbol
        })
        let provider = registry.marketData

        async let marketWork = try? await provider
            .quote(symbol: Benchmark.marketProxySymbol).changePercent
        async let sectorWork = withTaskGroup(of: (String, Double?).self) { group in
            for symbol in sectors {
                group.addTask {
                    (symbol, try? await provider.quote(symbol: symbol).changePercent)
                }
            }
            var moves: [String: Double?] = [:]
            for await (symbol, move) in group { moves[symbol] = move }
            return moves
        }

        let (market, sectorMoves) = await (marketWork, sectorWork)
        guard !Task.isCancelled else { return }

        rows = rows.map { row in
            var updated = row
            updated.marketPercent = market
            if let symbol = Benchmark.sector(matching: row.sector)?.etfSymbol {
                updated.sectorPercent = sectorMoves[symbol] ?? nil
            }
            return updated
        }
    }

    /// Records each quote so the watchlist accrues history simply by being
    /// opened — which is what gives Phase 6 something to compare against.
    private func persist(using snapshots: SnapshotStore?, provider: DataProviderID) async {
        guard let snapshots else { return }
        for row in rows {
            guard let quote = row.quote else { continue }
            do {
                try await snapshots.record(quote: quote, symbol: row.symbol, provider: provider)
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
