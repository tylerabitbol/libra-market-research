import Testing
import Foundation
import SwiftData
@testable import Vantage

/// Synthetic data must not reach disk, and the rows that already did must be
/// removable.
///
/// A keyless run served `SampleData` — a smooth five-year ramp with a bar on
/// every calendar day, weekends included — and the store recorded it. Nothing
/// afterwards could tell those bars from real sessions, so hydration served
/// them in preference to fetching, and the Security Detail page showed a live
/// $316 quote beside a chart, a range return and a moving-average claim all
/// computed from an invented ~$1,066 series, with no sample-data banner.
@Suite("Synthetic data never persists", .serialized)
struct SyntheticDataTests {

    private func makeStore() throws -> (SnapshotStore, ModelContainer) {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()
        return (SnapshotStore(modelContainer: container), container)
    }

    private func bar(day: Int, close: Double) -> PriceBarDTO {
        PriceBarDTO(date: Date(timeIntervalSince1970: 1_700_000_000 + Double(day) * 86_400),
                    open: close, high: close, low: close, close: close,
                    volume: 1_000, adjustedClose: close)
    }

    // MARK: - The door

    @Test("Sample bars are refused, not stored and flagged")
    func sampleBarsAreRefused() async throws {
        let (store, container) = try makeStore()
        let written = try await store.record(
            bars: (0..<5).map { bar(day: $0, close: 100 + Double($0)) },
            symbol: "TEST", resolution: .daily, provider: .sample)

        #expect(written == 0)
        let context = ModelContext(container)
        #expect(try context.fetchCount(FetchDescriptor<PriceBar>()) == 0)
        // And the read path a page uses to decide whether to spend a request
        // reports nothing held, so the request actually gets made.
        #expect(try await store.bars(symbol: "TEST").isEmpty)
        #expect(try await store.latestObservedAt(symbol: "TEST", kind: .bars) == nil)
    }

    @Test("Every ingest path that a mock can reach refuses it")
    func everySyntheticWriteIsRefused() async throws {
        let (store, _) = try makeStore()
        let when = Date(timeIntervalSince1970: 1_700_000_000)

        try await store.record(
            quote: QuoteDTO(symbol: "TEST", last: 100, open: nil, high: nil, low: nil,
                            previousClose: 99, volume: nil, quoteTime: when),
            symbol: "TEST", provider: .sample)
        let filings = try await store.record(filings: [
            FilingDTO(accessionNumber: "0000000000-00-000001", formType: "10-Q",
                      filedAt: when, periodOfReport: nil,
                      primaryDocumentURL: nil, filingIndexURL: nil)
        ], symbol: "TEST", provider: .sample)
        let insiders = try await store.record(insiders: [
            InsiderTransactionDTO(
                accessionNumber: "0000000000-00-900000", insiderName: "Sample Insider 1",
                insiderTitle: "Director", isDirector: true, isOfficer: false,
                isTenPercentOwner: false, transactionDate: when, filedAt: when,
                transactionCode: "P", isUnderTradingPlan: false, shares: 100,
                pricePerShare: 10, sharesOwnedAfter: 1_000)
        ], symbol: "TEST", provider: .sample)
        let events = try await store.record(events: [
            DetectedEventDTO(kind: .unusualPriceMove, occurredAt: when,
                             headline: "Fell 4.2%")
        ], symbol: "TEST", provider: .sample)

        #expect(try await store.observationCount(symbol: "TEST") == 0)
        #expect(filings == 0)
        #expect(insiders == 0)
        // An event detected over invented bars is an invented headline, and the
        // Research feed shows stored events with nothing marking their origin.
        #expect(events == 0)
    }

    @Test("Synthetic financial facts are refused like everything else")
    func syntheticFactsAreRefused() async throws {
        let (store, container) = try makeStore()
        let when = Date(timeIntervalSince1970: 1_700_000_000)
        let written = try await store.record(facts: [
            FinancialFactDTO(concept: .revenue, rawTag: "us-gaap:Revenues",
                             periodStart: when.addingTimeInterval(-90 * 86_400),
                             periodEnd: when, fiscalYear: 2024, fiscalQuarter: 4,
                             isAnnual: false, periodKind: .quarter, value: 1_000,
                             unit: "USD", filedAt: when,
                             accessionNumber: "0000000000-00-000001")
        ], symbol: "TEST", provider: .sample)

        // No mock fundamentals provider exists today, so nothing can reach this
        // path — which is exactly why the guard is here rather than a comment
        // saying it cannot happen.
        #expect(written == 0)
        #expect(try ModelContext(container)
            .fetchCount(FetchDescriptor<FinancialFactRecord>()) == 0)
    }

    @Test("Mock facts on disk go by the same impossible accession number")
    func evictionRemovesMockFacts() throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        let security = Security(symbol: "TEST", name: "Test Corp")
        context.insert(security)
        let when = Date(timeIntervalSince1970: 1_700_000_000)
        context.insert(FinancialFactRecord(
            security: security, concept: "revenue", periodEnd: when,
            fiscalYear: 2024, isAnnual: false, value: 1_000, unit: "USD",
            accessionNumber: "0000000000-00-000001"))
        context.insert(FinancialFactRecord(
            security: security, concept: "revenue", periodEnd: when,
            fiscalYear: 2023, isAnnual: true, value: 2_000, unit: "USD",
            accessionNumber: "0000320193-24-000123"))
        try context.save()

        AppModelContainer.evictSyntheticRows(in: container)

        let remaining = try ModelContext(container)
            .fetch(FetchDescriptor<FinancialFactRecord>())
        #expect(remaining.map(\.accessionNumber) == ["0000320193-24-000123"])
    }

    @Test("A real bar keeps the name of the provider that supplied it")
    func realBarsCarryProvenance() async throws {
        let (store, container) = try makeStore()
        try await store.record(bars: [bar(day: 0, close: 100)], symbol: "TEST",
                               resolution: .daily, provider: .tiingo)

        let context = ModelContext(container)
        let stored = try #require(try context.fetch(FetchDescriptor<PriceBar>()).first)
        // The column the store had no way to answer before: which provider
        // produced this row.
        #expect(stored.provider == .tiingo)
    }

    // MARK: - What is already on disk

    @Test("Unattributed bars are evicted, attributed ones are kept")
    func evictionRemovesUnattributedBars() throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        let security = Security(symbol: "TEST", name: "Test Corp")
        context.insert(security)
        let base = Date(timeIntervalSince1970: 1_700_000_000)
        // What a keyless run left: no provider, because the column did not
        // exist when it was written.
        for day in 0..<3 {
            context.insert(PriceBar(security: security,
                                    date: base.addingTimeInterval(Double(day) * 86_400),
                                    resolution: .daily, open: 100, high: 100, low: 100,
                                    close: 100))
        }
        context.insert(PriceBar(security: security, date: base.addingTimeInterval(86_400 * 9),
                                resolution: .daily, open: 100, high: 100, low: 100,
                                close: 100, provider: .tiingo))
        try context.save()

        AppModelContainer.evictSyntheticRows(in: container)

        let remaining = try ModelContext(container).fetch(FetchDescriptor<PriceBar>())
        #expect(remaining.count == 1, "A bar of unknown origin cannot be trusted or refetched around")
        #expect(remaining.first?.provider == .tiingo)
    }

    @Test("Mock filings and Form 4 lines go by their impossible accession number")
    func evictionRemovesMockDocuments() throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        let security = Security(symbol: "TEST", name: "Test Corp")
        context.insert(security)
        let when = Date(timeIntervalSince1970: 1_700_000_000)
        // EDGAR builds an accession from the filer's own ten-digit CIK, and no
        // filer has CIK zero. Only `MockSECDataProvider` emits this.
        context.insert(FilingRecord(security: security,
                                    accessionNumber: "0000000000-00-000000",
                                    formType: "10-Q", filedAt: when))
        context.insert(FilingRecord(security: security,
                                    accessionNumber: "0000320193-24-000123",
                                    formType: "10-K", filedAt: when))
        context.insert(InsiderTransaction(
            security: security, accessionNumber: "0000000000-00-900000",
            insiderName: "Sample Insider 1", insiderTitle: "Director",
            isDirector: true, isOfficer: false, isTenPercentOwner: false,
            transactionDate: when, filedAt: when, transactionCode: "P",
            isUnderTradingPlan: false))
        try context.save()

        AppModelContainer.evictSyntheticRows(in: container)

        let read = ModelContext(container)
        let filings = try read.fetch(FetchDescriptor<FilingRecord>())
        #expect(filings.map(\.accessionNumber) == ["0000320193-24-000123"])
        #expect(try read.fetchCount(FetchDescriptor<InsiderTransaction>()) == 0)
    }

    @Test("Events derived from evicted series go too, but only the derivable kinds")
    func evictionRemovesDerivedEventsOnly() throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        let security = Security(symbol: "TEST", name: "Test Corp")
        let untouched = Security(symbol: "OTHER", name: "Other Corp")
        context.insert(security)
        context.insert(untouched)
        let when = Date(timeIntervalSince1970: 1_700_000_000)
        context.insert(PriceBar(security: security, date: when, resolution: .daily,
                                open: 100, high: 100, low: 100, close: 100))
        context.insert(DetectedEvent(security: security, kind: .unusualPriceMove,
                                     occurredAt: when, headline: "Fell 4.2%"))
        context.insert(DetectedEvent(security: security, kind: .marginChange,
                                     occurredAt: when, headline: "Gross margin fell"))
        context.insert(DetectedEvent(security: untouched, kind: .unusualPriceMove,
                                     occurredAt: when, headline: "Rose 3.1%"))
        try context.save()

        AppModelContainer.evictSyntheticRows(in: container)

        let stored = try ModelContext(container).fetch(FetchDescriptor<DetectedEvent>())
        let headlines = Set(stored.map(\.headline))
        // The price move is re-detected from bars on the next visit, so
        // deleting it costs nothing permanent. The margin change is not
        // backfilled by anything, and the sample registry cannot produce
        // fundamentals in the first place — it stays.
        #expect(headlines == ["Gross margin fell", "Rose 3.1%"])
    }

    @Test("Eviction is a no-op on a store that holds only attributed rows")
    func evictionIsIdempotent() throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        let security = Security(symbol: "TEST", name: "Test Corp")
        context.insert(security)
        context.insert(PriceBar(security: security,
                                date: Date(timeIntervalSince1970: 1_700_000_000),
                                resolution: .daily, open: 100, high: 100, low: 100,
                                close: 100, provider: .tiingo))
        try context.save()

        #expect(AppModelContainer.evictSyntheticRows(in: container) == 0)
        #expect(AppModelContainer.evictSyntheticRows(in: container) == 0)
        #expect(try ModelContext(container).fetchCount(FetchDescriptor<PriceBar>()) == 1)
    }

    // MARK: - Identity

    @Test("No mock answers to a real vendor's identity")
    func mocksAreNotVendors() {
        #expect(MockMarketDataProvider().id == .sample)
        #expect(MockSECDataProvider().id == .sample)
        #expect(MockMacroDataProvider().id == .sample)
        #expect(MockNewsProvider().id == .sample)
        #expect(DataProviderID.sample.isSynthetic)
        for real in [DataProviderID.finnhub, .tiingo, .fred, .sec, .computed] {
            #expect(!real.isSynthetic)
        }
    }
}
