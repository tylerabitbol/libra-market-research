package com.tylerabitbol.libra.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import com.tylerabitbol.libra.models.core.ChartAxisTick
import com.tylerabitbol.libra.models.core.ChartPoint
import com.tylerabitbol.libra.models.core.ChartSegment
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.ui.components.PriceChart
import com.tylerabitbol.libra.ui.components.chartBounds
import com.tylerabitbol.libra.ui.components.spreadAcross
import com.tylerabitbol.libra.ui.components.string
import com.tylerabitbol.libra.ui.components.windowDescription
import com.tylerabitbol.libra.viewmodels.ChartValueFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What the chart says out loud.
 *
 * A line drawn on a canvas carries nothing for a screen reader, so `PLAN.md §8`
 * requires every chart to describe itself: what it is, and where the window
 * opened, closed and ended up. Swift did this with
 * `intradayChartLabel`/`intradayChartValue`; here it is a `contentDescription`
 * plus a `stateDescription`, and this suite is the only thing that checks the
 * two survive the Vico host.
 */
@OptIn(ExperimentalTestApi::class)
class PriceChartTest {

    private val start = Instant.parse("2026-09-14T13:30:00Z")

    private fun dailyBars(closes: List<Double>): List<PriceBar> = closes.mapIndexed { i, close ->
        PriceBar(
            date = start + i.days,
            open = close,
            high = close,
            low = close,
            close = close,
        )
    }

    private fun intradaySession(startId: Int, day: Int, closes: List<Double>): List<ChartPoint> {
        val session = start + day.days
        return closes.mapIndexed { i, close ->
            ChartPoint(
                id = startId + i,
                date = session + (i * 5).minutes,
                close = close,
                session = session,
            )
        }
    }

    private fun hasStateDescription(expected: String) = SemanticsMatcher.expectValue(
        SemanticsProperties.StateDescription,
        expected,
    )

    @Test
    fun theDailyChartNamesItselfAndStatesItsWindow() = runComposeUiTest {
        val bars = dailyBars(listOf(100.0, 104.0, 102.0, 110.0))
        setContent {
            LibraTheme {
                PriceChart(
                    bars = bars,
                    points = emptyList(),
                    segments = emptyList(),
                    ticks = emptyList(),
                    isIntraday = false,
                )
            }
        }
        onNodeWithContentDescription("Price chart")
            .assertIsDisplayed()
            .assert(hasStateDescription("\$100.00 to \$110.00, +10.00% across the window"))
    }

    @Test
    fun theIntradayChartCountsItsSessions() = runComposeUiTest {
        // Two traded runs with the overnight step between them: the shape the
        // segmented line exists for.
        val first = intradaySession(startId = 0, day = 0, closes = listOf(100.0, 101.0, 102.0))
        val second = intradaySession(startId = 3, day = 1, closes = listOf(103.0, 105.0))
        val segments = listOf(
            ChartSegment("s0", ChartSegment.Kind.Traded, first),
            ChartSegment("gap0", ChartSegment.Kind.Overnight, listOf(first.last(), second.first())),
            ChartSegment("s1", ChartSegment.Kind.Traded, second),
        )
        setContent {
            LibraTheme {
                PriceChart(
                    bars = emptyList(),
                    points = first + second,
                    segments = segments,
                    ticks = listOf(ChartAxisTick(0, "9:30"), ChartAxisTick(3, "Tue")),
                    isIntraday = true,
                )
            }
        }
        onNodeWithContentDescription("Intraday price chart, 2 trading sessions")
            .assertIsDisplayed()
            .assert(hasStateDescription("\$100.00 to \$105.00, +5.00% across the window"))
    }

    @Test
    fun oneSessionIsNotDescribedAsOneSessions() = runComposeUiTest {
        val only = intradaySession(startId = 0, day = 0, closes = listOf(100.0, 99.0, 98.0))
        setContent {
            LibraTheme {
                PriceChart(
                    bars = emptyList(),
                    points = only,
                    segments = listOf(ChartSegment("s0", ChartSegment.Kind.Traded, only)),
                    ticks = listOf(ChartAxisTick(0, "9:30")),
                    isIntraday = true,
                )
            }
        }
        onNodeWithContentDescription("Intraday price chart, one trading session").assertIsDisplayed()
    }

    @Test
    fun anIndexLevelIsSpokenAsPointsRatherThanDollars() {
        // The S&P 500 at 7691.76 is points. It said "$7,691.76" until
        // `ChartValueFormat` existed, which is the bug this line guards.
        assertEquals("7,691.76", ChartValueFormat.Points.string(7691.76))
        assertEquals("\$7,691.76", ChartValueFormat.Currency.string(7691.76))
    }

    @Test
    fun theWindowDescriptionReportsTheMoveBetweenItsEnds() {
        assertEquals(
            "\$50.00 to \$45.00, -10.00% across the window",
            windowDescription(listOf(50.0, 47.0, 45.0), ChartValueFormat.Currency),
        )
        assertEquals("No bars", windowDescription(emptyList(), ChartValueFormat.Currency))
    }

    @Test
    fun aFlatSeriesStillHasADomainToDrawIn() {
        // Equal floor and ceiling would collapse the y-axis, so a flat range
        // pads off the value itself rather than off a zero spread.
        val (floor, ceiling) = chartBounds(listOf(42.0, 42.0, 42.0))
        assertTrue(ceiling > floor, "a flat series must not collapse to a single y value")
        assertTrue(floor < 42.0 && ceiling > 42.0)

        val (low, high) = chartBounds(listOf(100.0, 110.0))
        assertTrue(low < 100.0 && high > 110.0)
        assertEquals(emptyList<Double>().let { chartBounds(it) }, 0.0 to 1.0)
    }

    @Test
    fun theDailyAxisLabelsFourDatesAndNoMoreThanItHas() {
        // Swift asked Charts for `desiredCount: 4`; a positional axis has to be
        // told which four, and the placer must be given real positions — an
        // empty axis label is a Vico error, not a blank tick.
        assertEquals(listOf(0.0, 40.0, 79.0, 119.0), spreadAcross(0..119, count = 4))
        assertEquals(listOf(0.0, 1.0, 2.0), spreadAcross(0..2, count = 4))
        assertEquals(emptyList(), spreadAcross(IntRange.EMPTY, count = 4))
    }
}
