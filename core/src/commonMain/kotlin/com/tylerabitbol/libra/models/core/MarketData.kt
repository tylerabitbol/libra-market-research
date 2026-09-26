package com.tylerabitbol.libra.models.core

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus

enum class BarResolution(val raw: String) {
    OneMinute("1"),
    FiveMinute("5"),
    FifteenMinute("15"),
    Hourly("60"),
    Daily("D"),
    Weekly("W"),
    Monthly("M");

    /** Daily or coarser bars come from a different vendor than intraday ones. */
    val isDailyOrCoarser: Boolean get() = this == Daily || this == Weekly || this == Monthly

    companion object {
        fun fromRaw(raw: String?): BarResolution? = entries.firstOrNull { it.raw == raw }
    }
}

/** The chart ranges from Section 5. */
enum class ChartRange(val raw: String) {
    OneDay("1D"),
    FiveDay("5D"),
    OneMonth("1M"),
    ThreeMonth("3M"),
    SixMonth("6M"),
    OneYear("1Y"),
    FiveYear("5Y");

    val id: String get() = raw

    /** The bar resolution that gives a readable chart without over-fetching. */
    val resolution: BarResolution
        get() = when (this) {
            OneDay -> BarResolution.FiveMinute
            FiveDay -> BarResolution.FifteenMinute
            OneMonth, ThreeMonth, SixMonth, OneYear -> BarResolution.Daily
            FiveYear -> BarResolution.Weekly
        }

    /**
     * Whether this range needs a series finer than daily — and therefore a
     * different vendor, with a different share of the tape behind it.
     */
    val usesIntraday: Boolean get() = !resolution.isDailyOrCoarser

    val datePeriod: DatePeriod
        get() = when (this) {
            OneDay -> DatePeriod(days = 1)
            FiveDay -> DatePeriod(days = 5)
            OneMonth -> DatePeriod(months = 1)
            ThreeMonth -> DatePeriod(months = 3)
            SixMonth -> DatePeriod(months = 6)
            OneYear -> DatePeriod(years = 1)
            FiveYear -> DatePeriod(years = 5)
        }

    fun startDate(
        end: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault()
    ): Instant = end.minus(datePeriod, zone)

    /**
     * How many trading sessions an intraday range shows, if it is one.
     *
     * Sessions, not calendar days: five days back from a Monday afternoon
     * reaches the previous Wednesday, so "5D" drew three sessions and called
     * them five.
     */
    val intradaySessions: Int?
        get() = when (this) {
            OneDay -> 1
            FiveDay -> 5
            else -> null
        }

    /**
     * How far back to ask an intraday vendor for bars.
     *
     * Deliberately wider than the range itself, because the count that
     * matters is sessions and the calendar does not know which days traded.
     * The surplus is trimmed once the bars are in hand.
     */
    fun intradayFetchStart(
        end: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault()
    ): Instant {
        val sessions = intradaySessions ?: return startDate(end, zone)
        val days = if (sessions == 1) 5 else sessions * 2 + 4
        return end.minus(DatePeriod(days = days), zone)
    }

    companion object {
        fun fromRaw(raw: String?): ChartRange? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * Whether a chart can be drawn, and what to say when it cannot.
 *
 * Exists because "no bars" and "still loading" were previously the same
 * state on screen, which is how 1D and 5D came to show a spinner that never
 * resolved for a range that had nothing to load.
 */
sealed interface ChartAvailability {
    data object Ready : ChartAvailability
    data object Loading : ChartAvailability
    data class Unavailable(val reason: String) : ChartAvailability
}

/**
 * One point on an intraday chart, placed by position rather than by clock.
 *
 * A wall-clock axis gives 17 of every 24 hours to time that did not trade:
 * on 5D three sessions occupied about a fifth of the width and the rest was
 * blank. Placing bars end to end spends the whole axis on trading, and the
 * axis labels below say which session each stretch belongs to.
 */
data class ChartPoint(
    val id: Int,
    val date: Instant,
    val close: Double,
    /** Start of the trading day this point belongs to, in New York. */
    val session: Instant
) {
    val position: Double get() = id.toDouble()
}

/**
 * A run of the intraday line that is drawn as one stroke.
 *
 * Sessions and the moves between them are drawn differently but belong to
 * one line. Splitting the chart into disconnected per-session lines was a
 * defence against a wall-clock axis, where the weekend spanned a fifth of
 * the width; on a positional axis the space between a close and the next
 * open is a single bar, and a stroke that narrow reads as the jump it is.
 */
data class ChartSegment(
    /** Also the chart's series key, which is why it is a `String` for both kinds. */
    val id: String,
    val kind: Kind,
    val points: List<ChartPoint>
) {
    enum class Kind {
        /** Bars that traded, consecutively, within one session. */
        Traded,

        /**
         * The step from one session's last close to the next session's open.
         * Exactly two points, one position apart.
         */
        Overnight
    }
}

/** A labelled position on the intraday chart's x-axis. */
data class ChartAxisTick(val id: Int, val label: String) {
    val position: Double get() = id.toDouble()
}
