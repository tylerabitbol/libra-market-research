import Foundation
import SwiftData
import OSLog

/// Writes observations into the append-only store.
///
/// Until now the schema in `Models/Core` described a history the app never
/// actually recorded: only `Security` and `WatchlistEntry` were ever written,
/// so nothing could answer "what changed since I last looked" — the question
/// the whole product is built around.
///
/// Two rules govern everything here:
///
/// - **Append, never update.** A restatement or a re-quote inserts a new row
///   carrying its own `observedAt`. Overwriting would destroy exactly the
///   record that makes historical comparison possible (Section 17).
/// - **Idempotent on natural keys.** Refreshing a screen must not multiply
///   rows. Observations that are genuinely new-in-time (quotes) always insert;
///   observations describing a fixed past fact (a bar for a given session, a
///   filing) insert once.
///
/// A `@ModelActor` so writes happen off the main actor, keeping a large XBRL
/// import from stalling the UI.
@ModelActor
actor SnapshotStore {
    private static let logger = Logger(
        subsystem: "com.tylerabitbol.vantage", category: "persistence"
    )

    // MARK: - Quotes

    /// Records a quote observation. Always inserts: a quote is a reading at a
    /// moment, and two readings of the same price at different times are two
    /// facts, not one.
    func record(quote: QuoteDTO, symbol: String) throws {
        guard let security = try security(for: symbol) else { return }
        let observation = QuoteObservation(
            security: security,
            quoteTime: quote.quoteTime,
            last: quote.last,
            open: quote.open, high: quote.high, low: quote.low,
            previousClose: quote.previousClose, volume: quote.volume
        )
        modelContext.insert(observation)
        try modelContext.save()
    }

    // MARK: - Price bars

    /// Records daily bars, skipping sessions already stored.
    ///
    /// A bar describes a closed session and does not change, so re-fetching a
    /// range must not duplicate it. Existing dates are read once and compared
    /// in memory rather than issuing a query per bar.
    @discardableResult
    func record(bars: [PriceBarDTO], symbol: String, resolution: BarResolution) throws -> Int {
        guard !bars.isEmpty, let security = try security(for: symbol) else { return 0 }

        let symbolKey = security.symbol
        let resolutionKey = resolution.rawValue
        let descriptor = FetchDescriptor<PriceBar>(
            predicate: #Predicate { bar in
                bar.security?.symbol == symbolKey && bar.resolutionRaw == resolutionKey
            }
        )
        let existing = Set((try? modelContext.fetch(descriptor))?.map(\.date) ?? [])

        var inserted = 0
        for bar in bars where !existing.contains(bar.date) {
            modelContext.insert(PriceBar(
                security: security, date: bar.date, resolution: resolution,
                open: bar.open, high: bar.high, low: bar.low, close: bar.close,
                volume: bar.volume, adjustedClose: bar.adjustedClose
            ))
            inserted += 1
        }
        if inserted > 0 { try modelContext.save() }
        return inserted
    }

    // MARK: - Fundamentals

    /// Records reported figures, keeping restatements alongside originals.
    ///
    /// A fact is considered already-stored only when the same concept, period
    /// and accession number are present. A new accession for a period the app
    /// already holds is a restatement and is inserted, which is what allows
    /// "what did this look like before it was corrected".
    @discardableResult
    func record(facts: [FinancialFactDTO], symbol: String) throws -> Int {
        guard !facts.isEmpty, let security = try security(for: symbol) else { return 0 }

        let symbolKey = security.symbol
        let descriptor = FetchDescriptor<FinancialFactRecord>(
            predicate: #Predicate { $0.security?.symbol == symbolKey }
        )
        let existing = Set((try? modelContext.fetch(descriptor))?.map(Self.factKey) ?? [])

        var inserted = 0
        for fact in facts {
            let key = Self.factKey(concept: fact.concept.rawValue,
                                   periodEnd: fact.periodEnd,
                                   accession: fact.accessionNumber)
            guard !existing.contains(key) else { continue }
            modelContext.insert(FinancialFactRecord(
                security: security,
                concept: fact.concept.rawValue,
                rawTag: fact.rawTag,
                periodStart: fact.periodStart,
                periodEnd: fact.periodEnd,
                fiscalYear: fact.fiscalYear,
                fiscalQuarter: fact.fiscalQuarter,
                isAnnual: fact.isAnnual,
                value: fact.value,
                unit: fact.unit,
                filedAt: fact.filedAt,
                accessionNumber: fact.accessionNumber
            ))
            inserted += 1
        }
        if inserted > 0 { try modelContext.save() }
        return inserted
    }

    // MARK: - Filings

    /// Records filings. `accessionNumber` is unique in the schema, so a
    /// duplicate insert would fail the whole save — existing rows are filtered
    /// out first rather than relying on the constraint to catch them.
    @discardableResult
    func record(filings: [FilingDTO], symbol: String) throws -> Int {
        guard !filings.isEmpty, let security = try security(for: symbol) else { return 0 }

        let numbers = Set(filings.map(\.accessionNumber))
        let descriptor = FetchDescriptor<FilingRecord>(
            predicate: #Predicate { numbers.contains($0.accessionNumber) }
        )
        let existing = Set((try? modelContext.fetch(descriptor))?.map(\.accessionNumber) ?? [])

        var inserted = 0
        for filing in filings where !existing.contains(filing.accessionNumber) {
            modelContext.insert(FilingRecord(
                security: security,
                accessionNumber: filing.accessionNumber,
                formType: filing.formType,
                filedAt: filing.filedAt,
                periodOfReport: filing.periodOfReport,
                primaryDocumentURL: filing.primaryDocumentURL,
                filingIndexURL: filing.filingIndexURL
            ))
            inserted += 1
        }
        if inserted > 0 { try modelContext.save() }
        return inserted
    }

    // MARK: - Reading history back

    /// The most recent quote observation recorded before `date`.
    ///
    /// Returns a value type rather than the `@Model` object: SwiftData models
    /// are not `Sendable`, and handing one out of the actor would let it be
    /// read on another thread against a context it does not belong to.
    func lastQuote(symbol: String, before date: Date) throws -> QuoteSnapshot? {
        var descriptor = FetchDescriptor<QuoteObservation>(
            predicate: #Predicate { $0.security?.symbol == symbol && $0.observedAt < date },
            sortBy: [SortDescriptor(\.observedAt, order: .reverse)]
        )
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map(QuoteSnapshot.init)
    }

    func observationCount(symbol: String) throws -> Int {
        try modelContext.fetchCount(FetchDescriptor<QuoteObservation>(
            predicate: #Predicate { $0.security?.symbol == symbol }
        ))
    }

    // MARK: - Helpers

    /// Looks up the security, creating nothing: history is only recorded for
    /// companies the user has actually added, so a stray symbol cannot quietly
    /// populate the store.
    private func security(for symbol: String) throws -> Security? {
        let key = symbol.uppercased()
        let descriptor = FetchDescriptor<Security>(predicate: #Predicate { $0.symbol == key })
        return try modelContext.fetch(descriptor).first
    }

    private static func factKey(_ record: FinancialFactRecord) -> String {
        factKey(concept: record.concept,
                periodEnd: record.periodEnd,
                accession: record.accessionNumber)
    }

    private static func factKey(concept: String, periodEnd: Date, accession: String?) -> String {
        "\(concept)|\(SECFundamentalsProvider.periodKey(periodEnd))|\(accession ?? "-")"
    }
}


/// A quote observation lifted out of the store as a value.
///
/// The comparison primitive Phase 6's "what changed since last time" is built
/// on, and safe to pass between actors.
struct QuoteSnapshot: Sendable, Hashable {
    let observedAt: Date
    let last: Double
    let previousClose: Double?
    let changePercent: Double?

    init(_ observation: QuoteObservation) {
        observedAt = observation.observedAt
        last = observation.last
        previousClose = observation.previousClose
        changePercent = observation.changePercent
    }
}
