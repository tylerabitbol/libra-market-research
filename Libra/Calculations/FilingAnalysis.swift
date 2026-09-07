import Foundation

/// What a single filing actually reported, and how each figure compares with
/// the same period a year earlier — Section 9's real ask.
///
/// The spec is explicit that the app must not "simply dump filing links into
/// the interface". Until this existed, a new filing produced "Form 10-Q filed
/// Jul 28" followed by a generic description of what a 10-Q contains: it
/// announced that a document existed without saying what the document said.
///
/// This costs no network request. Every `companyfacts` row carries the
/// accession number of the filing that reported it, and the submissions feed
/// carries the same accession in the same dashed format, so the join between
/// "a document was filed" and "these are its figures" is already on disk.
///
/// Three rules, each because the obvious alternative asserts something false:
///
/// - **A filing whose figures have not been published yet says so.**
///   `companyfacts` lags the submissions feed by hours to days. Rendering that
///   gap as "nothing changed" would be a false statement about a real document.
/// - **Only periodic reports are analysed.** An 8-K carries no tagged
///   financials — earnings figures live in Exhibit 99.1 as untagged HTML — so
///   it keeps the plain card rather than acquiring an empty figures section.
/// - **The comparison duration follows the form.** A 10-K reports annual
///   figures and a 10-Q quarterly ones. Reading a 10-K's revenue out of the
///   quarterly series returns nothing at all.
enum FilingAnalysis {

    /// Forms that carry tagged financial statements.
    static let periodicForms: Set<String> = ["10-K", "10-Q", "20-F", "40-F"]
    /// Of those, the ones whose figures cover a full year.
    static let annualForms: Set<String> = ["10-K", "20-F", "40-F"]

    /// One reported figure, with its year-earlier comparison where one exists.
    struct Line: Sendable, Hashable, Identifiable {
        var id: String { label }
        let label: String
        /// The figure as filed.
        let formatted: String
        /// The year-over-year move. Nil when no comparable period is held —
        /// a company's first year on file has nothing to compare against.
        let comparison: String?
        /// Carries its own epistemic status: a bare reported figure is a FACT,
        /// a figure plus a computed change is a CALCULATION.
        let claim: Claim
    }

    struct Result: Sendable, Hashable {
        let accessionNumber: String
        let formType: String
        let periodEnd: Date?
        let lines: [Line]
        /// The filing is known but its XBRL has not been published yet.
        let isAwaitingFacts: Bool

        var isAnnual: Bool { FilingAnalysis.annualForms.contains(formType) }
        var periodLabel: String { isAnnual ? "year" : "quarter" }
    }

    /// How a figure's year-over-year move should be expressed.
    private enum Comparison {
        /// For strictly positive levels. Refused on a non-positive base, where
        /// a percentage change is meaningless rather than merely large.
        case percent
        /// For rates already in percent, compared in percentage points.
        case points
        /// For anything that can cross zero. A company going from -$10M to
        /// +$10M has improved, and "-200%" describes that as a collapse.
        case absoluteCurrency
    }

    // MARK: - Entry point

    /// Analyses one filing against the company's reported history.
    ///
    /// `facts` is the full history, not just this filing's rows: the figures
    /// come from the filing, the comparison comes from everything else.
    ///
    /// Deliberately reads the in-memory facts rather than the store. Detection
    /// runs before persistence, so a store read would report the newest filing
    /// — the one the user actually came to see — as still awaiting its figures.
    static func analyse(filing: FilingDTO, facts: [FinancialFactDTO]) -> Result? {
        guard periodicForms.contains(filing.formType) else { return nil }

        let reported = facts.filter { $0.accessionNumber == filing.accessionNumber }
        guard let period = reported.map(\.periodEnd).max() else {
            return Result(accessionNumber: filing.accessionNumber,
                          formType: filing.formType,
                          periodEnd: filing.periodOfReport,
                          lines: [], isAwaitingFacts: true)
        }

        // A filing restates its comparatives, so its rows span several periods.
        // The one being reported is the latest of them.
        let kind: FiscalPeriodKind = annualForms.contains(filing.formType) ? .annual : .quarter
        let source = SourceReference(
            provider: .sec,
            detail: "\(filing.formType) \(filing.accessionNumber)",
            url: filing.primaryDocumentURL ?? filing.filingIndexURL,
            retrievedAt: filing.filedAt)
        let noun = annualForms.contains(filing.formType) ? "year" : "quarter"

        let lines = [
            line(label: "Revenue",
                 series: FundamentalDetector.flow(facts, .revenue, kind: kind),
                 period: period, noun: noun, source: source,
                 format: { Format.compactCurrency($0) }, comparison: .percent),
            line(label: "Gross margin",
                 series: FundamentalDetector.marginSeries(facts: facts, numerator: .grossProfit,
                                                          kind: kind),
                 period: period, noun: noun, source: source,
                 format: { Format.percent($0, precision: 1) }, comparison: .points),
            line(label: "Operating margin",
                 series: FundamentalDetector.marginSeries(facts: facts, numerator: .operatingIncome,
                                                          kind: kind),
                 period: period, noun: noun, source: source,
                 format: { Format.percent($0, precision: 1) }, comparison: .points),
            line(label: "Net income",
                 series: FundamentalDetector.flow(facts, .netIncome, kind: kind),
                 period: period, noun: noun, source: source,
                 format: { Format.compactCurrency($0) }, comparison: .absoluteCurrency),
            line(label: "Free cash flow",
                 series: FundamentalDetector.freeCashFlowSeries(facts: facts, kind: kind),
                 period: period, noun: noun, source: source,
                 format: { Format.compactCurrency($0) }, comparison: .absoluteCurrency),
            // Balance-sheet figures are instants whatever the form's duration.
            line(label: "Cash and equivalents",
                 series: FundamentalDetector.instant(facts, .cashAndEquivalents),
                 period: period, noun: noun, source: source,
                 format: { Format.compactCurrency($0) }, comparison: .absoluteCurrency),
            line(label: "Total debt",
                 series: FundamentalDetector.instant(facts, .totalDebt),
                 period: period, noun: noun, source: source,
                 format: { Format.compactCurrency($0) }, comparison: .absoluteCurrency)
        ].compactMap { $0 }

        return Result(accessionNumber: filing.accessionNumber,
                      formType: filing.formType,
                      periodEnd: period,
                      lines: lines,
                      isAwaitingFacts: false)
    }

    // MARK: - Line construction

    private static func line(
        label: String,
        series: [(period: Date, value: Double)],
        period: Date,
        noun: String,
        source: SourceReference,
        format: (Double) -> String,
        comparison: Comparison
    ) -> Line? {
        guard let current = value(in: series, at: period) else { return nil }
        let formatted = format(current)
        let prior = FundamentalDetector.yearOverYear(series)
            .first { matches($0.period, period) }?.prior

        guard let prior, let change = describe(current: current, prior: prior,
                                               comparison: comparison, format: format)
        else {
            // Reported, with nothing to compare it against. That is a fact
            // about the filing, not a calculation, and is badged accordingly.
            return Line(
                label: label, formatted: formatted, comparison: nil,
                claim: Claim(kind: .fact,
                             text: "\(label) was \(formatted) for the \(noun) ending "
                                 + "\(Format.shortDate(period)).",
                             sources: [source]))
        }

        return Line(
            label: label, formatted: formatted, comparison: change.text,
            claim: Claim(
                kind: .calculation,
                text: "\(label) was \(formatted) for the \(noun) ending "
                    + "\(Format.shortDate(period)), \(change.sentence) the same "
                    + "\(noun) a year earlier.",
                sources: [source],
                derivation: Derivation(
                    formula: change.formula,
                    inputs: [
                        .init(name: "this \(noun)", value: formatted, source: source),
                        .init(name: "a year earlier", value: format(prior), source: nil)
                    ],
                    result: change.text)))
    }

    private static func describe(
        current: Double,
        prior: Double,
        comparison: Comparison,
        format: (Double) -> String
    ) -> (text: String, sentence: String, formula: String)? {
        switch comparison {
        case .percent:
            // A percentage change off a non-positive base is not a growth rate
            // in any direction a reader would guess.
            guard prior > 0 else { return nil }
            let change = (current - prior) / prior * 100
            return ("\(Format.signedPercent(change, precision: 1)) YoY",
                    "\(change >= 0 ? "up" : "down") "
                        + "\(Format.percent(abs(change), precision: 1)) from",
                    "(current - prior) ÷ prior")
        case .points:
            let change = current - prior
            return ("\(Format.percentagePoints(change)) YoY",
                    "\(change >= 0 ? "up" : "down") "
                        + "\(Format.percentagePoints(abs(change), signed: false)) from",
                    "current - prior")
        case .absoluteCurrency:
            let change = current - prior
            let magnitude = Format.compactCurrency(abs(change))
            return ("\(change >= 0 ? "+" : "−")\(magnitude) YoY",
                    "\(change >= 0 ? "up" : "down") \(magnitude) from",
                    "current - prior")
        }
    }

    /// Periods come from one source and should match exactly, but a few days
    /// of tolerance costs nothing and survives a fiscal calendar that shifts
    /// its closing date.
    private static func matches(_ lhs: Date, _ rhs: Date, toleranceDays: Double = 5) -> Bool {
        abs(lhs.timeIntervalSince(rhs)) <= toleranceDays * 86_400
    }

    private static func value(
        in series: [(period: Date, value: Double)], at period: Date
    ) -> Double? {
        series.last { matches($0.period, period) }?.value
    }
}
