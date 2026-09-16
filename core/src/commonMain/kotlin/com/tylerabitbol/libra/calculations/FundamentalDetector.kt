package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import com.tylerabitbol.libra.support.Format
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Deterministic detection of change in the *reported figures* — the half of
 * Section 4's "what changed underneath" that price data cannot see.
 *
 * [EventDetector] answers "did the price do something unusual". This answers
 * "did the business". Both are pure Kotlin over data the app already holds, and
 * neither says why or what to do about it.
 *
 * Four decisions shape everything here, and each exists because the obvious
 * alternative produces a confident falsehood:
 *
 * - **Year-over-year, never quarter-over-quarter.** Most businesses are
 *   seasonal. A retailer's Q4 gross margin is not comparable to its Q3, and a
 *   detector built on consecutive quarters fires every year at the same time
 *   and reports the calendar as a change.
 * - **The comparison period is matched by date, not by counting back four.**
 *   A 52/53-week fiscal calendar shifts the closing date between years, and an
 *   issuer that skipped reporting a concept for one quarter would silently
 *   pair a period against the wrong year.
 * - **Margins and cash flow are compared in percentage points, not percent.**
 *   Free cash flow crosses zero regularly, and a percentage change through
 *   zero is either infinite or sign-flipped — a company going from -$10M to
 *   +$10M does not have a "-200% change" in any useful sense.
 * - **`occurredAt` is when the figure was filed, not the period it covers.**
 *   A June quarter disclosed in August is news in August. Dating it to June
 *   would file it behind price events the user has already seen and defeat
 *   "what changed since I last looked".
 */
object FundamentalDetector {

    /**
     * Reported periods required before a detector will say anything.
     *
     * Lower than the price detectors' 40 because the cadence is quarterly: 8
     * year-over-year observations already span three years of filings, and
     * waiting for 40 would mean saying nothing for a decade.
     */
    const val minimumSample = 8

    /**
     * Window for dispersion — three years, so a business that has genuinely
     * changed shape is judged against what it is now.
     */
    const val scaleWindow = 12

    /**
     * Window for rank. Ten years of quarters; rank costs nothing over more
     * data and "the largest in the record" is the more useful statement.
     */
    const val rankWindow = 40

    /**
     * How far into its own history a change must sit to be reported.
     *
     * Looser than the price detectors' 0.975 because the samples are two
     * orders of magnitude smaller: against 16 prior quarters, 0.975 means
     * "larger than every one", which would report only records.
     */
    const val rankThreshold = 0.80

    /** The wording for what these samples count. */
    const val sampleUnit = "reported quarters"

    /** One value at one period end, the shape every series below is built in. */
    data class SeriesPoint(val period: Instant, val value: Double)

    /** A period paired with the one about a year earlier. */
    data class YearPair(val period: Instant, val current: Double, val prior: Double)

    // MARK: - Entry point

    /**
     * Runs every fundamental detector over one company's reported figures.
     *
     * Judges the most recently reported period only. Unlike price bars, where
     * a large move on an unopened day would be lost, a filing stays the latest
     * filing until the next one lands — so the newest report is still new to a
     * user returning after a month. A second filing arriving inside one gap
     * is the exception, and the older of the two is not reported.
     */
    fun detect(
        facts: List<FinancialFactDTO>,
        sourceDetail: String = "SEC XBRL company facts"
    ): List<DetectedEventDTO> = listOfNotNull(
        marginChange(facts = facts, sourceDetail = sourceDetail),
        revenueGrowthChange(facts = facts, sourceDetail = sourceDetail),
        freeCashFlowChange(facts = facts, sourceDetail = sourceDetail),
        debtChange(facts = facts, sourceDetail = sourceDetail)
    )

    // MARK: - Margins

    /**
     * An unusual year-over-year move in gross, operating or net margin.
     *
     * One event covering all three rather than one per margin. They move
     * together and arrive in the same filing, so three cards would be three
     * views of one fact — and `naturalKey` is one event per kind per day, so
     * the store would collapse them anyway and keep an arbitrary one.
     */
    fun marginChange(
        facts: List<FinancialFactDTO>,
        minimumChangePoints: Double = 1.5,
        sourceDetail: String = "SEC XBRL company facts"
    ): DetectedEventDTO? {
        val margins = listOf(
            "Gross margin" to FinancialConcept.GrossProfit,
            "Operating margin" to FinancialConcept.OperatingIncome,
            "Net margin" to FinancialConcept.NetIncome
        )

        val lines = mutableListOf<String>()
        var strongest: Triple<AnomalyMeasure, String, Double>? = null
        var latestFiledAt: Instant? = null
        var period: Instant? = null

        for ((label, concept) in margins) {
            val series = marginSeries(facts = facts, numerator = concept)
            val move = latestUnusualChange(
                series = series, minimumChange = minimumChangePoints,
                filedAt = { filedAt(it, concept, facts) }
            ) ?: continue

            // "(quarterly)" is not decoration. `ValuationCalculator` reports
            // the same three margins over the trailing twelve months, on the
            // same page, and two windows under one name read as a
            // contradiction — GOOGL's 93.7% quarter beside its 54.8% TTM.
            lines.add(
                "$label (quarterly): ${Format.percent(move.priorValue, precision = 1)} → " +
                    "${Format.percent(move.currentValue, precision = 1)} " +
                    "(${Format.percentagePoints(move.change)} YoY)"
            )
            lines.add("  ${move.measure.comparisonLine}")

            val best = strongest
            if (best == null || move.measure.unusualness > best.first.unusualness) {
                strongest = Triple(move.measure, label, move.change)
            }
            move.filedAt?.let { moveFiledAt ->
                latestFiledAt = maxOf(latestFiledAt ?: moveFiledAt, moveFiledAt)
            }
            period = move.period
        }

        val best = strongest ?: return null
        val periodEnd = period ?: return null
        if (lines.isEmpty()) return null
        val (measure, label, change) = best
        val direction = if (change >= 0) "widened" else "narrowed"

        return DetectedEventDTO.create(
            kind = EventKind.MarginChange,
            occurredAt = latestFiledAt ?: periodEnd,
            headline = "$label $direction " +
                "${Format.percentagePoints(abs(change), signed = false)} year-over-year",
            detailLines = lines,
            context = "One quarter against the year-ago quarter, not a trailing " +
                "twelve-month figure — a single quarter can carry a gain or a " +
                "charge the annual number smooths away. A margin move of this " +
                "size against this company's own history is worth tracing back " +
                "to the filing. A change in product mix, a one-off charge, and " +
                "sustained pricing pressure all look identical at this level.",
            unusualness = measure.unusualness,
            sourceDetails = listOf(
                "$sourceDetail, period ending ${Format.shortDate(periodEnd)}"
            ),
            derivation = measure.derivation(
                label = "$label change",
                formatted = Format.percentagePoints(change)
            )
        )
    }

    // MARK: - Revenue growth

    /**
     * Year-over-year revenue growth that is unusual against its own history.
     *
     * Ranks the growth *rate*, not the change in it. "Grew 8.2%, the slowest
     * of the 16 quarters on record" is a single checkable statement; ranking
     * the second derivative produces a number nobody can picture.
     */
    fun revenueGrowthChange(
        facts: List<FinancialFactDTO>,
        sourceDetail: String = "SEC XBRL company facts"
    ): DetectedEventDTO? {
        val revenue = quarterly(facts, FinancialConcept.Revenue)
        val growth = yearOverYear(revenue).mapNotNull { pair ->
            // Growth off a negative base is not a growth rate; a swing from
            // -100 to -50 is not "50% growth" in any direction a reader would
            // guess. Revenue is rarely negative, but contra-revenue restatements
            // exist and would otherwise produce a confident nonsense figure.
            if (pair.prior <= 0) null
            else SeriesPoint(pair.period, (pair.current - pair.prior) / pair.prior * 100)
        }

        val latest = growth.lastOrNull() ?: return null
        val measure = measure(latest.value, growth.dropLast(1).map { it.value }) ?: return null
        if (measure.unusualness < rankThreshold) return null

        val priorGrowth = growth.dropLast(1).lastOrNull()?.value
        val direction = if (latest.value >= (priorGrowth ?: latest.value)) {
            "accelerated"
        } else {
            "slowed"
        }
        val filedAt = revenue.lastOrNull()?.let { point ->
            facts.firstOrNull {
                it.periodEnd == point.period && it.concept == FinancialConcept.Revenue
            }?.filedAt
        }

        val lines = mutableListOf(
            "Revenue growth: ${Format.signedPercent(latest.value, precision = 1)} YoY"
        )
        if (priorGrowth != null) {
            lines.add("Prior quarter: ${Format.signedPercent(priorGrowth, precision = 1)} YoY")
        }
        lines.add(measure.comparisonLine)

        return DetectedEventDTO.create(
            kind = EventKind.RevenueGrowthChange,
            occurredAt = filedAt ?: latest.period,
            headline = "Revenue growth $direction to " +
                "${Format.signedPercent(latest.value, precision = 1)} year-over-year",
            detailLines = lines,
            context = "Growth is compared with the same quarter a year earlier, so seasonality " +
                "is already removed. Whether this reflects demand, pricing, or a change in " +
                "what the company counts as revenue is not visible from the figure alone.",
            unusualness = measure.unusualness,
            sourceDetails = listOf(
                "$sourceDetail, period ending ${Format.shortDate(latest.period)}"
            ),
            derivation = measure.derivation(
                label = "Revenue growth",
                formatted = Format.signedPercent(latest.value, precision = 1)
            )
        )
    }

    // MARK: - Free cash flow

    /**
     * An unusual year-over-year move in free cash flow as a share of revenue.
     *
     * Measured as a margin rather than as a percentage change in the cash
     * figure itself, because free cash flow crosses zero: a company moving
     * from -$10M to +$10M has improved, and "-200%" describes that improvement
     * as a collapse.
     */
    fun freeCashFlowChange(
        facts: List<FinancialFactDTO>,
        minimumChangePoints: Double = 2.0,
        sourceDetail: String = "SEC XBRL company facts"
    ): DetectedEventDTO? {
        val series = freeCashFlowMarginSeries(facts = facts)
        val move = latestUnusualChange(
            series = series, minimumChange = minimumChangePoints,
            filedAt = { filedAt(it, FinancialConcept.OperatingCashFlow, facts) }
        ) ?: return null

        val cash = freeCashFlowSeries(facts = facts)
        val latestCash = cash.lastOrNull { it.period == move.period }?.value
        val direction = if (move.change >= 0) "improved" else "deteriorated"

        val lines = mutableListOf(
            "Free cash flow margin: ${Format.percent(move.priorValue, precision = 1)} → " +
                "${Format.percent(move.currentValue, precision = 1)} " +
                "(${Format.percentagePoints(move.change)} YoY)"
        )
        if (latestCash != null) {
            lines.add(
                "Free cash flow: ${Format.compactCurrency(latestCash)} " +
                    "(operating cash flow less capital expenditures)"
            )
        }
        lines.add(move.measure.comparisonLine)

        return DetectedEventDTO.create(
            kind = EventKind.FreeCashFlowChange,
            occurredAt = move.filedAt ?: move.period,
            headline = "Free cash flow margin $direction " +
                "${Format.percentagePoints(abs(move.change), signed = false)} year-over-year",
            detailLines = lines,
            context = "Free cash flow is operating cash flow less capital expenditures, as " +
                "reported. A quarter of heavy investment and a quarter of weak collections " +
                "both reduce it, and the statement of cash flows distinguishes them.",
            unusualness = move.measure.unusualness,
            sourceDetails = listOf(
                "$sourceDetail, period ending ${Format.shortDate(move.period)}"
            ),
            derivation = move.measure.derivation(
                label = "Free cash flow margin change",
                formatted = Format.percentagePoints(move.change)
            )
        )
    }

    // MARK: - Debt

    /**
     * An unusual year-over-year change in total debt.
     *
     * Debt is a balance-sheet instant and is non-negative, so unlike cash flow
     * it can honestly be compared as a percentage.
     */
    fun debtChange(
        facts: List<FinancialFactDTO>,
        minimumChangePercent: Double = 10.0,
        sourceDetail: String = "SEC XBRL company facts"
    ): DetectedEventDTO? {
        val debt = instant(facts, FinancialConcept.TotalDebt)
        val changes = yearOverYear(debt).mapNotNull { pair ->
            if (pair.prior <= 0) null
            else SeriesPoint(pair.period, (pair.current - pair.prior) / pair.prior * 100)
        }

        val latest = changes.lastOrNull() ?: return null
        if (abs(latest.value) < minimumChangePercent) return null
        val measure = measure(latest.value, changes.dropLast(1).map { it.value }) ?: return null
        if (measure.unusualness < rankThreshold) return null
        val current = debt.lastOrNull { it.period == latest.period }?.value ?: return null

        val direction = if (latest.value >= 0) "rose" else "fell"
        val lines = mutableListOf(
            "Total debt: ${Format.compactCurrency(current)} " +
                "(${Format.signedPercent(latest.value, precision = 1)} YoY)"
        )
        // Debt against cash, where the company reports both. The level matters
        // more than the change: a company that added debt while holding more
        // cash than it borrowed is in a different position from one that did not.
        instant(facts, FinancialConcept.CashAndEquivalents)
            .lastOrNull { it.period == latest.period }?.value
            ?.let { lines.add("Cash and equivalents: ${Format.compactCurrency(it)}") }
        lines.add(measure.comparisonLine)

        return DetectedEventDTO.create(
            kind = EventKind.DebtChange,
            occurredAt = filedAt(latest.period, FinancialConcept.TotalDebt, facts)
                ?: latest.period,
            headline = "Total debt $direction " +
                "${Format.percent(abs(latest.value), precision = 1)} year-over-year",
            detailLines = lines,
            context = "A change in borrowing is not by itself good or bad. Debt raised to fund " +
                "capacity and debt raised to cover operations look the same on this line; " +
                "the cash flow statement and the filing's own discussion do not.",
            unusualness = measure.unusualness,
            sourceDetails = listOf(
                "$sourceDetail, period ending ${Format.shortDate(latest.period)}"
            ),
            derivation = measure.derivation(
                label = "Total debt change",
                formatted = Format.signedPercent(latest.value, precision = 1)
            )
        )
    }

    // MARK: - Series construction

    /**
     * One value per period of a given duration for a flow concept, oldest first.
     *
     * Cumulative periods are excluded by construction — asking for [
     * FiscalPeriodKind.Quarter] cannot return a nine-month total. Reading one
     * as a quarter makes Q3 look roughly three times Q2 and corrupts every
     * growth rate computed from it, and a fact arriving from somewhere other
     * than the SEC provider would not have been filtered yet.
     *
     * The duration is a parameter because a 10-K reports annual figures and a
     * 10-Q reports quarterly ones, and filing analysis has to read whichever
     * the document actually filed.
     */
    fun flow(
        facts: List<FinancialFactDTO>,
        concept: FinancialConcept,
        kind: FiscalPeriodKind = FiscalPeriodKind.Quarter
    ): List<SeriesPoint> = facts
        .filter { it.concept == concept && it.periodKind == kind }
        .sortedBy { it.periodEnd }
        .map { SeriesPoint(it.periodEnd, it.value) }

    /** Quarterly flows — the common case, and what every detector above uses. */
    fun quarterly(
        facts: List<FinancialFactDTO>,
        concept: FinancialConcept
    ): List<SeriesPoint> = flow(facts, concept, FiscalPeriodKind.Quarter)

    /** One value per balance-sheet date, oldest first. */
    fun instant(
        facts: List<FinancialFactDTO>,
        concept: FinancialConcept
    ): List<SeriesPoint> = facts
        .filter { it.concept == concept && it.periodKind == FiscalPeriodKind.Instant }
        .sortedBy { it.periodEnd }
        .map { SeriesPoint(it.periodEnd, it.value) }

    /** A margin series in percent, one point per quarter that reports both legs. */
    fun marginSeries(
        facts: List<FinancialFactDTO>,
        numerator: FinancialConcept,
        kind: FiscalPeriodKind = FiscalPeriodKind.Quarter
    ): List<SeriesPoint> {
        val revenue = flow(facts, FinancialConcept.Revenue, kind)
            .associate { it.period to it.value }
        return flow(facts, numerator, kind).mapNotNull { point ->
            val sales = revenue[point.period]
            if (sales == null || sales <= 0) null
            else SeriesPoint(point.period, point.value / sales * 100)
        }
    }

    /**
     * Operating cash flow less capital expenditures, per quarter.
     *
     * Capital expenditure is filed as a positive magnitude under a payments
     * tag, so it is subtracted by absolute value: a company that reported it
     * with a negative sign would otherwise have it added to cash flow.
     */
    fun freeCashFlowSeries(
        facts: List<FinancialFactDTO>,
        kind: FiscalPeriodKind = FiscalPeriodKind.Quarter
    ): List<SeriesPoint> {
        val capex = flow(facts, FinancialConcept.CapitalExpenditures, kind)
            .associate { it.period to it.value }
        return flow(facts, FinancialConcept.OperatingCashFlow, kind).mapNotNull { point ->
            val spend = capex[point.period] ?: return@mapNotNull null
            SeriesPoint(point.period, point.value - abs(spend))
        }
    }

    fun freeCashFlowMarginSeries(
        facts: List<FinancialFactDTO>,
        kind: FiscalPeriodKind = FiscalPeriodKind.Quarter
    ): List<SeriesPoint> {
        val revenue = flow(facts, FinancialConcept.Revenue, kind)
            .associate { it.period to it.value }
        return freeCashFlowSeries(facts, kind).mapNotNull { point ->
            val sales = revenue[point.period]
            if (sales == null || sales <= 0) null
            else SeriesPoint(point.period, point.value / sales * 100)
        }
    }

    /**
     * Pairs each period with the one about a year earlier.
     *
     * Matched by nearest date within a tolerance rather than by counting four
     * periods back: a 52/53-week fiscal calendar moves the closing date
     * between years, and a concept an issuer skipped for one quarter would
     * otherwise pair a period against the wrong year without any signal that
     * it had done so.
     */
    fun yearOverYear(
        series: List<SeriesPoint>,
        toleranceDays: Int = 45
    ): List<YearPair> {
        val tolerance = toleranceDays.days
        return series.mapNotNull { point ->
            val target = point.period - 365.days
            val match = series
                .filter { it.period < point.period }
                .minByOrNull { (it.period - target).absoluteValue }
                ?: return@mapNotNull null
            if ((match.period - target).absoluteValue > tolerance) null
            else YearPair(point.period, point.value, match.value)
        }
    }

    // MARK: - Shared measurement

    /** One period's year-over-year change, judged against every earlier one. */
    data class UnusualChange(
        val period: Instant,
        val currentValue: Double,
        val priorValue: Double,
        /** In percentage points, since every caller is comparing rates. */
        val change: Double,
        val measure: AnomalyMeasure,
        val filedAt: Instant?
    )

    /**
     * The latest year-over-year change in a series, if it is unusual enough
     * to report.
     *
     * [minimumChange] is noise suppression — a judgement about what is worth a
     * reader's attention, not a statistical claim — and is applied on top of
     * the rank, exactly as the price detector applies its minimum move.
     */
    private fun latestUnusualChange(
        series: List<SeriesPoint>,
        minimumChange: Double,
        filedAt: (Instant) -> Instant? = { null }
    ): UnusualChange? {
        val paired = yearOverYear(series)
        val changes = paired.map { SeriesPoint(it.period, it.current - it.prior) }

        val latest = paired.lastOrNull() ?: return null
        val latestChange = changes.lastOrNull() ?: return null
        if (abs(latestChange.value) < minimumChange) return null
        val measure = measure(latestChange.value, changes.dropLast(1).map { it.value })
            ?: return null
        if (measure.unusualness < rankThreshold) return null

        return UnusualChange(
            period = latest.period,
            currentValue = latest.current,
            priorValue = latest.prior,
            change = latestChange.value,
            measure = measure,
            filedAt = filedAt(latest.period)
        )
    }

    private fun measure(observation: Double, priors: List<Double>): AnomalyMeasure? =
        AnomalyMeasure.measure(
            observation, priors,
            minimumSample = minimumSample,
            scaleWindow = scaleWindow,
            rankWindow = rankWindow,
            unit = sampleUnit
        )

    private fun filedAt(
        period: Instant,
        concept: FinancialConcept,
        facts: List<FinancialFactDTO>
    ): Instant? = facts.firstOrNull {
        it.periodEnd == period && it.concept == concept
    }?.filedAt
}
