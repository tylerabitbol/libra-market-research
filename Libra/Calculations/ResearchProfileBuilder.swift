import Foundation

/// Builds the Section 13 profile and, with it, Section 12's disconfirming
/// evidence.
///
/// Pure over values so it can be tested without a provider or a container, and
/// so the direction rules are inspectable in one place rather than scattered
/// across the view layer.
///
/// Every dimension follows the same shape: compute the figure, state the
/// arithmetic as a `.calculation`, then classify. The classification is derived
/// from the figure rather than chosen alongside it, which is what stops the
/// engine from finding only the evidence it went looking for.
enum ResearchProfileBuilder {

    /// Not `Sendable`: `PriceBar` is a `@Model` class, exactly as it is for
    /// every other detector that takes bars. The builder runs where the data
    /// already lives rather than crossing an actor to reach it.
    struct Inputs {
        var bars: [PriceBar] = []
        var rangeReturn: PeriodReturn?
        var relativeToMarket: RelativePerformance?
        var sectorRelativeToMarket: RelativePerformance?
        var sectorName: String?
        var fundamentals: [FinancialFactDTO] = []
        var metrics: CompanyMetricsDTO?
        var ratings: RatingSnapshotDTO?
        /// Discretionary open-market insider transactions. Scheduled plans are
        /// excluded by the caller — a 10b5-1 sale carries no opinion.
        var insiderPurchases: Int?
        var insiderSales: Int?
    }

    static func build(_ inputs: Inputs) -> ResearchProfile {
        ResearchProfile(components: [
            momentum(inputs),
            relativeStrength(inputs),
            revenueTrend(inputs),
            earningsTrend(inputs),
            profitability(inputs),
            valuation(inputs),
            balanceSheet(inputs),
            analystPosture(inputs),
            insiderActivity(inputs),
            sectorStrength(inputs),
            volatility(inputs)
        ])
    }

    // MARK: - Price dimensions

    private static func momentum(_ inputs: Inputs) -> ResearchComponent {
        guard let periodReturn = inputs.rangeReturn else {
            return .unavailable(.momentum, reason: "No price history loaded.")
        }
        return ResearchComponent(
            dimension: .momentum,
            summary: Format.signedPercent(periodReturn.percent, precision: 1),
            direction: direction(periodReturn.percent, threshold: 1),
            claim: Claim(
                kind: .calculation,
                text: "Returned \(Format.signedPercent(periodReturn.percent, precision: 1)) "
                    + "between \(Format.shortDate(periodReturn.startDate)) and "
                    + "\(Format.shortDate(periodReturn.endDate)).",
                derivation: Derivation(
                    formula: "(end - start) ÷ start",
                    inputs: [.init(name: "window",
                                   value: "\(Format.shortDate(periodReturn.startDate)) – "
                                        + Format.shortDate(periodReturn.endDate), source: nil)],
                    result: Format.signedPercent(periodReturn.percent, precision: 1))))
    }

    private static func relativeStrength(_ inputs: Inputs) -> ResearchComponent {
        guard let relative = inputs.relativeToMarket else {
            return .unavailable(.relativeStrength, reason: "No market series to compare against.")
        }
        return ResearchComponent(
            dimension: .relativeStrength,
            summary: "\(Format.percentagePoints(relative.differencePoints)) vs S&P",
            direction: direction(relative.differencePoints, threshold: 1),
            claim: relative.claim(securityName: "This security", benchmarkName: "the S&P 500"))
    }

    private static func sectorStrength(_ inputs: Inputs) -> ResearchComponent {
        guard let relative = inputs.sectorRelativeToMarket, let name = inputs.sectorName else {
            return .unavailable(.sectorStrength, reason: "No sector benchmark for this company.")
        }
        return ResearchComponent(
            dimension: .sectorStrength,
            summary: "\(Format.percentagePoints(relative.differencePoints)) vs S&P",
            direction: direction(relative.differencePoints, threshold: 1),
            claim: relative.claim(securityName: name, benchmarkName: "the S&P 500"))
    }

    private static func volatility(_ inputs: Inputs) -> ResearchComponent {
        let closes = inputs.bars.sorted { $0.date < $1.date }.map(\.analysisClose)
        guard closes.count >= 120 else {
            return .unavailable(.volatility, reason: "Fewer than 120 sessions of history.")
        }
        var returns: [Double] = []
        for index in 1..<closes.count where closes[index - 1] != 0 {
            returns.append((closes[index] - closes[index - 1]) / closes[index - 1] * 100)
        }
        let recent = Array(returns.suffix(60))
        let prior = Array(returns.dropLast(60).suffix(60))
        guard let now = Statistics.annualisedVolatility(percentReturns: recent),
              let before = Statistics.annualisedVolatility(percentReturns: prior), before > 0
        else {
            return .unavailable(.volatility, reason: "Not enough movement to measure.")
        }

        let change = (now - before) / before * 100
        return ResearchComponent(
            dimension: .volatility,
            summary: "\(Format.percent(now, precision: 1)) annualised",
            // Section 12 lists increased risk among the things to surface, so
            // rising volatility challenges. It is not a forecast of direction.
            direction: change > 20 ? .challenging : (change < -20 ? .supportive : .neutral),
            claim: Claim(
                kind: .calculation,
                text: "Realised volatility is \(Format.percent(now, precision: 1)) annualised "
                    + "over the last 60 sessions, against "
                    + "\(Format.percent(before, precision: 1)) over the 60 before that.",
                derivation: Derivation(
                    formula: "standard deviation of daily returns × √252",
                    inputs: [.init(name: "recent 60 sessions",
                                   value: Format.percent(now, precision: 1), source: nil),
                             .init(name: "prior 60 sessions",
                                   value: Format.percent(before, precision: 1), source: nil)],
                    result: Format.signedPercent(change, precision: 1))))
    }

    // MARK: - Fundamental dimensions

    private static func revenueTrend(_ inputs: Inputs) -> ResearchComponent {
        growthComponent(.revenueTrend, concept: .revenue, label: "Revenue", inputs: inputs)
    }

    private static func earningsTrend(_ inputs: Inputs) -> ResearchComponent {
        growthComponent(.earningsTrend, concept: .netIncome, label: "Net income", inputs: inputs)
    }

    private static func growthComponent(
        _ dimension: ResearchDimension,
        concept: FinancialConcept,
        label: String,
        inputs: Inputs
    ) -> ResearchComponent {
        let series = FundamentalDetector.quarterly(inputs.fundamentals, concept)
        guard let latest = FundamentalDetector.yearOverYear(series).last else {
            return .unavailable(dimension, reason: "No comparable period a year earlier.")
        }
        // Growth off a non-positive base is not a growth rate. Net income
        // crossing from a loss to a profit is real news, and "+250%" is not the
        // way to say it.
        guard latest.prior > 0 else {
            return ResearchComponent(
                dimension: dimension,
                summary: "\(Format.compactCurrency(latest.current)) this quarter",
                direction: latest.current > latest.prior ? .supportive : .challenging,
                claim: Claim(
                    kind: .calculation,
                    text: "\(label) was \(Format.compactCurrency(latest.current)) against "
                        + "\(Format.compactCurrency(latest.prior)) a year earlier. A percentage "
                        + "change from a non-positive base would not describe this.",
                    sources: []))
        }

        let growth = (latest.current - latest.prior) / latest.prior * 100
        return ResearchComponent(
            dimension: dimension,
            summary: "\(Format.signedPercent(growth, precision: 1)) YoY",
            direction: direction(growth, threshold: 1),
            claim: Claim(
                kind: .calculation,
                text: "\(label) grew \(Format.signedPercent(growth, precision: 1)) against the "
                    + "same quarter a year earlier, so seasonality is already removed.",
                derivation: Derivation(
                    formula: "(current - yearEarlier) ÷ yearEarlier",
                    inputs: [.init(name: "this quarter",
                                   value: Format.compactCurrency(latest.current), source: nil),
                             .init(name: "a year earlier",
                                   value: Format.compactCurrency(latest.prior), source: nil)],
                    result: Format.signedPercent(growth, precision: 1))))
    }

    private static func profitability(_ inputs: Inputs) -> ResearchComponent {
        let series = FundamentalDetector.marginSeries(facts: inputs.fundamentals,
                                                      numerator: .operatingIncome)
        guard let latest = FundamentalDetector.yearOverYear(series).last else {
            return .unavailable(.profitability, reason: "Operating margin not reported.")
        }
        let change = latest.current - latest.prior
        return ResearchComponent(
            dimension: .profitability,
            summary: "\(Format.percent(latest.current, precision: 1)) operating margin",
            direction: direction(change, threshold: 0.5),
            claim: Claim(
                kind: .calculation,
                text: "Operating margin was \(Format.percent(latest.current, precision: 1)), "
                    + "\(Format.percentagePoints(change)) against the same quarter a year "
                    + "earlier.",
                derivation: Derivation(
                    formula: "operatingIncome ÷ revenue",
                    inputs: [.init(name: "this quarter",
                                   value: Format.percent(latest.current, precision: 1), source: nil),
                             .init(name: "a year earlier",
                                   value: Format.percent(latest.prior, precision: 1), source: nil)],
                    result: Format.percentagePoints(change))))
    }

    private static func balanceSheet(_ inputs: Inputs) -> ResearchComponent {
        let debt = FundamentalDetector.instant(inputs.fundamentals, .totalDebt)
        let cash = FundamentalDetector.instant(inputs.fundamentals, .cashAndEquivalents)
        guard let latestDebt = debt.last, let latestCash = cash.last else {
            return .unavailable(.balanceSheet, reason: "Debt or cash not reported.")
        }
        let net = latestCash.value - latestDebt.value
        return ResearchComponent(
            dimension: .balanceSheet,
            summary: net >= 0
                ? "\(Format.compactCurrency(net)) net cash"
                : "\(Format.compactCurrency(abs(net))) net debt",
            direction: net >= 0 ? .supportive : .challenging,
            claim: Claim(
                kind: .calculation,
                text: "Cash and equivalents of \(Format.compactCurrency(latestCash.value)) "
                    + "against total debt of \(Format.compactCurrency(latestDebt.value)). "
                    + "Net debt is not by itself a problem; it is a constraint whose cost "
                    + "depends on rates and on what the borrowing funded.",
                derivation: Derivation(
                    formula: "cash - totalDebt",
                    inputs: [.init(name: "cash",
                                   value: Format.compactCurrency(latestCash.value), source: nil),
                             .init(name: "total debt",
                                   value: Format.compactCurrency(latestDebt.value), source: nil)],
                    result: Format.compactCurrency(net))))
    }

    private static func valuation(_ inputs: Inputs) -> ResearchComponent {
        guard let metrics = inputs.metrics else {
            return .unavailable(.valuation, reason: "No metrics loaded.")
        }
        let metric = ValuationMetric.all.first { $0.key == "peTTM" } ?? ValuationMetric.all[0]
        guard let normalized = metrics.normalized(for: metric),
              let context = ValuationCalculator.historicalContext(
                current: normalized.current, history: normalized.history,
                lowerIsCheaper: metric.lowerIsCheaper,
                currentIsFromHistory: normalized.currentIsFromHistory)
        else {
            return .unavailable(.valuation, reason: "Not enough history to rank a multiple.")
        }
        guard context.meaningfulness.isRankable else {
            // A negative multiple ranked naively lands at the bottom and reads
            // as cheap when it means the company lost money.
            return ResearchComponent(
                dimension: .valuation,
                summary: "\(metric.displayName) not meaningful",
                direction: .challenging,
                claim: Claim(kind: .interpretation,
                             text: context.meaningfulness.reason ?? "Cannot be ranked."))
        }

        return ResearchComponent(
            dimension: .valuation,
            summary: "\(metric.displayName) \(metric.format(context.current)), "
                + "\(Format.ordinal(context.percentile)) pctile",
            // Section 12 lists expensive valuation among the things to surface.
            // High in its own range challenges; low supports. Neither is advice.
            direction: context.percentile >= 75 ? .challenging
                : (context.percentile <= 25 ? .supportive : .neutral),
            claim: Claim(
                kind: .calculation,
                text: "\(metric.displayName) of \(metric.format(context.current)) sits at the "
                    + "\(Format.ordinal(context.percentile)) percentile of this company's own "
                    + "\(context.observationCount) observations since "
                    + "\(Format.shortDate(context.earliest)) — not against other companies.",
                derivation: Derivation(
                    formula: "share of prior observations at or below the current value",
                    inputs: [.init(name: "current",
                                   value: metric.format(context.current), source: nil),
                             .init(name: "median",
                                   value: metric.format(context.median), source: nil),
                             .init(name: "observations",
                                   value: "\(context.observationCount)", source: nil)],
                    result: "\(Format.ordinal(context.percentile)) percentile")))
    }

    // MARK: - Third-party dimensions

    private static func analystPosture(_ inputs: Inputs) -> ResearchComponent {
        guard let ratings = inputs.ratings, ratings.total > 0 else {
            return .unavailable(.analystPosture,
                                reason: "No published ratings. Estimate revisions need a paid tier.")
        }
        let positive = ratings.strongBuy + ratings.buy
        let negative = ratings.sell + ratings.strongSell
        let net = Double(positive - negative) / Double(ratings.total) * 100

        return ResearchComponent(
            dimension: .analystPosture,
            summary: "\(positive) buy / \(ratings.hold) hold / \(negative) sell",
            direction: direction(net, threshold: 10),
            claim: Claim(
                kind: .calculation,
                text: "Of \(ratings.total) published ratings, \(positive) are buy-equivalent, "
                    + "\(ratings.hold) hold and \(negative) sell-equivalent, as of "
                    + "\(Format.shortDate(ratings.asOf)). What analysts publish is not "
                    + "evidence about the business, only about their opinions of it.",
                sources: [SourceReference(provider: .finnhub, detail: "Recommendation trends",
                                          retrievedAt: ratings.asOf)]))
    }

    private static func insiderActivity(_ inputs: Inputs) -> ResearchComponent {
        guard let purchases = inputs.insiderPurchases, let sales = inputs.insiderSales,
              purchases + sales > 0
        else {
            return .unavailable(.insiderActivity,
                                reason: "No discretionary insider transactions on record.")
        }
        return ResearchComponent(
            dimension: .insiderActivity,
            summary: "\(purchases) purchase\(purchases == 1 ? "" : "s") / "
                + "\(sales) sale\(sales == 1 ? "" : "s")",
            direction: purchases == sales ? .neutral : (purchases > sales ? .supportive : .challenging),
            claim: Claim(
                kind: .calculation,
                text: "\(purchases) discretionary open-market purchase"
                    + "\(purchases == 1 ? "" : "s") and \(sales) sale\(sales == 1 ? "" : "s") "
                    + "on record. Scheduled plans, grants and tax withholding are excluded "
                    + "because they carry no opinion. Insider activity does not predict "
                    + "returns.",
                sources: [SourceReference(provider: .sec, detail: "Form 4 filings",
                                          retrievedAt: .now)]))
    }

    // MARK: - Classification

    /// Positive supports, negative challenges, and a band around zero is
    /// neither. The band exists so a 0.2% move is not dressed up as a finding.
    private static func direction(_ value: Double, threshold: Double) -> EvidenceDirection {
        if value > threshold { return .supportive }
        if value < -threshold { return .challenging }
        return .neutral
    }
}
