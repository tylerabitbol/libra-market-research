package com.tylerabitbol.libra.calculations

/**
 * What a drawn price line did, in the numbers a reader looks for first.
 *
 * Computed from exactly the closes the chart draws, so the summary above a
 * chart and the line inside it cannot disagree: the change is last against
 * first, the high and low are the highest and lowest *closes* on the line,
 * not intraday extremes the line never reaches.
 */
data class ChartSummary(
    val first: Double,
    val last: Double,
    val high: Double,
    val low: Double,
) {
    /** The move as a percentage of the first close; null off a non-positive base. */
    val percent: Double? get() = percentFrom(last)

    /** 1 up, -1 down, 0 flat: what colours the line. */
    val direction: Int get() = direction(last)

    /** The change from the first close to [value], as a percentage. */
    fun percentFrom(value: Double): Double? =
        if (first > 0 && value.isFinite()) (value - first) / first * 100 else null

    /** The direction from the first close to [value]. */
    fun direction(value: Double): Int = when {
        value > first -> 1
        value < first -> -1
        else -> 0
    }

    companion object {
        /** Null for fewer than two finite closes: one point is not a move. */
        fun of(closes: List<Double>): ChartSummary? {
            val usable = closes.filter { it.isFinite() }
            if (usable.size < 2) return null
            return ChartSummary(
                first = usable.first(),
                last = usable.last(),
                high = usable.max(),
                low = usable.min(),
            )
        }
    }
}
