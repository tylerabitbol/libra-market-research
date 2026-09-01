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

    // MARK: - Insider transactions

    /// Records insider transactions, one row per Form 4 line.
    ///
    /// Keyed on the accession *and* the line's own date and code: a single
    /// filing reports several transactions, so deduplicating on the accession
    /// alone would keep one line and discard the rest.
    @discardableResult
    func record(insiders: [InsiderTransactionDTO], symbol: String) throws -> Int {
        guard !insiders.isEmpty, let security = try security(for: symbol) else { return 0 }

        let symbolKey = security.symbol
        let descriptor = FetchDescriptor<InsiderTransaction>(
            predicate: #Predicate { $0.security?.symbol == symbolKey }
        )
        let existing = Set((try? modelContext.fetch(descriptor))?.map {
            "\($0.accessionNumber)|\($0.transactionDate.timeIntervalSince1970)|\($0.transactionCode)"
        } ?? [])

        var inserted = 0
        for transaction in insiders {
            let key = "\(transaction.accessionNumber)|"
                + "\(transaction.transactionDate.timeIntervalSince1970)|"
                + transaction.transactionCode
            guard !existing.contains(key) else { continue }
            modelContext.insert(InsiderTransaction(
                security: security,
                accessionNumber: transaction.accessionNumber,
                insiderName: transaction.insiderName,
                insiderTitle: transaction.insiderTitle,
                isDirector: transaction.isDirector,
                isOfficer: transaction.isOfficer,
                isTenPercentOwner: transaction.isTenPercentOwner,
                transactionDate: transaction.transactionDate,
                filedAt: transaction.filedAt,
                transactionCode: transaction.transactionCode,
                isUnderTradingPlan: transaction.isUnderTradingPlan,
                shares: transaction.shares,
                pricePerShare: transaction.pricePerShare,
                sharesOwnedAfter: transaction.sharesOwnedAfter))
            inserted += 1
        }
        if inserted > 0 { try modelContext.save() }
        return inserted
    }

    // MARK: - Detected events

    /// Records detected events, one per kind per day per security.
    ///
    /// Detection re-runs on every visit over the same bars, so the same Tuesday
    /// volume spike would otherwise accumulate a row per refresh. Deduplication
    /// is on `naturalKey` rather than on the headline text: the wording of a
    /// headline can change with a code edit, and that must not resurrect an
    /// event the user has already acknowledged.
    @discardableResult
    func record(events: [DetectedEventDTO], symbol: String) throws -> Int {
        // An unfinished session is not yet a fact. A +8% reading at midday can
        // close at +2%, and the permanent record must not keep the midday
        // figure as what happened — the closed session is picked up from the
        // bars on a later visit. Enforced here rather than at the call site so
        // no future caller can store one by forgetting.
        let events = events.filter { !$0.isProvisional }
        guard !events.isEmpty, let security = try security(for: symbol) else { return 0 }

        let symbolKey = security.symbol
        let descriptor = FetchDescriptor<DetectedEvent>(
            predicate: #Predicate { $0.security?.symbol == symbolKey }
        )
        let existing = Set((try? modelContext.fetch(descriptor))?.map {
            "\($0.kindRaw)|\(DetectedEventDTO.dayKey($0.occurredAt))"
        } ?? [])

        var inserted = 0
        for event in events where !existing.contains(event.naturalKey) {
            modelContext.insert(DetectedEvent(
                security: security,
                kind: event.kind,
                occurredAt: event.occurredAt,
                headline: event.headline,
                detailLines: event.detailLines,
                context: event.context,
                unusualness: event.unusualness,
                sourceDetails: event.sourceDetails,
                sourceURLs: event.sourceURLs,
                derivation: event.derivation
            ))
            inserted += 1
        }
        if inserted > 0 { try modelContext.save() }
        return inserted
    }

    /// Stored events for one security, most recent occurrence first.
    func events(symbol: String, limit: Int = 50) throws -> [DetectedEventDTO] {
        let key = symbol.uppercased()
        var descriptor = FetchDescriptor<DetectedEvent>(
            predicate: #Predicate { $0.security?.symbol == key },
            sortBy: [SortDescriptor(\.occurredAt, order: .reverse)]
        )
        descriptor.fetchLimit = limit
        return try modelContext.fetch(descriptor).map(\.snapshot)
    }

    /// Events across every security in the store, for the Research feed.
    func recentEvents(limit: Int = 100) throws -> [SecurityEvent] {
        var descriptor = FetchDescriptor<DetectedEvent>(
            sortBy: [SortDescriptor(\.occurredAt, order: .reverse)]
        )
        descriptor.fetchLimit = limit
        return try modelContext.fetch(descriptor).compactMap { event in
            guard let security = event.security else { return nil }
            return SecurityEvent(
                symbol: security.symbol,
                name: security.name,
                event: event.snapshot
            )
        }
    }

    func acknowledge(symbol: String, naturalKey: String) throws {
        let key = symbol.uppercased()
        let descriptor = FetchDescriptor<DetectedEvent>(
            predicate: #Predicate { $0.security?.symbol == key }
        )
        for event in try modelContext.fetch(descriptor)
        where event.snapshot.naturalKey == naturalKey {
            event.isAcknowledged = true
        }
        try modelContext.save()
    }

    // MARK: - Screening

    /// Every security the app holds data for, with the figures a screen can
    /// test — read entirely from disk, issuing no requests.
    ///
    /// Benchmarks are excluded: a sector ETF is not a company, and screening
    /// one on revenue growth would return nothing while looking like a result.
    func screenSubjects() throws -> [ScreenSubject] {
        let securities = try modelContext.fetch(FetchDescriptor<Security>())
        var subjects: [ScreenSubject] = []

        for security in securities where !security.isBenchmark {
            var subject = ScreenSubject(symbol: security.symbol, name: security.name,
                                        sector: security.sector)

            if let quote = try lastQuote(symbol: security.symbol, before: .now) {
                subject.price = quote.last
                subject.dailyChangePercent = quote.changePercent
            }

            let facts = try facts(symbol: security.symbol)
            if let growth = FundamentalDetector.yearOverYear(
                FundamentalDetector.quarterly(facts, .revenue)).last, growth.prior > 0 {
                subject.revenueGrowth = (growth.current - growth.prior) / growth.prior * 100
            }
            subject.grossMargin = FundamentalDetector
                .marginSeries(facts: facts, numerator: .grossProfit).last?.value
            subject.operatingMargin = FundamentalDetector
                .marginSeries(facts: facts, numerator: .operatingIncome).last?.value

            let debt = FundamentalDetector.instant(facts, .totalDebt).last?.value
            let cash = FundamentalDetector.instant(facts, .cashAndEquivalents).last?.value
            if let debt, let cash { subject.netCash = cash - debt }

            if let event = try events(symbol: security.symbol, limit: 1).first {
                subject.latestUnusualness = event.unusualness
                subject.daysSinceLastEvent = Date.now.timeIntervalSince(event.occurredAt) / 86_400
            }

            subjects.append(subject)
        }
        return subjects.sorted { $0.symbol < $1.symbol }
    }

    // MARK: - Visits

    /// When the user last opened this security, before the current visit.
    ///
    /// Must be read *before* `markViewed` — the whole point is the previous
    /// timestamp, and stamping first would make every visit report no changes.
    func lastViewed(symbol: String) throws -> Date? {
        try security(for: symbol)?.lastViewedAt
    }

    func markViewed(symbol: String, at date: Date = .now) throws {
        guard let security = try security(for: symbol) else { return }
        security.lastViewedAt = date
        try modelContext.save()
    }

    // MARK: - Reading history back

    /// The most recent quote observation recorded before `date`.
    ///
    /// Returns a value type rather than the `@Model` object: SwiftData models
    /// are not `Sendable`, and handing one out of the actor would let it be
    /// read on another thread against a context it does not belong to.
    func lastQuote(symbol: String, before date: Date) throws -> QuoteSnapshot? {
        let key = symbol.uppercased()
        var descriptor = FetchDescriptor<QuoteObservation>(
            predicate: #Predicate { $0.security?.symbol == key && $0.observedAt < date },
            sortBy: [SortDescriptor(\.observedAt, order: .reverse)]
        )
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map(QuoteSnapshot.init)
    }

    func observationCount(symbol: String) throws -> Int {
        let key = symbol.uppercased()
        return try modelContext.fetchCount(FetchDescriptor<QuoteObservation>(
            predicate: #Predicate { $0.security?.symbol == key }
        ))
    }

    // MARK: - Reading the store back

    /// Daily bars already held for a symbol, oldest first.
    ///
    /// The read half of `record(bars:symbol:resolution:)`, which until now had
    /// none. Bars are the scarcest request in the app — Tiingo's free tier
    /// refills roughly one token every 80 seconds — so a screen that can answer
    /// from disk must not spend one. Bounds are inclusive and default to
    /// everything stored.
    func bars(
        symbol: String,
        from: Date? = nil,
        to: Date? = nil,
        resolution: BarResolution = .daily
    ) throws -> [PriceBarDTO] {
        let key = symbol.uppercased()
        let resolutionKey = resolution.rawValue
        // `#Predicate` cannot compare against an optional bound, so an absent
        // bound becomes an unbounded one rather than a branch per combination.
        let lower = from ?? .distantPast
        let upper = to ?? .distantFuture

        let descriptor = FetchDescriptor<PriceBar>(
            predicate: #Predicate { bar in
                bar.security?.symbol == key
                    && bar.resolutionRaw == resolutionKey
                    && bar.date >= lower
                    && bar.date <= upper
            },
            sortBy: [SortDescriptor(\.date, order: .forward)]
        )
        return try modelContext.fetch(descriptor).map {
            PriceBarDTO(date: $0.date, open: $0.open, high: $0.high, low: $0.low,
                        close: $0.close, volume: $0.volume, adjustedClose: $0.adjustedClose)
        }
    }

    /// Reported figures held for a symbol, oldest period first, with
    /// restatements collapsed to the most recently filed figure per period.
    ///
    /// This is the *current* view of the company's history, which is what the
    /// analysis layer wants. `factRevisions(symbol:concept:since:)` returns the
    /// superseded rows alongside it — the question the append-only design
    /// exists to answer, and the input the restatement detector needs.
    ///
    /// `since` bounds the period a figure describes, not when it was observed:
    /// a 2024 quarter restated last week is still a 2024 quarter.
    func facts(
        symbol: String,
        concepts: [FinancialConcept] = FinancialConcept.allCases,
        since: Date? = nil
    ) throws -> [FinancialFactDTO] {
        let rows = try factRows(symbol: symbol, concepts: concepts, since: since)

        // `deduplicated` keys on the period alone, because the provider calls
        // it inside a single concept's loop where the concept is already fixed.
        // Handing it a mixed-concept array would collapse revenue and net
        // income for the same quarter into one row and silently discard the
        // loser, so the rows are grouped by concept before it ever sees them.
        return Dictionary(grouping: rows, by: \.concept)
            .values
            .flatMap(SECFundamentalsProvider.deduplicated)
            .sorted { lhs, rhs in
                lhs.periodEnd == rhs.periodEnd
                    ? lhs.concept.rawValue < rhs.concept.rawValue
                    : lhs.periodEnd < rhs.periodEnd
            }
    }

    /// Every stored version of one concept, restatements included, ordered by
    /// the period described and then by when each version was filed.
    ///
    /// Two rows sharing a period are an issuer revising a figure it had already
    /// reported. Nothing else in the app can see that, because every other read
    /// path deliberately collapses it away.
    func factRevisions(
        symbol: String,
        concept: FinancialConcept,
        since: Date? = nil
    ) throws -> [FinancialFactDTO] {
        try factRows(symbol: symbol, concepts: [concept], since: since)
            .sorted { lhs, rhs in
                lhs.periodEnd == rhs.periodEnd
                    ? (lhs.filedAt ?? .distantPast) < (rhs.filedAt ?? .distantPast)
                    : lhs.periodEnd < rhs.periodEnd
            }
    }

    /// Filings held for a symbol, most recently filed first.
    func filings(symbol: String, limit: Int = 50) throws -> [FilingDTO] {
        let key = symbol.uppercased()
        var descriptor = FetchDescriptor<FilingRecord>(
            predicate: #Predicate { $0.security?.symbol == key },
            sortBy: [SortDescriptor(\.filedAt, order: .reverse)]
        )
        descriptor.fetchLimit = limit
        return try modelContext.fetch(descriptor).map {
            FilingDTO(accessionNumber: $0.accessionNumber, formType: $0.formType,
                      filedAt: $0.filedAt, periodOfReport: $0.periodOfReport,
                      primaryDocumentURL: $0.primaryDocumentURL,
                      filingIndexURL: $0.filingIndexURL)
        }
    }

    /// When a stored series was last written, so a caller can decide whether to
    /// spend a request before it makes one.
    ///
    /// Deliberately the *observation* time rather than the period a record
    /// describes: the question is how old the held copy is, and a freshly
    /// fetched five-year-old annual figure is not stale data.
    func latestObservedAt(symbol: String, kind: StoredDataKind) throws -> Date? {
        let key = symbol.uppercased()
        switch kind {
        case .quote:
            var descriptor = FetchDescriptor<QuoteObservation>(
                predicate: #Predicate { $0.security?.symbol == key },
                sortBy: [SortDescriptor(\.observedAt, order: .reverse)])
            descriptor.fetchLimit = 1
            return try modelContext.fetch(descriptor).first?.observedAt
        case .bars:
            var descriptor = FetchDescriptor<PriceBar>(
                predicate: #Predicate { $0.security?.symbol == key },
                sortBy: [SortDescriptor(\.observedAt, order: .reverse)])
            descriptor.fetchLimit = 1
            return try modelContext.fetch(descriptor).first?.observedAt
        case .facts:
            var descriptor = FetchDescriptor<FinancialFactRecord>(
                predicate: #Predicate { $0.security?.symbol == key },
                sortBy: [SortDescriptor(\.observedAt, order: .reverse)])
            descriptor.fetchLimit = 1
            return try modelContext.fetch(descriptor).first?.observedAt
        case .filings:
            var descriptor = FetchDescriptor<FilingRecord>(
                predicate: #Predicate { $0.security?.symbol == key },
                sortBy: [SortDescriptor(\.observedAt, order: .reverse)])
            descriptor.fetchLimit = 1
            return try modelContext.fetch(descriptor).first?.observedAt
        case .events:
            var descriptor = FetchDescriptor<DetectedEvent>(
                predicate: #Predicate { $0.security?.symbol == key },
                sortBy: [SortDescriptor(\.detectedAt, order: .reverse)])
            descriptor.fetchLimit = 1
            return try modelContext.fetch(descriptor).first?.detectedAt
        }
    }

    private func factRows(
        symbol: String,
        concepts: [FinancialConcept],
        since: Date?
    ) throws -> [FinancialFactDTO] {
        guard !concepts.isEmpty else { return [] }
        let key = symbol.uppercased()
        let conceptKeys = Set(concepts.map(\.rawValue))
        let lower = since ?? .distantPast

        let descriptor = FetchDescriptor<FinancialFactRecord>(
            predicate: #Predicate { record in
                record.security?.symbol == key
                    && conceptKeys.contains(record.concept)
                    && record.periodEnd >= lower
            },
            sortBy: [SortDescriptor(\.periodEnd, order: .forward)]
        )
        return try modelContext.fetch(descriptor).compactMap(Self.factDTO)
    }

    /// Rebuilds the transport type from a stored row.
    ///
    /// Nil for a concept this build no longer understands: a row written by an
    /// older version with a since-renamed concept is skipped rather than
    /// crashing or being coerced into a neighbouring case.
    private static func factDTO(_ record: FinancialFactRecord) -> FinancialFactDTO? {
        guard let concept = FinancialConcept(rawValue: record.concept) else { return nil }
        return FinancialFactDTO(
            concept: concept,
            rawTag: record.rawTag,
            periodStart: record.periodStart,
            periodEnd: record.periodEnd,
            fiscalYear: record.fiscalYear,
            fiscalQuarter: record.fiscalQuarter,
            isAnnual: record.isAnnual,
            // `periodKind` is not a stored column. It is re-derived from the
            // period's own duration exactly as extraction derived it, so a row
            // reconstitutes with the classification it was filtered on — and a
            // cumulative figure could not have been stored in the first place.
            periodKind: FiscalPeriodKind.classify(
                days: record.periodStart.map {
                    record.periodEnd.timeIntervalSince($0) / 86_400
                }
            ),
            value: record.value,
            unit: record.unit,
            filedAt: record.filedAt,
            accessionNumber: record.accessionNumber
        )
    }

    // MARK: - Benchmarks

    /// Ensures a row exists for a benchmark symbol so its history can be
    /// stored and hydrated like any other.
    ///
    /// `security(for:)` deliberately creates nothing — history is only recorded
    /// for companies the user actually follows, so a stray symbol cannot
    /// quietly populate the store. A sector ETF is the exception the
    /// `isBenchmark` flag was added for: the app needs its bars to answer "how
    /// did this company do against its sector", and re-fetching five years of
    /// XLK on every visit would spend the scarcest request in the app on a
    /// series that eleven companies share.
    func ensureBenchmark(symbol: String, name: String) throws {
        let key = symbol.uppercased()
        if try security(for: key) != nil { return }
        let benchmark = Security(symbol: key, name: name)
        benchmark.isBenchmark = true
        modelContext.insert(benchmark)
        try modelContext.save()
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


/// A stored series, for asking how old the held copy is.
///
/// Named per series rather than per model because that is the granularity a
/// refresh decision is made at: quotes go stale in a minute and filings in an
/// hour, and `StalenessPolicy` already encodes exactly that difference.
enum StoredDataKind: String, Sendable, CaseIterable {
    case quote, bars, facts, filings, events
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


/// One event together with the security it belongs to, for feeds that mix
/// securities. The store's `DetectedEvent` reaches its symbol through a
/// relationship, which does not survive the trip out of the actor.
struct SecurityEvent: Sendable, Hashable, Identifiable {
    var id: String { "\(symbol)|\(event.naturalKey)" }
    let symbol: String
    let name: String
    let event: DetectedEventDTO
}
