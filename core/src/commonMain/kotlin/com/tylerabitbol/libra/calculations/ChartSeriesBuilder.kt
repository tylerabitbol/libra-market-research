package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.ChartAxisTick
import com.tylerabitbol.libra.models.core.ChartPoint
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.models.core.ChartSegment
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.support.Format
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * The arithmetic behind the intraday chart, kept apart from any view model.
 *
 * These were computed properties on `SecurityDetailViewModel` until the
 * dashboard's benchmarks needed the same chart. They are pure functions of a
 * bar series and a range — no state, no coroutine — so putting them here lets
 * a second screen draw an identical chart without inheriting a security's
 * loading machinery, and lets the session and axis rules be tested directly.
 *
 * Nothing downstream of this treats a position as a time. The positional
 * layout exists because a wall-clock axis gave five sixths of the width to
 * hours in which nothing traded.
 */
object ChartSeriesBuilder {

    /**
     * Sessions are bounded by the exchange's day, not the device's. Grouping
     * on local midnight would split one session in two for anyone east of
     * New York.
     */
    val marketZone: TimeZone = TimeZone.of("America/New_York")

    private fun startOfSession(instant: Instant): Instant =
        instant.toLocalDateTime(marketZone).date.atStartOfDayIn(marketZone)

    /**
     * The intraday bars a range actually draws: regular hours only, trimmed
     * to the last N sessions.
     *
     * Counted in sessions, not in hours. "1D" as a rolling 24 hours began
     * mid-afternoon the previous day and opened with a straight line across
     * the overnight gap; "5D" as five calendar days reached back to Wednesday
     * and drew three sessions.
     */
    fun regularHoursBars(bars: List<PriceBar>, range: ChartRange): List<PriceBar> {
        val series = bars.filter { isRegularHours(it) }.sortedBy { it.date }
        val wanted = range.intradaySessions ?: return series
        val days = series.map { startOfSession(it.date) }.toSet()
        val kept = days.sorted().takeLast(wanted).toSet()
        return series.filter { kept.contains(startOfSession(it.date)) }
    }

    /**
     * The daily bars a range draws, cut by the same rule as
     * [ReturnCalculator.trailingReturn]: back one period from the *latest
     * bar*, starting at the last bar at or before that date.
     *
     * The chart used to start at the first bar after one period before
     * *now*. On an end-of-day series that is a day behind, the two windows
     * began on different sessions, and the S&P page showed "+0.37%" over the
     * chart beside "+0.41%" under Trailing for the same month. Cut this way,
     * the chart's first and last closes are exactly the return's.
     *
     * When the history is shorter than the range, every bar is drawn, as
     * the return falls back to the earliest bar.
     */
    fun dailyWindow(
        bars: List<PriceBar>,
        range: ChartRange,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<PriceBar> {
        val sorted = bars.sortedBy { it.date }
        val last = sorted.lastOrNull() ?: return emptyList()
        val start = last.date.minus(range.datePeriod, zone)
        val anchor = sorted.lastOrNull { it.date <= start }
        return sorted.filter { it.date > start || it === anchor }
    }

    /** The series laid out end to end, one position per bar. */
    fun points(bars: List<PriceBar>): List<ChartPoint> = bars.mapIndexed { index, bar ->
        ChartPoint(
            id = index, date = bar.date, close = bar.analysisClose,
            session = startOfSession(bar.date)
        )
    }

    /**
     * The line broken into the runs the chart strokes differently: one per
     * session, and one across each break between them.
     *
     * The overnight runs hold exactly two points, one position apart, so the
     * move between a close and the next open is drawn at the width of a
     * single bar. That is the whole argument for drawing it at all — at that
     * width it cannot be mistaken for a gradual drift, and leaving it out
     * turned one instrument into five floating fragments.
     */
    fun segments(points: List<ChartPoint>): List<ChartSegment> {
        if (points.isEmpty()) return emptyList()

        val traded = mutableListOf<ChartSegment>()
        var run = mutableListOf<ChartPoint>()
        for (point in points) {
            val first = run.firstOrNull()
            if (first != null && first.session != point.session) {
                traded.add(
                    ChartSegment(
                        id = "session-${first.session.epochSeconds}",
                        kind = ChartSegment.Kind.Traded, points = run
                    )
                )
                run = mutableListOf()
            }
            run.add(point)
        }
        run.firstOrNull()?.let { first ->
            traded.add(
                ChartSegment(
                    id = "session-${first.session.epochSeconds}",
                    kind = ChartSegment.Kind.Traded, points = run
                )
            )
        }

        val overnight = traded.zipWithNext().mapNotNull { (earlier, later) ->
            val close = earlier.points.lastOrNull() ?: return@mapNotNull null
            val open = later.points.firstOrNull() ?: return@mapNotNull null
            ChartSegment(
                id = "overnight-${close.id}", kind = ChartSegment.Kind.Overnight,
                points = listOf(close, open)
            )
        }
        return traded + overnight
    }

    /**
     * Where to label the axis: each hour on 1D, each session otherwise.
     *
     * Positions are not evenly spaced in time — a session with thin trading
     * holds fewer bars — so the labels are placed on the bars themselves
     * rather than computed by stride.
     */
    fun axisTicks(points: List<ChartPoint>, range: ChartRange): List<ChartAxisTick> {
        if (points.isEmpty()) return emptyList()

        if (range == ChartRange.OneDay) {
            val ticks = mutableListOf<ChartAxisTick>()
            var seen: Int? = null
            for (point in points) {
                val parts = point.date.toLocalDateTime(marketZone)
                if (parts.hour == seen) continue
                seen = parts.hour
                // The session opens at 9:30, and labelling that bar "9 AM"
                // both misstated it by half an hour and crowded the 10 AM
                // label beside it. A tick has to sit near the hour it names.
                if (parts.minute > 10) continue
                ticks.add(ChartAxisTick(id = point.id, label = hourLabel(point.date)))
            }
            return ticks
        }

        val ticks = mutableListOf<ChartAxisTick>()
        var seen: Instant? = null
        for (point in points) {
            if (point.session == seen) continue
            seen = point.session
            ticks.add(ChartAxisTick(id = point.id, label = sessionLabel(point.date)))
        }
        return ticks
    }

    /**
     * Axis labels are in market time, matching the session the bars belong
     * to. A reader in another time zone would otherwise see a session that
     * appears to open at 6:30.
     */
    fun hourLabel(date: Instant): String {
        val hour = date.toLocalDateTime(marketZone).hour
        val suffix = if (hour < 12) "AM" else "PM"
        val displayed = when {
            hour % 12 == 0 -> 12
            else -> hour % 12
        }
        return "$displayed $suffix"
    }

    fun sessionLabel(date: Instant): String = Format.dayAndMonth(date, marketZone)

    /**
     * Whether a bar falls inside the regular session, 9:30 to 16:00 New York.
     *
     * Alpaca returns extended-hours bars, and pre-market IEX trading is thin
     * enough that a single 7 a.m. print bridged to the open as one long
     * diagonal — the widest move on the chart, and an artefact of two sparse
     * bars rather than a move anyone could have traded.
     */
    fun isRegularHours(bar: PriceBar): Boolean {
        val parts = bar.date.toLocalDateTime(marketZone)
        val minutes = parts.hour * 60 + parts.minute
        return minutes >= 9 * 60 + 30 && minutes < 16 * 60
    }
}
