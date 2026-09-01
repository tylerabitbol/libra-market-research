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

    // MARK: - Reading the store back
    //
    // Until these methods existed the store was write-only: bars, facts and
    // filings were recorded on every visit and never read back, so each visit
    // re-fetched history it already held against Tiingo's 50-per-hour budget,
    // and nothing could open offline.

    private func quarterFact(
        _ concept: FinancialConcept,
        value: Double,
        periodEnd: Date,
        accession: String,
        filedAt: Date
    ) -> FinancialFactDTO {
        FinancialFactDTO(
            concept: concept, rawTag: nil,
            periodStart: periodEnd.addingTimeInterval(-90 * 86_400),
            periodEnd: periodEnd, fiscalYear: 2025, fiscalQuarter: 2,
            isAnnual: false, periodKind: .quarter, value: value, unit: "USD",
            filedAt: filedAt, accessionNumber: accession)
    }

    @Test("Bars written are bars read back, oldest first")
    func barsRoundTrip() async throws {
        let (store, _) = try makeStore()
        try await store.record(bars: (0..<5).map { bar(day: $0, close: 100 + Double($0)) },
                               symbol: "TEST", resolution: .daily)

        let read = try await store.bars(symbol: "TEST")
        #expect(read.count == 5)
        // Ascending by date. A detector handed these in reverse would treat the
        // oldest session as the newest and judge the wrong day.
        #expect(read.map(\.close) == [100, 101, 102, 103, 104])
        #expect(read.first?.adjustedClose == 100, "The adjusted series must survive the round trip")
    }

    @Test("A bounded read returns only the sessions asked for")
    func barsRespectRange() async throws {
        let (store, _) = try makeStore()
        try await store.record(bars: (0..<10).map { bar(day: $0, close: 100 + Double($0)) },
                               symbol: "TEST", resolution: .daily)

        let base = Date(timeIntervalSince1970: 1_700_000_000)
        let read = try await store.bars(symbol: "TEST",
                                        from: base.addingTimeInterval(3 * 86_400),
                                        to: base.addingTimeInterval(6 * 86_400))
        #expect(read.map(\.close) == [103, 104, 105, 106], "Both bounds are inclusive")
    }

    @Test("Bars stored at one resolution are not returned for another")
    func barsAreResolutionScoped() async throws {
        let (store, _) = try makeStore()
        try await store.record(bars: (0..<3).map { bar(day: $0, close: 100) },
                               symbol: "TEST", resolution: .daily)

        let weekly = try await store.bars(symbol: "TEST", resolution: .weekly)
        #expect(weekly.isEmpty, "Mixing resolutions would put weekly bars in a daily return series")
    }

    @Test("Reading facts collapses a restatement to the figure filed most recently")
    func factsCollapseRestatements() async throws {
        let (store, _) = try makeStore()
        let period = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.record(facts: [
            quarterFact(.revenue, value: 100, periodEnd: period,
                        accession: "original", filedAt: period),
            quarterFact(.revenue, value: 110, periodEnd: period,
                        accession: "restated", filedAt: period.addingTimeInterval(86_400))
        ], symbol: "TEST")

        let read = try await store.facts(symbol: "TEST", concepts: [.revenue])
        #expect(read.count == 1, "Two conflicting figures for one quarter must not both reach a chart")
        #expect(read.first?.value == 110, "The correction supersedes the original")
    }

    @Test("Both versions stay readable, which is what append-only was for")
    func factRevisionsKeepSupersededRows() async throws {
        let (store, _) = try makeStore()
        let period = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.record(facts: [
            quarterFact(.revenue, value: 100, periodEnd: period,
                        accession: "original", filedAt: period),
            quarterFact(.revenue, value: 110, periodEnd: period,
                        accession: "restated", filedAt: period.addingTimeInterval(86_400))
        ], symbol: "TEST")

        let revisions = try await store.factRevisions(symbol: "TEST", concept: .revenue)
        #expect(revisions.map(\.value) == [100, 110], "Ordered by when each version was filed")
    }

    @Test("Two concepts for the same quarter both survive the read")
    func factsDoNotCollapseAcrossConcepts() async throws {
        let (store, _) = try makeStore()
        let period = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.record(facts: [
            quarterFact(.revenue, value: 500, periodEnd: period, accession: "a", filedAt: period),
            quarterFact(.netIncome, value: 50, periodEnd: period, accession: "a", filedAt: period)
        ], symbol: "TEST")

        let read = try await store.facts(symbol: "TEST")
        // The dedup helper keys on the period alone, because the provider calls
        // it with one concept already fixed. Handed a mixed-concept array it
        // would drop one of these silently — revenue or net income, whichever
        // lost the dictionary race.
        #expect(Set(read.map(\.concept)) == [.revenue, .netIncome])
    }

    @Test("`since` bounds the period described, not when it was observed")
    func factsRespectSince() async throws {
        let (store, _) = try makeStore()
        let old = Date(timeIntervalSince1970: 1_600_000_000)
        let recent = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.record(facts: [
            quarterFact(.revenue, value: 100, periodEnd: old, accession: "old", filedAt: old),
            quarterFact(.revenue, value: 200, periodEnd: recent, accession: "new", filedAt: recent)
        ], symbol: "TEST")

        let read = try await store.facts(symbol: "TEST", concepts: [.revenue],
                                         since: Date(timeIntervalSince1970: 1_650_000_000))
        #expect(read.map(\.value) == [200])
    }

    @Test("A stored quarter reconstitutes as a quarter")
    func factPeriodKindIsRederived() async throws {
        let (store, _) = try makeStore()
        let period = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.record(facts: [
            quarterFact(.revenue, value: 100, periodEnd: period, accession: "a", filedAt: period)
        ], symbol: "TEST")

        // periodKind is not a stored column — it is re-derived from the period's
        // own duration. Getting this wrong would let a cumulative nine-month
        // figure read as a quarter, which is the error that makes Q3 look 3x Q2.
        let read = try await store.facts(symbol: "TEST", concepts: [.revenue])
        #expect(read.first?.periodKind == .quarter)
    }

    @Test("A balance-sheet row with no duration reconstitutes as an instant")
    func instantFactsHaveNoDuration() async throws {
        let (store, _) = try makeStore()
        let period = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.record(facts: [FinancialFactDTO(
            concept: .cashAndEquivalents, rawTag: nil, periodStart: nil,
            periodEnd: period, fiscalYear: 2025, fiscalQuarter: 2, isAnnual: false,
            periodKind: .instant, value: 1000, unit: "USD",
            filedAt: period, accessionNumber: "a")], symbol: "TEST")

        let read = try await store.facts(symbol: "TEST", concepts: [.cashAndEquivalents])
        #expect(read.first?.periodKind == .instant)
    }

    @Test("Filings read back most recently filed first")
    func filingsRoundTrip() async throws {
        let (store, _) = try makeStore()
        let base = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.record(filings: [
            FilingDTO(accessionNumber: "older", formType: "10-K", filedAt: base,
                      periodOfReport: nil, primaryDocumentURL: nil, filingIndexURL: nil),
            FilingDTO(accessionNumber: "newer", formType: "10-Q",
                      filedAt: base.addingTimeInterval(86_400),
                      periodOfReport: nil, primaryDocumentURL: nil, filingIndexURL: nil)
        ], symbol: "TEST")

        let read = try await store.filings(symbol: "TEST")
        #expect(read.map(\.accessionNumber) == ["newer", "older"])
        #expect(read.first?.formType == "10-Q")
    }

    @Test("An empty series reports no observation rather than inventing a date")
    func latestObservedAtIsNilWhenEmpty() async throws {
        let (store, _) = try makeStore()
        for kind in StoredDataKind.allCases {
            let stamp = try await store.latestObservedAt(symbol: "TEST", kind: kind)
            #expect(stamp == nil, "\(kind) must not report freshness for data it does not hold")
        }
    }

    @Test("Each series reports its own last write, independently")
    func latestObservedAtIsPerSeries() async throws {
        let (store, _) = try makeStore()
        try await store.record(bars: [bar(day: 0, close: 100)],
                               symbol: "TEST", resolution: .daily)

        let bars = try await store.latestObservedAt(symbol: "TEST", kind: .bars)
        let facts = try await store.latestObservedAt(symbol: "TEST", kind: .facts)
        #expect(bars != nil)
        // A screen deciding whether to spend a request must not be told
        // fundamentals are fresh because bars happen to be.
        #expect(facts == nil)
    }

    @Test("Reads accept any casing, as every other lookup in the store does")
    func readsAreCaseInsensitive() async throws {
        let (store, _) = try makeStore()
        try await store.record(bars: [bar(day: 0, close: 100)],
                               symbol: "test", resolution: .daily)
        try await store.record(quote: quote(105), symbol: "test")

        let bars = try await store.bars(symbol: "test")
        let count = try await store.observationCount(symbol: "test")
        #expect(bars.count == 1)
        #expect(count == 1)
    }
}
