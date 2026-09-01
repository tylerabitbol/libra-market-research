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

/// Where a price sits relative to its own recent averages and its peak.
///
/// Deliberately three figures. Section 5 asks for context and then says
/// explicitly not to turn the page into a technical-analysis dashboard, so
/// there is no oscillator suite here — only what answers "is this high or low
/// for this security lately, and how far has it fallen from its best".
///
/// Every figure is computed from the adjusted close, because a split would
/// otherwise show up as a 50% drawdown that never happened.
struct PriceContext: Sendable, Hashable {
    let last: Double
    /// Nil when fewer sessions are held than the average needs. A "200-day
    /// average" of 60 sessions is a different statistic wearing the same name.
    let fiftyDayAverage: Double?
    let twoHundredDayAverage: Double?
    /// The current fall from the highest close in the window, in percent.
    /// Zero at a new high, never positive.
    let drawdownFromPeak: Double
    /// The largest peak-to-trough fall within the window, in percent.
    let deepestDrawdown: Double
    let peak: Double
    let windowStart: Date
    let windowEnd: Date

    /// Distance from an average, in percent. Nil when that average is nil.
    func distance(from average: Double?) -> Double? {
        guard let average, average > 0 else { return nil }
        return (last - average) / average * 100
    }

    var fiftyDayClaim: Claim? { averageClaim(average: fiftyDayAverage, sessions: 50) }
    var twoHundredDayClaim: Claim? { averageClaim(average: twoHundredDayAverage, sessions: 200) }

    private func averageClaim(average: Double?, sessions: Int) -> Claim? {
        guard let average, let distance = distance(from: average) else { return nil }
        let direction = distance >= 0 ? "above" : "below"
        return Claim(
            kind: .calculation,
            text: "Trading \(Format.percent(abs(distance), precision: 1)) \(direction) its "
                + "\(sessions)-session average close.",
            derivation: Derivation(
                formula: "(last - average) ÷ average",
                inputs: [
                    .init(name: "last", value: Format.currency(last), source: nil),
                    .init(name: "\(sessions)-session average",
                          value: Format.currency(average), source: nil)
                ],
                result: Format.signedPercent(distance, precision: 1)))
    }

    var drawdownClaim: Claim {
        Claim(
            kind: .calculation,
            text: drawdownFromPeak >= -0.05
                ? "At its highest close of the period."
                : "Down \(Format.percent(abs(drawdownFromPeak), precision: 1)) from its "
                    + "highest close of the period.",
            derivation: Derivation(
                formula: "(last - peak) ÷ peak",
                inputs: [
                    .init(name: "last", value: Format.currency(last), source: nil),
                    .init(name: "peak close", value: Format.currency(peak), source: nil),
                    .init(name: "period", value: "\(Format.shortDate(windowStart)) – "
                          + Format.shortDate(windowEnd), source: nil)
                ],
                result: Format.signedPercent(drawdownFromPeak, precision: 1)))
    }
}

extension ReturnCalculator {
    /// Price context over the bars given, which is the visible range rather
    /// than everything held: "down 18% from its peak" means a different thing
    /// over one month than over five years, and the window is stated.
    static func priceContext(bars: [PriceBar]) -> PriceContext? {
        let sorted = bars.sorted { $0.date < $1.date }
        guard let last = sorted.last, let first = sorted.first, sorted.count > 1 else { return nil }
        let closes = sorted.map(\.analysisClose)

        var runningPeak = closes[0]
        var deepest = 0.0
        for close in closes {
            runningPeak = max(runningPeak, close)
            guard runningPeak > 0 else { continue }
            deepest = min(deepest, (close - runningPeak) / runningPeak * 100)
        }
        let peak = closes.max() ?? closes[0]

        return PriceContext(
            last: last.analysisClose,
            fiftyDayAverage: average(closes, sessions: 50),
            twoHundredDayAverage: average(closes, sessions: 200),
            drawdownFromPeak: peak > 0 ? (last.analysisClose - peak) / peak * 100 : 0,
            deepestDrawdown: deepest,
            peak: peak,
            windowStart: first.date,
            windowEnd: last.date)
    }

    /// Mean of the most recent `sessions` closes, or nil if there are fewer.
    private static func average(_ closes: [Double], sessions: Int) -> Double? {
        guard closes.count >= sessions else { return nil }
        let window = closes.suffix(sessions)
        return window.reduce(0, +) / Double(window.count)
    }
}
