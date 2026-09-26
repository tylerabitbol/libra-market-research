package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import com.tylerabitbol.libra.services.providers.RatingSnapshotDTO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * Sections 12 and 13: the components, and the search for evidence that argues
 * the other way.
 *
 * The property that matters most is symmetry. An engine that finds
 * disconfirming evidence only when the recent picture is good is a bull-case
 * finder wearing a skeptic's label, and the tests below check both directions
 * of every rule rather than only the one that reads well.
 */
class ResearchProfileTest {

    private val zone = TimeZone.UTC

    private fun quarterEnd(index: Int): Instant {
        val year = 2019 + index / 4
        val month = listOf(3, 6, 9, 12)[index % 4]
        return LocalDate(year, month, 28).atStartOfDayIn(zone)
    }

    private fun fact(
        concept: FinancialConcept,
        index: Int,
        value: Double,
        kind: FiscalPeriodKind = FiscalPeriodKind.Quarter
    ): FinancialFactDTO {
        val end = quarterEnd(index)
        return FinancialFactDTO(
            concept = concept,
            periodStart = if (kind == FiscalPeriodKind.Instant) null else end - 90.days,
            periodEnd = end,
            fiscalYear = end.toLocalDateTime(zone).year,
            fiscalQuarter = index % 4 + 1,
            isAnnual = false,
            periodKind = kind,
            value = value,
            unit = "USD",
            filedAt = end,
            accessionNumber = "acc-$index"
        )
    }

    private fun periodReturn(percent: Double): PeriodReturn = PeriodReturn(
        percent = percent, startDate = quarterEnd(0), endDate = quarterEnd(4),
        startPrice = 100.0, endPrice = 100 * (1 + percent / 100), isFullWindow = true
    )

    private fun relative(points: Double): RelativePerformance = RelativePerformance(
        securityReturn = points, benchmarkReturn = 0.0, differencePoints = points,
        startDate = quarterEnd(0), endDate = quarterEnd(4)
    )

    private fun component(profile: ResearchProfile, dimension: ResearchDimension) =
        profile.components.firstOrNull { it.dimension == dimension }

    @Test
    fun noCompositeExists() {
        // Section 13 asked for "Research Signal: 82/100". The components are
        // the deliverable; the number is refused. If a score is ever added,
        // this test is the place the decision has to be re-argued.
        //
        // Swift used Mirror; common Kotlin has no reflection, so this reads the
        // data class's own rendering of its stored properties.
        val rendered = ResearchProfile(components = emptyList()).toString()
        assertEquals("ResearchProfile(components=[])", rendered)
        assertFalse(rendered.lowercase().contains("score"))
    }

    @Test
    fun absentDataIsNotReassurance() {
        val profile = ResearchProfileBuilder.build(ResearchProfileBuilder.Inputs())
        assertTrue(profile.measured.isEmpty())
        assertEquals(ResearchDimension.entries.size, profile.unavailable.size)
        // Treating an absent figure as "nothing to worry about" is the
        // fabrication Section 21 forbids, applied to judgement.
        assertFalse(profile.components.any { it.direction == EvidenceDirection.Neutral })
    }

    @Test
    fun priceDimensionsAreSymmetric() {
        val up = ResearchProfileBuilder.build(
            ResearchProfileBuilder.Inputs(
                rangeReturn = periodReturn(12.0), relativeToMarket = relative(7.0)
            )
        )
        assertEquals(
            EvidenceDirection.Supportive,
            component(up, ResearchDimension.Momentum)?.direction
        )
        assertEquals(
            EvidenceDirection.Supportive,
            component(up, ResearchDimension.RelativeStrength)?.direction
        )

        val down = ResearchProfileBuilder.build(
            ResearchProfileBuilder.Inputs(
                rangeReturn = periodReturn(-12.0), relativeToMarket = relative(-7.0)
            )
        )
        assertEquals(
            EvidenceDirection.Challenging,
            component(down, ResearchDimension.Momentum)?.direction
        )
        assertEquals(
            EvidenceDirection.Challenging,
            component(down, ResearchDimension.RelativeStrength)?.direction
        )
    }

    @Test
    fun smallMovesAreNeutral() {
        val flat = ResearchProfileBuilder.build(
            ResearchProfileBuilder.Inputs(rangeReturn = periodReturn(0.2))
        )
        assertEquals(
            EvidenceDirection.Neutral,
            component(flat, ResearchDimension.Momentum)?.direction
        )
    }

    @Test
    fun revenueTrendIsSymmetric() {
        val growing = mutableListOf<FinancialFactDTO>()
        val shrinking = mutableListOf<FinancialFactDTO>()
        for (index in 0 until 8) {
            val base = 1_000.0
            growing.add(
                fact(FinancialConcept.Revenue, index, base * (if (index >= 4) 1.2 else 1.0))
            )
            shrinking.add(
                fact(FinancialConcept.Revenue, index, base * (if (index >= 4) 0.8 else 1.0))
            )
        }
        assertEquals(
            EvidenceDirection.Supportive,
            component(
                ResearchProfileBuilder.build(
                    ResearchProfileBuilder.Inputs(fundamentals = growing)
                ),
                ResearchDimension.RevenueTrend
            )?.direction
        )
        assertEquals(
            EvidenceDirection.Challenging,
            component(
                ResearchProfileBuilder.build(
                    ResearchProfileBuilder.Inputs(fundamentals = shrinking)
                ),
                ResearchDimension.RevenueTrend
            )?.direction
        )
    }

    @Test
    fun lossToProfitIsNotAPercentage() {
        val facts = (0 until 8).map { index ->
            fact(FinancialConcept.NetIncome, index, if (index >= 4) 50.0 else -50.0)
        }
        val component = assertNotNull(
            component(
                ResearchProfileBuilder.build(
                    ResearchProfileBuilder.Inputs(fundamentals = facts)
                ),
                ResearchDimension.EarningsTrend
            )
        )

        // Crossing from a loss to a profit is real news, and "+200%" is not the
        // way to say it. The direction is still recognised as supportive.
        assertEquals(EvidenceDirection.Supportive, component.direction)
        assertFalse(component.summary.contains("%"))
        assertTrue(component.claim?.text?.contains("non-positive base") == true)
    }

    @Test
    fun balanceSheetIsSymmetric() {
        val indebted = listOf(
            fact(FinancialConcept.TotalDebt, 0, 5_000.0, FiscalPeriodKind.Instant),
            fact(FinancialConcept.CashAndEquivalents, 0, 1_000.0, FiscalPeriodKind.Instant)
        )
        val liquid = listOf(
            fact(FinancialConcept.TotalDebt, 0, 1_000.0, FiscalPeriodKind.Instant),
            fact(FinancialConcept.CashAndEquivalents, 0, 5_000.0, FiscalPeriodKind.Instant)
        )

        val a = component(
            ResearchProfileBuilder.build(ResearchProfileBuilder.Inputs(fundamentals = indebted)),
            ResearchDimension.BalanceSheet
        )
        val b = component(
            ResearchProfileBuilder.build(ResearchProfileBuilder.Inputs(fundamentals = liquid)),
            ResearchDimension.BalanceSheet
        )

        assertEquals(EvidenceDirection.Challenging, a?.direction)
        assertTrue(a?.summary?.contains("net debt") == true)
        assertEquals(EvidenceDirection.Supportive, b?.direction)
        assertTrue(b?.summary?.contains("net cash") == true)
        // Net debt is a constraint, not a verdict, and the claim says so.
        assertTrue(a.claim?.text?.contains("not by itself a problem") == true)
    }

    @Test
    fun analystPostureIsSymmetric() {
        val now = Clock.System.now()
        val bullish = RatingSnapshotDTO(
            asOf = now, strongBuy = 10, buy = 10, hold = 2, sell = 0, strongSell = 0
        )
        val bearish = RatingSnapshotDTO(
            asOf = now, strongBuy = 0, buy = 0, hold = 2, sell = 10, strongSell = 10
        )
        val up = assertNotNull(
            component(
                ResearchProfileBuilder.build(ResearchProfileBuilder.Inputs(ratings = bullish)),
                ResearchDimension.AnalystPosture
            )
        )
        val down = component(
            ResearchProfileBuilder.build(ResearchProfileBuilder.Inputs(ratings = bearish)),
            ResearchDimension.AnalystPosture
        )

        assertEquals(EvidenceDirection.Supportive, up.direction)
        assertEquals(EvidenceDirection.Challenging, down?.direction)
        assertTrue(up.claim?.text?.contains("not evidence about the business") == true)
    }

    @Test
    fun insiderActivityIsSymmetric() {
        val buying = assertNotNull(
            component(
                ResearchProfileBuilder.build(
                    ResearchProfileBuilder.Inputs(insiderPurchases = 4, insiderSales = 1)
                ),
                ResearchDimension.InsiderActivity
            )
        )
        val selling = component(
            ResearchProfileBuilder.build(
                ResearchProfileBuilder.Inputs(insiderPurchases = 0, insiderSales = 5)
            ),
            ResearchDimension.InsiderActivity
        )

        assertEquals(EvidenceDirection.Supportive, buying.direction)
        assertEquals(EvidenceDirection.Challenging, selling?.direction)
        assertTrue(buying.claim?.text?.contains("does not predict returns") == true)
        assertTrue(buying.claim.text.contains("Scheduled plans") == true)
    }

    @Test
    fun conflictIsSurfaced() {
        val profile = ResearchProfileBuilder.build(
            ResearchProfileBuilder.Inputs(
                rangeReturn = periodReturn(20.0), // supports
                relativeToMarket = relative(-8.0), // challenges
                fundamentals = listOf(
                    fact(FinancialConcept.TotalDebt, 0, 5_000.0, FiscalPeriodKind.Instant),
                    fact(
                        FinancialConcept.CashAndEquivalents, 0, 1_000.0,
                        FiscalPeriodKind.Instant
                    )
                )
            )
        )

        assertTrue(profile.isConflicted)
        assertEquals(ClaimKind.Interpretation, profile.shapeClaim.kind)
        assertTrue(profile.shapeClaim.text.contains("both ways"))
        // Counting is not weighing, and the text refuses to pretend otherwise.
        assertTrue(profile.shapeClaim.text.contains("not a count"))
    }

    @Test
    fun oneSidedProfileIsQualified() {
        val profile = ResearchProfileBuilder.build(
            ResearchProfileBuilder.Inputs(
                rangeReturn = periodReturn(20.0), relativeToMarket = relative(8.0)
            )
        )
        assertFalse(profile.isConflicted)
        assertTrue(profile.challenging.isEmpty())
        assertTrue(profile.shapeClaim.text.contains("not an absence of risk"))
    }
}
