import Foundation

/// Where a current value sits within its own history.
///
/// Section 6 of the specification asks for exactly this: "P/E: 31, historical
/// percentile: 78th" rather than a bare multiple. The comparison is against
/// the company's *own* past, not against other companies — a software firm at
/// a P/E of 31 and a utility at 31 are not comparable, but a company against
/// its own ten-year range is.
/// Why a value cannot be ranked, when it cannot.
///
/// A negative multiple is the important case. Ranked naively it lands at the
/// bottom of a positive history and the descriptor reads "near the low end of
/// its own range" — which a reader takes as cheap, when it actually means the
/// company lost money. Refusing to rank it, and saying why, is the only honest
/// option; omitting it silently would hide the loss entirely.
enum MetricMeaningfulness: Sendable, Hashable {
    case rankable
    case notMeaningful(reason: String)

    var isRankable: Bool { self == .rankable }

    var reason: String? {
        if case .notMeaningful(let reason) = self { return reason }
        return nil
    }
}

struct HistoricalContext: Sendable, Hashable {
    let current: Double
    /// 0–100. The share of *prior* observations at or below `current`.
    /// Meaningless unless `meaningfulness` is `.rankable`.
    let percentile: Int
    let median: Double
    let minimum: Double
    let maximum: Double
    let observationCount: Int
    let earliest: Date
    let latest: Date
    let meaningfulness: MetricMeaningfulness
    /// True when `current` was taken from the history rather than from a live
    /// figure, so the UI can date-stamp it instead of implying it is current.
    let currentIsFromHistory: Bool

    /// An `.interpretation` claim — a judgement about what the number shows,
    /// deliberately not a `.fact`, and never a recommendation.
    var descriptor: String {
        if let reason = meaningfulness.reason { return reason }
        // Explicit returns throughout: the early return above makes the
        // implicit-return form invalid.
        switch percentile {
        case ..<10: return "near the low end of its own range"
        case ..<25: return "below its usual range"
        case ..<75: return "within its usual range"
        case ..<90: return "above its usual range"
        default: return "near the high end of its own range"
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
        minimumObservations: Int = 8,
        lowerIsCheaper: Bool = true,
        currentIsFromHistory: Bool = false
    ) -> HistoricalContext? {
        let usable = history.filter { $0.value.isFinite }
        guard current.isFinite,
              usable.count >= minimumObservations,
              let earliest = usable.map(\.period).min(),
              let latest = usable.map(\.period).max()
        else { return nil }

        // When `current` came from the history, it is the last element of it.
        // Counting an observation in its own ranking inflates the percentile by
        // roughly 1/n and guarantees a value can never rank below itself.
        let priors = currentIsFromHistory ? Array(usable.dropLast()) : usable
        guard priors.count >= minimumObservations - 1 else { return nil }

        let values = priors.map(\.value).sorted()
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
            latest: latest,
            meaningfulness: meaningfulness(
                current: current, history: values, lowerIsCheaper: lowerIsCheaper
            ),
            currentIsFromHistory: currentIsFromHistory
        )
    }

    /// Decides whether a ranking would mean anything.
    ///
    /// Only applied to metrics where lower is conventionally cheaper — the
    /// price multiples. A negative margin or return on equity is a perfectly
    /// meaningful figure that genuinely sits at the bottom of its range.
    static func meaningfulness(
        current: Double,
        history: [Double],
        lowerIsCheaper: Bool
    ) -> MetricMeaningfulness {
        guard lowerIsCheaper else { return .rankable }

        if current < 0 {
            return .notMeaningful(reason: "Not meaningful — a negative multiple reflects "
                                        + "negative earnings, not a low valuation.")
        }
        // A history straddling zero cannot be ordered sensibly either: a large
        // negative and a large positive sit at opposite ends of a range whose
        // middle has no interpretation.
        if history.contains(where: { $0 < 0 }) && history.contains(where: { $0 > 0 }) {
            return .notMeaningful(reason: "Not meaningful — this metric was negative in "
                                        + "part of the period, so its range cannot be ranked.")
        }
        return .rankable
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

/// How a metric should be read and displayed.
enum MetricUnit: Sendable, Hashable {
    /// A multiple, e.g. P/E 34.0.
    case multiple
    /// A percentage, displayed as 46.2%.
    case percent
}

/// A valuation or profitability metric, with the two provider keys it is
/// assembled from and the scaling needed to reconcile them.
///
/// The scaling is not incidental. Finnhub reports the same concept at different
/// scales in its two blocks: `grossMarginTTM` is 48.65 in the current metrics
/// while `grossMargin` is 0.4622 in the historical series. Ranking one against
/// the other puts every margin at the 100th percentile forever — a number that
/// looks authoritative and means nothing. Both halves are normalised to one
/// canonical scale before anything is compared.
struct ValuationMetric: Sendable, Hashable, Identifiable {
    var id: String { key }
    /// Key in the historical `series` block; also the metric's identity.
    let key: String
    /// Key in the current `metric` block, when one exists under a different name.
    let currentKey: String?
    let displayName: String
    let unit: MetricUnit
    /// Multiplier bringing a `series` value onto the canonical scale.
    let historyScale: Double
    /// Multiplier bringing a `metric` value onto the canonical scale.
    let currentScale: Double
    /// True when a *lower* value is conventionally the cheaper one. Recorded so
    /// the UI can explain direction without implying a recommendation.
    let lowerIsCheaper: Bool

    private static func multiple(
        _ key: String, _ currentKey: String?, _ name: String, lowerIsCheaper: Bool = true
    ) -> ValuationMetric {
        .init(key: key, currentKey: currentKey, displayName: name, unit: .multiple,
              historyScale: 1, currentScale: 1, lowerIsCheaper: lowerIsCheaper)
    }

    /// Series values arrive as ratios (0.46) and current values as percentages
    /// (48.65); both are normalised to percent.
    private static func margin(
        _ key: String, _ currentKey: String?, _ name: String
    ) -> ValuationMetric {
        .init(key: key, currentKey: currentKey, displayName: name, unit: .percent,
              historyScale: 100, currentScale: 1, lowerIsCheaper: false)
    }

    static let all: [ValuationMetric] = [
        multiple("peTTM", "peTTM", "P/E"),
        multiple("psTTM", "psTTM", "P/S"),
        multiple("pb", "pb", "P/B"),
        // Finnhub's metric block carries no P/FCF, so this one is sourced from
        // the historical series and date-stamped rather than shown as current.
        multiple("pfcfTTM", nil, "P/FCF"),
        // EV/EBITDA, not EV/free-cash-flow. These are different metrics and
        // Finnhub reports both; an earlier revision read the wrong one and
        // displayed it under this label.
        multiple("evEbitdaTTM", "evEbitdaTTM", "EV/EBITDA"),
        // The window is part of the name. `FundamentalDetector` reports a
        // single quarter against the year-ago quarter under the same words,
        // and both appear on the Security Detail page: GOOGL showed "Net
        // margin 54.8%" here beside "Net margin: 29.2% → 93.7%" under What
        // changed. Both were right — 244.3/445.9 over twelve months against
        // 112.19/119.80 for Q2 — and nothing on screen said so.
        margin("grossMargin", "grossMarginTTM", "Gross margin (TTM)"),
        margin("operatingMargin", "operatingMarginTTM", "Operating margin (TTM)"),
        margin("netMargin", "netProfitMarginTTM", "Net margin (TTM)"),
        margin("roe", "roeTTM", "Return on equity (TTM)")
    ]

    /// Formats a canonical-scale value for display.
    func format(_ value: Double) -> String {
        switch unit {
        case .multiple: Format.ratio(value, precision: 1)
        case .percent: Format.percent(value, precision: 1)
        }
    }
}

extension CompanyMetricsDTO {
    /// Current value and history for a metric, both on the metric's canonical
    /// scale so they can legitimately be compared.
    ///
    /// The scaling is validated rather than assumed. Finnhub reports margins as
    /// ratios in the series block and as percentages in the metric block, but
    /// that is an observation about today's API, not a guarantee. Multiplying a
    /// series that is already in percent would render margins 100x wrong while
    /// looking entirely plausible — the worst failure available here — so the
    /// data is inspected before any factor is applied.
    func normalized(for metric: ValuationMetric) -> NormalizedMetric? {
        let raw = self.history(metric.key)
        guard !raw.isEmpty else { return nil }

        let scale = Self.resolvedHistoryScale(declared: metric.historyScale, values: raw.map(\.value))
        let history = raw.map { MetricPoint(period: $0.period, value: $0.value * scale) }

        if let currentKey = metric.currentKey, let value = currentValue(currentKey) {
            return NormalizedMetric(current: value * metric.currentScale,
                                    history: history,
                                    currentIsFromHistory: false,
                                    asOf: asOf)
        }
        guard let latest = history.last else { return nil }
        return NormalizedMetric(current: latest.value,
                                history: history,
                                currentIsFromHistory: true,
                                asOf: latest.period)
    }

    /// Applies the declared scale only when the data is consistent with it.
    ///
    /// A ratio series carries values around 0–1.5. If the values are larger,
    /// the series is already in percent and multiplying again would be wrong.
    static func resolvedHistoryScale(declared: Double, values: [Double]) -> Double {
        guard declared != 1 else { return 1 }
        let magnitudes = values.map(abs).filter { $0 > 0 }
        guard let largest = magnitudes.max() else { return declared }
        // Ratios above 1.5 do occur (ROE of 1.37 is 137%), so the threshold is
        // set well clear of them; anything above 3 is percent already.
        return largest > 3 ? 1 : declared
    }
}

/// A metric's current value and history, reconciled onto one scale.
struct NormalizedMetric: Sendable, Hashable {
    let current: Double
    let history: [MetricPoint]
    /// True when `current` is the newest history point rather than a live value.
    let currentIsFromHistory: Bool
    /// The date `current` refers to.
    let asOf: Date
}
