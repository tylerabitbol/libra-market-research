package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.ClaimKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod

private val END = Instant.fromEpochSeconds(1_756_000_000)

/** Daily bars ending at [end], with an explicit closing price per day. */
private fun bars(closes: List<Double>, end: Instant = END): List<PriceBar> =
    closes.mapIndexed { offset, close ->
        PriceBar(
            date = end - ((closes.size - 1 - offset).days),
            resolution = BarResolution.Daily,
            open = close, high = close, low = close, close = close,
            adjustedClose = close
        )
    }

/** Ported from LibraTests/ReturnCalculatorTests.swift, suite "Return calculations". */
class ReturnCalculatorTest {

    @Test
    fun a_simple_return_is_the_percentage_change_between_two_prices() {
        assertEquals(12.0, ReturnCalculator.simpleReturn(100.0, 112.0))
        assertEquals(-12.0, ReturnCalculator.simpleReturn(100.0, 88.0))
    }

    @Test
    fun a_zero_base_returns_null_rather_than_infinity() {
        assertNull(ReturnCalculator.simpleReturn(0.0, 50.0))
    }

    @Test
    fun non_finite_inputs_return_null() {
        assertNull(ReturnCalculator.simpleReturn(100.0, Double.POSITIVE_INFINITY))
        assertNull(ReturnCalculator.simpleReturn(Double.NaN, 100.0))
    }

    @Test
    fun a_trailing_return_measures_from_the_bar_at_the_window_start() {
        // 8 daily bars; 7 days back from the last is the first.
        val series = bars(listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 106.0, 110.0))
        val result = assertNotNull(
            ReturnCalculator.trailingReturn(series, DatePeriod(days = -7))
        )
        assertEquals(100.0, result.startPrice)
        assertEquals(110.0, result.endPrice)
        assertTrue(abs(result.percent - 10) < 0.0001)
        assertTrue(result.isFullWindow)
    }

    @Test
    fun a_window_longer_than_the_available_history_is_flagged_as_partial() {
        // Only 5 days of history, but a 1-month window was requested.
        val series = bars(listOf(100.0, 102.0, 104.0, 106.0, 108.0))
        val result = assertNotNull(
            ReturnCalculator.trailingReturn(series, DatePeriod(months = -1))
        )
        assertFalse(
            result.isFullWindow,
            "A short read must be labelled, not presented as a full month"
        )
        assertEquals(100.0, result.startPrice)
    }

    @Test
    fun a_weekend_gap_still_counts_as_a_full_window() {
        val series = bars((0 until 10).map { 100.0 + it })
        val result = assertNotNull(
            ReturnCalculator.trailingReturn(series, DatePeriod(days = -7))
        )
        assertTrue(result.isFullWindow)
    }

    @Test
    fun insufficient_bars_return_null_rather_than_a_fabricated_zero() {
        assertNull(ReturnCalculator.trailingReturn(bars(listOf(100.0)), DatePeriod(days = -7)))
    }

    @Test
    fun an_empty_series_returns_null() {
        assertNull(ReturnCalculator.trailingReturn(emptyList(), DatePeriod(days = -7)))
    }

    @Test
    fun bars_in_arbitrary_order_are_sorted_before_measuring() {
        val series =
            bars(listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 106.0, 110.0)).shuffled()
        val result = assertNotNull(
            ReturnCalculator.trailingReturn(series, DatePeriod(days = -7))
        )
        assertEquals(100.0, result.startPrice)
        assertEquals(110.0, result.endPrice)
    }

    @Test
    fun adjusted_close_is_preferred_over_raw_close() {
        val raw = listOf(
            PriceBar(
                date = END - 7.days, resolution = BarResolution.Daily,
                open = 200.0, high = 200.0, low = 200.0, close = 200.0, adjustedClose = 100.0
            ),
            PriceBar(
                date = END, resolution = BarResolution.Daily,
                open = 110.0, high = 110.0, low = 110.0, close = 110.0, adjustedClose = 110.0
            )
        )
        val result = assertNotNull(
            ReturnCalculator.trailingReturn(raw, DatePeriod(days = -7))
        )
        assertEquals(100.0, result.startPrice, "Split-adjusted series must drive the calculation")
        assertTrue(abs(result.percent - 10) < 0.0001)
    }
}

/** Ported from LibraTests/ReturnCalculatorTests.swift, suite "Relative performance". */
class RelativePerformanceTest {

    private fun period(percent: Double, startOffsetDays: Long) = PeriodReturn(
        percent = percent,
        startDate = END - startOffsetDays.days,
        endDate = END,
        startPrice = 100.0,
        endPrice = 100 * (1 + percent / 100),
        isFullWindow = true
    )

    @Test
    fun outperformance_is_stated_in_percentage_points() {
        val result = assertNotNull(
            ReturnCalculator.relativePerformance(period(12.0, 30), period(5.0, 30))
        )
        assertTrue(abs(result.differencePoints - 7) < 0.0001)
    }

    @Test
    fun mismatched_windows_refuse_to_produce_a_comparison() {
        assertNull(
            ReturnCalculator.relativePerformance(period(12.0, 30), period(5.0, 90)),
            "Comparing a 1-month return against a 3-month return is meaningless"
        )
    }

    @Test
    fun a_missing_leg_produces_no_comparison() {
        assertNull(ReturnCalculator.relativePerformance(period(12.0, 30), null))
        assertNull(ReturnCalculator.relativePerformance(null, period(5.0, 30)))
    }

    @Test
    fun the_generated_claim_is_a_calculation_never_an_interpretation() {
        val result = assertNotNull(
            ReturnCalculator.relativePerformance(period(12.0, 30), period(5.0, 30))
        )
        val claim = result.claim("NVDA", "Information Technology")
        assertEquals(ClaimKind.Calculation, claim.kind)
        assertTrue(claim.isTraceable)
        assertTrue(claim.text.contains("outperformed"))
        assertTrue(claim.text.contains("pp"), "Percentage points must be distinguished from percent")
    }

    @Test
    fun underperformance_is_described_without_euphemism() {
        val result = assertNotNull(
            ReturnCalculator.relativePerformance(period(2.0, 30), period(9.0, 30))
        )
        assertTrue(result.claim("X", "Y").text.contains("underperformed"))
    }

    @Test
    fun the_verb_carries_the_direction_so_the_sentences_figure_is_unsigned() {
        val result = assertNotNull(
            ReturnCalculator.relativePerformance(period(2.0, 30), period(9.0, 30))
        )
        val claim = result.claim("AAPL", "Information Technology")

        assertTrue(claim.text.contains("underperformed"))
        assertTrue(claim.text.contains("7.0 pp"))
        assertFalse(
            claim.text.contains("+"),
            "\"underperformed by +7.0 pp\" states the direction twice and disagrees with itself"
        )

        // The derivation is the audit trail and stays literal.
        assertEquals("-7.0 pp", claim.derivation?.result)
    }

    @Test
    fun outperformance_reads_the_same_way_without_a_redundant_sign() {
        val result = assertNotNull(
            ReturnCalculator.relativePerformance(period(12.0, 30), period(5.0, 30))
        )
        val claim = result.claim("NVDA", "Information Technology")
        assertTrue(claim.text.contains("outperformed Information Technology by 7.0 pp"))
        assertFalse(claim.text.contains("+7.0 pp"))
        assertEquals("+7.0 pp", claim.derivation?.result)
    }
}

/** Ported from LibraTests/ReturnCalculatorTests.swift, suite "Price context". */
class PriceContextTest {

    private fun contextBars(closes: List<Double>, adjusted: List<Double>? = null) =
        closes.mapIndexed { index, close ->
            PriceBar(
                date = Instant.fromEpochSeconds(1_700_000_000) + index.days,
                resolution = BarResolution.Daily,
                open = close, high = close, low = close, close = close,
                adjustedClose = adjusted?.get(index) ?: close
            )
        }

    @Test
    fun an_average_shorter_than_its_window_is_not_reported() {
        val context = assertNotNull(
            ReturnCalculator.priceContext(contextBars((0 until 60).map { 100.0 + it }))
        )
        assertNotNull(context.fiftyDayAverage)
        // A "200-session average" over 60 sessions is a different statistic
        // wearing the same name.
        assertNull(context.twoHundredDayAverage)
    }

    @Test
    fun distance_from_an_average_is_stated_against_the_average_not_the_peak() {
        // Fifty closes at 100, then one at 110. The 50-session average covers
        // the last fifty: 49 hundreds and one 110.
        val context = assertNotNull(
            ReturnCalculator.priceContext(contextBars(List(50) { 100.0 } + listOf(110.0)))
        )
        val average = assertNotNull(context.fiftyDayAverage)
        assertTrue(abs(average - 100.2) < 0.001)
        assertTrue(context.distance(average)!! > 9)
    }

    @Test
    fun drawdown_from_the_peak_is_zero_at_a_new_high_and_never_positive() {
        val context =
            assertNotNull(ReturnCalculator.priceContext(contextBars(listOf(100.0, 110.0, 120.0))))
        assertEquals(0.0, context.drawdownFromPeak)
        assertEquals(120.0, context.peak)
        assertTrue(context.drawdownClaim.text.contains("highest close"))
    }

    @Test
    fun the_deepest_fall_within_the_period_is_found_even_after_a_recovery() {
        // 100 → 50 → 120. The current drawdown is zero; the deepest was -50%.
        val context =
            assertNotNull(ReturnCalculator.priceContext(contextBars(listOf(100.0, 50.0, 120.0))))
        assertEquals(0.0, context.drawdownFromPeak)
        assertTrue(abs(context.deepestDrawdown - -50) < 0.001)
    }

    @Test
    fun context_is_computed_from_the_adjusted_close_so_a_split_is_not_a_crash() {
        // A 2-for-1 split halves the raw close and leaves the adjusted series
        // continuous. Reading the raw series would report a 50% collapse that
        // never happened.
        val context = assertNotNull(
            ReturnCalculator.priceContext(
                contextBars(listOf(200.0, 200.0, 100.0), adjusted = listOf(100.0, 100.0, 100.0))
            )
        )
        assertEquals(0.0, context.deepestDrawdown)
        assertEquals(100.0, context.last)
    }

    @Test
    fun a_single_bar_has_no_context_to_give() {
        assertNull(ReturnCalculator.priceContext(contextBars(listOf(100.0))))
    }
}
