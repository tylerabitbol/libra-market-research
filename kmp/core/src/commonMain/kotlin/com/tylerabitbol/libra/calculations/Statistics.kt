package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.support.Format
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Small statistical primitives, kept separate so they can be tested against
 * known values rather than only through the detectors that use them.
 */
object Statistics {

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else {
            sorted[middle]
        }
    }

    /**
     * Median absolute deviation scaled to be comparable with a standard
     * deviation under normality. Chosen over standard deviation because the
     * outlier being measured would otherwise inflate the very yardstick used
     * to judge it.
     */
    fun robustScale(values: List<Double>, median: Double? = null): Double? {
        if (values.isEmpty()) return null
        val centre = median ?: median(values)
        val mad = median(values.map { abs(it - centre) })
        if (mad <= 0 || !mad.isFinite()) return null
        return 1.4826 * mad
    }

    fun standardDeviation(values: List<Double>): Double? {
        if (values.size <= 1) return null
        val mean = values.sum() / values.size
        val variance = values.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) } /
            (values.size - 1)
        if (!variance.isFinite() || variance < 0) return null
        return sqrt(variance)
    }

    /**
     * Annualised realised volatility from returns already expressed in percent.
     * 252 is the conventional count of US trading sessions in a year.
     */
    fun annualisedVolatility(percentReturns: List<Double>): Double? {
        val daily = standardDeviation(percentReturns.filter { it.isFinite() }) ?: return null
        return daily * sqrt(252.0)
    }
}

/**
 * How far an observation sits from the middle of its own reference sample,
 * expressed two ways: robustly scaled deviations, and position within the
 * sample. Both are reported because neither alone is enough — deviations
 * without rank overstate rarity in a fat-tailed series, rank without
 * deviations cannot distinguish "slightly the largest" from "enormous".
 */
data class AnomalyMeasure(
    val observation: Double,
    val median: Double,
    /**
     * Median absolute deviation, rescaled so it is comparable to a standard
     * deviation for normally distributed data.
     */
    val scale: Double,
    /** Size of the window [exceededCount] is drawn from. */
    val sampleSize: Int,
    /** Prior observations whose absolute deviation was smaller than this one's. */
    val exceededCount: Int,
    /** Size of the shorter window the dispersion was measured over. */
    val scaleSampleSize: Int,
    /**
     * What the sample counts, for wording. Price data compares closed
     * sessions; fundamentals compare reported quarters, and calling those
     * "sessions" would misdescribe the comparison being made.
     */
    val unit: String
) {
    val deviations: Double get() = (observation - median) / scale

    /** 0–1. Position within the sample, not a probability. */
    val unusualness: Double get() = exceededCount.toDouble() / sampleSize

    /**
     * What the observation was compared against, in words.
     * "Larger than 250 of the prior 250" is technically right and reads badly;
     * the whole point of that reading is that nothing in the window beat it.
     */
    val comparisonLine: String
        get() = if (exceededCount == sampleSize) {
            "Larger than every one of the prior $sampleSize $unit"
        } else {
            "Larger than $exceededCount of the prior $sampleSize $unit"
        }

    fun derivation(label: String, formatted: String): Derivation = Derivation(
        formula = "(observation - median) ÷ (1.4826 × median absolute deviation)",
        inputs = listOf(
            Derivation.Input(label, formatted),
            Derivation.Input("sample median", Format.signedPercent(median, precision = 2)),
            Derivation.Input("robust scale", Format.percent(scale, precision = 2)),
            Derivation.Input("rank sample", "$sampleSize $unit"),
            Derivation.Input("scale sample", "$scaleSampleSize $unit")
        ),
        result = "${Format.multiple(abs(deviations), precision = 1)} typical"
    )

    companion object {
        /**
         * Null when the sample is too small or has no dispersion to measure
         * against — a flat series makes every deviation infinite, which would
         * render as an extraordinary event rather than as an absence of movement.
         *
         * Dispersion and rank are drawn from different windows on purpose. Scale
         * must reflect the *current* regime, so it uses the shorter window. Rank
         * is a statement about how rare something is, and is better the more
         * history it sees, so it uses the longer one.
         *
         * The windows are parameters rather than constants because the cadences
         * differ by an order of magnitude: 250 daily sessions of price history
         * against roughly 20 reported quarters. Defaults preserve the price
         * behaviour, so a caller that says nothing gets exactly what it got before.
         */
        fun measure(
            observation: Double,
            against: List<Double>,
            minimumSample: Int = EventDetector.minimumSample,
            scaleWindow: Int = EventDetector.scaleWindow,
            rankWindow: Int = EventDetector.rankWindow,
            unit: String = "closed sessions"
        ): AnomalyMeasure? {
            val usable = against.filter { it.isFinite() }
            if (!observation.isFinite() || usable.size < minimumSample - 1) return null

            val scaleSample = usable.takeLast(scaleWindow)
            val median = Statistics.median(scaleSample)
            val scale = Statistics.robustScale(scaleSample, median) ?: return null
            if (scale <= 0) return null

            val rankSample = usable.takeLast(rankWindow)
            val magnitude = abs(observation - median)
            return AnomalyMeasure(
                observation = observation,
                median = median,
                scale = scale,
                sampleSize = rankSample.size,
                exceededCount = rankSample.count { abs(it - median) < magnitude },
                scaleSampleSize = scaleSample.size,
                unit = unit
            )
        }
    }
}

/**
 * Why a given form type is worth reading, from Section 9's "meaningful filing"
 * list. Plain description of what the document contains — never a judgement
 * about what it implies for the security.
 */
object FilingSignificance {
    fun explanation(formType: String): String {
        val type = formType.uppercase()
        return when {
            type.startsWith("10-K") ->
                "The annual report: audited financial statements, risk factors, and " +
                    "management's discussion of the year."
            type.startsWith("10-Q") ->
                "The quarterly report: unaudited statements and any material change " +
                    "since the last annual report."
            type.startsWith("8-K") ->
                "A current report, filed when something material happens between " +
                    "scheduled reports. The item numbers say what."
            type == "4" || type == "4/A" ->
                "An insider's transaction in the company's own shares, reportable " +
                    "within two business days."
            type.startsWith("S-") ->
                "A registration statement — the company proposing to sell securities."
            type.startsWith("DEF 14A") || type.startsWith("DEFA") ->
                "The proxy statement: executive compensation, board nominees, and " +
                    "matters put to a shareholder vote."
            type.startsWith("SC 13") || type.startsWith("SC 14") ->
                "A beneficial-ownership filing — someone crossing a reporting " +
                    "threshold in the company's shares."
            else ->
                "Filed with the SEC. Open the document to see what it contains."
        }
    }
}
