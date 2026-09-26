package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.models.core.ChartSegment
import com.tylerabitbol.libra.models.core.PriceBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant

/**
 * The intraday chart's session and axis rules.
 *
 * No Swift counterpart: `ChartSeries.swift` was covered only through the view
 * models. The rules are worth pinning directly, and one of them — grouping on
 * the exchange's day rather than the device's — depends on a named time zone
 * resolving on every target, which this exercises on JVM and on iOS.
 */
class ChartSeriesBuilderTest {

    private val zone = ChartSeriesBuilder.marketZone

    /** A bar at a wall-clock time in New York. */
    private fun bar(
        day: Int,
        hour: Int,
        minute: Int,
        close: Double = 100.0
    ): PriceBar {
        val at: Instant = LocalDateTime(2026, 6, day, hour, minute).toInstant(zone)
        return PriceBar(
            date = at, resolution = BarResolution.FiveMinute,
            open = close, high = close, low = close, close = close
        )
    }

    @Test
    fun extendedHoursBarsAreExcluded() {
        // A single thin 7 a.m. print bridged to the open as one long diagonal
        // — the widest move on the chart, and an artefact rather than a move.
        assertFalse(ChartSeriesBuilder.isRegularHours(bar(1, 7, 0)))
        assertFalse(ChartSeriesBuilder.isRegularHours(bar(1, 9, 29)))
        assertTrue(ChartSeriesBuilder.isRegularHours(bar(1, 9, 30)))
        assertTrue(ChartSeriesBuilder.isRegularHours(bar(1, 15, 59)))
        // 16:00 is the close; the bar stamped at it belongs to after-hours.
        assertFalse(ChartSeriesBuilder.isRegularHours(bar(1, 16, 0)))
    }

    @Test
    fun rangesAreTrimmedInSessionsNotHours() {
        // Three sessions of two bars each, plus one pre-market bar that must
        // not survive the filter.
        val bars = listOf(
            bar(1, 7, 0),
            bar(1, 10, 0), bar(1, 15, 0),
            bar(2, 10, 0), bar(2, 15, 0),
            bar(3, 10, 0), bar(3, 15, 0)
        )

        val oneDay = ChartSeriesBuilder.regularHoursBars(bars, ChartRange.OneDay)
        assertEquals(2, oneDay.size, "1D is the latest session, not a rolling 24 hours")
        assertTrue(oneDay.all { it.date.toString().startsWith("2026-06-03") })

        val fiveDay = ChartSeriesBuilder.regularHoursBars(bars, ChartRange.FiveDay)
        assertEquals(6, fiveDay.size, "Only three sessions exist; 5D takes all of them")

        val daily = ChartSeriesBuilder.regularHoursBars(bars, ChartRange.OneMonth)
        assertEquals(6, daily.size, "A range with no session count is not trimmed")
    }

    @Test
    fun eachSessionIsOneRunWithAnOvernightStepBetween() {
        val bars = listOf(
            bar(1, 10, 0), bar(1, 15, 0),
            bar(2, 10, 0), bar(2, 15, 0)
        )
        val segments = ChartSeriesBuilder.segments(ChartSeriesBuilder.points(bars))

        val traded = segments.filter { it.kind == ChartSegment.Kind.Traded }
        val overnight = segments.filter { it.kind == ChartSegment.Kind.Overnight }
        assertEquals(2, traded.size)
        assertEquals(1, overnight.size)
        // Exactly two points, one position apart: at that width the step
        // cannot be mistaken for a gradual drift.
        assertEquals(2, overnight.first().points.size)
        assertEquals(1, overnight.first().points[1].id - overnight.first().points[0].id)
    }

    @Test
    fun anEmptySeriesDrawsNothing() {
        assertTrue(ChartSeriesBuilder.segments(emptyList()).isEmpty())
        assertTrue(ChartSeriesBuilder.axisTicks(emptyList(), ChartRange.OneDay).isEmpty())
    }

    @Test
    fun intradayTicksSitNearTheHourTheyName() {
        val bars = listOf(
            bar(1, 9, 30), bar(1, 9, 45),
            bar(1, 10, 5), bar(1, 10, 30),
            bar(1, 11, 0)
        )
        val ticks = ChartSeriesBuilder.axisTicks(
            ChartSeriesBuilder.points(bars), ChartRange.OneDay
        )

        // 9:30 is skipped: labelling it "9 AM" misstates it by half an hour
        // and crowds the 10 AM label beside it.
        assertEquals(listOf("10 AM", "11 AM"), ticks.map { it.label })
    }

    @Test
    fun multiSessionTicksAreOnePerSession() {
        val bars = listOf(
            bar(1, 10, 0), bar(1, 15, 0),
            bar(2, 10, 0), bar(2, 15, 0)
        )
        val ticks = ChartSeriesBuilder.axisTicks(
            ChartSeriesBuilder.points(bars), ChartRange.FiveDay
        )
        assertEquals(listOf("Jun 1", "Jun 2"), ticks.map { it.label })
    }

    @Test
    fun labelsAreInMarketTimeNotTheDevicesTime() {
        // A reader in another zone would otherwise see a session opening at
        // 6:30. The bar below is 10:00 New York whatever the host is set to.
        assertEquals("10 AM", ChartSeriesBuilder.hourLabel(bar(1, 10, 0).date))
        assertEquals("12 PM", ChartSeriesBuilder.hourLabel(bar(1, 12, 0).date))
        assertEquals("Jun 1", ChartSeriesBuilder.sessionLabel(bar(1, 10, 0).date))
    }
}
