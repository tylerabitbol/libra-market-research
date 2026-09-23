package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.PriceBar
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone

/**
 * Answers worked by hand, not ported from Swift.
 *
 * The ported suites prove the port agrees with the Swift app. These prove the
 * arithmetic is right, and pin the places where the port now deliberately
 * disagrees with Swift. Each expected value is derived in the comment beside it.
 */
class WorkedExampleTest {

    private val utc = TimeZone.UTC

    /** Midnight UTC on day [n] after an arbitrary Monday. */
    private fun day(n: Int): Instant = Instant.parse("2026-06-01T00:00:00Z") + n.days

    private fun bar(at: Instant, close: Double) = PriceBar(
        date = at, resolution = BarResolution.Daily,
        open = close, high = close, low = close, close = close, adjustedClose = close
    )

    private fun near(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected, got $actual")

    // MARK: - Trailing return

    @Test
    fun a_start_bar_left_weeks_early_by_a_hole_is_not_a_full_window() {
        // A 7-day window ending day 30 asks to start on day 23. The only bar at
        // or before day 23 is on day 0, 23 days early: that measures a 30-day
        // move, so it is not a full 7-day window. Swift called it full.
        val bars = listOf(bar(day(0), 100.0), bar(day(30), 110.0))
        val result = assertNotNull(
            ReturnCalculator.trailingReturn(bars, DatePeriod(days = -7), zone = utc)
        )
        near(10.0, result.percent) // (110 - 100) / 100 × 100
        assertFalse(result.isFullWindow)
    }

    @Test
    fun a_start_bar_a_long_weekend_early_is_still_a_full_window() {
        // Requested start day 23; the prior bar is on day 19, 4 days early.
        val bars = listOf(bar(day(19), 100.0), bar(day(30), 105.0))
        val result = assertNotNull(
            ReturnCalculator.trailingReturn(bars, DatePeriod(days = -7), zone = utc)
        )
        assertTrue(result.isFullWindow)
    }

    // MARK: - Relative performance

    private fun period(percent: Double, start: Instant, end: Instant) = PeriodReturn(
        percent = percent, startDate = start, endDate = end,
        startPrice = 100.0, endPrice = 100 + percent, isFullWindow = true
    )

    @Test
    fun a_benchmark_that_stopped_a_week_ago_is_not_compared() {
        // Same start, but the benchmark's last close is 7 days old.
        val security = period(12.0, day(0), day(30))
        val stale = period(5.0, day(0), day(23))
        assertNull(ReturnCalculator.relativePerformance(security, stale))
    }

    @Test
    fun aligned_relative_performance_ends_both_legs_on_the_same_session() {
        // Security: 100 → 110 → 121 on days 0, 1, 2.
        // Market (a session late, as FRED is): 100 → 105 on days 0, 1.
        // Measured to each series' own end, the security's 1-day return is
        // day 1 → day 2 = +10%, the market's day 0 → day 1 = +5%: two
        // different sessions, reported as +5 pp.
        // Aligned to day 1, both are day 0 → day 1: +10% and +5%.
        val security = listOf(bar(day(0), 100.0), bar(day(1), 110.0), bar(day(2), 121.0))
        val market = listOf(bar(day(0), 100.0), bar(day(1), 105.0))

        val aligned = assertNotNull(
            ReturnCalculator.alignedRelativePerformance(
                security, market, DatePeriod(days = -1), zone = utc
            )
        )
        near(10.0, aligned.securityReturn) // (110 - 100) / 100
        near(5.0, aligned.benchmarkReturn) // (105 - 100) / 100
        near(5.0, aligned.differencePoints)
        assertEquals(day(0), aligned.startDate)
        assertEquals(day(1), aligned.endDate)
    }

    @Test
    fun alignment_is_by_day_not_by_the_instant_a_vendor_stamps() {
        // The market's bar for day 1 is stamped 04:00Z, the security's at
        // midnight. Cutting at the market's instant would still include the
        // security's day-1 bar; cutting at the security's midnight must not
        // drop the market's day-1 bar either. Either way both legs are day 1.
        val security = listOf(bar(day(0), 100.0), bar(day(1), 120.0), bar(day(2), 90.0))
        val market = listOf(bar(day(0) + 4.hours, 100.0), bar(day(1) + 4.hours, 102.0))

        val aligned = assertNotNull(
            ReturnCalculator.alignedRelativePerformance(
                security, market, DatePeriod(days = -1), zone = utc
            )
        )
        near(20.0, aligned.securityReturn) // 100 → 120
        near(2.0, aligned.benchmarkReturn) // 100 → 102
        near(18.0, aligned.differencePoints)
    }

    @Test
    fun a_price_context_worked_by_hand() {
        // Closes 100, 120, 90, 108. Peak 120. Last 108.
        // Drawdown from peak: (108 - 120) / 120 = -10%.
        // Deepest: at 90 against the running peak 120: (90 - 120) / 120 = -25%.
        val bars = listOf(100.0, 120.0, 90.0, 108.0).mapIndexed { i, c -> bar(day(i), c) }
        val context = assertNotNull(ReturnCalculator.priceContext(bars))
        near(-10.0, context.drawdownFromPeak)
        near(-25.0, context.deepestDrawdown)
        assertEquals(120.0, context.peak)
    }

    // MARK: - Statistics

    @Test
    fun median_and_robust_scale_worked_by_hand() {
        // 1, 2, 3, 4, 100: median 3.
        // Absolute deviations 2, 1, 0, 1, 97 → sorted 0, 1, 1, 2, 97: MAD 1.
        // Robust scale = 1.4826 × 1.
        val values = listOf(1.0, 2.0, 3.0, 4.0, 100.0)
        assertEquals(3.0, Statistics.median(values))
        near(1.4826, assertNotNull(Statistics.robustScale(values)))
    }

    @Test
    fun sample_standard_deviation_uses_n_minus_one() {
        // 2, 4, 4, 4, 5, 5, 7, 9: mean 5, squared deviations sum to 32.
        // Sample variance 32 / 7; population would be 32 / 8 = 4.
        val sd = assertNotNull(
            Statistics.standardDeviation(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))
        )
        near(kotlin.math.sqrt(32.0 / 7.0), sd)
    }

    // MARK: - Fundamentals

    @Test
    fun year_over_year_pairs_a_quarter_with_the_one_a_year_before() {
        // Quarter ends 2025-06-30 and 2026-06-30 are 365 days apart.
        // 120 against 100 is +20%.
        val series = listOf(
            FundamentalDetector.SeriesPoint(Instant.parse("2025-06-30T00:00:00Z"), 100.0),
            FundamentalDetector.SeriesPoint(Instant.parse("2025-09-30T00:00:00Z"), 105.0),
            FundamentalDetector.SeriesPoint(Instant.parse("2026-06-30T00:00:00Z"), 120.0),
        )
        val pairs = FundamentalDetector.yearOverYear(series)
        val latest = pairs.single()
        assertEquals(120.0, latest.current)
        assertEquals(100.0, latest.prior)
    }

    // MARK: - Valuation

    @Test
    fun a_ttm_margin_is_ranked_against_full_years_not_single_quarters() {
        // Eight years of annual gross margin, 40%…47% (as ratios), and eight
        // quarters that include one freak 93.7% quarter.
        // TTM now: 46.5%. Against the years, 7 of 8 are at or below it:
        // 7 / 8 = 87.5% → 88th. Against the quarters it would be judged
        // beside the 93.7% outlier instead.
        val years = (0 until 8).map {
            com.tylerabitbol.libra.services.providers.MetricPoint(
                Instant.parse("2018-12-31T00:00:00Z") + (365 * it).days, 0.40 + it * 0.01
            )
        }
        val quarters = (0 until 8).map {
            com.tylerabitbol.libra.services.providers.MetricPoint(
                Instant.parse("2024-09-30T00:00:00Z") + (91 * it).days,
                if (it == 7) 0.937 else 0.45
            )
        }
        val metrics = com.tylerabitbol.libra.services.providers.CompanyMetricsDTO(
            current = mapOf("grossMarginTTM" to 46.5),
            annual = mapOf("grossMargin" to years),
            quarterly = mapOf("grossMargin" to quarters),
            asOf = Instant.parse("2026-09-01T00:00:00Z"),
        )
        val metric = ValuationMetric.all.first { it.key == "grossMargin" }
        val normalized = assertNotNull(metrics.normalized(metric))
        assertEquals(years.map { it.value * 100 }, normalized.history.map { it.value })

        val context = assertNotNull(
            ValuationCalculator.historicalContext(
                normalized.current, normalized.history, lowerIsCheaper = false
            )
        )
        assertEquals(88, context.percentile)
    }

    @Test
    fun a_ttm_multiple_keeps_its_denser_quarterly_history() {
        val quarterly = (0 until 8).map {
            com.tylerabitbol.libra.services.providers.MetricPoint(
                Instant.parse("2024-09-30T00:00:00Z") + (91 * it).days, 30.0 + it
            )
        }
        val metrics = com.tylerabitbol.libra.services.providers.CompanyMetricsDTO(
            current = mapOf("peTTM" to 33.0),
            quarterly = mapOf("peTTM" to quarterly),
            annual = mapOf("peTTM" to quarterly.take(2)),
            asOf = Instant.parse("2026-09-01T00:00:00Z"),
        )
        val metric = ValuationMetric.all.first { it.key == "peTTM" }
        assertEquals(8, assertNotNull(metrics.normalized(metric)).history.size)
    }
}
