import Foundation

/// Separating "the market moved" from "this company moved" — the deterministic
/// half of Section 4's *why*.
///
/// A detector can say a move was unusual. It cannot say why. But it can say
/// how much of the move the market accounts for, which is the single most
/// useful decomposition available without reading any news: a stock down 6% on
/// a day the market fell 5% is a very different situation from the same 6% on
/// a flat day, and a normal stock app shows both identically.
///
/// Everything here is arithmetic over aligned daily returns. No model is
/// fitted beyond a single-factor beta, and that beta is shown rather than
/// assumed so the reader can reject it.
enum RelativeAnalysis {

    /// Observations required before a beta is reported.
    ///
    /// A beta from a handful of sessions is noise with a Greek letter on it.
    static let minimumBetaObservations = 60

    /// Sessions a beta is fitted over — about two years.
    ///
    /// Capped rather than "use everything available". Beta is not a constant:
    /// a company's sensitivity to the market changes as its business, size and
    /// leverage change, and NVIDIA's five-year history spans regimes that have
    /// little to do with each other. A longer window looks more rigorous and
    /// is actually less informative, because it averages a sensitivity that no
    /// longer applies into the one being used today.
    static let betaWindow = 500

    /// Daily returns for the security and the market on the same sessions.
    ///
    /// Sessions present in only one series are dropped rather than
    /// interpolated. A holiday in one calendar and not the other would
    /// otherwise pair a two-day move against a one-day move and call the
    /// difference company-specific.
    static func align(
        security bars: [PriceBar],
        market: [(date: Date, close: Double)],
        calendar: Calendar = .current
    ) -> [AlignedReturn] {
        let securityByDay = dailyReturnsByDay(
            bars.sorted { $0.date < $1.date }.map { (date: $0.date, close: $0.analysisClose) },
            calendar: calendar
        )
        let marketByDay = dailyReturnsByDay(
            market.sorted { $0.date < $1.date }, calendar: calendar
        )

        return securityByDay.keys
            .compactMap { day -> AlignedReturn? in
                guard let security = securityByDay[day], let market = marketByDay[day]
                else { return nil }
                // Both legs must span the *same* pair of sessions. Matching on
                // the end day alone is not enough: after a holiday present in
                // one calendar and not the other, one series' "daily" return
                // covers two days and the other covers one. The difference
                // then lands in the residual and reads as company-specific.
                guard security.previousDay == market.previousDay else { return nil }
                return AlignedReturn(date: security.date,
                                     security: security.percent,
                                     market: market.percent)
            }
            .sorted { $0.date < $1.date }
    }

    /// Sensitivity of the security's daily returns to the market's.
    ///
    /// Ordinary covariance over variance — the textbook single-factor beta.
    /// Returned with its sample size so the UI can say what it rests on, and
    /// nil rather than a number when the market series barely moved, since
    /// dividing by a near-zero variance produces an enormous beta from nothing.
    static func beta(_ aligned: [AlignedReturn]) -> Beta? {
        guard aligned.count >= minimumBetaObservations else { return nil }
        let window = Array(aligned.suffix(betaWindow))

        let securityMean = window.map(\.security).reduce(0, +) / Double(window.count)
        let marketMean = window.map(\.market).reduce(0, +) / Double(window.count)

        var covariance = 0.0
        var variance = 0.0
        for point in window {
            let marketDeviation = point.market - marketMean
            covariance += (point.security - securityMean) * marketDeviation
            variance += marketDeviation * marketDeviation
        }
        guard variance > 0, covariance.isFinite else { return nil }

        let value = covariance / variance
        guard value.isFinite else { return nil }
        return Beta(value: value,
                    observationCount: window.count,
                    earliest: window.first?.date ?? .now,
                    latest: window.last?.date ?? .now)
    }

    /// Splits one session's move into the part the market accounts for and the
    /// part it does not.
    ///
    /// With a beta, the market's share is `beta × marketMove` — a one-factor
    /// model, named as such. Without one, the comparison is the plain
    /// difference and is labelled as not beta-adjusted, because for a
    /// high-beta name those two answers differ enough to change the reading.
    static func attribute(
        securityMove: Double,
        marketMove: Double,
        marketName: String,
        beta: Beta?,
        isMarketProxy: Bool = false
    ) -> MoveAttribution {
        let explained = beta.map { $0.value * marketMove } ?? marketMove
        return MoveAttribution(
            securityMove: securityMove,
            marketMove: marketMove,
            marketName: marketName,
            beta: beta,
            explainedByMarket: explained,
            residual: securityMove - explained,
            isMarketProxy: isMarketProxy
        )
    }

    /// Daily returns keyed by session, each carrying the session it was
    /// measured from so two series can be checked for identical windows.
    private static func dailyReturnsByDay(
        _ series: [(date: Date, close: Double)],
        calendar: Calendar
    ) -> [Date: (date: Date, percent: Double, previousDay: Date)] {
        guard series.count > 1 else { return [:] }
        var result: [Date: (date: Date, percent: Double, previousDay: Date)] = [:]
        for index in 1..<series.count {
            guard let percent = ReturnCalculator.simpleReturn(
                from: series[index - 1].close, to: series[index].close
            ) else { continue }
            let day = calendar.startOfDay(for: series[index].date)
            result[day] = (series[index].date, percent,
                           calendar.startOfDay(for: series[index - 1].date))
        }
        return result
    }
}

struct AlignedReturn: Sendable, Hashable {
    let date: Date
    let security: Double
    let market: Double
}

struct Beta: Sendable, Hashable {
    let value: Double
    let observationCount: Int
    let earliest: Date
    let latest: Date

    var claim: Claim {
        Claim(
            kind: .calculation,
            text: "Beta of \(Format.ratio(value, precision: 2)) against the market, "
                + "measured over the \(observationCount) sessions to "
                + "\(Format.shortDate(latest)).",
            derivation: Derivation(
                formula: "covariance(security, market) ÷ variance(market)",
                inputs: [
                    .init(name: "sessions", value: "\(observationCount)", source: nil),
                    .init(name: "from", value: Format.shortDate(earliest), source: nil),
                    .init(name: "to", value: Format.shortDate(latest), source: nil)
                ],
                result: Format.ratio(value, precision: 2)
            )
        )
    }
}

/// One session's move split between market and company.
struct MoveAttribution: Sendable, Hashable {
    let securityMove: Double
    let marketMove: Double
    let marketName: String
    let beta: Beta?
    /// The move a beta-times-market model accounts for, in percentage points.
    let explainedByMarket: Double
    /// What is left over. Not "the company's move" — it is what this model
    /// does not explain, which may be the sector, a filing, or nothing at all.
    let residual: Double
    /// True when the market leg came from an ETF standing in for the index,
    /// because the index itself publishes only at the close.
    let isMarketProxy: Bool

    /// Which Section 12 bucket the evidence points at.
    ///
    /// Deliberately coarse. The residual being large means the market does not
    /// account for the move; it does not identify what does.
    var leaning: EvidenceCategory {
        abs(residual) > abs(explainedByMarket) ? .companySpecific : .marketWide
    }

    var detailLines: [String] {
        var lines = [
            "\(marketName) the same session: \(Format.signedPercent(marketMove, precision: 2))"
                + (isMarketProxy ? " (ETF proxy)" : "")
        ]
        if let beta {
            lines.append("Beta \(Format.ratio(beta.value, precision: 2)) implies "
                + "\(Format.signedPercent(explainedByMarket, precision: 2)) from the market alone")
            lines.append("Unexplained by the market: "
                + "\(Format.percentagePoints(residual)) ")
        } else {
            lines.append("Difference: \(Format.percentagePoints(residual)) "
                + "(not beta-adjusted — too little overlapping history)")
        }
        return lines
    }

    /// An INTERPRETATION: it is a judgement about what the arithmetic shows,
    /// resting on a one-factor model that is named in the text rather than
    /// hidden. It states where to look, never what to conclude.
    var claim: Claim {
        let text: String
        if leaning == .marketWide {
            text = "Most of this move is what a one-factor model would expect from "
                + "\(marketName) alone, so it looks market-wide rather than specific to "
                + "this company. Company news may still exist; it is simply not needed "
                + "to account for the size of the move."
        } else {
            text = "\(marketName) does not account for most of this move under a "
                + "one-factor model, which points at something specific to this company "
                + "or its industry. Filings, earnings, and sector peers are where to look."
        }
        return Claim(
            kind: .interpretation,
            text: text,
            derivation: Derivation(
                formula: "residual = securityMove - beta × marketMove",
                inputs: [
                    .init(name: "security", value: Format.signedPercent(securityMove, precision: 2),
                          source: nil),
                    .init(name: marketName, value: Format.signedPercent(marketMove, precision: 2),
                          source: nil),
                    .init(name: "beta", value: beta.map { Format.ratio($0.value, precision: 2) }
                            ?? "not measured", source: nil)
                ],
                result: Format.percentagePoints(residual)
            )
        )
    }
}
