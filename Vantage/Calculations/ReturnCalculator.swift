import Foundation

/// The realised return over a window, together with the window actually used.
///
/// The second part matters. If you ask for a 1-month return and only three
/// weeks of history exist, silently returning the three-week number is how a
/// research tool starts lying. This type reports what was actually measured so
/// the UI can label it, and `isFullWindow` lets a caller reject a short read
/// outright.
struct PeriodReturn: Sendable, Hashable {
    let percent: Double
    let startDate: Date
    let endDate: Date
    let startPrice: Double
    let endPrice: Double
    /// False when the available history didn't reach back to the requested start.
    let isFullWindow: Bool

    var derivation: Derivation {
        Derivation(
            formula: "(endPrice - startPrice) / startPrice × 100",
            inputs: [
                .init(name: "startPrice (\(Format.shortDate(startDate)))",
                      value: Format.currency(startPrice), source: nil),
                .init(name: "endPrice (\(Format.shortDate(endDate)))",
                      value: Format.currency(endPrice), source: nil)
            ],
            result: Format.signedPercent(percent)
        )
    }
}

/// Deterministic return and relative-performance arithmetic.
///
/// Everything here is pure and total: given the same bars it produces the same
/// answer, and it returns `nil` rather than guessing when the inputs can't
/// support a result. No AI involvement — Section 23 keeps calculation in Swift.
enum ReturnCalculator {

    /// Simple percentage change between two prices.
    /// Nil when the base is zero or either input is non-finite.
    static func simpleReturn(from start: Double, to end: Double) -> Double? {
        guard start != 0, start.isFinite, end.isFinite else { return nil }
        return (end - start) / start * 100
    }

    /// Return over a trailing window ending at the most recent bar.
    ///
    /// Uses the last bar at or before the window start, so a weekend or holiday
    /// boundary resolves to the prior session rather than failing.
    ///
    /// - Parameter tolerance: how far before the requested start the chosen bar
    ///   may sit while still counting as a full window. Defaults to 5 days,
    ///   which absorbs a long weekend without hiding a genuine data gap.
    static func trailingReturn(
        bars: [PriceBar],
        window: DateComponents,
        asOf: Date? = nil,
        tolerance: TimeInterval = 5 * 86_400,
        calendar: Calendar = .current
    ) -> PeriodReturn? {
        let sorted = bars.sorted { $0.date < $1.date }
        let cutoff = asOf
        guard let endBar = sorted.last(where: { bar in
            guard let cutoff else { return true }
            return bar.date <= cutoff
        }) else {
            return nil
        }
        guard let requestedStart = calendar.date(byAdding: window, to: endBar.date) else {
            return nil
        }

        // The last bar at or before the requested start; failing that, the
        // earliest bar we have, flagged as a partial window.
        let startBar = sorted.last(where: { $0.date <= requestedStart }) ?? sorted.first
        guard let startBar, startBar.date < endBar.date else { return nil }

        guard let percent = simpleReturn(
            from: startBar.analysisClose,
            to: endBar.analysisClose
        ) else { return nil }

        let gap = startBar.date.timeIntervalSince(requestedStart)
        return PeriodReturn(
            percent: percent,
            startDate: startBar.date,
            endDate: endBar.date,
            startPrice: startBar.analysisClose,
            endPrice: endBar.analysisClose,
            isFullWindow: gap <= tolerance
        )
    }

    /// Relative performance in percentage points.
    ///
    /// Section 7 wants "NVDA outperformed its sector by approximately 7
    /// percentage points" stated as arithmetic rather than adjectives. Both
    /// legs must cover comparable windows or the comparison is meaningless, so
    /// mismatched windows return nil instead of a misleading number.
    static func relativePerformance(
        security: PeriodReturn?,
        benchmark: PeriodReturn?,
        maxWindowMismatch: TimeInterval = 3 * 86_400
    ) -> RelativePerformance? {
        guard let security, let benchmark else { return nil }
        let mismatch = abs(security.startDate.timeIntervalSince(benchmark.startDate))
        guard mismatch <= maxWindowMismatch else { return nil }

        return RelativePerformance(
            securityReturn: security.percent,
            benchmarkReturn: benchmark.percent,
            differencePoints: security.percent - benchmark.percent,
            startDate: max(security.startDate, benchmark.startDate),
            endDate: min(security.endDate, benchmark.endDate)
        )
    }
}

/// One security measured against one benchmark over a shared window.
struct RelativePerformance: Sendable, Hashable {
    let securityReturn: Double
    let benchmarkReturn: Double
    /// Positive means the security outpaced the benchmark.
    let differencePoints: Double
    let startDate: Date
    let endDate: Date

    /// A `.calculation` claim, not an interpretation: it states the arithmetic
    /// without characterising it as good, strong, or promising.
    func claim(securityName: String, benchmarkName: String) -> Claim {
        let verb = differencePoints >= 0 ? "outperformed" : "underperformed"
        return Claim(
            kind: .calculation,
            text: "\(securityName) \(verb) \(benchmarkName) by "
                + "\(Format.percentagePoints(abs(differencePoints))) "
                + "between \(Format.shortDate(startDate)) and \(Format.shortDate(endDate)).",
            derivation: Derivation(
                formula: "securityReturn - benchmarkReturn",
                inputs: [
                    .init(name: securityName, value: Format.signedPercent(securityReturn), source: nil),
                    .init(name: benchmarkName, value: Format.signedPercent(benchmarkReturn), source: nil)
                ],
                result: Format.percentagePoints(differencePoints)
            )
        )
    }
}
