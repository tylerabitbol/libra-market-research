package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.Claim
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.models.provenance.SourceReference
import com.tylerabitbol.libra.services.providers.CompanyMetricsDTO
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.RatingSnapshotDTO
import com.tylerabitbol.libra.support.Format
import kotlin.math.abs
import kotlin.time.Clock

/**
 * Builds the Section 13 profile and, with it, Section 12's disconfirming
 * evidence.
 *
 * Pure over values so it can be tested without a provider or a container, and
 * so the direction rules are inspectable in one place rather than scattered
 * across the view layer.
 *
 * Every dimension follows the same shape: compute the figure, state the
 * arithmetic as a [ClaimKind.Calculation], then classify. The classification is
 * derived from the figure rather than chosen alongside it, which is what stops
 * the engine from finding only the evidence it went looking for.
 */
object ResearchProfileBuilder {

    data class Inputs(
        val bars: List<PriceBar> = emptyList(),
        val rangeReturn: PeriodReturn? = null,
        val relativeToMarket: RelativePerformance? = null,
        val sectorRelativeToMarket: RelativePerformance? = null,
        val sectorName: String? = null,
        val fundamentals: List<FinancialFactDTO> = emptyList(),
        val metrics: CompanyMetricsDTO? = null,
        val ratings: RatingSnapshotDTO? = null,
        /**
         * Discretionary open-market insider transactions. Scheduled plans are
         * excluded by the caller — a 10b5-1 sale carries no opinion.
         */
        val insiderPurchases: Int? = null,
        val insiderSales: Int? = null
    )

    fun build(inputs: Inputs): ResearchProfile = ResearchProfile(
        components = listOf(
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
        )
    )

    // MARK: - Price dimensions

    private fun momentum(inputs: Inputs): ResearchComponent {
        val periodReturn = inputs.rangeReturn
            ?: return ResearchComponent.unavailable(
                ResearchDimension.Momentum, "No price history loaded."
            )
        val formatted = Format.signedPercent(periodReturn.percent, precision = 1)
        return ResearchComponent(
            dimension = ResearchDimension.Momentum,
            summary = formatted,
            direction = direction(periodReturn.percent, threshold = 1.0),
            claim = Claim(
                kind = ClaimKind.Calculation,
                text = "Returned $formatted " +
                    "between ${Format.shortDate(periodReturn.startDate)} and " +
                    "${Format.shortDate(periodReturn.endDate)}.",
                derivation = Derivation(
                    formula = "(end - start) ÷ start",
                    inputs = listOf(
                        Derivation.Input(
                            "window",
                            "${Format.shortDate(periodReturn.startDate)} – " +
                                Format.shortDate(periodReturn.endDate)
                        )
                    ),
                    result = formatted
                )
            )
        )
    }

    private fun relativeStrength(inputs: Inputs): ResearchComponent {
        val relative = inputs.relativeToMarket
            ?: return ResearchComponent.unavailable(
                ResearchDimension.RelativeStrength, "No market series to compare against."
            )
        return ResearchComponent(
            dimension = ResearchDimension.RelativeStrength,
            summary = "${Format.percentagePoints(relative.differencePoints)} vs S&P",
            direction = direction(relative.differencePoints, threshold = 1.0),
            claim = relative.claim(
                securityName = "This security", benchmarkName = "the S&P 500"
            )
        )
    }

    private fun sectorStrength(inputs: Inputs): ResearchComponent {
        val relative = inputs.sectorRelativeToMarket
        val name = inputs.sectorName
        if (relative == null || name == null) {
            return ResearchComponent.unavailable(
                ResearchDimension.SectorStrength, "No sector benchmark for this company."
            )
        }
        return ResearchComponent(
            dimension = ResearchDimension.SectorStrength,
            summary = "${Format.percentagePoints(relative.differencePoints)} vs S&P",
            direction = direction(relative.differencePoints, threshold = 1.0),
            claim = relative.claim(securityName = name, benchmarkName = "the S&P 500")
        )
    }

    private fun volatility(inputs: Inputs): ResearchComponent {
        val closes = inputs.bars.sortedBy { it.date }.map { it.analysisClose }
        if (closes.size < 120) {
            return ResearchComponent.unavailable(
                ResearchDimension.Volatility, "Fewer than 120 sessions of history."
            )
        }
        val returns = mutableListOf<Double>()
        for (index in 1 until closes.size) {
            if (closes[index - 1] == 0.0) continue
            returns.add((closes[index] - closes[index - 1]) / closes[index - 1] * 100)
        }
        val recent = returns.takeLast(60)
        val prior = returns.dropLast(60).takeLast(60)
        val now = Statistics.annualisedVolatility(recent)
        val before = Statistics.annualisedVolatility(prior)
        if (now == null || before == null || before <= 0) {
            return ResearchComponent.unavailable(
                ResearchDimension.Volatility, "Not enough movement to measure."
            )
        }

        val change = (now - before) / before * 100
        return ResearchComponent(
            dimension = ResearchDimension.Volatility,
            summary = "${Format.percent(now, precision = 1)} annualised",
            // Section 12 lists increased risk among the things to surface, so
            // rising volatility challenges. It is not a forecast of direction.
            direction = when {
                change > 20 -> EvidenceDirection.Challenging
                change < -20 -> EvidenceDirection.Supportive
                else -> EvidenceDirection.Neutral
            },
            claim = Claim(
                kind = ClaimKind.Calculation,
                text = "Realised volatility is ${Format.percent(now, precision = 1)} annualised " +
                    "over the last 60 sessions, against " +
                    "${Format.percent(before, precision = 1)} over the 60 before that.",
                derivation = Derivation(
                    formula = "standard deviation of daily returns × √252",
                    inputs = listOf(
                        Derivation.Input(
                            "recent 60 sessions", Format.percent(now, precision = 1)
                        ),
                        Derivation.Input(
                            "prior 60 sessions", Format.percent(before, precision = 1)
                        )
                    ),
                    result = Format.signedPercent(change, precision = 1)
                )
            )
        )
    }

    // MARK: - Fundamental dimensions

    private fun revenueTrend(inputs: Inputs): ResearchComponent = growthComponent(
        ResearchDimension.RevenueTrend, FinancialConcept.Revenue, "Revenue", inputs
    )

    private fun earningsTrend(inputs: Inputs): ResearchComponent = growthComponent(
        ResearchDimension.EarningsTrend, FinancialConcept.NetIncome, "Net income", inputs
    )

    private fun growthComponent(
        dimension: ResearchDimension,
        concept: FinancialConcept,
        label: String,
        inputs: Inputs
    ): ResearchComponent {
        val series = FundamentalDetector.quarterly(inputs.fundamentals, concept)
        val latest = FundamentalDetector.yearOverYear(series).lastOrNull()
            ?: return ResearchComponent.unavailable(
                dimension, "No comparable period a year earlier."
            )
        // Growth off a non-positive base is not a growth rate. Net income
        // crossing from a loss to a profit is real news, and "+250%" is not the
        // way to say it.
        if (latest.prior <= 0) {
            return ResearchComponent(
                dimension = dimension,
                summary = "${Format.compactCurrency(latest.current)} this quarter",
                direction = if (latest.current > latest.prior) {
                    EvidenceDirection.Supportive
                } else {
                    EvidenceDirection.Challenging
                },
                claim = Claim(
                    kind = ClaimKind.Calculation,
                    text = "$label was ${Format.compactCurrency(latest.current)} against " +
                        "${Format.compactCurrency(latest.prior)} a year earlier. A percentage " +
                        "change from a non-positive base would not describe this.",
                    sources = emptyList()
                )
            )
        }

        val growth = (latest.current - latest.prior) / latest.prior * 100
        return ResearchComponent(
            dimension = dimension,
            summary = "${Format.signedPercent(growth, precision = 1)} YoY",
            direction = direction(growth, threshold = 1.0),
            claim = Claim(
                kind = ClaimKind.Calculation,
                text = "$label grew ${Format.signedPercent(growth, precision = 1)} against the " +
                    "same quarter a year earlier, so seasonality is already removed.",
                derivation = Derivation(
                    formula = "(current - yearEarlier) ÷ yearEarlier",
                    inputs = listOf(
                        Derivation.Input(
                            "this quarter", Format.compactCurrency(latest.current)
                        ),
                        Derivation.Input(
                            "a year earlier", Format.compactCurrency(latest.prior)
                        )
                    ),
                    result = Format.signedPercent(growth, precision = 1)
                )
            )
        )
    }

    private fun profitability(inputs: Inputs): ResearchComponent {
        val series = FundamentalDetector.marginSeries(
            inputs.fundamentals, FinancialConcept.OperatingIncome
        )
        val latest = FundamentalDetector.yearOverYear(series).lastOrNull()
            ?: return ResearchComponent.unavailable(
                ResearchDimension.Profitability, "Operating margin not reported."
            )
        val change = latest.current - latest.prior
        return ResearchComponent(
            dimension = ResearchDimension.Profitability,
            summary = "${Format.percent(latest.current, precision = 1)} operating margin",
            direction = direction(change, threshold = 0.5),
            claim = Claim(
                kind = ClaimKind.Calculation,
                text = "Operating margin was ${Format.percent(latest.current, precision = 1)}, " +
                    "${Format.percentagePoints(change)} against the same quarter a year " +
                    "earlier.",
                derivation = Derivation(
                    formula = "operatingIncome ÷ revenue",
                    inputs = listOf(
                        Derivation.Input(
                            "this quarter", Format.percent(latest.current, precision = 1)
                        ),
                        Derivation.Input(
                            "a year earlier", Format.percent(latest.prior, precision = 1)
                        )
                    ),
                    result = Format.percentagePoints(change)
                )
            )
        )
    }

    private fun balanceSheet(inputs: Inputs): ResearchComponent {
        val debt = FundamentalDetector.instant(inputs.fundamentals, FinancialConcept.TotalDebt)
        val cash = FundamentalDetector.instant(
            inputs.fundamentals, FinancialConcept.CashAndEquivalents
        )
        val latestDebt = debt.lastOrNull()
        val latestCash = cash.lastOrNull()
        if (latestDebt == null || latestCash == null) {
            return ResearchComponent.unavailable(
                ResearchDimension.BalanceSheet, "Debt or cash not reported."
            )
        }
        val net = latestCash.value - latestDebt.value
        return ResearchComponent(
            dimension = ResearchDimension.BalanceSheet,
            summary = if (net >= 0) {
                "${Format.compactCurrency(net)} net cash"
            } else {
                "${Format.compactCurrency(abs(net))} net debt"
            },
            direction = if (net >= 0) {
                EvidenceDirection.Supportive
            } else {
                EvidenceDirection.Challenging
            },
            claim = Claim(
                kind = ClaimKind.Calculation,
                text = "Cash and equivalents of ${Format.compactCurrency(latestCash.value)} " +
                    "against total debt of ${Format.compactCurrency(latestDebt.value)}. " +
                    "Net debt is not by itself a problem; it is a constraint whose cost " +
                    "depends on rates and on what the borrowing funded.",
                derivation = Derivation(
                    formula = "cash - totalDebt",
                    inputs = listOf(
                        Derivation.Input("cash", Format.compactCurrency(latestCash.value)),
                        Derivation.Input("total debt", Format.compactCurrency(latestDebt.value))
                    ),
                    result = Format.compactCurrency(net)
                )
            )
        )
    }

    private fun valuation(inputs: Inputs): ResearchComponent {
        val metrics = inputs.metrics
            ?: return ResearchComponent.unavailable(
                ResearchDimension.Valuation, "No metrics loaded."
            )
        val metric = ValuationMetric.all.firstOrNull { it.key == "peTTM" } ?: ValuationMetric.all[0]
        val normalized = metrics.normalized(metric)
        val context = normalized?.let {
            ValuationCalculator.historicalContext(
                current = it.current, history = it.history,
                lowerIsCheaper = metric.lowerIsCheaper,
                currentIsFromHistory = it.currentIsFromHistory
            )
        } ?: return ResearchComponent.unavailable(
            ResearchDimension.Valuation, "Not enough history to rank a multiple."
        )

        if (!context.meaningfulness.isRankable) {
            // A negative multiple ranked naively lands at the bottom and reads
            // as cheap when it means the company lost money.
            return ResearchComponent(
                dimension = ResearchDimension.Valuation,
                summary = "${metric.displayName} not meaningful",
                direction = EvidenceDirection.Challenging,
                claim = Claim(
                    kind = ClaimKind.Interpretation,
                    text = context.meaningfulness.reason ?: "Cannot be ranked."
                )
            )
        }

        return ResearchComponent(
            dimension = ResearchDimension.Valuation,
            summary = "${metric.displayName} ${metric.format(context.current)}, " +
                "${Format.ordinal(context.percentile)} pctile",
            // Section 12 lists expensive valuation among the things to surface.
            // High in its own range challenges; low supports. Neither is advice.
            direction = when {
                context.percentile >= 75 -> EvidenceDirection.Challenging
                context.percentile <= 25 -> EvidenceDirection.Supportive
                else -> EvidenceDirection.Neutral
            },
            claim = Claim(
                kind = ClaimKind.Calculation,
                text = "${metric.displayName} of ${metric.format(context.current)} sits at the " +
                    "${Format.ordinal(context.percentile)} percentile of this company's own " +
                    "${context.observationCount} observations since " +
                    "${Format.shortDate(context.earliest)} — not against other companies.",
                derivation = Derivation(
                    formula = "share of prior observations at or below the current value",
                    inputs = listOf(
                        Derivation.Input("current", metric.format(context.current)),
                        Derivation.Input("median", metric.format(context.median)),
                        Derivation.Input("observations", "${context.observationCount}")
                    ),
                    result = "${Format.ordinal(context.percentile)} percentile"
                )
            )
        )
    }

    // MARK: - Third-party dimensions

    private fun analystPosture(inputs: Inputs): ResearchComponent {
        val ratings = inputs.ratings
        if (ratings == null || ratings.total <= 0) {
            return ResearchComponent.unavailable(
                ResearchDimension.AnalystPosture,
                "No published ratings. Estimate revisions need a paid tier."
            )
        }
        val positive = ratings.strongBuy + ratings.buy
        val negative = ratings.sell + ratings.strongSell
        val net = (positive - negative).toDouble() / ratings.total * 100

        return ResearchComponent(
            dimension = ResearchDimension.AnalystPosture,
            summary = "$positive buy / ${ratings.hold} hold / $negative sell",
            direction = direction(net, threshold = 10.0),
            claim = Claim(
                kind = ClaimKind.Calculation,
                text = "Of ${ratings.total} published ratings, $positive are buy-equivalent, " +
                    "${ratings.hold} hold and $negative sell-equivalent, as of " +
                    "${Format.shortDate(ratings.asOf)}. What analysts publish is not " +
                    "evidence about the business, only about their opinions of it.",
                sources = listOf(
                    SourceReference(
                        provider = DataProviderID.Finnhub,
                        detail = "Recommendation trends",
                        retrievedAt = ratings.asOf
                    )
                )
            )
        )
    }

    private fun insiderActivity(inputs: Inputs): ResearchComponent {
        val purchases = inputs.insiderPurchases
        val sales = inputs.insiderSales
        if (purchases == null || sales == null || purchases + sales <= 0) {
            return ResearchComponent.unavailable(
                ResearchDimension.InsiderActivity,
                "No discretionary insider transactions on record."
            )
        }
        val purchaseWord = if (purchases == 1) "" else "s"
        val saleWord = if (sales == 1) "" else "s"
        return ResearchComponent(
            dimension = ResearchDimension.InsiderActivity,
            summary = "$purchases purchase$purchaseWord / $sales sale$saleWord",
            direction = when {
                purchases == sales -> EvidenceDirection.Neutral
                purchases > sales -> EvidenceDirection.Supportive
                else -> EvidenceDirection.Challenging
            },
            claim = Claim(
                kind = ClaimKind.Calculation,
                text = "$purchases discretionary open-market purchase$purchaseWord and " +
                    "$sales sale$saleWord on record. Scheduled plans, grants and tax " +
                    "withholding are excluded because they carry no opinion. Insider " +
                    "activity does not predict returns.",
                sources = listOf(
                    SourceReference(
                        provider = DataProviderID.SEC,
                        detail = "Form 4 filings",
                        retrievedAt = Clock.System.now()
                    )
                )
            )
        )
    }

    // MARK: - Classification

    /**
     * Positive supports, negative challenges, and a band around zero is
     * neither. The band exists so a 0.2% move is not dressed up as a finding.
     */
    private fun direction(value: Double, threshold: Double): EvidenceDirection = when {
        value > threshold -> EvidenceDirection.Supportive
        value < -threshold -> EvidenceDirection.Challenging
        else -> EvidenceDirection.Neutral
    }
}
