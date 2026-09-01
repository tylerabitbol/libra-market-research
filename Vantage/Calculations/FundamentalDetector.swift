import Foundation

/// Deterministic detection of change in the *reported figures* — the half of
/// Section 4's "what changed underneath" that price data cannot see.
///
/// `EventDetector` answers "did the price do something unusual". This answers
/// "did the business". Both are pure Swift over data the app already holds, and
/// neither says why or what to do about it.
///
/// Four decisions shape everything here, and each exists because the obvious
/// alternative produces a confident falsehood:
///
/// - **Year-over-year, never quarter-over-quarter.** Most businesses are
///   seasonal. A retailer's Q4 gross margin is not comparable to its Q3, and a
///   detector built on consecutive quarters fires every year at the same time
///   and reports the calendar as a change.
/// - **The comparison period is matched by date, not by counting back four.**
///   A 52/53-week fiscal calendar shifts the closing date between years, and an
///   issuer that skipped reporting a concept for one quarter would silently
///   pair a period against the wrong year.
/// - **Margins and cash flow are compared in percentage points, not percent.**
///   Free cash flow crosses zero regularly, and a percentage change through
///   zero is either infinite or sign-flipped — a company going from -$10M to
///   +$10M does not have a "-200% change" in any useful sense.
/// - **`occurredAt` is when the figure was filed, not the period it covers.**
///   A June quarter disclosed in August is news in August. Dating it to June
///   would file it behind price events the user has already seen and defeat
///   "what changed since I last looked".
enum FundamentalDetector {

    /// Reported periods required before a detector will say anything.
    ///
    /// Lower than the price detectors' 40 because the cadence is quarterly: 8
    /// year-over-year observations already span three years of filings, and
    /// waiting for 40 would mean saying nothing for a decade.
    static let minimumSample = 8

    /// Window for dispersion — three years, so a business that has genuinely
    /// changed shape is judged against what it is now.
    static let scaleWindow = 12

    /// Window for rank. Ten years of quarters; rank costs nothing over more
    /// data and "the largest in the record" is the more useful statement.
    static let rankWindow = 40

    /// How far into its own history a change must sit to be reported.
    ///
    /// Looser than the price detectors' 0.975 because the samples are two
    /// orders of magnitude smaller: against 16 prior quarters, 0.975 means
    /// "larger than every one", which would report only records.
    static let rankThreshold = 0.80

    /// The wording for what these samples count.
    static let sampleUnit = "reported quarters"

    // MARK: - Entry point

    /// Runs every fundamental detector over one company's reported figures.
    ///
    /// Judges the most recently reported period only. Unlike price bars, where
    /// a large move on an unopened day would be lost, a filing stays the latest
    /// filing until the next one lands — so the newest report is still new to a
    /// user returning after a month. A second filing arriving inside one gap
    /// is the exception, and the older of the two is not reported.
    static func detect(
        facts: [FinancialFactDTO],
        sourceDetail: String = "SEC XBRL company facts"
    ) -> [DetectedEventDTO] {
        [
            marginChange(facts: facts, sourceDetail: sourceDetail),
            revenueGrowthChange(facts: facts, sourceDetail: sourceDetail),
            freeCashFlowChange(facts: facts, sourceDetail: sourceDetail),
            debtChange(facts: facts, sourceDetail: sourceDetail)
        ].compactMap { $0 }
    }

    // MARK: - Margins

    /// An unusual year-over-year move in gross, operating or net margin.
    ///
    /// One event covering all three rather than one per margin. They move
    /// together and arrive in the same filing, so three cards would be three
    /// views of one fact — and `naturalKey` is one event per kind per day, so
    /// the store would collapse them anyway and keep an arbitrary one.
    static func marginChange(
        facts: [FinancialFactDTO],
        minimumChangePoints: Double = 1.5,
        sourceDetail: String = "SEC XBRL company facts"
    ) -> DetectedEventDTO? {
        let margins: [(String, FinancialConcept)] = [
            ("Gross margin", .grossProfit),
            ("Operating margin", .operatingIncome),
            ("Net margin", .netIncome)
        ]

        var lines: [String] = []
        var strongest: (measure: AnomalyMeasure, label: String, change: Double)?
        var latestFiledAt: Date?
        var period: Date?

        for (label, concept) in margins {
            let series = marginSeries(facts: facts, numerator: concept)
            guard let move = latestUnusualChange(
                in: series, minimumChange: minimumChangePoints,
                filedAt: { filedAt(for: $0, concept: concept, in: facts) })
            else { continue }

            lines.append(
                "\(label): \(Format.percent(move.priorValue, precision: 1)) → "
                + "\(Format.percent(move.currentValue, precision: 1)) "
                + "(\(Format.percentagePoints(move.change)) YoY)")
            lines.append("  \(move.measure.comparisonLine)")

            if strongest == nil || move.measure.unusualness > strongest!.measure.unusualness {
                strongest = (move.measure, label, move.change)
            }
            if let moveFiledAt = move.filedAt {
                latestFiledAt = max(latestFiledAt ?? moveFiledAt, moveFiledAt)
            }
            period = move.period
        }

        guard let strongest, let period, !lines.isEmpty else { return nil }
        let direction = strongest.change >= 0 ? "widened" : "narrowed"

        return DetectedEventDTO(
            kind: .marginChange,
            occurredAt: latestFiledAt ?? period,
            headline: "\(strongest.label) \(direction) "
                + "\(Format.percentagePoints(abs(strongest.change))) year-over-year",
            detailLines: lines,
            context: "A margin move of this size against this company's own history is "
                + "worth tracing back to the filing. A change in product mix, a one-off "
                + "charge, and sustained pricing pressure all look identical at this level.",
            unusualness: strongest.measure.unusualness,
            sourceDetails: ["\(sourceDetail), period ending \(Format.shortDate(period))"],
            derivation: strongest.measure.derivation(
                label: "\(strongest.label) change",
                formatted: Format.percentagePoints(strongest.change))
        )
    }

    // MARK: - Revenue growth

    /// Year-over-year revenue growth that is unusual against its own history.
    ///
    /// Ranks the growth *rate*, not the change in it. "Grew 8.2%, the slowest
    /// of the 16 quarters on record" is a single checkable statement; ranking
    /// the second derivative produces a number nobody can picture.
    static func revenueGrowthChange(
        facts: [FinancialFactDTO],
        sourceDetail: String = "SEC XBRL company facts"
    ) -> DetectedEventDTO? {
        let revenue = quarterly(facts, .revenue)
        let growth = yearOverYear(revenue).compactMap { pair -> (period: Date, value: Double)? in
            guard pair.prior != 0 else { return nil }
            // Growth off a negative base is not a growth rate; a swing from
            // -100 to -50 is not "50% growth" in any direction a reader would
            // guess. Revenue is rarely negative, but contra-revenue restatements
            // exist and would otherwise produce a confident nonsense figure.
            guard pair.prior > 0 else { return nil }
            return (pair.period, (pair.current - pair.prior) / pair.prior * 100)
        }

        guard let latest = growth.last,
              let measure = measure(latest.value, priors: growth.dropLast().map(\.value)),
              measure.unusualness >= rankThreshold
        else { return nil }

        let priorGrowth = growth.dropLast().last?.value
        let direction = latest.value >= (priorGrowth ?? latest.value) ? "accelerated" : "slowed"
        let filedAt = revenue.last.flatMap { point in
            facts.first { $0.periodEnd == point.period && $0.concept == .revenue }?.filedAt
        }

        var lines = ["Revenue growth: \(Format.signedPercent(latest.value, precision: 1)) YoY"]
        if let priorGrowth {
            lines.append("Prior quarter: \(Format.signedPercent(priorGrowth, precision: 1)) YoY")
        }
        lines.append(measure.comparisonLine)

        return DetectedEventDTO(
            kind: .revenueGrowthChange,
            occurredAt: filedAt ?? latest.period,
            headline: "Revenue growth \(direction) to "
                + "\(Format.signedPercent(latest.value, precision: 1)) year-over-year",
            detailLines: lines,
            context: "Growth is compared with the same quarter a year earlier, so seasonality "
                + "is already removed. Whether this reflects demand, pricing, or a change in "
                + "what the company counts as revenue is not visible from the figure alone.",
            unusualness: measure.unusualness,
            sourceDetails: ["\(sourceDetail), period ending \(Format.shortDate(latest.period))"],
            derivation: measure.derivation(
                label: "Revenue growth",
                formatted: Format.signedPercent(latest.value, precision: 1))
        )
    }

    // MARK: - Free cash flow

    /// An unusual year-over-year move in free cash flow as a share of revenue.
    ///
    /// Measured as a margin rather than as a percentage change in the cash
    /// figure itself, because free cash flow crosses zero: a company moving
    /// from -$10M to +$10M has improved, and "-200%" describes that improvement
    /// as a collapse.
    static func freeCashFlowChange(
        facts: [FinancialFactDTO],
        minimumChangePoints: Double = 2.0,
        sourceDetail: String = "SEC XBRL company facts"
    ) -> DetectedEventDTO? {
        let series = freeCashFlowMarginSeries(facts: facts)
        guard let move = latestUnusualChange(
            in: series, minimumChange: minimumChangePoints,
            filedAt: { filedAt(for: $0, concept: .operatingCashFlow, in: facts) })
        else { return nil }

        let cash = freeCashFlowSeries(facts: facts)
        let latestCash = cash.last(where: { $0.period == move.period })?.value
        let direction = move.change >= 0 ? "improved" : "deteriorated"

        var lines = [
            "Free cash flow margin: \(Format.percent(move.priorValue, precision: 1)) → "
            + "\(Format.percent(move.currentValue, precision: 1)) "
            + "(\(Format.percentagePoints(move.change)) YoY)"
        ]
        if let latestCash {
            lines.append("Free cash flow: \(Format.compactCurrency(latestCash)) "
                         + "(operating cash flow less capital expenditures)")
        }
        lines.append(move.measure.comparisonLine)

        return DetectedEventDTO(
            kind: .freeCashFlowChange,
            occurredAt: move.filedAt ?? move.period,
            headline: "Free cash flow margin \(direction) "
                + "\(Format.percentagePoints(abs(move.change))) year-over-year",
            detailLines: lines,
            context: "Free cash flow is operating cash flow less capital expenditures, as "
                + "reported. A quarter of heavy investment and a quarter of weak collections "
                + "both reduce it, and the statement of cash flows distinguishes them.",
            unusualness: move.measure.unusualness,
            sourceDetails: ["\(sourceDetail), period ending \(Format.shortDate(move.period))"],
            derivation: move.measure.derivation(
                label: "Free cash flow margin change",
                formatted: Format.percentagePoints(move.change))
        )
    }

    // MARK: - Debt

    /// An unusual year-over-year change in total debt.
    ///
    /// Debt is a balance-sheet instant and is non-negative, so unlike cash flow
    /// it can honestly be compared as a percentage.
    static func debtChange(
        facts: [FinancialFactDTO],
        minimumChangePercent: Double = 10,
        sourceDetail: String = "SEC XBRL company facts"
    ) -> DetectedEventDTO? {
        let debt = instant(facts, .totalDebt)
        let changes = yearOverYear(debt).compactMap { pair -> (period: Date, value: Double)? in
            guard pair.prior > 0 else { return nil }
            return (pair.period, (pair.current - pair.prior) / pair.prior * 100)
        }

        guard let latest = changes.last,
              abs(latest.value) >= minimumChangePercent,
              let measure = measure(latest.value, priors: changes.dropLast().map(\.value)),
              measure.unusualness >= rankThreshold,
              let current = debt.last(where: { $0.period == latest.period })?.value
        else { return nil }

        let direction = latest.value >= 0 ? "rose" : "fell"
        var lines = [
            "Total debt: \(Format.compactCurrency(current)) "
            + "(\(Format.signedPercent(latest.value, precision: 1)) YoY)"
        ]
        // Debt against cash, where the company reports both. The level matters
        // more than the change: a company that added debt while holding more
        // cash than it borrowed is in a different position from one that did not.
        if let cash = instant(facts, .cashAndEquivalents)
            .last(where: { $0.period == latest.period })?.value {
            lines.append("Cash and equivalents: \(Format.compactCurrency(cash))")
        }
        lines.append(measure.comparisonLine)

        return DetectedEventDTO(
            kind: .debtChange,
            occurredAt: filedAt(for: latest.period, concept: .totalDebt, in: facts) ?? latest.period,
            headline: "Total debt \(direction) "
                + "\(Format.percent(abs(latest.value), precision: 1)) year-over-year",
            detailLines: lines,
            context: "A change in borrowing is not by itself good or bad. Debt raised to fund "
                + "capacity and debt raised to cover operations look the same on this line; "
                + "the cash flow statement and the filing's own discussion do not.",
            unusualness: measure.unusualness,
            sourceDetails: ["\(sourceDetail), period ending \(Format.shortDate(latest.period))"],
            derivation: measure.derivation(
                label: "Total debt change",
                formatted: Format.signedPercent(latest.value, precision: 1))
        )
    }

    // MARK: - Restatements

    /// A period an issuer has reported more than once with a different figure.
    ///
    /// The one detector that needs the superseded rows rather than the current
    /// view of history, so it takes `SnapshotStore.factRevisions(symbol:concept:)`
    /// output rather than `facts(symbol:concepts:since:)`. Every other read path
    /// in the app deliberately collapses exactly what this looks for.
    ///
    /// A revision is reported, never characterised. Issuers restate for reasons
    /// ranging from an accounting-standard adoption to a discovered error, and
    /// the figure alone does not say which.
    static func restatements(
        revisions: [FinancialFactDTO],
        concept: FinancialConcept,
        minimumChangePercent: Double = 1,
        sourceDetail: String = "SEC XBRL company facts"
    ) -> DetectedEventDTO? {
        // Keyed on the duration as well as the date, exactly as `deduplicated`
        // is. A fiscal year and its own fourth quarter end on the same day, so
        // keying on the date alone pairs Apple's Q4 FY2020 revenue against its
        // FY2020 revenue and reports a +324% restatement that never happened.
        let byPeriod = Dictionary(grouping: revisions.filter { $0.concept == concept }) {
            "\(SECFundamentalsProvider.periodKey($0.periodEnd))|\($0.periodKind.rawValue)"
        }

        let revised = byPeriod.values.compactMap { versions -> (original: FinancialFactDTO,
                                                                latest: FinancialFactDTO,
                                                                change: Double)? in
            let ordered = versions.sorted { ($0.filedAt ?? .distantPast) < ($1.filedAt ?? .distantPast) }
            guard let original = ordered.first, let latest = ordered.last,
                  ordered.count > 1,
                  original.accessionNumber != latest.accessionNumber,
                  original.value != 0
            else { return nil }
            let change = (latest.value - original.value) / abs(original.value) * 100
            guard abs(change) >= minimumChangePercent else { return nil }
            return (original, latest, change)
        }

        guard let mostRecent = revised.max(by: {
            ($0.latest.filedAt ?? .distantPast) < ($1.latest.filedAt ?? .distantPast)
        }) else { return nil }

        return DetectedEventDTO(
            kind: .fundamentalShift,
            occurredAt: mostRecent.latest.filedAt ?? mostRecent.latest.periodEnd,
            headline: "\(concept.displayName) for "
                + "\(Format.shortDate(mostRecent.latest.periodEnd)) was restated",
            detailLines: [
                "As first reported: \(Format.compactCurrency(mostRecent.original.value))",
                "As now reported: \(Format.compactCurrency(mostRecent.latest.value))",
                "Change: \(Format.signedPercent(mostRecent.change, precision: 1))"
            ],
            context: "The issuer has reported this period twice with different figures. "
                + "Restatements follow from adopting a new accounting standard, "
                + "reclassifying a segment, or correcting an error, and the revised "
                + "figure alone does not distinguish them.",
            unusualness: 0,
            sourceDetails: [
                "\(sourceDetail), original filing \(mostRecent.original.accessionNumber ?? "unknown")",
                "\(sourceDetail), revised filing \(mostRecent.latest.accessionNumber ?? "unknown")"
            ]
        )
    }

    // MARK: - Series construction

    /// One value per period of a given duration for a flow concept, oldest first.
    ///
    /// Cumulative periods are excluded by construction — asking for `.quarter`
    /// cannot return a nine-month total. Reading one as a quarter makes Q3 look
    /// roughly three times Q2 and corrupts every growth rate computed from it,
    /// and a fact arriving from somewhere other than the SEC provider would not
    /// have been filtered yet.
    ///
    /// The duration is a parameter because a 10-K reports annual figures and a
    /// 10-Q reports quarterly ones, and filing analysis has to read whichever
    /// the document actually filed.
    static func flow(
        _ facts: [FinancialFactDTO],
        _ concept: FinancialConcept,
        kind: FiscalPeriodKind = .quarter
    ) -> [(period: Date, value: Double)] {
        facts
            .filter { $0.concept == concept && $0.periodKind == kind }
            .sorted { $0.periodEnd < $1.periodEnd }
            .map { (period: $0.periodEnd, value: $0.value) }
    }

    /// Quarterly flows — the common case, and what every detector above uses.
    static func quarterly(
        _ facts: [FinancialFactDTO],
        _ concept: FinancialConcept
    ) -> [(period: Date, value: Double)] {
        flow(facts, concept, kind: .quarter)
    }

    /// One value per balance-sheet date, oldest first.
    static func instant(
        _ facts: [FinancialFactDTO],
        _ concept: FinancialConcept
    ) -> [(period: Date, value: Double)] {
        facts
            .filter { $0.concept == concept && $0.periodKind == .instant }
            .sorted { $0.periodEnd < $1.periodEnd }
            .map { (period: $0.periodEnd, value: $0.value) }
    }

    /// A margin series in percent, one point per quarter that reports both legs.
    static func marginSeries(
        facts: [FinancialFactDTO],
        numerator concept: FinancialConcept,
        kind: FiscalPeriodKind = .quarter
    ) -> [(period: Date, value: Double)] {
        let revenue = Dictionary(flow(facts, .revenue, kind: kind).map { ($0.period, $0.value) },
                                 uniquingKeysWith: { _, last in last })
        return flow(facts, concept, kind: kind).compactMap { point in
            guard let sales = revenue[point.period], sales > 0 else { return nil }
            return (point.period, point.value / sales * 100)
        }
    }

    /// Operating cash flow less capital expenditures, per quarter.
    ///
    /// Capital expenditure is filed as a positive magnitude under a payments
    /// tag, so it is subtracted by absolute value: a company that reported it
    /// with a negative sign would otherwise have it added to cash flow.
    static func freeCashFlowSeries(
        facts: [FinancialFactDTO],
        kind: FiscalPeriodKind = .quarter
    ) -> [(period: Date, value: Double)] {
        let capex = Dictionary(flow(facts, .capitalExpenditures, kind: kind)
                                .map { ($0.period, $0.value) },
                               uniquingKeysWith: { _, last in last })
        return flow(facts, .operatingCashFlow, kind: kind).compactMap { point in
            guard let spend = capex[point.period] else { return nil }
            return (point.period, point.value - abs(spend))
        }
    }

    static func freeCashFlowMarginSeries(
        facts: [FinancialFactDTO],
        kind: FiscalPeriodKind = .quarter
    ) -> [(period: Date, value: Double)] {
        let revenue = Dictionary(flow(facts, .revenue, kind: kind).map { ($0.period, $0.value) },
                                 uniquingKeysWith: { _, last in last })
        return freeCashFlowSeries(facts: facts, kind: kind).compactMap { point in
            guard let sales = revenue[point.period], sales > 0 else { return nil }
            return (point.period, point.value / sales * 100)
        }
    }

    /// Pairs each period with the one about a year earlier.
    ///
    /// Matched by nearest date within a tolerance rather than by counting four
    /// periods back: a 52/53-week fiscal calendar moves the closing date
    /// between years, and a concept an issuer skipped for one quarter would
    /// otherwise pair a period against the wrong year without any signal that
    /// it had done so.
    static func yearOverYear(
        _ series: [(period: Date, value: Double)],
        toleranceDays: Double = 45
    ) -> [(period: Date, current: Double, prior: Double)] {
        let tolerance = toleranceDays * 86_400
        return series.compactMap { point in
            let target = point.period.addingTimeInterval(-365 * 86_400)
            let match = series
                .filter { $0.period < point.period }
                .min { abs($0.period.timeIntervalSince(target)) < abs($1.period.timeIntervalSince(target)) }
            guard let match, abs(match.period.timeIntervalSince(target)) <= tolerance
            else { return nil }
            return (point.period, point.value, match.value)
        }
    }

    // MARK: - Shared measurement

    /// One period's year-over-year change, judged against every earlier one.
    struct UnusualChange: Sendable {
        let period: Date
        let currentValue: Double
        let priorValue: Double
        /// In percentage points, since every caller is comparing rates.
        let change: Double
        let measure: AnomalyMeasure
        let filedAt: Date?
    }

    /// The latest year-over-year change in a series, if it is unusual enough
    /// to report.
    ///
    /// `minimumChange` is noise suppression — a judgement about what is worth a
    /// reader's attention, not a statistical claim — and is applied on top of
    /// the rank, exactly as the price detector applies its minimum move.
    private static func latestUnusualChange(
        in series: [(period: Date, value: Double)],
        minimumChange: Double,
        filedAt resolve: (Date) -> Date? = { _ in nil }
    ) -> UnusualChange? {
        let paired = yearOverYear(series)
        let changes = paired.map { (period: $0.period, value: $0.current - $0.prior) }

        guard let latest = paired.last, let latestChange = changes.last,
              abs(latestChange.value) >= minimumChange,
              let measure = measure(latestChange.value, priors: changes.dropLast().map(\.value)),
              measure.unusualness >= rankThreshold
        else { return nil }

        return UnusualChange(
            period: latest.period,
            currentValue: latest.current,
            priorValue: latest.prior,
            change: latestChange.value,
            measure: measure,
            filedAt: resolve(latest.period)
        )
    }

    private static func measure(_ observation: Double, priors: [Double]) -> AnomalyMeasure? {
        AnomalyMeasure.measure(
            observation, against: priors,
            minimumSample: minimumSample,
            scaleWindow: scaleWindow,
            rankWindow: rankWindow,
            unit: sampleUnit
        )
    }

    private static func filedAt(
        for period: Date,
        concept: FinancialConcept,
        in facts: [FinancialFactDTO]
    ) -> Date? {
        facts.first { $0.periodEnd == period && $0.concept == concept }?.filedAt
    }
}
