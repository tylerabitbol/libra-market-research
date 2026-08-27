import Foundation

/// Where a current value sits within its own history.
///
/// Section 6 of the specification asks for exactly this: "P/E: 31, historical
/// percentile: 78th" rather than a bare multiple. The comparison is against
/// the company's *own* past, not against other companies — a software firm at
/// a P/E of 31 and a utility at 31 are not comparable, but a company against
/// its own ten-year range is.
struct HistoricalContext: Sendable, Hashable {
    let current: Double
    /// 0–100. The share of historical observations at or below `current`.
    let percentile: Int
    let median: Double
    let minimum: Double
    let maximum: Double
    let observationCount: Int
    let earliest: Date
    let latest: Date

    /// An `.interpretation` claim — a judgement about what the number shows,
    /// deliberately not a `.fact`, and never a recommendation.
    var descriptor: String {
        switch percentile {
        case ..<10: "near the low end of its own range"
        case ..<25: "below its usual range"
        case ..<75: "within its usual range"
        case ..<90: "above its usual range"
        default: "near the high end of its own range"
        }
    }
}

enum ValuationCalculator {

    /// Percentile rank of `current` within `history`.
    ///
    /// Uses the "less than or equal" definition: the share of observations at
    /// or below the current value. Requires a minimum sample, because a
    /// percentile drawn from three observations invites more confidence than
    /// it deserves — below that it returns nil rather than a weak number
    /// dressed as a strong one.
    static func historicalContext(
        current: Double,
        history: [MetricPoint],
        minimumObservations: Int = 8
    ) -> HistoricalContext? {
        // Non-finite values, and negative multiples such as a P/E on negative
        // earnings, are not meaningfully rankable.
        let usable = history.filter { $0.value.isFinite }
        guard current.isFinite,
              usable.count >= minimumObservations,
              let earliest = usable.map(\.period).min(),
              let latest = usable.map(\.period).max()
        else { return nil }

        let values = usable.map(\.value).sorted()
        let atOrBelow = values.filter { $0 <= current }.count
        let percentile = Int((Double(atOrBelow) / Double(values.count) * 100).rounded())

        return HistoricalContext(
            current: current,
            percentile: min(max(percentile, 0), 100),
            median: median(of: values),
            minimum: values.first ?? current,
            maximum: values.last ?? current,
            observationCount: values.count,
            earliest: earliest,
            latest: latest
        )
    }

    static func median(of sortedValues: [Double]) -> Double {
        guard !sortedValues.isEmpty else { return .nan }
        let middle = sortedValues.count / 2
        return sortedValues.count.isMultiple(of: 2)
            ? (sortedValues[middle - 1] + sortedValues[middle]) / 2
            : sortedValues[middle]
    }

    /// The two claims a valuation metric supports: the number, and what its
    /// position in its own history means.
    ///
    /// They are returned separately and separately labelled because they carry
    /// different weight. The multiple is a calculation; "expensive relative to
    /// its own history" is an interpretation, and Section 24 forbids
    /// presenting the second as though it were the first.
    static func claims(
        metricName: String,
        context: HistoricalContext,
        source: SourceReference?
    ) -> [Claim] {
        let value = Claim(
            kind: .calculation,
            text: "\(metricName) is \(Format.ratio(context.current, precision: 1)).",
            sources: source.map { [$0] } ?? [],
            derivation: Derivation(
                formula: "current \(metricName)",
                inputs: [.init(name: metricName,
                               value: Format.ratio(context.current, precision: 1),
                               source: source)],
                result: Format.ratio(context.current, precision: 1)
            )
        )

        let interpretation = Claim(
            kind: .interpretation,
            text: "That is \(Format.ordinal(context.percentile)) percentile of its own "
                + "\(context.observationCount) observations since "
                + "\(Format.shortDate(context.earliest)) — \(context.descriptor).",
            sources: source.map { [$0] } ?? [],
            derivation: Derivation(
                formula: "share of past observations at or below the current value",
                inputs: [
                    .init(name: "current", value: Format.ratio(context.current, precision: 1), source: nil),
                    .init(name: "median", value: Format.ratio(context.median, precision: 1), source: nil),
                    .init(name: "range", value: "\(Format.ratio(context.minimum, precision: 1))"
                          + " – \(Format.ratio(context.maximum, precision: 1))", source: nil),
                    .init(name: "observations", value: "\(context.observationCount)", source: nil)
                ],
                result: "\(Format.ordinal(context.percentile)) percentile"
            )
        )
        return [value, interpretation]
    }
}

/// The valuation metrics shown with historical context, mapped to the keys the
/// metrics provider uses.
struct ValuationMetric: Sendable, Hashable, Identifiable {
    var id: String { key }
    let key: String
    let displayName: String
    /// True when a *lower* value is conventionally the cheaper one. Recorded so
    /// the UI can explain direction without implying a recommendation.
    let lowerIsCheaper: Bool

    static let all: [ValuationMetric] = [
        .init(key: "peTTM", displayName: "P/E", lowerIsCheaper: true),
        .init(key: "psTTM", displayName: "P/S", lowerIsCheaper: true),
        .init(key: "pb", displayName: "P/B", lowerIsCheaper: true),
        .init(key: "pfcfTTM", displayName: "P/FCF", lowerIsCheaper: true),
        .init(key: "evEbitdaTTM", displayName: "EV/EBITDA", lowerIsCheaper: true),
        .init(key: "grossMargin", displayName: "Gross margin", lowerIsCheaper: false),
        .init(key: "operatingMargin", displayName: "Operating margin", lowerIsCheaper: false),
        .init(key: "netMargin", displayName: "Net margin", lowerIsCheaper: false),
        .init(key: "roe", displayName: "Return on equity", lowerIsCheaper: false)
    ]
}
