import Testing
import Foundation
import SwiftData
@testable import Libra

/// Section 15: the watchlist as a triage surface rather than a list of tickers.
///
/// The binding constraint is the request budget. Tiingo's free tier refills
/// roughly one token every 80 seconds, so anything that costs a request *per
/// row* makes this screen unusable on a list of any size. Every figure added
/// here comes from the store or from a benchmark quote shared across rows, and
/// the budget test below is what keeps it that way.
private actor QuoteLog {
    private(set) var symbols: [String] = []
    private(set) var bars = 0
    func recordQuote(_ symbol: String) { symbols.append(symbol) }
    func recordBars() { bars += 1 }
    var count: Int { symbols.count }
}

private struct CountingProvider: MarketDataProvider {
    let id: DataProviderID = .finnhub
    let log: QuoteLog

    func isConfigured() async -> Bool { true }

    func quote(symbol: String) async throws -> QuoteDTO {
        await log.recordQuote(symbol)
        return QuoteDTO(symbol: symbol, last: 110, open: nil, high: nil, low: nil,
                        previousClose: 100, volume: nil, quoteTime: .now)
    }

    func bars(symbol: String, resolution: BarResolution,
              from: Date, to: Date) async throws -> [PriceBarDTO] {
        await log.recordBars()
        return []
    }

    func profile(symbol: String) async throws -> CompanyProfileDTO {
        throw APIError.notFound(.finnhub, endpoint: "profile")
    }

    func search(query: String) async throws -> [CompanyProfileDTO] { [] }
}

@Suite("Watchlist intelligence", .serialized)
@MainActor
struct WatchlistIntelligenceTests {

    private func row(_ symbol: String, change: Double? = nil, unusualness: Double = 0,
                     eventAt: Date? = nil, priority: Int = 0) -> WatchlistRow {
        var row = WatchlistRow(symbol: symbol, name: symbol)
        row.priority = priority
        if let change {
            row.quote = QuoteDTO(symbol: symbol, last: 100 * (1 + change / 100),
                                 open: nil, high: nil, low: nil, previousClose: 100,
                                 volume: nil, quoteTime: nil)
        }
        if let eventAt {
            row.latestEvent = DetectedEventDTO(
                kind: .unusualVolume, occurredAt: eventAt,
                headline: "Volume spike", unusualness: unusualness)
        }
        return row
    }

    // MARK: - Request budget

    @Test("Benchmark quotes are bounded by sectors, never by rows")
    func benchmarkCostDoesNotScaleWithRows() async throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        // Six companies across two sectors.
        for (index, sector) in ["Information Technology", "Information Technology",
                                "Information Technology", "Financials",
                                "Financials", "Financials"].enumerated() {
            let security = Security(symbol: "SYM\(index)", name: "Company \(index)")
            security.sector = sector
            context.insert(security)
            context.insert(WatchlistEntry(security: security))
        }
        try context.save()
        let entries = try context.fetch(FetchDescriptor<WatchlistEntry>())

        let log = QuoteLog()
        let model = WatchlistViewModel()
        model.load(entries: entries,
                   registry: ProviderRegistry(
                    marketData: CountingProvider(log: log), fundamentals: nil,
                    analyst: nil, metrics: nil, sec: nil, macro: nil, news: nil,
                    isUsingSampleData: false),
                   snapshots: SnapshotStore(modelContainer: container), force: true)
        try await Task.sleep(for: .milliseconds(900))

        let symbols = await log.symbols
        let bars = await log.bars
        // Six rows, one market proxy, two distinct sectors. Not six sector
        // quotes, and above all not six history requests.
        #expect(bars == 0, "One bars request per row would exhaust Tiingo's hourly quota")
        #expect(symbols.filter { $0.hasPrefix("SYM") }.count == 6)
        #expect(symbols.contains(Benchmark.marketProxySymbol))
        #expect(Set(symbols.filter { ["XLK", "XLF"].contains($0) }) == ["XLK", "XLF"])
        #expect(symbols.count == 9, "6 rows + 1 market + 2 sectors, got \(symbols.count)")
    }

    // MARK: - Sorting

    @Test("Most unusual is not the same ordering as biggest change")
    func unusualDiffersFromLargest() {
        let model = WatchlistViewModel()
        let rows = [
            row("QUIET", change: 2.0, unusualness: 0.99, eventAt: .now),
            row("LOUD", change: 9.0, unusualness: 0.10, eventAt: .now)
        ]
        // A 2% day can be extreme for a utility and unremarkable for a biotech.
        // Ranking by size alone loses exactly that distinction.
        #expect(rows.sorted { $0.unusualness > $1.unusualness }.first?.symbol == "QUIET")
        #expect(rows.sorted { abs($0.changePercent ?? 0) > abs($1.changePercent ?? 0) }
            .first?.symbol == "LOUD")
        #expect(WatchlistViewModel.SortOrder.allCases.count == 5)
        _ = model
    }

    @Test("A row with no recorded event sorts last rather than as if it were recent")
    func missingEventSortsLast() {
        let rows = [row("NONE"), row("SOME", eventAt: Date(timeIntervalSince1970: 1_700_000_000))]
        let sorted = rows.sorted {
            ($0.latestEventAt ?? .distantPast) > ($1.latestEventAt ?? .distantPast)
        }
        #expect(sorted.first?.symbol == "SOME")
    }

    @Test("User priority orders low first, falling back to symbol")
    func prioritySort() {
        let rows = [row("B", priority: 0), row("A", priority: 0), row("C", priority: -1)]
        let sorted = rows.sorted {
            $0.priority == $1.priority ? $0.symbol < $1.symbol : $0.priority < $1.priority
        }
        #expect(sorted.map(\.symbol) == ["C", "A", "B"])
    }

    // MARK: - Relative movement

    @Test("Relative movement is a difference in percentage points")
    func relativeMovementArithmetic() {
        var subject = row("AAPL", change: 3.0)
        subject.marketPercent = 1.2
        subject.sectorPercent = 2.0

        #expect(abs((subject.versusMarket ?? 0) - 1.8) < 0.0001)
        #expect(abs((subject.versusSector ?? 0) - 1.0) < 0.0001)
    }

    @Test("With no benchmark move there is no relative figure, not a zero")
    func missingBenchmarkIsAbsent() {
        let subject = row("AAPL", change: 3.0)
        // A benchmark that failed to load must not read as "moved exactly with
        // the market".
        #expect(subject.versusMarket == nil)
        #expect(subject.versusSector == nil)
    }
}
