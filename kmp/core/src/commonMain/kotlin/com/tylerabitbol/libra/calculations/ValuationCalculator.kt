package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.provenance.Claim
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.models.provenance.SourceReference
import com.tylerabitbol.libra.services.providers.CompanyMetricsDTO
import com.tylerabitbol.libra.services.providers.MetricPoint
import com.tylerabitbol.libra.support.Format
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * Why a value cannot be ranked, when it cannot.
 *
 * A negative multiple is the important case. Ranked naively it lands at the
 * bottom of a positive history and the descriptor reads "near the low end of
 * its own range" — which a reader takes as cheap, when it actually means the
 * company lost money. Refusing to rank it, and saying why, is the only honest
 * option; omitting it silently would hide the loss entirely.
 */
sealed interface MetricMeaningfulness {
    data object Rankable : MetricMeaningfulness
    data class NotMeaningful(val notMeaningfulReason: String) : MetricMeaningfulness

    val isRankable: Boolean get() = this is Rankable

    val reason: String?
        get() = (this as? NotMeaningful)?.notMeaningfulReason
}

/**
 * Where a current value sits within its own history.
 *
 * Section 6 of the specification asks for exactly this: "P/E: 31, historical
 * percentile: 78th" rather than a bare multiple. The comparison is against
 * the company's *own* past, not against other companies — a software firm at
 * a P/E of 31 and a utility at 31 are not comparable, but a company against
 * its own ten-year range is.
 */
data class HistoricalContext(
    val current: Double,
    /**
     * 0–100. The share of *prior* observations at or below [current].
     * Meaningless unless [meaningfulness] is rankable.
     */
    val percentile: Int,
    val median: Double,
    val minimum: Double,
    val maximum: Double,
    val observationCount: Int,
    val earliest: Instant,
    val latest: Instant,
    val meaningfulness: MetricMeaningfulness,
    /**
     * True when [current] was taken from the history rather than from a live
     * figure, so the UI can date-stamp it instead of implying it is current.
     */
    val currentIsFromHistory: Boolean
) {
    val descriptor: String
        get() {
            meaningfulness.reason?.let { return it }
            return when {
                percentile < 10 -> "near the low end of its own range"
                percentile < 25 -> "below its usual range"
                percentile < 75 -> "within its usual range"
                percentile < 90 -> "above its usual range"
                else -> "near the high end of its own range"
            }
        }
}

object ValuationCalculator {

    /** Fewer observations than this and a percentile is not reported. */
    const val minimumObservations = 8

    /**
     * Percentile rank of [current] within [history].
     *
     * Uses the "less than or equal" definition: the share of observations at
     * or below the current value. Requires a minimum sample, because a
     * percentile drawn from three observations invites more confidence than
     * it deserves — below that it returns null rather than a weak number
     * dressed as a strong one.
     */
    fun historicalContext(
        current: Double,
        history: List<MetricPoint>,
        minimumObservations: Int = ValuationCalculator.minimumObservations,
        lowerIsCheaper: Boolean = true,
        currentIsFromHistory: Boolean = false
    ): HistoricalContext? {
        val usable = history.filter { it.value.isFinite() }
        if (!current.isFinite() || usable.size < minimumObservations) return null
        val earliest = usable.minOfOrNull { it.period } ?: return null
        val latest = usable.maxOfOrNull { it.period } ?: return null

        // When `current` came from the history, it is the last element of it.
        // Counting an observation in its own ranking inflates the percentile by
        // roughly 1/n and guarantees a value can never rank below itself.
        val priors = if (currentIsFromHistory) usable.dropLast(1) else usable
        if (priors.size < minimumObservations - 1) return null

        val values = priors.map { it.value }.sorted()
        val atOrBelow = values.count { it <= current }
        val percentile = (atOrBelow.toDouble() / values.size * 100).roundToInt()

        return HistoricalContext(
            current = current,
            percentile = percentile.coerceIn(0, 100),
            median = median(values),
            minimum = values.firstOrNull() ?: current,
            maximum = values.lastOrNull() ?: current,
            observationCount = values.size,
            earliest = earliest,
            latest = latest,
            meaningfulness = meaningfulness(current, values, lowerIsCheaper),
            currentIsFromHistory = currentIsFromHistory
        )
    }

    /**
     * Decides whether a ranking would mean anything.
     *
     * Only applied to metrics where lower is conventionally cheaper — the
     * price multiples. A negative margin or return on equity is a perfectly
     * meaningful figure that genuinely sits at the bottom of its range.
     */
    fun meaningfulness(
        current: Double,
        history: List<Double>,
        lowerIsCheaper: Boolean
    ): MetricMeaningfulness {
        if (!lowerIsCheaper) return MetricMeaningfulness.Rankable

        if (current < 0) {
            return MetricMeaningfulness.NotMeaningful(
                "Not meaningful — a negative multiple reflects " +
                    "negative earnings, not a low valuation."
            )
        }
        // A history straddling zero cannot be ordered sensibly either: a large
        // negative and a large positive sit at opposite ends of a range whose
        // middle has no interpretation.
        if (history.any { it < 0 } && history.any { it > 0 }) {
            return MetricMeaningfulness.NotMeaningful(
                "Not meaningful — this metric was negative in " +
                    "part of the period, so its range cannot be ranked."
            )
        }
        return MetricMeaningfulness.Rankable
    }

    fun median(sortedValues: List<Double>): Double {
        if (sortedValues.isEmpty()) return Double.NaN
        val middle = sortedValues.size / 2
        return if (sortedValues.size % 2 == 0) {
            (sortedValues[middle - 1] + sortedValues[middle]) / 2
        } else {
            sortedValues[middle]
        }
    }

    /**
     * The two claims a valuation metric supports: the number, and what its
     * position in its own history means.
     *
     * They are returned separately and separately labelled because they carry
     * different weight. The multiple is a calculation; "expensive relative to
     * its own history" is an interpretation, and Section 24 forbids
     * presenting the second as though it were the first.
     */
    fun claims(
        metricName: String,
        context: HistoricalContext,
        source: SourceReference?
    ): List<Claim> {
        val sources = source?.let { listOf(it) } ?: emptyList()

        val value = Claim(
            kind = ClaimKind.Calculation,
            text = "$metricName is ${Format.ratio(context.current, precision = 1)}.",
            sources = sources,
            derivation = Derivation(
                formula = "current $metricName",
                inputs = listOf(
                    Derivation.Input(
                        metricName,
                        Format.ratio(context.current, precision = 1),
                        source
                    )
                ),
                result = Format.ratio(context.current, precision = 1)
            )
        )

        val interpretation = Claim(
            kind = ClaimKind.Interpretation,
            text = "That is ${Format.ordinal(context.percentile)} percentile of its own " +
                "${context.observationCount} observations since " +
                "${Format.shortDate(context.earliest)} — ${context.descriptor}.",
            sources = sources,
            derivation = Derivation(
                formula = "share of past observations at or below the current value",
                inputs = listOf(
                    Derivation.Input("current", Format.ratio(context.current, precision = 1)),
                    Derivation.Input("median", Format.ratio(context.median, precision = 1)),
                    Derivation.Input(
                        "range",
                        "${Format.ratio(context.minimum, precision = 1)}" +
                            " – ${Format.ratio(context.maximum, precision = 1)}"
                    ),
                    Derivation.Input("observations", "${context.observationCount}")
                ),
                result = "${Format.ordinal(context.percentile)} percentile"
            )
        )
        return listOf(value, interpretation)
    }
}

/** How a metric should be read and displayed. */
enum class MetricUnit {
    /** A multiple, e.g. P/E 34.0. */
    Multiple,

    /** A percentage, displayed as 46.2%. */
    Percent
}

/**
 * A valuation or profitability metric, with the two provider keys it is
 * assembled from and the scaling needed to reconcile them.
 *
 * The scaling is not incidental. Finnhub reports the same concept at different
 * scales in its two blocks: `grossMarginTTM` is 48.65 in the current metrics
 * while `grossMargin` is 0.4622 in the historical series. Ranking one against
 * the other puts every margin at the 100th percentile forever — a number that
 * looks authoritative and means nothing. Both halves are normalised to one
 * canonical scale before anything is compared.
 */
data class ValuationMetric(
    /** Key in the historical `series` block; also the metric's identity. */
    val key: String,
    /** Key in the current `metric` block, when one exists under a different name. */
    val currentKey: String?,
    val displayName: String,
    val unit: MetricUnit,
    /** Multiplier bringing a `series` value onto the canonical scale. */
    val historyScale: Double,
    /** Multiplier bringing a `metric` value onto the canonical scale. */
    val currentScale: Double,
    /**
     * True when a *lower* value is conventionally the cheaper one. Recorded so
     * the UI can explain direction without implying a recommendation.
     */
    val lowerIsCheaper: Boolean,
    /**
     * True when the current value is a trailing-twelve-month figure whose
     * history must also be twelve-month figures: the margins and ROE, whose
     * quarterly series are single quarters. The TTM multiples (`peTTM`,
     * `psTTM`) have TTM quarterly series and keep the denser history.
     */
    val historyIsAnnual: Boolean = false
) {
    val id: String get() = key

    /** Formats a canonical-scale value for display. */
    fun format(value: Double): String = when (unit) {
        MetricUnit.Multiple -> Format.ratio(value, precision = 1)
        MetricUnit.Percent -> Format.percent(value, precision = 1)
    }

    companion object {
        private fun multiple(
            key: String,
            currentKey: String?,
            name: String,
            lowerIsCheaper: Boolean = true
        ) = ValuationMetric(
            key = key, currentKey = currentKey, displayName = name, unit = MetricUnit.Multiple,
            historyScale = 1.0, currentScale = 1.0, lowerIsCheaper = lowerIsCheaper
        )

        /**
         * Series values arrive as ratios (0.46) and current values as
         * percentages (48.65); both are normalised to percent.
         */
        private fun margin(key: String, currentKey: String?, name: String) = ValuationMetric(
            key = key, currentKey = currentKey, displayName = name, unit = MetricUnit.Percent,
            historyScale = 100.0, currentScale = 1.0, lowerIsCheaper = false,
            historyIsAnnual = true
        )

        val all: List<ValuationMetric> = listOf(
            multiple("peTTM", "peTTM", "P/E"),
            multiple("psTTM", "psTTM", "P/S"),
            multiple("pb", "pb", "P/B"),
            // Finnhub's metric block carries no P/FCF, so this one is sourced from
            // the historical series and date-stamped rather than shown as current.
            multiple("pfcfTTM", null, "P/FCF"),
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
        )
    }
}

/** A metric's current value and history, reconciled onto one scale. */
data class NormalizedMetric(
    val current: Double,
    val history: List<MetricPoint>,
    /** True when [current] is the newest history point rather than a live value. */
    val currentIsFromHistory: Boolean,
    /** The date [current] refers to. */
    val asOf: Instant
)

/**
 * Applies the declared scale only when the data is consistent with it.
 *
 * A ratio series carries values around 0–1.5. If the values are larger,
 * the series is already in percent and multiplying again would be wrong.
 */
fun resolvedHistoryScale(declared: Double, values: List<Double>): Double {
    if (declared == 1.0) return 1.0
    val magnitudes = values.map { abs(it) }.filter { it > 0 }
    val largest = magnitudes.maxOrNull() ?: return declared
    // Ratios above 1.5 do occur (ROE of 1.37 is 137%), so the threshold is
    // set well clear of them; anything above 3 is percent already.
    return if (largest > 3) 1.0 else declared
}

/**
 * Current value and history for a metric, both on the metric's canonical
 * scale so they can legitimately be compared.
 *
 * The scaling is validated rather than assumed. Finnhub reports margins as
 * ratios in the series block and as percentages in the metric block, but
 * that is an observation about today's API, not a guarantee. Multiplying a
 * series that is already in percent would render margins 100x wrong while
 * looking entirely plausible — the worst failure available here — so the
 * data is inspected before any factor is applied.
 */
fun CompanyMetricsDTO.normalized(metric: ValuationMetric): NormalizedMetric? {
    val raw = if (metric.historyIsAnnual) {
        twelveMonthHistory(metric.key, minimumYears = ValuationCalculator.minimumObservations)
    } else {
        history(metric.key)
    }
    if (raw.isEmpty()) return null

    val scale = resolvedHistoryScale(metric.historyScale, raw.map { it.value })
    val scaledHistory = raw.map { MetricPoint(it.period, it.value * scale) }

    val currentKey = metric.currentKey
    if (currentKey != null) {
        val value = currentValue(currentKey)
        if (value != null) {
            return NormalizedMetric(
                current = value * metric.currentScale,
                history = scaledHistory,
                currentIsFromHistory = false,
                asOf = asOf
            )
        }
    }
    val latest = scaledHistory.lastOrNull() ?: return null
    return NormalizedMetric(
        current = latest.value,
        history = scaledHistory,
        currentIsFromHistory = true,
        asOf = latest.period
    )
}
