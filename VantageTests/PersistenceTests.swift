import Testing
import Foundation
import SwiftData
@testable import Vantage

/// Append-only persistence semantics.
///
/// These are the guarantees Section 17 rests on: history accumulates, nothing
/// is overwritten, and refreshing a screen does not multiply rows. Until this
/// layer existed the schema stored nothing at all, so none of it was exercised.
@Suite("Snapshot store", .serialized)
struct PersistenceTests {

    private func makeStore() throws -> (SnapshotStore, ModelContainer) {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()
        return (SnapshotStore(modelContainer: container), container)
    }

    private func quote(_ last: Double, previousClose: Double = 100) -> QuoteDTO {
        QuoteDTO(symbol: "TEST", last: last, open: nil, high: nil, low: nil,
                 previousClose: previousClose, volume: nil, quoteTime: nil)
    }

    private func bar(day: Int, close: Double) -> PriceBarDTO {
        PriceBarDTO(date: Date(timeIntervalSince1970: 1_700_000_000 + Double(day) * 86_400),
                    open: close, high: close, low: close, close: close,
                    volume: 1000, adjustedClose: close)
    }

    @Test("Two quotes of the same price are two observations, not one")
    func quotesAlwaysAppend() async throws {
        let (store, _) = try makeStore()
        try await store.record(quote: quote(105), symbol: "TEST")
        try await store.record(quote: quote(105), symbol: "TEST")

        // A quote is a reading at a moment. Two readings are two facts, even at
        // an identical price — that is what makes "since you last looked" work.
        #expect(try await store.observationCount(symbol: "TEST") == 2)
    }

    @Test("Re-recording the same bars does not duplicate them")
    func barsAreIdempotent() async throws {
        let (store, container) = try makeStore()
        let bars = (0..<5).map { bar(day: $0, close: 100 + Double($0)) }

        let first = try await store.record(bars: bars, symbol: "TEST", resolution: .daily)
        let second = try await store.record(bars: bars, symbol: "TEST", resolution: .daily)

        #expect(first == 5)
        #expect(second == 0, "A closed session does not change; refetching must not duplicate it")

        let context = ModelContext(container)
        #expect(try context.fetchCount(FetchDescriptor<PriceBar>()) == 5)
    }

    @Test("A later fetch adds only the new sessions")
    func barsExtendIncrementally() async throws {
        let (store, container) = try makeStore()
        try await store.record(bars: (0..<5).map { bar(day: $0, close: 100) },
                               symbol: "TEST", resolution: .daily)
        let added = try await store.record(bars: (0..<8).map { bar(day: $0, close: 100) },
                                           symbol: "TEST", resolution: .daily)
        #expect(added == 3)

        let context = ModelContext(container)
        #expect(try context.fetchCount(FetchDescriptor<PriceBar>()) == 8)
    }

    @Test("A restatement adds a row rather than replacing the original")
    func restatementsAppend() async throws {
        let (store, container) = try makeStore()
        let period = Date(timeIntervalSince1970: 1_700_000_000)

        func fact(value: Double, accession: String) -> FinancialFactDTO {
            FinancialFactDTO(
                concept: .revenue, rawTag: "us-gaap:Revenues", periodStart: nil,
                periodEnd: period, fiscalYear: 2025, fiscalQuarter: 2,
                isAnnual: false, periodKind: .quarter, value: value, unit: "USD",
                filedAt: period, accessionNumber: accession)
        }

        try await store.record(facts: [fact(value: 100, accession: "original")], symbol: "TEST")
        try await store.record(facts: [fact(value: 110, accession: "restated")], symbol: "TEST")

        let context = ModelContext(container)
        let stored = try context.fetch(FetchDescriptor<FinancialFactRecord>())
        // Both survive: "what did this look like before it was corrected" is
        // precisely the question the append-only design exists to answer.
        #expect(stored.count == 2)
        #expect(Set(stored.map(\.value)) == [100, 110])
    }

    @Test("The same fact from the same filing is stored once")
    func identicalFactsAreIdempotent() async throws {
        let (store, container) = try makeStore()
        let fact = FinancialFactDTO(
            concept: .revenue, rawTag: nil, periodStart: nil,
            periodEnd: Date(timeIntervalSince1970: 1_700_000_000),
            fiscalYear: 2025, fiscalQuarter: 2, isAnnual: false,
            periodKind: .quarter, value: 100, unit: "USD",
            filedAt: nil, accessionNumber: "same")

        try await store.record(facts: [fact], symbol: "TEST")
        try await store.record(facts: [fact], symbol: "TEST")

        let context = ModelContext(container)
        #expect(try context.fetchCount(FetchDescriptor<FinancialFactRecord>()) == 1)
    }

    @Test("Filings are stored once despite the unique accession constraint")
    func filingsAreIdempotent() async throws {
        let (store, container) = try makeStore()
        let filing = FilingDTO(
            accessionNumber: "0000320193-26-000013", formType: "10-Q",
            filedAt: Date(timeIntervalSince1970: 1_700_000_000),
            periodOfReport: nil, primaryDocumentURL: nil, filingIndexURL: nil)

        #expect(try await store.record(filings: [filing], symbol: "TEST") == 1)
        // accessionNumber is @Attribute(.unique); a blind second insert would
        // fail the entire save, taking unrelated rows down with it.
        #expect(try await store.record(filings: [filing], symbol: "TEST") == 0)

        let context = ModelContext(container)
        #expect(try context.fetchCount(FetchDescriptor<FilingRecord>()) == 1)
    }

    @Test("History is only recorded for securities the user actually follows")
    func unknownSymbolIsNotRecorded() async throws {
        let (store, container) = try makeStore()
        try await store.record(quote: quote(105), symbol: "NOTFOLLOWED")

        let context = ModelContext(container)
        #expect(try context.fetchCount(FetchDescriptor<QuoteObservation>()) == 0,
                "A stray symbol must not quietly populate the store")
    }

    @Test("The previous observation can be read back, which is what Phase 6 needs")
    func lastQuoteBeforeDate() async throws {
        let (store, _) = try makeStore()
        try await store.record(quote: quote(100), symbol: "TEST")
        try await Task.sleep(for: .milliseconds(20))
        let cutoff = Date.now
        try await Task.sleep(for: .milliseconds(20))
        try await store.record(quote: quote(120), symbol: "TEST")

        let earlier = try await store.lastQuote(symbol: "TEST", before: cutoff)
        #expect(earlier?.last == 100, "Must return the state as of then, not the latest")

        let latest = try await store.lastQuote(symbol: "TEST", before: .now)
        #expect(latest?.last == 120)
    }

    @Test("Recorded quotes carry a change computed from their own previous close")
    func recordedQuoteComputesChange() async throws {
        let (store, container) = try makeStore()
        try await store.record(quote: quote(110, previousClose: 100), symbol: "TEST")

        let context = ModelContext(container)
        let observation = try #require(
            try context.fetch(FetchDescriptor<QuoteObservation>()).first)
        #expect(observation.changePercent == 10)
        #expect(observation.observedAt <= .now)
    }
}
