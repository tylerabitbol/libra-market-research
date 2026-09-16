package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.EvidenceCategory
import com.tylerabitbol.libra.models.core.PriceBar
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Ported from LibraTests/SectorAttributionTests.swift.
 *
 * The whole risk here is double-counting. A sector ETF moves with the market —
 * XLK and the S&P share most of their variance — so subtracting a raw sector
 * move from what the market already explained counts the market twice and
 * leaves a residual that is mostly sign noise. The sector is orthogonalised
 * against the market before it is used as a factor, and the first test below
 * is what proves it.
 */
private val SESSION_START = Instant.fromEpochSeconds(1_600_000_000)

private fun day(index: Int): Instant = SESSION_START + index.days

/** Turns a return series (percent) into closes starting at 100. */
private fun closes(returns: List<Double>): List<Double> {
    var level = 100.0
    val result = mutableListOf(level)
    for (percent in returns) {
        level *= (1 + percent / 100)
        result.add(level)
    }
    return result
}

private fun bars(returns: List<Double>): List<PriceBar> =
    closes(returns).mapIndexed { index, close ->
        PriceBar(
            date = day(index), resolution = BarResolution.Daily,
            open = close, high = close, low = close, close = close, adjustedClose = close
        )
    }

private fun series(returns: List<Double>): List<ClosePoint> =
    closes(returns).mapIndexed { index, close -> ClosePoint(day(index), close) }

/**
 * A deterministic generator, so a fit is stable across runs.
 *
 * Sums of sinusoids were tried first and are a trap here: two such series at
 * different frequencies are still measurably correlated over a few hundred
 * points, which is precisely the thing these tests need to be free of. A
 * linear congruential generator gives independent series with no such
 * accidental structure.
 */
private class Deterministic(seed: ULong) {
    private var state: ULong = seed

    private fun next(): Double {
        state = state * 6_364_136_223_846_793_005UL + 1_442_695_040_888_963_407UL
        return (state shr 11).toDouble() / (1L shl 53).toDouble()
    }

    /** Roughly standard normal by the central limit theorem. */
    fun normal(): Double {
        var sum = 0.0
        repeat(12) { sum += next() }
        return sum - 6.0
    }

    companion object {
        fun returns(count: Int, seed: ULong, scale: Double = 1.0): List<Double> {
            val generator = Deterministic(seed)
            return (0 until count).map { generator.normal() * scale }
        }
    }
}

class SectorAttributionTest {

    @Test
    fun a_sector_barely_distinct_from_the_market_explains_almost_none_of_a_move() {
        val market = Deterministic.returns(400, 11UL)
        val sectorOwn = Deterministic.returns(400, 22UL)
        // A sector that is 99% the market. Whatever it adds, the security does
        // not respond to it — so the sector leg must be negligible and the
        // residual must land where the one-factor model already put it. This is
        // the double-counting test: without orthogonalisation the sector's
        // market component would be subtracted a second time here.
        val sector = market.zip(sectorOwn) { m, s -> m + s * 0.01 }
        val securityReturns = market.map { it * 1.5 }

        val aligned = RelativeAnalysis.align(
            bars(securityReturns), series(market), bars(sector)
        )
        val factor = assertNotNull(
            RelativeAnalysis.sectorFactor(aligned, "Information Technology", isProxy = true)
        )

        assertTrue(abs(factor.marketBeta - 1.0) < 0.02)
        assertTrue(
            abs(factor.sensitivity) < 0.2,
            "The security does not track the sector's own move"
        )

        val attribution = RelativeAnalysis.attribute(
            securityMove = 3.0, marketMove = 2.0, marketName = "S&P 500",
            beta = Beta(1.5, 400, day(0), day(400)),
            sector = factor, sectorMove = 2.0
        )

        val leg = assertNotNull(attribution.sector)
        assertTrue(abs(leg.excess) < 0.05, "Almost none of the sector's move is beyond the market")
        // 3.0 - 1.5 x 2.0 = 0, the one-factor answer, undisturbed.
        assertTrue(abs(attribution.residual) < 0.05)
    }

    @Test
    fun a_sector_identical_to_the_market_is_refused_rather_than_fitted() {
        val market = Deterministic.returns(200, 33UL)
        // Zero variance beyond the market means there is no second factor to
        // fit. Returning null drops the leg and leaves the one-factor model,
        // which is the honest outcome.
        val aligned = RelativeAnalysis.align(
            bars(market.map { it * 1.5 }), series(market), bars(market)
        )
        assertNull(RelativeAnalysis.sectorFactor(aligned, "Tech", isProxy = true))
    }

    @Test
    fun a_sector_that_moves_on_its_own_carries_the_part_the_market_cannot() {
        val market = Deterministic.returns(400, 44UL)
        val sectorOwn = Deterministic.returns(400, 55UL, scale = 0.8)
        // Sector = market + its own independent component. The security follows
        // the market at 1.0 and that component at 2.0, and the fit must recover
        // both without confusing one for the other.
        val sector = market.zip(sectorOwn) { m, s -> m + s }
        val securityReturns = market.zip(sectorOwn) { m, s -> m + s * 2.0 }

        val aligned = RelativeAnalysis.align(
            bars(securityReturns), series(market), bars(sector)
        )
        val factor = assertNotNull(
            RelativeAnalysis.sectorFactor(aligned, "Information Technology", isProxy = true)
        )

        assertTrue(abs(factor.marketBeta - 1.0) < 0.05)
        assertTrue(
            abs(factor.sensitivity - 2.0) < 0.15,
            "Recovered the security's sensitivity to the sector's own move"
        )
    }

    @Test
    fun too_little_overlapping_history_produces_no_factor_rather_than_a_weak_one() {
        val market = Deterministic.returns(20, 66UL)
        val aligned = RelativeAnalysis.align(bars(market), series(market), bars(market))
        assertNull(RelativeAnalysis.sectorFactor(aligned, "Tech", isProxy = true))
    }

    @Test
    fun a_flat_market_produces_no_factor_rather_than_an_enormous_one() {
        val flat = List(200) { 0.0 }
        val aligned = RelativeAnalysis.align(
            bars(Deterministic.returns(200, 77UL)), series(flat), bars(flat)
        )
        // Dividing by a near-zero variance manufactures a coefficient from
        // nothing at all.
        assertNull(RelativeAnalysis.sectorFactor(aligned, "Tech", isProxy = true))
    }

    @Test
    fun three_way_alignment_keeps_only_sessions_every_leg_shares() {
        val full = bars(Deterministic.returns(10, 88UL))
        val market = series(Deterministic.returns(10, 88UL))
        // The sector is missing its later sessions entirely.
        val shortSector = bars(Deterministic.returns(10, 88UL)).take(5)

        val aligned = RelativeAnalysis.align(full, market, shortSector)
        assertTrue(aligned.size <= 4, "A session the sector never traded cannot be compared")
        assertTrue(aligned.all { it.date <= shortSector.last().date })
    }

    @Test
    fun the_leaning_names_the_industry_when_the_sector_carries_the_move() {
        val attribution = RelativeAnalysis.attribute(
            securityMove = 5.0, marketMove = 0.1, marketName = "S&P 500",
            beta = Beta(1.0, 200, day(0), day(200)),
            sector = SectorFactor(
                name = "Semiconductors", marketBeta = 1.0, sensitivity = 1.0,
                observationCount = 200, isProxy = true, earliest = day(0), latest = day(200)
            ),
            sectorMove = 4.9
        )

        // Sector beyond market is 4.8 of a 5.0 move; the market explains 0.1.
        assertEquals(EvidenceCategory.Industry, attribution.leaning)
        assertTrue(attribution.claim.text.contains("Semiconductors"))
        // Never a share of one move — the parts can point opposite ways.
        assertFalse(attribution.claim.text.contains("%of"))
    }

    @Test
    fun without_a_sector_the_attribution_is_unchanged_and_says_so() {
        val attribution = RelativeAnalysis.attribute(
            securityMove = 3.0, marketMove = 1.0, marketName = "S&P 500",
            beta = Beta(2.0, 200, day(0), day(200)),
            sector = null, sectorMove = null
        )

        assertNull(attribution.sector)
        assertEquals(1.0, attribution.residual, "3.0 - 2.0 x 1.0")
        assertTrue(attribution.claim.text.contains("one-factor"))
    }

    @Test
    fun the_company_specific_reading_is_a_complete_sentence_without_a_sector() {
        val attribution = RelativeAnalysis.attribute(
            securityMove = 8.0, marketMove = 0.5, marketName = "S&P 500",
            beta = Beta(1.0, 200, day(0), day(200)),
            sector = null, sectorMove = null
        )

        assertTrue(
            attribution.claim.text.contains("does not explain most of this move"),
            "A dropped verb read \"S&P 500 does not most of this move\""
        )
    }

    @Test
    fun a_sector_move_with_no_fitted_factor_is_not_guessed_at() {
        val attribution = RelativeAnalysis.attribute(
            securityMove = 3.0, marketMove = 1.0, marketName = "S&P 500",
            beta = null, sector = null, sectorMove = 4.0
        )
        assertNull(
            attribution.sector,
            "A sector move without a sensitivity to apply it at explains nothing"
        )
    }
}
