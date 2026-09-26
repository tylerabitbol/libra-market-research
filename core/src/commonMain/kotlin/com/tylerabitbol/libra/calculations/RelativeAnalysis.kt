package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.EvidenceCategory
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.Claim
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.support.Format
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** A close on a date — the market leg's shape, which has no bars of its own. */
data class ClosePoint(val date: Instant, val close: Double)

data class AlignedReturn(
    val date: Instant,
    val security: Double,
    val market: Double
)

/** One session with all three legs measured over identical sessions. */
data class AlignedFactorReturn(
    val date: Instant,
    val security: Double,
    val market: Double,
    val sector: Double
)

/** The sector factor, fitted and shown so it can be rejected. */
data class SectorFactor(
    val name: String,
    /**
     * How the sector itself moves with the market. Used only to strip the
     * market out of the sector before the sector is used as a factor.
     */
    val marketBeta: Double,
    /** How this security moves with the sector's market-adjusted move. */
    val sensitivity: Double,
    val observationCount: Int,
    /**
     * True when the sector is represented by an ETF rather than an index.
     * FRED publishes no sector series, so this is always true today.
     */
    val isProxy: Boolean,
    val earliest: Instant,
    val latest: Instant
) {
    val claim: Claim
        get() = Claim(
            kind = ClaimKind.Calculation,
            text = "Sensitivity of ${Format.ratio(sensitivity, precision = 2)} to $name " +
                "beyond the market, measured over $observationCount sessions to " +
                "${Format.shortDate(latest)}.",
            derivation = Derivation(
                formula = "slope(security, sector - sectorMarketBeta × market)",
                inputs = listOf(
                    Derivation.Input(
                        "sector beta to market",
                        Format.ratio(marketBeta, precision = 2)
                    ),
                    Derivation.Input("sessions", "$observationCount"),
                    Derivation.Input("from", Format.shortDate(earliest)),
                    Derivation.Input("to", Format.shortDate(latest))
                ),
                result = Format.ratio(sensitivity, precision = 2)
            )
        )
}

data class Beta(
    val value: Double,
    val observationCount: Int,
    val earliest: Instant,
    val latest: Instant
) {
    val claim: Claim
        get() = Claim(
            kind = ClaimKind.Calculation,
            text = "Beta of ${Format.ratio(value, precision = 2)} against the market, " +
                "measured over the $observationCount sessions to " +
                "${Format.shortDate(latest)}.",
            derivation = Derivation(
                formula = "covariance(security, market) ÷ variance(market)",
                inputs = listOf(
                    Derivation.Input("sessions", "$observationCount"),
                    Derivation.Input("from", Format.shortDate(earliest)),
                    Derivation.Input("to", Format.shortDate(latest))
                ),
                result = Format.ratio(value, precision = 2)
            )
        )
}

/** One session's move split between market and company. */
data class MoveAttribution(
    val securityMove: Double,
    val marketMove: Double,
    val marketName: String,
    val beta: Beta?,
    /** The move a beta-times-market model accounts for, in percentage points. */
    val explainedByMarket: Double,
    /**
     * What is left over. Not "the company's move" — it is what this model
     * does not explain, which may be the sector, a filing, or nothing at all.
     */
    val residual: Double,
    /**
     * True when the market leg came from an ETF standing in for the index,
     * because the index itself publishes only at the close.
     */
    val isMarketProxy: Boolean = false,
    /**
     * The sector leg, when the company maps to a sector we track and there is
     * enough overlapping history to fit it.
     */
    val sector: SectorLeg? = null,
    /**
     * The fitted sector factor, kept so the UI can show what it rests on and
     * the reader can reject it — the same treatment beta gets.
     */
    val sectorFactor: SectorFactor? = null
) {
    val sectorFactorClaim: Claim? get() = sectorFactor?.claim

    /** What the security's own sector accounts for, over and above the market. */
    data class SectorLeg(
        val name: String,
        /** The sector's raw move that session. */
        val move: Double,
        /** The part of it the market does not explain. */
        val excess: Double,
        /** This security's sensitivity to that excess. */
        val sensitivity: Double,
        /** sensitivity × excess, in percentage points. */
        val explained: Double,
        val isProxy: Boolean
    )

    /**
     * Which Section 12 bucket the evidence points at.
     *
     * Deliberately coarse. The residual being large means the market does not
     * account for the move; it does not identify what does.
     */
    val leaning: EvidenceCategory
        get() {
            val sectorPart = abs(sector?.explained ?: 0.0)
            val marketPart = abs(explainedByMarket)
            val residualPart = abs(residual)
            if (residualPart >= marketPart && residualPart >= sectorPart) {
                return EvidenceCategory.CompanySpecific
            }
            return if (sectorPart > marketPart) EvidenceCategory.Industry
            else EvidenceCategory.MarketWide
        }

    val detailLines: List<String>
        get() {
            val lines = mutableListOf(
                "$marketName the same session: " +
                    Format.signedPercent(marketMove, precision = 2) +
                    (if (isMarketProxy) " (ETF proxy)" else "")
            )
            if (beta != null) {
                lines.add(
                    "Beta ${Format.ratio(beta.value, precision = 2)} implies " +
                        "${Format.signedPercent(explainedByMarket, precision = 2)} " +
                        "from the market alone"
                )
                lines.add("Unexplained by the market: ${Format.percentagePoints(residual)} ")
            } else {
                lines.add(
                    "Difference: ${Format.percentagePoints(residual)} " +
                        "(not beta-adjusted — too little overlapping history)"
                )
            }
            if (sector != null) {
                lines.add(
                    "${sector.name} the same session: " +
                        Format.signedPercent(sector.move, precision = 2) +
                        (if (sector.isProxy) " (ETF proxy)" else "")
                )
                lines.add(
                    "Beyond the market, the sector moved " +
                        "${Format.signedPercent(sector.excess, precision = 2)}; " +
                        "at a sensitivity of " +
                        "${Format.ratio(sector.sensitivity, precision = 2)} that accounts for " +
                        Format.signedPercent(sector.explained, precision = 2)
                )
            }
            return lines
        }

    /**
     * An INTERPRETATION: it is a judgement about what the arithmetic shows,
     * resting on a one-factor model that is named in the text rather than
     * hidden. It states where to look, never what to conclude.
     */
    val claim: Claim
        get() {
            val model = if (sector == null) "a one-factor model" else "a two-factor model"
            val text = when (leaning) {
                EvidenceCategory.MarketWide ->
                    "Most of this move is what $model would expect from $marketName " +
                        "alone, so it looks market-wide rather than specific to this company. " +
                        "Company news may still exist; it is simply not needed to account for " +
                        "the size of the move."

                EvidenceCategory.Industry ->
                    "${sector?.name ?: "The sector"} accounts for more of this move than " +
                        "$marketName does, once the market's own effect on the sector is " +
                        "removed. That points at something affecting these companies together " +
                        "rather than this one alone — peers and industry news are where to look."

                else -> {
                    val unexplained = sector?.let { "Neither $marketName nor ${it.name}" }
                        ?: "$marketName does not"
                    // Both branches need a verb. "Neither A nor B" takes the
                    // singular "accounts for"; the single-subject form needs the
                    // bare infinitive after "does not".
                    val verb = if (sector == null) " explain" else " accounts for"
                    "$unexplained$verb most of this move under $model, which points " +
                        "at something specific to this company. Filings, earnings, and its own " +
                        "news are where to look."
                }
            }

            val inputs = mutableListOf(
                Derivation.Input("security", Format.signedPercent(securityMove, precision = 2)),
                Derivation.Input(marketName, Format.signedPercent(marketMove, precision = 2)),
                Derivation.Input(
                    "beta",
                    beta?.let { Format.ratio(it.value, precision = 2) } ?: "not measured"
                )
            )
            if (sector != null) {
                inputs.add(
                    Derivation.Input(
                        "${sector.name} beyond the market",
                        Format.signedPercent(sector.excess, precision = 2)
                    )
                )
                inputs.add(
                    Derivation.Input(
                        "sensitivity to it",
                        Format.ratio(sector.sensitivity, precision = 2)
                    )
                )
            }

            return Claim(
                kind = ClaimKind.Interpretation,
                text = text,
                derivation = Derivation(
                    formula = if (sector == null) {
                        "residual = securityMove - beta × marketMove"
                    } else {
                        "residual = securityMove - beta × marketMove " +
                            "- sensitivity × (sectorMove - sectorMarketBeta × marketMove)"
                    },
                    inputs = inputs,
                    result = Format.percentagePoints(residual)
                )
            )
        }
}

/**
 * Separating "the market moved" from "this company moved" — the deterministic
 * half of Section 4's *why*.
 *
 * A detector can say a move was unusual. It cannot say why. But it can say
 * how much of the move the market accounts for, which is the single most
 * useful decomposition available without reading any news: a stock down 6% on
 * a day the market fell 5% is a very different situation from the same 6% on
 * a flat day, and a normal stock app shows both identically.
 *
 * Everything here is arithmetic over aligned daily returns. No model is
 * fitted beyond a single-factor beta, and that beta is shown rather than
 * assumed so the reader can reject it.
 */
object RelativeAnalysis {

    /**
     * Observations required before a beta is reported.
     *
     * A beta from a handful of sessions is noise with a Greek letter on it.
     */
    const val minimumBetaObservations = 60

    /**
     * Sessions a beta is fitted over — about two years.
     *
     * Capped rather than "use everything available". Beta is not a constant:
     * a company's sensitivity to the market changes as its business, size and
     * leverage change, and NVIDIA's five-year history spans regimes that have
     * little to do with each other. A longer window looks more rigorous and
     * is actually less informative, because it averages a sensitivity that no
     * longer applies into the one being used today.
     */
    const val betaWindow = 500

    private data class DailyReturn(
        val date: Instant,
        val percent: Double,
        val previousDay: LocalDate
    )

    /**
     * Daily returns for the security and the market on the same sessions.
     *
     * Sessions present in only one series are dropped rather than
     * interpolated. A holiday in one calendar and not the other would
     * otherwise pair a two-day move against a one-day move and call the
     * difference company-specific.
     */
    fun align(
        security: List<PriceBar>,
        market: List<ClosePoint>,
        zone: TimeZone = TimeZone.currentSystemDefault()
    ): List<AlignedReturn> {
        val securityByDay = dailyReturnsByDay(
            security.sortedBy { it.date }.map { ClosePoint(it.date, it.analysisClose) },
            zone
        )
        val marketByDay = dailyReturnsByDay(market.sortedBy { it.date }, zone)

        return securityByDay.keys.mapNotNull { day ->
            val securityLeg = securityByDay[day] ?: return@mapNotNull null
            val marketLeg = marketByDay[day] ?: return@mapNotNull null
            // Both legs must span the *same* pair of sessions. Matching on
            // the end day alone is not enough: after a holiday present in
            // one calendar and not the other, one series' "daily" return
            // covers two days and the other covers one. The difference
            // then lands in the residual and reads as company-specific.
            if (securityLeg.previousDay != marketLeg.previousDay) return@mapNotNull null
            AlignedReturn(securityLeg.date, securityLeg.percent, marketLeg.percent)
        }.sortedBy { it.date }
    }

    /**
     * Security, market and sector returns on identical sessions.
     *
     * Same rule as the two-series case, applied across three: every leg must
     * span the same pair of sessions, or a holiday in one calendar and not
     * another silently compares a two-day move against a one-day move.
     */
    fun align(
        security: List<PriceBar>,
        market: List<ClosePoint>,
        sector: List<PriceBar>,
        zone: TimeZone = TimeZone.currentSystemDefault()
    ): List<AlignedFactorReturn> {
        val marketLeg = align(security, market, zone).associateBy { it.date }
        // Reusing the pairwise aligner for the sector leg keeps the
        // previous-session rule in exactly one place.
        val sectorLeg = align(
            security,
            sector.sortedBy { it.date }.map { ClosePoint(it.date, it.analysisClose) },
            zone
        ).associate { it.date to it.market }

        return marketLeg.values.mapNotNull { pair ->
            val sectorReturn = sectorLeg[pair.date] ?: return@mapNotNull null
            AlignedFactorReturn(pair.date, pair.security, pair.market, sectorReturn)
        }.sortedBy { it.date }
    }

    /**
     * Sensitivity of the security's daily returns to the market's.
     *
     * Ordinary covariance over variance — the textbook single-factor beta.
     * Returned with its sample size so the UI can say what it rests on, and
     * null rather than a number when the market series barely moved, since
     * dividing by a near-zero variance produces an enormous beta from nothing.
     */
    fun beta(aligned: List<AlignedReturn>): Beta? {
        if (aligned.size < minimumBetaObservations) return null
        val window = aligned.takeLast(betaWindow)

        val securityMean = window.sumOf { it.security } / window.size
        val marketMean = window.sumOf { it.market } / window.size

        var covariance = 0.0
        var variance = 0.0
        for (point in window) {
            val marketDeviation = point.market - marketMean
            covariance += (point.security - securityMean) * marketDeviation
            variance += marketDeviation * marketDeviation
        }
        if (variance <= 0 || !covariance.isFinite()) return null

        val value = covariance / variance
        if (!value.isFinite()) return null
        return Beta(
            value = value,
            observationCount = window.size,
            earliest = window.firstOrNull()?.date ?: Clock.System.now(),
            latest = window.lastOrNull()?.date ?: Clock.System.now()
        )
    }

    /**
     * Splits one session's move into the part the market accounts for and the
     * part it does not.
     *
     * With a beta, the market's share is `beta × marketMove` — a one-factor
     * model, named as such. Without one, the comparison is the plain
     * difference and is labelled as not beta-adjusted, because for a
     * high-beta name those two answers differ enough to change the reading.
     */
    fun attribute(
        securityMove: Double,
        marketMove: Double,
        marketName: String,
        beta: Beta?,
        isMarketProxy: Boolean = false
    ): MoveAttribution {
        val explained = beta?.let { it.value * marketMove } ?: marketMove
        return MoveAttribution(
            securityMove = securityMove,
            marketMove = marketMove,
            marketName = marketName,
            beta = beta,
            explainedByMarket = explained,
            residual = securityMove - explained,
            isMarketProxy = isMarketProxy
        )
    }

    /**
     * Splits a move across the market and the security's sector.
     *
     * The sector leg is **orthogonalised against the market before it is
     * used**. A sector ETF moves with the market — XLK and the S&P share most
     * of their variance — so subtracting a raw sector move from what the
     * market already explained counts the market twice and leaves a residual
     * that is mostly sign noise.
     *
     * What is measured instead is the part of the sector's move the market
     * does not account for, and how sensitive this security is to *that*.
     * Because the second factor is uncorrelated with the first by
     * construction, the two sensitivities can be fitted separately and still
     * mean what a joint fit would have meant.
     *
     * Still a model, and still named as one. The residual is what neither
     * factor accounts for — not "the company's move".
     */
    fun attribute(
        securityMove: Double,
        marketMove: Double,
        marketName: String,
        beta: Beta?,
        sector: SectorFactor?,
        sectorMove: Double?,
        isMarketProxy: Boolean = false
    ): MoveAttribution {
        val explained = beta?.let { it.value * marketMove } ?: marketMove

        var leg: MoveAttribution.SectorLeg? = null
        if (sector != null && sectorMove != null) {
            val excess = sectorMove - sector.marketBeta * marketMove
            leg = MoveAttribution.SectorLeg(
                name = sector.name,
                move = sectorMove,
                excess = excess,
                sensitivity = sector.sensitivity,
                explained = sector.sensitivity * excess,
                isProxy = sector.isProxy
            )
        }

        return MoveAttribution(
            securityMove = securityMove,
            marketMove = marketMove,
            marketName = marketName,
            beta = beta,
            explainedByMarket = explained,
            residual = securityMove - explained - (leg?.explained ?: 0.0),
            isMarketProxy = isMarketProxy,
            sector = leg,
            sectorFactor = sector
        )
    }

    /**
     * Fits the sector factor: how the sector moves with the market, and how
     * this security moves with what is left of the sector after that.
     *
     * Null rather than a number when there is too little overlapping history —
     * a sensitivity from a handful of sessions is noise with a name on it.
     */
    fun sectorFactor(
        aligned: List<AlignedFactorReturn>,
        name: String,
        isProxy: Boolean
    ): SectorFactor? {
        if (aligned.size < minimumBetaObservations) return null
        val window = aligned.takeLast(betaWindow)

        val marketBeta = slope(window.map { it.sector }, window.map { it.market }) ?: return null

        val excess = window.map { it.sector - marketBeta * it.market }
        val sensitivity = slope(window.map { it.security }, excess) ?: return null

        return SectorFactor(
            name = name,
            marketBeta = marketBeta,
            sensitivity = sensitivity,
            observationCount = window.size,
            isProxy = isProxy,
            earliest = window.firstOrNull()?.date ?: Instant.DISTANT_PAST,
            latest = window.lastOrNull()?.date ?: Instant.DISTANT_PAST
        )
    }

    /**
     * Ordinary least-squares slope of [y] on [x].
     *
     * Null when [x] barely moved: dividing by a near-zero variance produces an
     * enormous coefficient out of nothing at all.
     */
    private fun slope(y: List<Double>, x: List<Double>): Double? {
        if (y.size != x.size || y.size <= 1) return null
        val count = y.size.toDouble()
        val meanX = x.sum() / count
        val meanY = y.sum() / count
        var covariance = 0.0
        var variance = 0.0
        for (index in x.indices) {
            val dx = x[index] - meanX
            covariance += dx * (y[index] - meanY)
            variance += dx * dx
        }
        if (variance <= 1e-12) return null
        return covariance / variance
    }

    /**
     * Daily returns keyed by session, each carrying the session it was
     * measured from so two series can be checked for identical windows.
     */
    private fun dailyReturnsByDay(
        series: List<ClosePoint>,
        zone: TimeZone
    ): Map<LocalDate, DailyReturn> {
        if (series.size <= 1) return emptyMap()
        val result = mutableMapOf<LocalDate, DailyReturn>()
        for (index in 1 until series.size) {
            val percent = ReturnCalculator.simpleReturn(
                series[index - 1].close, series[index].close
            ) ?: continue
            val day = series[index].date.toLocalDateTime(zone).date
            result[day] = DailyReturn(
                date = series[index].date,
                percent = percent,
                previousDay = series[index - 1].date.toLocalDateTime(zone).date
            )
        }
        return result
    }
}
