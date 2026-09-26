package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.EvidenceCategory
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val EPOCH = Instant.fromEpochSeconds(1_700_000_000)

private fun day(index: Int): Instant = EPOCH + index.days

private fun sameDay(a: Instant, b: Instant): Boolean {
    val zone = TimeZone.currentSystemDefault()
    return a.toLocalDateTime(zone).date == b.toLocalDateTime(zone).date
}

/**
 * Ported from LibraTests/RelativeAnalysisTests.swift.
 *
 * Separating "the market moved" from "this company moved".
 *
 * The failure this guards against is subtle: an attribution built on
 * misaligned sessions, or on a beta fitted to noise, reads exactly like a
 * correct one. Every test here is about refusing to answer rather than
 * answering confidently.
 */
class RelativeAnalysisTest {

    /**
     * Security bars whose daily moves are `beta × market` plus an optional
     * per-session residual.
     */
    private fun bars(
        marketMoves: List<Double>,
        beta: Double,
        residuals: List<Double> = emptyList()
    ): List<PriceBar> {
        var close = 100.0
        val result = mutableListOf(
            PriceBar(
                date = day(0), resolution = BarResolution.Daily,
                open = close, high = close, low = close, close = close,
                volume = 1000.0, adjustedClose = close
            )
        )
        marketMoves.forEachIndexed { index, move ->
            val residual = if (index < residuals.size) residuals[index] else 0.0
            close *= (1 + (beta * move + residual) / 100)
            result.add(
                PriceBar(
                    date = day(index + 1), resolution = BarResolution.Daily,
                    open = close, high = close, low = close, close = close,
                    volume = 1000.0, adjustedClose = close
                )
            )
        }
        return result
    }

    private fun marketCloses(moves: List<Double>): List<ClosePoint> {
        var close = 1000.0
        val result = mutableListOf(ClosePoint(day(0), close))
        moves.forEachIndexed { index, move ->
            close *= (1 + move / 100)
            result.add(ClosePoint(day(index + 1), close))
        }
        return result
    }

    private fun alternating(count: Int, magnitude: Double = 1.0): List<Double> =
        (0 until count).map { if (it % 2 == 0) magnitude else -magnitude }

    // MARK: - Alignment

    @Test
    fun only_sessions_present_in_both_series_are_compared() {
        // A holiday in one calendar and not the other would otherwise pair a
        // two-day move against a one-day move and call the gap company-specific.
        val moves = alternating(80)
        val security = bars(moves, beta = 1.0)
        val market = marketCloses(moves).filterNot { sameDay(it.date, day(40)) }

        val aligned = RelativeAnalysis.align(security, market)
        assertFalse(aligned.any { sameDay(it.date, day(40)) })
        // Day 41's market return would span two days; it must be dropped too.
        assertFalse(
            aligned.any { sameDay(it.date, day(41)) },
            "A two-day market move must not be paired with a one-day move"
        )
    }

    @Test
    fun aligned_returns_come_back_in_chronological_order() {
        val moves = alternating(80)
        val aligned = RelativeAnalysis.align(bars(moves, beta = 1.0), marketCloses(moves))
        assertEquals(aligned.map { it.date }.sorted(), aligned.map { it.date })
    }

    // MARK: - Beta

    @Test
    fun beta_recovers_a_known_sensitivity() {
        val moves = (0 until 120).map { (it % 7).toDouble() - 3 }
        val aligned = RelativeAnalysis.align(bars(moves, beta = 2.0), marketCloses(moves))
        val beta = assertNotNull(RelativeAnalysis.beta(aligned))

        // Compounding makes this approximate rather than exact.
        assertTrue(abs(beta.value - 2) < 0.1)
        assertEquals(aligned.size, beta.observationCount)
    }

    @Test
    fun beta_is_fitted_over_a_bounded_recent_window_not_all_of_history() {
        // Beta is not a constant. A five-year fit averages a sensitivity the
        // company no longer has into the one being applied today — and it
        // looks more rigorous while being less informative.
        val moves = (0 until 900).map { (it % 7).toDouble() - 3 }
        val aligned = RelativeAnalysis.align(bars(moves, beta = 2.0), marketCloses(moves))
        val beta = assertNotNull(RelativeAnalysis.beta(aligned))

        assertTrue(aligned.size > RelativeAnalysis.betaWindow)
        assertEquals(RelativeAnalysis.betaWindow, beta.observationCount)
        assertEquals(aligned.last().date, beta.latest, "The window must end at the present")
    }

    @Test
    fun too_few_overlapping_sessions_yields_no_beta() {
        val moves = alternating(20)
        val aligned = RelativeAnalysis.align(bars(moves, beta = 1.0), marketCloses(moves))
        // A beta from a handful of sessions is noise with a Greek letter on it.
        assertNull(RelativeAnalysis.beta(aligned))
    }

    @Test
    fun a_motionless_market_yields_no_beta() {
        val flat = List(100) { 0.0 }
        val aligned = RelativeAnalysis.align(
            bars(flat, beta = 1.0, residuals = alternating(100)),
            marketCloses(flat)
        )
        // Dividing by a near-zero variance produces an enormous beta from nothing.
        assertNull(RelativeAnalysis.beta(aligned))
    }

    // MARK: - Attribution

    @Test
    fun a_high_beta_names_market_share_is_scaled_not_taken_raw() {
        val beta = Beta(2.24, 250, day(0), day(250))
        val attribution = RelativeAnalysis.attribute(
            securityMove = 8.74, marketMove = 0.31, marketName = "S&P 500", beta = beta
        )

        // Raw difference would call 8.43 pp company-specific; beta accounts
        // for more than twice as much of the move.
        assertTrue(abs(attribution.explainedByMarket - 0.6944) < 0.001)
        assertTrue(abs(attribution.residual - 8.0456) < 0.001)
        assertEquals(EvidenceCategory.CompanySpecific, attribution.leaning)
    }

    @Test
    fun without_a_beta_the_comparison_is_the_plain_difference_and_says_so() {
        val attribution = RelativeAnalysis.attribute(
            securityMove = 3.0, marketMove = 1.0, marketName = "S&P 500", beta = null
        )
        assertEquals(2.0, attribution.residual)
        assertTrue(
            attribution.detailLines.any { it.contains("not beta-adjusted") },
            "A high-beta name reads very differently unadjusted"
        )
    }

    @Test
    fun a_move_the_market_accounts_for_leans_market_wide() {
        val beta = Beta(1.0, 250, day(0), day(250))
        val attribution = RelativeAnalysis.attribute(
            securityMove = 2.2, marketMove = 2.0, marketName = "S&P 500", beta = beta
        )
        assertEquals(EvidenceCategory.MarketWide, attribution.leaning)
        assertEquals(
            ClaimKind.Interpretation, attribution.claim.kind,
            "A one-factor model's verdict is a judgement, not a fact"
        )
    }

    @Test
    fun an_etf_standing_in_for_the_index_is_disclosed() {
        val attribution = RelativeAnalysis.attribute(
            securityMove = 5.0, marketMove = 1.0, marketName = "S&P 500 (SPY)",
            beta = null, isMarketProxy = true
        )
        assertTrue(attribution.detailLines.any { it.contains("ETF proxy") })
    }

    @Test
    fun opposite_directions_are_not_forced_into_a_share_of_one_move() {
        // A stock rising on a falling market breaks any "percentage of the
        // move" framing, so the two parts are reported separately and signed.
        val beta = Beta(1.0, 250, day(0), day(250))
        val attribution = RelativeAnalysis.attribute(
            securityMove = 3.0, marketMove = -2.0, marketName = "S&P 500", beta = beta
        )
        assertEquals(-2.0, attribution.explainedByMarket)
        assertEquals(5.0, attribution.residual)
    }
}
