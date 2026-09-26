package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.Claim
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.support.Format
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * The realised return over a window, together with the window actually used.
 *
 * The second part matters. If you ask for a 1-month return and only three
 * weeks of history exist, silently returning the three-week number is how a
 * research tool starts lying. This type reports what was actually measured so
 * the UI can label it, and [isFullWindow] lets a caller reject a short read
 * outright.
 */
data class PeriodReturn(
    val percent: Double,
    val startDate: Instant,
    val endDate: Instant,
    val startPrice: Double,
    val endPrice: Double,
    /** False when the available history didn't reach back to the requested start. */
    val isFullWindow: Boolean
) {
    val derivation: Derivation
        get() = Derivation(
            formula = "(endPrice - startPrice) / startPrice × 100",
            inputs = listOf(
                Derivation.Input(
                    name = "startPrice (${Format.shortDate(startDate)})",
                    value = Format.currency(startPrice)
                ),
                Derivation.Input(
                    name = "endPrice (${Format.shortDate(endDate)})",
                    value = Format.currency(endPrice)
                )
            ),
            result = Format.signedPercent(percent)
        )
}

/** One security measured against one benchmark over a shared window. */
data class RelativePerformance(
    val securityReturn: Double,
    val benchmarkReturn: Double,
    /** Positive means the security outpaced the benchmark. */
    val differencePoints: Double,
    val startDate: Instant,
    val endDate: Instant
) {
    /**
     * A calculation claim, not an interpretation: it states the arithmetic
     * without characterising it as good, strong, or promising.
     */
    fun claim(securityName: String, benchmarkName: String): Claim {
        val verb = if (differencePoints >= 0) "outperformed" else "underperformed"
        return Claim(
            kind = ClaimKind.Calculation,
            text = "$securityName $verb $benchmarkName by " +
                "${Format.percentagePoints(abs(differencePoints), signed = false)} " +
                "between ${Format.shortDate(startDate)} and ${Format.shortDate(endDate)}.",
            derivation = Derivation(
                formula = "securityReturn - benchmarkReturn",
                inputs = listOf(
                    Derivation.Input(securityName, Format.signedPercent(securityReturn)),
                    Derivation.Input(benchmarkName, Format.signedPercent(benchmarkReturn))
                ),
                result = Format.percentagePoints(differencePoints)
            )
        )
    }
}

/**
 * Where a price sits relative to its own recent averages and its peak.
 *
 * Deliberately three figures. Section 5 asks for context and then says
 * explicitly not to turn the page into a technical-analysis dashboard, so
 * there is no oscillator suite here — only what answers "is this high or low
 * for this security lately, and how far has it fallen from its best".
 *
 * Every figure is computed from the adjusted close, because a split would
 * otherwise show up as a 50% drawdown that never happened.
 */
data class PriceContext(
    val last: Double,
    /**
     * Null when fewer sessions are held than the average needs. A "200-day
     * average" of 60 sessions is a different statistic wearing the same name.
     */
    val fiftyDayAverage: Double?,
    val twoHundredDayAverage: Double?,
    /**
     * The current fall from the highest close in the window, in percent.
     * Zero at a new high, never positive.
     */
    val drawdownFromPeak: Double,
    /** The largest peak-to-trough fall within the window, in percent. */
    val deepestDrawdown: Double,
    val peak: Double,
    val windowStart: Instant,
    val windowEnd: Instant
) {
    /** Distance from an average, in percent. Null when that average is null. */
    fun distance(from: Double?): Double? {
        if (from == null || from <= 0) return null
        return (last - from) / from * 100
    }

    val fiftyDayClaim: Claim? get() = averageClaim(fiftyDayAverage, 50)
    val twoHundredDayClaim: Claim? get() = averageClaim(twoHundredDayAverage, 200)

    private fun averageClaim(average: Double?, sessions: Int): Claim? {
        if (average == null) return null
        val distance = distance(average) ?: return null
        val direction = if (distance >= 0) "above" else "below"
        return Claim(
            kind = ClaimKind.Calculation,
            text = "Trading ${Format.percent(abs(distance), precision = 1)} $direction its " +
                "$sessions-session average close.",
            derivation = Derivation(
                formula = "(last - average) ÷ average",
                inputs = listOf(
                    Derivation.Input("last", Format.currency(last)),
                    Derivation.Input("$sessions-session average", Format.currency(average))
                ),
                result = Format.signedPercent(distance, precision = 1)
            )
        )
    }

    val drawdownClaim: Claim
        get() = Claim(
            kind = ClaimKind.Calculation,
            text = if (drawdownFromPeak >= -0.05) {
                "At its highest close of the period."
            } else {
                "Down ${Format.percent(abs(drawdownFromPeak), precision = 1)} from its " +
                    "highest close of the period."
            },
            derivation = Derivation(
                formula = "(last - peak) ÷ peak",
                inputs = listOf(
                    Derivation.Input("last", Format.currency(last)),
                    Derivation.Input("peak close", Format.currency(peak)),
                    Derivation.Input(
                        "period",
                        "${Format.shortDate(windowStart)} – ${Format.shortDate(windowEnd)}"
                    )
                ),
                result = Format.signedPercent(drawdownFromPeak, precision = 1)
            )
        )
}

/**
 * Deterministic return and relative-performance arithmetic.
 *
 * Everything here is pure and total: given the same bars it produces the same
 * answer, and it returns null rather than guessing when the inputs can't
 * support a result. No AI involvement — Section 23 keeps calculation in code.
 */
object ReturnCalculator {

    /**
     * Simple percentage change between two prices.
     * Null when the base is zero or either input is non-finite.
     */
    fun simpleReturn(from: Double, to: Double): Double? {
        if (from == 0.0 || !from.isFinite() || !to.isFinite()) return null
        return (to - from) / from * 100
    }

    /**
     * [window] as a step back in time, whichever sign it was written with.
     *
     * Callers pass both forms: `DatePeriod(months = -1)` in the trailing
     * figures, and `ChartRange.datePeriod`, a positive length, everywhere a
     * range is measured. Added as given, the positive form asked for a window
     * starting in the future, and every range return — the headline return,
     * the sector and market legs, "vs S&P" — was null.
     */
    private fun backwards(window: DatePeriod): DatePeriod = DatePeriod(
        months = -abs(window.years * 12 + window.months),
        days = -abs(window.days),
    )

    /**
     * Return over a trailing window ending at the most recent bar.
     *
     * Uses the last bar at or before the window start, so a weekend or holiday
     * boundary resolves to the prior session rather than failing.
     *
     * [tolerance] is how far from the requested start the chosen bar may sit
     * while still counting as a full window. Defaults to 5 days, which absorbs
     * a long weekend without hiding a genuine data gap. It applies both ways:
     * a start bar weeks *before* the requested start, left by a hole in the
     * data, measures a longer window than was asked for, and is no more a
     * full window than one that starts late. (Swift checked the late side
     * only.)
     */
    fun trailingReturn(
        bars: List<PriceBar>,
        window: DatePeriod,
        asOf: Instant? = null,
        tolerance: Duration = 5.days,
        zone: TimeZone = TimeZone.currentSystemDefault()
    ): PeriodReturn? {
        val sorted = bars.sortedBy { it.date }
        val endBar = sorted.lastOrNull { bar -> asOf == null || bar.date <= asOf } ?: return null
        val requestedStart = endBar.date.plus(backwards(window), zone)

        // The last bar at or before the requested start; failing that, the
        // earliest bar we have, flagged as a partial window.
        val startBar = sorted.lastOrNull { it.date <= requestedStart } ?: sorted.firstOrNull()
        if (startBar == null || startBar.date >= endBar.date) return null

        val percent = simpleReturn(startBar.analysisClose, endBar.analysisClose) ?: return null

        val gap = startBar.date - requestedStart
        return PeriodReturn(
            percent = percent,
            startDate = startBar.date,
            endDate = endBar.date,
            startPrice = startBar.analysisClose,
            endPrice = endBar.analysisClose,
            isFullWindow = gap.absoluteValue <= tolerance
        )
    }

    /**
     * Relative performance in percentage points.
     *
     * Section 7 wants "NVDA outperformed its sector by approximately 7
     * percentage points" stated as arithmetic rather than adjectives. Both
     * legs must cover comparable windows or the comparison is meaningless, so
     * mismatched windows return null instead of a misleading number.
     *
     * Both ends are checked. Swift checked only the start, so a benchmark
     * whose last close was a week old was compared against a security priced
     * today. Prefer [alignedRelativePerformance], which gives both legs the
     * same end rather than merely tolerating a difference.
     */
    fun relativePerformance(
        security: PeriodReturn?,
        benchmark: PeriodReturn?,
        maxWindowMismatch: Duration = 3.days
    ): RelativePerformance? {
        if (security == null || benchmark == null) return null
        val startMismatch = (security.startDate - benchmark.startDate).absoluteValue
        val endMismatch = (security.endDate - benchmark.endDate).absoluteValue
        if (startMismatch > maxWindowMismatch || endMismatch > maxWindowMismatch) return null

        return RelativePerformance(
            securityReturn = security.percent,
            benchmarkReturn = benchmark.percent,
            differencePoints = security.percent - benchmark.percent,
            startDate = maxOf(security.startDate, benchmark.startDate),
            endDate = minOf(security.endDate, benchmark.endDate)
        )
    }

    /**
     * Relative performance with both legs measured over the same window.
     *
     * The two series rarely end on the same day. FRED publishes the S&P 500 a
     * session late, so measuring each series to its own last bar subtracts a
     * return ending yesterday from one ending today — two windows a session
     * apart, reported as one. Ending both on the day of the earlier last bar
     * makes the difference a difference over one window.
     *
     * The cut is the end of that UTC day, not the bar's instant: vendors
     * stamp a daily bar at different times of day (midnight UTC, or 04:00Z),
     * and a cut at one vendor's midnight would drop the other's bar for the
     * same session.
     */
    fun alignedRelativePerformance(
        security: List<PriceBar>,
        benchmark: List<PriceBar>,
        window: DatePeriod,
        zone: TimeZone = TimeZone.currentSystemDefault()
    ): RelativePerformance? {
        val securityEnd = security.maxOfOrNull { it.date } ?: return null
        val benchmarkEnd = benchmark.maxOfOrNull { it.date } ?: return null
        val lastDay = minOf(securityEnd, benchmarkEnd).toLocalDateTime(TimeZone.UTC).date
        val end = lastDay.plus(DatePeriod(days = 1)).atStartOfDayIn(TimeZone.UTC) - 1.nanoseconds
        return relativePerformance(
            trailingReturn(security, window, asOf = end, zone = zone),
            trailingReturn(benchmark, window, asOf = end, zone = zone)
        )
    }

    /**
     * Price context over the bars given, which is the visible range rather
     * than everything held: "down 18% from its peak" means a different thing
     * over one month than over five years, and the window is stated.
     */
    fun priceContext(bars: List<PriceBar>): PriceContext? {
        val sorted = bars.sortedBy { it.date }
        if (sorted.size <= 1) return null
        val last = sorted.last()
        val first = sorted.first()
        val closes = sorted.map { it.analysisClose }

        var runningPeak = closes[0]
        var deepest = 0.0
        for (close in closes) {
            runningPeak = maxOf(runningPeak, close)
            if (runningPeak <= 0) continue
            deepest = minOf(deepest, (close - runningPeak) / runningPeak * 100)
        }
        val peak = closes.max()

        return PriceContext(
            last = last.analysisClose,
            fiftyDayAverage = average(closes, 50),
            twoHundredDayAverage = average(closes, 200),
            drawdownFromPeak = if (peak > 0) (last.analysisClose - peak) / peak * 100 else 0.0,
            deepestDrawdown = deepest,
            peak = peak,
            windowStart = first.date,
            windowEnd = last.date
        )
    }

    /** Mean of the most recent [sessions] closes, or null if there are fewer. */
    private fun average(closes: List<Double>, sessions: Int): Double? {
        if (closes.size < sessions) return null
        val window = closes.takeLast(sessions)
        return window.sum() / window.size
    }
}
