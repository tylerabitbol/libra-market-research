import Testing
import Foundation
import SwiftData
@testable import Vantage

/// Reading the store back before reaching for the network.
///
/// The store used to be write-only: bars, facts and filings were recorded on
/// every visit and never read. So every visit re-fetched five years of history
/// it already held — against Tiingo's 50-requests-per-hour budget — and a
/// security that had been opened a hundred times still showed nothing offline.
///
/// These tests pin the two properties that follow from fixing it: a fresh held
/// copy costs no request, and a failed fetch degrades to the last good copy
/// instead of to an empty page.
private actor CallLog {
    private(set) var quotes = 0
    private(set) var bars = 0
    func recordQuote() { quotes += 1 }
    func recordBars() { bars += 1 }
}

/// Counts what it is asked for, and can be told to fail — the offline case.
private struct StubMarketProvider: MarketDataProvider {
    let id: DataProviderID = .finnhub
    let log: CallLog
    var isOffline = false

    func isConfigured() async -> Bool { true }

    func quote(symbol: String) async throws -> QuoteDTO {
        await log.recordQuote()
        if isOffline { throw APIError.transport(.finnhub, underlying: "offline") }
        return QuoteDTO(symbol: symbol, last: 200, open: nil, high: nil, low: nil,
                        previousClose: 190, volume: nil, quoteTime: .now)
    }

    func bars(symbol: String, resolution: BarResolution,
              from: Date, to: Date) async throws -> [PriceBarDTO] {
        await log.recordBars()
        if isOffline { throw APIError.transport(.tiingo, underlying: "offline") }
        return SampleData.bars(symbol: symbol, resolution: resolution, from: from, to: to)
    }

    func profile(symbol: String) async throws -> CompanyProfileDTO {
        throw APIError.notFound(.finnhub, endpoint: "profile")
    }

    func search(query: String) async throws -> [CompanyProfileDTO] { [] }
}

@Suite("Store hydration", .serialized)
@MainActor
struct HydrationTests {

    private func makeContainer() throws -> ModelContainer {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        let security = Security(symbol: "TEST", name: "Test Corp")
        context.insert(security)
        context.insert(WatchlistEntry(security: security))
        try context.save()
        return container
    }

    private func registry(_ log: CallLog, offline: Bool = false) -> ProviderRegistry {
        ProviderRegistry(
            marketData: StubMarketProvider(log: log, isOffline: offline),
            fundamentals: nil, analyst: nil, metrics: nil, sec: nil,
            macro: nil, news: nil, isUsingSampleData: false
        )
    }

    private func bar(day: Int, close: Double) -> PriceBarDTO {
        PriceBarDTO(date: Date(timeIntervalSince1970: 1_700_000_000 + Double(day) * 86_400),
                    open: close, high: close, low: close, close: close,
                    volume: 1_000, adjustedClose: close)
    }

    // MARK: - Detail page

    @Test("A fresh held copy of the bars costs no history request")
    func freshBarsAreNotRefetched() async throws {
        let container = try makeContainer()
        let store = SnapshotStore(modelContainer: container)
        try await store.record(bars: (0..<5).map { bar(day: $0, close: 100 + Double($0)) },
                               symbol: "TEST", resolution: .daily, provider: .tiingo)

        let log = CallLog()
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry(log), snapshots: store)
        try await Task.sleep(for: .milliseconds(600))

        let requested = await log.bars
        #expect(requested == 0, "Bars observed moments ago are current; refetching buys nothing")
        #expect(model.bars.count == 5, "And the page is populated regardless")
    }

    @Test("A stale held copy is refreshed rather than trusted forever")
    func staleBarsAreRefetched() async throws {
        let container = try makeContainer()
        let context = ModelContext(container)
        let security = try #require(
            try context.fetch(FetchDescriptor<Security>()).first)

        // Inserted directly so `observedAt` can be backdated past the six-hour
        // daily-bar window; the store always stamps its own writes with now.
        let old = Date.now.addingTimeInterval(-48 * 3600)
        context.insert(PriceBar(security: security, date: old, resolution: .daily,
                                observedAt: old, open: 100, high: 100, low: 100,
                                close: 100, volume: 1_000, adjustedClose: 100,
                                provider: .tiingo))
        try context.save()

        let log = CallLog()
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry(log), snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(600))

        #expect(await log.bars == 1, "A two-day-old copy of a daily series is stale")
    }

    @Test("A forced refresh reaches the network even when the copy is fresh")
    func forcedRefreshIgnoresFreshness() async throws {
        let container = try makeContainer()
        let store = SnapshotStore(modelContainer: container)
        try await store.record(bars: (0..<5).map { bar(day: $0, close: 100) },
                               symbol: "TEST", resolution: .daily, provider: .tiingo)

        let log = CallLog()
        let model = SecurityDetailViewModel(symbol: "TEST")
        // Pull-to-refresh means "I want current data", not "check whether you
        // think it is current".
        model.load(using: registry(log), snapshots: store, force: true)
        try await Task.sleep(for: .milliseconds(600))

        #expect(await log.bars == 1)
    }

    @Test("Offline, the page shows the stored copy instead of blanking")
    func offlineFallsBackToStoredCopy() async throws {
        let container = try makeContainer()
        let store = SnapshotStore(modelContainer: container)
        try await store.record(bars: (0..<5).map { bar(day: $0, close: 100 + Double($0)) },
                               symbol: "TEST", resolution: .daily, provider: .tiingo)

        let log = CallLog()
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry(log, offline: true), snapshots: store)
        try await Task.sleep(for: .milliseconds(600))

        #expect(model.bars.count == 5, "A failed request must not empty a page the store can fill")
        #expect(model.hydratedFromStore)
        #expect(model.isShowingSavedCopy, "And the user is told what they are looking at")
        // Falls back to the last close, which the saved-copy notice dates.
        #expect(model.displayPrice == 104)
        // The change comes from the last two stored closes, 103 → 104.
        let expected = (104.0 - 103.0) / 103.0 * 100
        #expect(abs((model.displayChangePercent ?? 0) - expected) < 0.0001)
    }

    @Test("With nothing stored, an offline page reports failure rather than a saved copy")
    func offlineWithNothingStoredIsHonest() async throws {
        let container = try makeContainer()
        let log = CallLog()
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry(log, offline: true),
                   snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(600))

        #expect(model.bars.isEmpty)
        #expect(!model.isShowingSavedCopy, "There is no saved copy to show")
        #expect(model.displayPrice == nil, "And no price may be invented")
    }

    @Test("Hydration works without a store, leaving the page to fetch as before")
    func noStoreStillLoads() async throws {
        let log = CallLog()
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry(log), snapshots: nil)
        try await Task.sleep(for: .milliseconds(600))

        #expect(await log.bars == 1)
        #expect(!model.hydratedFromStore)
    }

    // MARK: - Watchlist

    @Test("A watchlist row falls back to the last recorded price")
    func watchlistRowUsesStoredQuote() async throws {
        let container = try makeContainer()
        let store = SnapshotStore(modelContainer: container)
        try await store.record(
            quote: QuoteDTO(symbol: "TEST", last: 150, open: nil, high: nil, low: nil,
                            previousClose: 100, volume: nil, quoteTime: nil),
            symbol: "TEST", provider: .finnhub)

        let context = ModelContext(container)
        let entries = try context.fetch(FetchDescriptor<WatchlistEntry>())

        let model = WatchlistViewModel()
        model.load(entries: entries, registry: registry(CallLog(), offline: true),
                   snapshots: store, force: true)
        try await Task.sleep(for: .milliseconds(600))

        let row = try #require(model.rows.first)
        // A price from an hour ago beats a column of error text: the refresh
        // failing does not make the last known price untrue, only old.
        #expect(row.last == 150)
        #expect(row.changePercent == 50)
        #expect(row.isStoredCopy)
        #expect(row.asOf != nil, "And it is dated, so it cannot pass for live")
    }

    @Test("A live quote supersedes the stored one and is not marked as saved")
    func liveQuoteWinsOverStored() async throws {
        let container = try makeContainer()
        let store = SnapshotStore(modelContainer: container)
        try await store.record(
            quote: QuoteDTO(symbol: "TEST", last: 150, open: nil, high: nil, low: nil,
                            previousClose: 100, volume: nil, quoteTime: nil),
            symbol: "TEST", provider: .finnhub)

        let context = ModelContext(container)
        let entries = try context.fetch(FetchDescriptor<WatchlistEntry>())

        let model = WatchlistViewModel()
        model.load(entries: entries, registry: registry(CallLog()),
                   snapshots: store, force: true)
        try await Task.sleep(for: .milliseconds(600))

        let row = try #require(model.rows.first)
        #expect(row.last == 200, "The stub's live quote, not the stored 150")
        #expect(!row.isStoredCopy)
        #expect(row.asOf == nil)
    }
}

// MARK: - Fundamental detection through the page

private struct StubFundamentalsProvider: FundamentalsProvider {
    let id: DataProviderID = .sec
    let facts: [FinancialFactDTO]
    func isConfigured() async -> Bool { true }
    func facts(symbol: String, cik: String?, concepts: [FinancialConcept],
               since: Date?) async throws -> [FinancialFactDTO] { facts }
}

private struct StubSECProvider: SECDataProvider {
    let id: DataProviderID = .sec
    func isConfigured() async -> Bool { true }
    func resolveCIK(symbol: String) async throws -> String { "0000000320" }
    func filings(cik: String, formTypes: [String], limit: Int) async throws -> [FilingDTO] { [] }
    func insiderTransactions(cik: String, since: Date?) async throws -> [InsiderTransactionDTO] { [] }
}

/// The detectors reaching the screen, not just passing in isolation.
@Suite("Fundamental events on the detail page", .serialized)
@MainActor
struct FundamentalEventWiringTests {
    private let calendar = Calendar(identifier: .iso8601)

    private func quarterEnd(_ index: Int) -> Date {
        let year = 2019 + index / 4
        let month = [3, 6, 9, 12][index % 4]
        let day = [31, 30, 30, 31][index % 4]
        return calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    private func fact(_ concept: FinancialConcept, index: Int, value: Double,
                      accession: String? = nil, filedDays: Double = 30) -> FinancialFactDTO {
        let end = quarterEnd(index)
        return FinancialFactDTO(
            concept: concept, rawTag: nil,
            periodStart: end.addingTimeInterval(-90 * 86_400), periodEnd: end,
            fiscalYear: calendar.component(.year, from: end), fiscalQuarter: index % 4 + 1,
            isAnnual: false, periodKind: .quarter, value: value, unit: "USD",
            filedAt: end.addingTimeInterval(filedDays * 86_400),
            accessionNumber: accession ?? "acc-\(index)")
    }

    /// Twenty quarters of stable gross margin, breaking in the last one.
    private var breakingMarginFacts: [FinancialFactDTO] {
        let margins: [Double] = [
            40.0, 40.2, 39.8, 40.1, 40.3, 39.9, 40.0, 40.2, 40.1, 39.8,
            40.2, 40.0, 39.9, 40.3, 40.1, 40.0, 40.2, 39.9, 40.1, 34.0
        ]
        return margins.enumerated().flatMap { index, margin in
            [fact(.revenue, index: index, value: 1_000),
             fact(.grossProfit, index: index, value: 1_000 * margin / 100)]
        }
    }

    private func makeContainer() throws -> ModelContainer {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()
        return container
    }

    private func registry(facts: [FinancialFactDTO]) -> ProviderRegistry {
        ProviderRegistry(
            marketData: StubMarketProvider(log: CallLog(), isOffline: true),
            fundamentals: StubFundamentalsProvider(facts: facts),
            analyst: nil, metrics: nil, sec: StubSECProvider(),
            macro: nil, news: nil, isUsingSampleData: false
        )
    }

    @Test("A margin break reaches the page as an event")
    func marginEventReachesThePage() async throws {
        let container = try makeContainer()
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry(facts: breakingMarginFacts),
                   snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(800))

        #expect(model.events.contains { $0.kind == .marginChange },
                "The detectors are wired into the page, not only unit-tested")
    }

    @Test("An amendment is caught on the visit it arrives, not the one after")
    func restatementIsCaughtImmediately() async throws {
        let container = try makeContainer()
        let store = SnapshotStore(modelContainer: container)

        // The figure as originally reported, on disk from a visit three days
        // ago. Inserted directly so `observedAt` can be backdated: recorded
        // through the store it would be fresh, the staleness gate would
        // correctly skip the refetch, and the amendment would never arrive.
        var original = breakingMarginFacts
        original.append(fact(.revenue, index: 20, value: 1_000, accession: "original"))

        let context = ModelContext(container)
        let security = try #require(try context.fetch(FetchDescriptor<Security>()).first)
        let threeDaysAgo = Date.now.addingTimeInterval(-3 * 86_400)
        for stored in original {
            context.insert(FinancialFactRecord(
                security: security, concept: stored.concept.rawValue, rawTag: stored.rawTag,
                periodStart: stored.periodStart, periodEnd: stored.periodEnd,
                fiscalYear: stored.fiscalYear, fiscalQuarter: stored.fiscalQuarter,
                isAnnual: stored.isAnnual, value: stored.value, unit: stored.unit,
                filedAt: stored.filedAt, accessionNumber: stored.accessionNumber,
                observedAt: threeDaysAgo))
        }
        try context.save()

        // The same period, refiled with a different number.
        var amended = breakingMarginFacts
        amended.append(fact(.revenue, index: 20, value: 1_150,
                            accession: "amended", filedDays: 200))

        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry(facts: amended), snapshots: store)
        try await Task.sleep(for: .milliseconds(800))

        // Detection runs before persistence, so reading the store alone would
        // miss the amendment until the next visit.
        #expect(model.events.contains { $0.kind == .fundamentalShift },
                "A restatement must not be delayed by one visit")
    }

    @Test("A company with no reported history produces no fundamental events")
    func noFactsMeansNoEvents() async throws {
        let container = try makeContainer()
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry(facts: []),
                   snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(800))

        #expect(!model.events.contains { $0.kind == .marginChange })
        #expect(!model.events.contains { $0.kind == .fundamentalShift })
    }
}
