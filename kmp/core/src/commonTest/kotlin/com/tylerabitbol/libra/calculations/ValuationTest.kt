package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.services.providers.CompanyMetricsDTO
import com.tylerabitbol.libra.services.providers.MetricPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Ported from LibraTests/ValuationTests.swift, suite "Historical valuation
 * context". The "Finnhub metrics decoding" suite drives a live provider and is
 * ported in Phase 6.
 */
class ValuationTest {

    private fun series(values: List<Double>): List<MetricPoint> =
        values.mapIndexed { index, value ->
            MetricPoint(
                period = Instant.fromEpochSeconds(1_500_000_000) + (index * 90).days,
                value = value
            )
        }

    @Test
    fun percentile_is_the_share_of_past_observations_at_or_below_the_current_value() {
        // Ten observations, current above eight of them.
        val history = series(listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0, 26.0, 40.0))
        val context = assertNotNull(ValuationCalculator.historicalContext(25.0, history))
        assertEquals(80, context.percentile)
        assertEquals(10, context.observationCount)
    }

    @Test
    fun a_value_at_the_bottom_and_top_of_the_range_read_as_10th_and_100th() {
        val history = series((1..10).map { it * 10.0 })
        val low = assertNotNull(ValuationCalculator.historicalContext(10.0, history))
        val high = assertNotNull(ValuationCalculator.historicalContext(100.0, history))
        assertEquals(10, low.percentile)
        assertEquals(100, high.percentile)
    }

    @Test
    fun too_few_observations_produce_nothing_rather_than_a_weak_percentile() {
        // Three points cannot support a percentile that invites confidence.
        assertNull(
            ValuationCalculator.historicalContext(20.0, series(listOf(10.0, 20.0, 30.0)))
        )
    }

    @Test
    fun non_finite_inputs_are_refused() {
        val history = series(listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0))
        assertNull(ValuationCalculator.historicalContext(Double.NaN, history))
        assertNull(ValuationCalculator.historicalContext(Double.POSITIVE_INFINITY, history))
    }

    @Test
    fun median_is_correct_for_both_even_and_odd_sample_sizes() {
        assertEquals(2.5, ValuationCalculator.median(listOf(1.0, 2.0, 3.0, 4.0)))
        assertEquals(2.0, ValuationCalculator.median(listOf(1.0, 2.0, 3.0)))
    }

    @Test
    fun range_and_observation_window_are_reported_alongside_the_percentile() {
        val history = series(listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0))
        val context = assertNotNull(ValuationCalculator.historicalContext(15.0, history))
        assertEquals(10.0, context.minimum)
        assertEquals(24.0, context.maximum)
        assertEquals(17.0, context.median)
        assertTrue(context.earliest < context.latest)
    }

    @Test
    fun the_multiple_is_a_calculation_its_position_in_history_is_an_interpretation() {
        val history = series(listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0, 26.0, 40.0))
        val context = assertNotNull(ValuationCalculator.historicalContext(25.0, history))
        val claims = ValuationCalculator.claims("P/E", context, source = null)

        assertEquals(2, claims.size)
        assertEquals(ClaimKind.Calculation, claims[0].kind)
        assertEquals(
            ClaimKind.Interpretation, claims[1].kind,
            "A judgement about whether a multiple is high must not be labelled a fact"
        )
        assertTrue(claims.all { it.isTraceable })
        assertTrue(claims[1].text.contains("80th"))
    }

    @Test
    fun descriptors_never_recommend_an_action() {
        val banned = listOf("buy", "sell", "cheap", "overvalued", "undervalued", "bargain")
        for (percentile in 0..100 step 5) {
            val context = HistoricalContext(
                current = 20.0, percentile = percentile, median = 18.0, minimum = 10.0,
                maximum = 40.0, observationCount = 20,
                earliest = Instant.DISTANT_PAST, latest = Clock.System.now(),
                meaningfulness = MetricMeaningfulness.Rankable, currentIsFromHistory = false
            )
            val descriptor = context.descriptor.lowercase()
            for (word in banned) {
                assertFalse(
                    descriptor.contains(word),
                    "Descriptor at ${percentile}th contains '$word'"
                )
            }
        }
    }

    @Test
    fun a_percent_current_value_is_never_ranked_against_a_ratio_history() {
        // Finnhub's two blocks disagree on scale for the same concept:
        // grossMarginTTM is 48.65 while the grossMargin series is 0.4622.
        // Comparing them unreconciled puts every margin at the 100th
        // percentile — authoritative-looking and meaningless.
        val history = (0 until 12).map { index ->
            MetricPoint(
                period = Instant.fromEpochSeconds(1_500_000_000) + (index * 90).days,
                value = 0.40 + index * 0.005 // ratios
            )
        }
        val metrics = CompanyMetricsDTO(
            current = mapOf("grossMarginTTM" to 48.65), // percent
            annual = emptyMap(),
            quarterly = mapOf("grossMargin" to history),
            asOf = Clock.System.now()
        )
        val metric = assertNotNull(ValuationMetric.all.firstOrNull { it.key == "grossMargin" })
        val pair = assertNotNull(metrics.normalized(metric))

        // Both halves land on percent.
        assertTrue(abs(pair.current - 48.65) < 0.001)
        assertTrue(pair.history.all { it.value > 1 })

        val context = assertNotNull(
            ValuationCalculator.historicalContext(pair.current, pair.history)
        )
        assertEquals(
            100, context.percentile,
            "48.65% genuinely exceeds a 40-45% history — but by 3 points, not by 100x"
        )
        assertTrue(context.maximum < 50, "History must be on the percent scale, not the ratio scale")
    }

    @Test
    fun a_multiple_needs_no_rescaling_in_either_direction() {
        val history = (0 until 10).map { index ->
            MetricPoint(
                period = Instant.fromEpochSeconds(1_500_000_000) + (index * 90).days,
                value = 30.0 + index
            )
        }
        val metrics = CompanyMetricsDTO(
            current = mapOf("peTTM" to 34.36), annual = emptyMap(),
            quarterly = mapOf("peTTM" to history), asOf = Clock.System.now()
        )
        val metric = assertNotNull(ValuationMetric.all.firstOrNull { it.key == "peTTM" })
        val pair = assertNotNull(metrics.normalized(metric))
        assertEquals(34.36, pair.current)
        assertEquals(30.0, pair.history.first().value)
    }

    @Test
    fun with_no_current_value_the_latest_history_point_is_used_at_the_same_scale() {
        val history = (0 until 10).map { index ->
            MetricPoint(
                period = Instant.fromEpochSeconds(1_500_000_000) + (index * 90).days,
                value = 0.30 + index * 0.01
            )
        }
        val metrics = CompanyMetricsDTO(
            current = emptyMap(), annual = emptyMap(),
            quarterly = mapOf("netMargin" to history), asOf = Clock.System.now()
        )
        val metric = assertNotNull(ValuationMetric.all.firstOrNull { it.key == "netMargin" })
        val pair = assertNotNull(metrics.normalized(metric))
        // Scaled to percent like the history, not left as a bare ratio.
        assertTrue(abs(pair.current - 39) < 0.001)
    }

    @Test
    fun margins_format_as_percentages_and_multiples_as_bare_numbers() {
        val margin = assertNotNull(ValuationMetric.all.firstOrNull { it.key == "grossMargin" })
        val multiple = assertNotNull(ValuationMetric.all.firstOrNull { it.key == "peTTM" })
        assertEquals("46.2%", margin.format(46.2))
        assertEquals("34.0", multiple.format(34.0))
        assertFalse(multiple.format(34.0).contains("%"))
    }

    @Test
    fun a_metric_with_no_history_produces_nothing_rather_than_a_bare_number() {
        val metrics = CompanyMetricsDTO(
            current = mapOf("peTTM" to 34.36), annual = emptyMap(),
            quarterly = emptyMap(), asOf = Clock.System.now()
        )
        val metric = assertNotNull(ValuationMetric.all.firstOrNull { it.key == "peTTM" })
        assertNull(
            metrics.normalized(metric),
            "A multiple without context is what this app exists to avoid"
        )
    }

    @Test
    fun metric_definitions_record_direction_without_implying_a_recommendation() {
        val pe = assertNotNull(ValuationMetric.all.firstOrNull { it.key == "peTTM" })
        val margin = assertNotNull(ValuationMetric.all.firstOrNull { it.key == "grossMargin" })
        assertTrue(pe.lowerIsCheaper)
        assertFalse(margin.lowerIsCheaper)
        assertEquals(ValuationMetric.all.size, ValuationMetric.all.map { it.key }.toSet().size)
        assertEquals(MetricUnit.Multiple, pe.unit)
        assertEquals(MetricUnit.Percent, margin.unit)
    }

    @Test
    fun a_negative_multiple_is_refused_rather_than_ranked_at_the_bottom() {
        // No Swift counterpart as a standalone test, but the behaviour is
        // load-bearing: ranked naively this reads "near the low end of its own
        // range", which a reader takes as cheap.
        val history = series(listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0))
        val context = assertNotNull(ValuationCalculator.historicalContext(-5.0, history))
        assertFalse(context.meaningfulness.isRankable)
        assertTrue(context.descriptor.contains("negative earnings"))
    }
}
