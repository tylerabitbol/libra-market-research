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

    /// Security, market and sector returns on identical sessions.
    ///
    /// Same rule as the two-series case, applied across three: every leg must
    /// span the same pair of sessions, or a holiday in one calendar and not
    /// another silently compares a two-day move against a one-day move.
    static func align(
        security bars: [PriceBar],
        market: [(date: Date, close: Double)],
        sector: [PriceBar],
        calendar: Calendar = .current
    ) -> [AlignedFactorReturn] {
        let marketLeg = Dictionary(
            align(security: bars, market: market, calendar: calendar)
                .map { ($0.date, $0) },
            uniquingKeysWith: { _, last in last })
        // Reusing the pairwise aligner for the sector leg keeps the
        // previous-session rule in exactly one place.
        let sectorLeg = Dictionary(
            align(security: bars,
                  market: sector.sorted { $0.date < $1.date }
                      .map { (date: $0.date, close: $0.analysisClose) },
                  calendar: calendar)
                .map { ($0.date, $0.market) },
            uniquingKeysWith: { _, last in last })

        return marketLeg.values
            .compactMap { pair -> AlignedFactorReturn? in
                guard let sectorReturn = sectorLeg[pair.date] else { return nil }
                return AlignedFactorReturn(date: pair.date, security: pair.security,
                                           market: pair.market, sector: sectorReturn)
            }
            .sorted { $0.date < $1.date }
    }

    /// Splits a move across the market and the security's sector.
    ///
    /// The sector leg is **orthogonalised against the market before it is
    /// used**. A sector ETF moves with the market — XLK and the S&P share most
    /// of their variance — so subtracting a raw sector move from what the
    /// market already explained counts the market twice and leaves a residual
    /// that is mostly sign noise.
    ///
    /// What is measured instead is the part of the sector's move the market
    /// does not account for, and how sensitive this security is to *that*.
    /// Because the second factor is uncorrelated with the first by
    /// construction, the two sensitivities can be fitted separately and still
    /// mean what a joint fit would have meant.
    ///
    /// Still a model, and still named as one. The residual is what neither
    /// factor accounts for — not "the company's move".
    static func attribute(
        securityMove: Double,
        marketMove: Double,
        marketName: String,
        beta: Beta?,
        sector: SectorFactor?,
        sectorMove: Double?,
        isMarketProxy: Bool = false
    ) -> MoveAttribution {
        let explained = beta.map { $0.value * marketMove } ?? marketMove

        var leg: MoveAttribution.SectorLeg?
        if let sector, let sectorMove {
            let excess = sectorMove - sector.marketBeta * marketMove
            leg = MoveAttribution.SectorLeg(
                name: sector.name,
                move: sectorMove,
                excess: excess,
                sensitivity: sector.sensitivity,
                explained: sector.sensitivity * excess,
                isProxy: sector.isProxy)
        }

        return MoveAttribution(
            securityMove: securityMove,
            marketMove: marketMove,
            marketName: marketName,
            beta: beta,
            explainedByMarket: explained,
            residual: securityMove - explained - (leg?.explained ?? 0),
            isMarketProxy: isMarketProxy,
            sector: leg,
            sectorFactor: sector
        )
    }

    /// Fits the sector factor: how the sector moves with the market, and how
    /// this security moves with what is left of the sector after that.
    ///
    /// Nil rather than a number when there is too little overlapping history —
    /// a sensitivity from a handful of sessions is noise with a name on it.
    static func sectorFactor(
        _ aligned: [AlignedFactorReturn],
        name: String,
        isProxy: Bool
    ) -> SectorFactor? {
        guard aligned.count >= minimumBetaObservations else { return nil }
        let window = Array(aligned.suffix(betaWindow))

        guard let marketBeta = slope(of: window.map(\.sector), on: window.map(\.market))
        else { return nil }

        let excess = window.map { $0.sector - marketBeta * $0.market }
        guard let sensitivity = slope(of: window.map(\.security), on: excess) else { return nil }

        return SectorFactor(
            name: name, marketBeta: marketBeta, sensitivity: sensitivity,
            observationCount: window.count, isProxy: isProxy,
            earliest: window.first?.date ?? .distantPast,
            latest: window.last?.date ?? .distantPast)
    }

    /// Ordinary least-squares slope of `y` on `x`.
    ///
    /// Nil when `x` barely moved: dividing by a near-zero variance produces an
    /// enormous coefficient out of nothing at all.
    private static func slope(of y: [Double], on x: [Double]) -> Double? {
        guard y.count == x.count, y.count > 1 else { return nil }
        let count = Double(y.count)
        let meanX = x.reduce(0, +) / count
        let meanY = y.reduce(0, +) / count
        var covariance = 0.0
        var variance = 0.0
        for index in x.indices {
            let dx = x[index] - meanX
            covariance += dx * (y[index] - meanY)
            variance += dx * dx
        }
        guard variance > 1e-12 else { return nil }
        return covariance / variance
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

/// One session with all three legs measured over identical sessions.
struct AlignedFactorReturn: Sendable, Hashable {
    let date: Date
    let security: Double
    let market: Double
    let sector: Double
}

/// The sector factor, fitted and shown so it can be rejected.
struct SectorFactor: Sendable, Hashable {
    let name: String
    /// How the sector itself moves with the market. Used only to strip the
    /// market out of the sector before the sector is used as a factor.
    let marketBeta: Double
    /// How this security moves with the sector's market-adjusted move.
    let sensitivity: Double
    let observationCount: Int
    /// True when the sector is represented by an ETF rather than an index.
    /// FRED publishes no sector series, so this is always true today.
    let isProxy: Bool
    let earliest: Date
    let latest: Date

    var claim: Claim {
        Claim(
            kind: .calculation,
            text: "Sensitivity of \(Format.ratio(sensitivity, precision: 2)) to \(name) "
                + "beyond the market, measured over \(observationCount) sessions to "
                + "\(Format.shortDate(latest)).",
            derivation: Derivation(
                formula: "slope(security, sector - sectorMarketBeta × market)",
                inputs: [
                    .init(name: "sector beta to market",
                          value: Format.ratio(marketBeta, precision: 2), source: nil),
                    .init(name: "sessions", value: "\(observationCount)", source: nil),
                    .init(name: "from", value: Format.shortDate(earliest), source: nil),
                    .init(name: "to", value: Format.shortDate(latest), source: nil)
                ],
                result: Format.ratio(sensitivity, precision: 2)))
    }
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
    /// The sector leg, when the company maps to a sector we track and there is
    /// enough overlapping history to fit it.
    var sector: SectorLeg?
    /// The fitted sector factor, kept so the UI can show what it rests on and
    /// the reader can reject it — the same treatment beta gets.
    var sectorFactor: SectorFactor?

    var sectorFactorClaim: Claim? { sectorFactor?.claim }

    /// What the security's own sector accounts for, over and above the market.
    struct SectorLeg: Sendable, Hashable {
        let name: String
        /// The sector's raw move that session.
        let move: Double
        /// The part of it the market does not explain.
        let excess: Double
        /// This security's sensitivity to that excess.
        let sensitivity: Double
        /// sensitivity × excess, in percentage points.
        let explained: Double
        let isProxy: Bool
    }

    /// Which Section 12 bucket the evidence points at.
    ///
    /// Deliberately coarse. The residual being large means the market does not
    /// account for the move; it does not identify what does.
    var leaning: EvidenceCategory {
        let sectorPart = abs(sector?.explained ?? 0)
        let marketPart = abs(explainedByMarket)
        let residualPart = abs(residual)
        if residualPart >= marketPart && residualPart >= sectorPart { return .companySpecific }
        return sectorPart > marketPart ? .industry : .marketWide
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
        if let sector {
            lines.append("\(sector.name) the same session: "
                + "\(Format.signedPercent(sector.move, precision: 2))"
                + (sector.isProxy ? " (ETF proxy)" : ""))
            lines.append("Beyond the market, the sector moved "
                + "\(Format.signedPercent(sector.excess, precision: 2)); at a sensitivity of "
                + "\(Format.ratio(sector.sensitivity, precision: 2)) that accounts for "
                + "\(Format.signedPercent(sector.explained, precision: 2))")
        }
        return lines
    }

    /// An INTERPRETATION: it is a judgement about what the arithmetic shows,
    /// resting on a one-factor model that is named in the text rather than
    /// hidden. It states where to look, never what to conclude.
    var claim: Claim {
        let model = sector == nil ? "a one-factor model" : "a two-factor model"
        let text: String
        switch leaning {
        case .marketWide:
            text = "Most of this move is what \(model) would expect from \(marketName) "
                + "alone, so it looks market-wide rather than specific to this company. "
                + "Company news may still exist; it is simply not needed to account for "
                + "the size of the move."
        case .industry:
            text = "\(sector?.name ?? "The sector") accounts for more of this move than "
                + "\(marketName) does, once the market's own effect on the sector is "
                + "removed. That points at something affecting these companies together "
                + "rather than this one alone — peers and industry news are where to look."
        default:
            let unexplained = sector.map { "Neither \(marketName) nor \($0.name)" }
                ?? "\(marketName) does not"
            // Both branches need a verb. "Neither A nor B" takes the
            // singular "accounts for"; the single-subject form needs the bare
            // infinitive after "does not".
            let verb = sector == nil ? " explain" : " accounts for"
            text = "\(unexplained)\(verb) most of this move under \(model), which points "
                + "at something specific to this company. Filings, earnings, and its own "
                + "news are where to look."
        }

        var inputs: [Derivation.Input] = [
            .init(name: "security", value: Format.signedPercent(securityMove, precision: 2),
                  source: nil),
            .init(name: marketName, value: Format.signedPercent(marketMove, precision: 2),
                  source: nil),
            .init(name: "beta", value: beta.map { Format.ratio($0.value, precision: 2) }
                    ?? "not measured", source: nil)
        ]
        if let sector {
            inputs.append(.init(name: "\(sector.name) beyond the market",
                                value: Format.signedPercent(sector.excess, precision: 2),
                                source: nil))
            inputs.append(.init(name: "sensitivity to it",
                                value: Format.ratio(sector.sensitivity, precision: 2),
                                source: nil))
        }

        return Claim(
            kind: .interpretation,
            text: text,
            derivation: Derivation(
                formula: sector == nil
                    ? "residual = securityMove - beta × marketMove"
                    : "residual = securityMove - beta × marketMove "
                        + "- sensitivity × (sectorMove - sectorMarketBeta × marketMove)",
                inputs: inputs,
                result: Format.percentagePoints(residual)
            )
        )
    }
}
