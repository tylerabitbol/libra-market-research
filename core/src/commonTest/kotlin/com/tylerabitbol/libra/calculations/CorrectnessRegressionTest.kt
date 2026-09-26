package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.services.providers.CompanyMetricsDTO
import com.tylerabitbol.libra.services.providers.MetricPoint
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
 * Regressions for defects that were visible on screen rather than in a test.
 *
 * Three of the Swift suites in `CorrectnessRegressionTests.swift` are not here
 * yet: `CIKTests` needs `SECProvider` (Phase 6), and `SortOptionTests` and
 * `AnnualPeriodKeyingTests` need the watchlist and security-detail view models
 * (Phase 7). See KNOWN_ISSUES.md.
 */
class CorrectnessRegressionTest {

    private fun points(values: List<Double>): List<MetricPoint> =
        values.mapIndexed { index, value ->
            MetricPoint(
                period = Instant.fromEpochSeconds(1_500_000_000) + (index * 90).days,
                value = value
            )
        }

    // MARK: - Metric key mapping

    @Test
    fun evEbitdaMapsToItself() {
        val metric = assertNotNull(ValuationMetric.all.firstOrNull { it.key == "evEbitdaTTM" })
        assertEquals(
            "evEbitdaTTM", metric.currentKey,
            "An earlier revision pointed this at currentEv/freeCashFlowTTM, " +
                "a different metric shown under this label"
        )
    }

    @Test
    fun noCrossedWires() {
        // Every current key must either match its history key or be a declared
        // alias of the same concept. A mismatch of the EV/EBITDA kind must not
        // be able to slip in unnoticed again.
        val knownAliases = mapOf(
            "grossMargin" to "grossMarginTTM",
            "operatingMargin" to "operatingMarginTTM",
            "netMargin" to "netProfitMarginTTM",
            "roe" to "roeTTM"
        )
        for (metric in ValuationMetric.all) {
            val currentKey = metric.currentKey ?: continue
            val valid = currentKey == metric.key || knownAliases[metric.key] == currentKey
            assertTrue(valid, "${metric.key} reads its current value from $currentKey")
        }
    }

    @Test
    fun pfcfIsHistorySourced() {
        // Finnhub's metric block carries no P/FCF; the value shown comes from
        // the quarterly series and must be date-stamped rather than implied
        // current.
        val metric = assertNotNull(ValuationMetric.all.firstOrNull { it.key == "pfcfTTM" })
        assertNull(metric.currentKey)

        val metrics = CompanyMetricsDTO(
            quarterly = mapOf(
                "pfcfTTM" to points(
                    listOf(20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0, 27.0, 28.0, 29.0)
                )
            ),
            asOf = Clock.System.now()
        )
        val normalized = assertNotNull(metrics.normalized(metric))
        assertTrue(normalized.currentIsFromHistory)
        assertTrue(
            normalized.asOf < Clock.System.now() - 1.days,
            "The as-of date must be the observation's, not now"
        )
    }

    // MARK: - Unrankable multiples

    @Test
    fun negativeMultipleIsFlagged() {
        val history = points(listOf(20.0, 22.0, 24.0, 26.0, 28.0, 30.0, 32.0, 34.0, 36.0, 38.0))
        val context = assertNotNull(
            ValuationCalculator.historicalContext(
                current = -15.0, history = history, lowerIsCheaper = true
            )
        )

        assertFalse(context.meaningfulness.isRankable)
        val descriptor = context.descriptor.lowercase()
        assertTrue(descriptor.contains("negative earnings"))
        assertFalse(
            descriptor.contains("low end of its own range"),
            "That phrasing reads as cheap when the company is losing money"
        )
    }

    @Test
    fun straddlingHistoryIsFlagged() {
        val history = points(
            listOf(-10.0, -5.0, 5.0, 12.0, 18.0, 22.0, 26.0, 30.0, 33.0, 36.0)
        )
        val context = assertNotNull(
            ValuationCalculator.historicalContext(
                current = 25.0, history = history, lowerIsCheaper = true
            )
        )
        assertFalse(context.meaningfulness.isRankable)
    }

    @Test
    fun negativeMarginRemainsRankable() {
        val history = points(listOf(-5.0, -2.0, 1.0, 4.0, 7.0, 10.0, 13.0, 16.0, 19.0, 22.0))
        val context = assertNotNull(
            ValuationCalculator.historicalContext(
                current = -3.0, history = history, lowerIsCheaper = false
            )
        )
        assertTrue(
            context.meaningfulness.isRankable,
            "A negative margin means what it says; a negative P/E does not"
        )
    }

    @Test
    fun positiveMultipleRankable() {
        val history = points(listOf(20.0, 22.0, 24.0, 26.0, 28.0, 30.0, 32.0, 34.0, 36.0, 38.0))
        val context = assertNotNull(
            ValuationCalculator.historicalContext(
                current = 31.0, history = history, lowerIsCheaper = true
            )
        )
        assertTrue(context.meaningfulness.isRankable)
        assertTrue(context.percentile > 0 && context.percentile < 100)
    }

    // MARK: - Percentile self-exclusion

    @Test
    fun currentExcludedFromOwnRanking() {
        val history = points(
            listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)
        )

        val included = assertNotNull(
            ValuationCalculator.historicalContext(
                current = 100.0, history = history, currentIsFromHistory = false
            )
        )
        val excluded = assertNotNull(
            ValuationCalculator.historicalContext(
                current = 100.0, history = history, currentIsFromHistory = true
            )
        )

        assertEquals(10, included.observationCount)
        assertEquals(9, excluded.observationCount, "The current observation is dropped")
        // Both still rank at the top; the point is the sample, not the result.
        assertEquals(100, excluded.percentile)
        assertEquals(90.0, excluded.maximum, "The value must not appear in its own range")
    }

    @Test
    fun exclusionRespectsMinimum() {
        val history = points(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0))
        // Eight observations minus the current leaves seven, which is the floor.
        assertNotNull(
            ValuationCalculator.historicalContext(
                current = 8.0, history = history, currentIsFromHistory = true
            )
        )
        assertNull(
            ValuationCalculator.historicalContext(
                current = 7.0,
                history = points(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0)),
                currentIsFromHistory = true
            )
        )
    }

    // MARK: - Scale validation

    @Test
    fun ratioSeriesIsScaled() {
        assertEquals(100.0, resolvedHistoryScale(declared = 100.0, values = listOf(0.42, 0.46, 0.48)))
    }

    @Test
    fun percentSeriesNotDoubleScaled() {
        // If Finnhub ever returns percent here, blindly multiplying would give
        // 4622% — plausible-looking and catastrophically wrong.
        assertEquals(1.0, resolvedHistoryScale(declared = 100.0, values = listOf(42.0, 46.2, 48.6)))
    }

    @Test
    fun highRatioStillScaled() {
        // Apple's ROE is around 1.37 as a ratio — well below the threshold.
        assertEquals(100.0, resolvedHistoryScale(declared = 100.0, values = listOf(1.10, 1.37, 1.42)))
    }

    @Test
    fun multiplesNeverRescaled() {
        assertEquals(1.0, resolvedHistoryScale(declared = 1.0, values = listOf(30.0, 40.0, 50.0)))
    }
}
