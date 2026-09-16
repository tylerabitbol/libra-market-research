package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * Joining a filing to the figures it reported.
 *
 * The join is the whole feature: every XBRL fact carries the accession number
 * of the filing that reported it, and the submissions feed supplies the same
 * accession in the same dashed format. If those formats ever diverge this
 * returns nothing at all rather than failing loudly.
 *
 * The two fixture-backed tests from the Swift suite (`realAccessionJoins`,
 * `annualFormsUseAnnualFigures`) need the `sec_companyfacts_AAPL` payload and
 * `SECFundamentalsProvider.extract`, neither of which exists before Phase 6.
 * They are deferred with the provider. See KNOWN_ISSUES.md.
 *
 * UTC, because that is how EDGAR's dates are parsed. A local-timezone
 * calendar here builds a date hours off the fixture's and makes an exact
 * comparison fail for reasons that have nothing to do with the code.
 */
class FilingAnalysisTest {

    private val zone = TimeZone.UTC

    private fun date(year: Int, month: Int, day: Int): Instant =
        LocalDate(year, month, day).atStartOfDayIn(zone)

    private fun filing(
        form: String = "10-Q",
        accession: String = "acc-current",
        filed: Instant = date(2025, 8, 1),
        period: Instant? = null
    ): FilingDTO = FilingDTO(
        accessionNumber = accession, formType = form, filedAt = filed, periodOfReport = period
    )

    private fun fact(
        concept: FinancialConcept,
        value: Double,
        periodEnd: Instant,
        kind: FiscalPeriodKind = FiscalPeriodKind.Quarter,
        accession: String
    ): FinancialFactDTO = FinancialFactDTO(
        concept = concept,
        periodStart = if (kind == FiscalPeriodKind.Instant) {
            null
        } else {
            periodEnd - (if (kind == FiscalPeriodKind.Annual) 365 else 90).days
        },
        periodEnd = periodEnd,
        fiscalYear = periodEnd.toLocalDateTime(zone).year,
        fiscalQuarter = if (kind == FiscalPeriodKind.Annual) null else 2,
        isAnnual = kind == FiscalPeriodKind.Annual,
        periodKind = kind,
        value = value,
        unit = "USD",
        filedAt = periodEnd + 30.days,
        accessionNumber = accession
    )

    // MARK: - Scope

    @Test
    fun eightKIsNotAnalysed() {
        assertNull(FilingAnalysis.analyse(filing("8-K"), emptyList()))
        assertNull(FilingAnalysis.analyse(filing("4"), emptyList()))
    }

    @Test
    fun awaitingFactsIsDistinctFromNothingChanged() {
        // companyfacts lags the submissions feed by hours to days. Rendering
        // that gap as "nothing changed" would be a false statement about a
        // real document.
        val result = assertNotNull(
            FilingAnalysis.analyse(
                filing("10-Q", accession = "not-yet-published"),
                listOf(
                    fact(
                        FinancialConcept.Revenue, 100.0, date(2025, 6, 30),
                        accession = "some-older-filing"
                    )
                )
            )
        )
        assertTrue(result.isAwaitingFacts)
        assertTrue(result.lines.isEmpty())
    }

    // MARK: - Comparison arithmetic

    @Test
    fun comparedFigureIsACalculation() {
        val facts = listOf(
            fact(FinancialConcept.Revenue, 1_000.0, date(2024, 6, 30), accession = "prior"),
            fact(FinancialConcept.Revenue, 1_200.0, date(2025, 6, 30), accession = "acc-current")
        )
        val result = assertNotNull(FilingAnalysis.analyse(filing(), facts))
        val revenue = assertNotNull(result.lines.firstOrNull { it.label == "Revenue" })

        assertEquals(ClaimKind.Calculation, revenue.claim.kind)
        assertTrue(
            revenue.comparison?.contains("20.0") == true,
            "1,000 → 1,200 is +20%"
        )
        assertEquals(2, revenue.claim.derivation?.inputs?.size)
    }

    @Test
    fun uncomparedFigureIsAFact() {
        val facts = listOf(
            fact(FinancialConcept.Revenue, 1_200.0, date(2025, 6, 30), accession = "acc-current")
        )
        val result = assertNotNull(FilingAnalysis.analyse(filing(), facts))
        val revenue = assertNotNull(result.lines.firstOrNull { it.label == "Revenue" })

        // A company's first year on file has no prior year. Badging that the
        // same as a compared figure would overstate what the app knows.
        assertEquals(ClaimKind.Fact, revenue.claim.kind)
        assertNull(revenue.comparison)
        assertNull(revenue.claim.derivation)
    }

    @Test
    fun percentChangeNeedsAPositiveBase() {
        val facts = listOf(
            fact(FinancialConcept.Revenue, 0.0, date(2024, 6, 30), accession = "prior"),
            fact(FinancialConcept.Revenue, 500.0, date(2025, 6, 30), accession = "acc-current")
        )
        val result = assertNotNull(FilingAnalysis.analyse(filing(), facts))
        val revenue = assertNotNull(result.lines.firstOrNull { it.label == "Revenue" })

        // Division by zero would render as an infinite growth rate; the figure
        // is still reported, just without a comparison it cannot support.
        assertNull(revenue.comparison)
        assertEquals(ClaimKind.Fact, revenue.claim.kind)
    }

    @Test
    fun cashFlowAvoidsPercentThroughZero() {
        val facts = listOf(
            fact(
                FinancialConcept.OperatingCashFlow, -50.0, date(2024, 6, 30),
                accession = "prior"
            ),
            fact(
                FinancialConcept.CapitalExpenditures, 50.0, date(2024, 6, 30),
                accession = "prior"
            ),
            fact(
                FinancialConcept.OperatingCashFlow, 150.0, date(2025, 6, 30),
                accession = "acc-current"
            ),
            fact(
                FinancialConcept.CapitalExpenditures, 50.0, date(2025, 6, 30),
                accession = "acc-current"
            )
        )
        val result = assertNotNull(FilingAnalysis.analyse(filing(), facts))
        val fcf = assertNotNull(result.lines.firstOrNull { it.label == "Free cash flow" })

        // -$100 to +$100. A percentage change here is either -200% or +200%
        // depending on the sign convention, and neither describes what happened.
        val comparison = assertNotNull(fcf.comparison)
        assertTrue(comparison.contains("$"))
        assertFalse(comparison.contains("%"))
    }

    @Test
    fun marginsUsePercentagePoints() {
        val facts = listOf(
            fact(FinancialConcept.Revenue, 1_000.0, date(2024, 6, 30), accession = "prior"),
            fact(FinancialConcept.GrossProfit, 400.0, date(2024, 6, 30), accession = "prior"),
            fact(FinancialConcept.Revenue, 1_000.0, date(2025, 6, 30), accession = "acc-current"),
            fact(FinancialConcept.GrossProfit, 450.0, date(2025, 6, 30), accession = "acc-current")
        )
        val result = assertNotNull(FilingAnalysis.analyse(filing(), facts))
        val margin = assertNotNull(result.lines.firstOrNull { it.label == "Gross margin" })

        assertTrue(margin.formatted.contains("45"))
        assertTrue(margin.comparison?.contains("pp") == true, "40% to 45% is 5pp, not 5%")
    }

    @Test
    fun balanceSheetFiguresAreInstants() {
        val facts = listOf(
            fact(
                FinancialConcept.TotalDebt, 900.0, date(2024, 6, 30),
                kind = FiscalPeriodKind.Instant, accession = "prior"
            ),
            fact(
                FinancialConcept.TotalDebt, 1_100.0, date(2025, 6, 30),
                kind = FiscalPeriodKind.Instant, accession = "acc-current"
            )
        )
        val result = assertNotNull(FilingAnalysis.analyse(filing(), facts))
        val debt = assertNotNull(result.lines.firstOrNull { it.label == "Total debt" })
        assertTrue(debt.comparison?.contains("$") == true)
    }

    @Test
    fun otherFilingsFiguresAreExcluded() {
        val facts = listOf(
            fact(FinancialConcept.Revenue, 999.0, date(2025, 6, 30), accession = "someone-else")
        )
        // The accession is the whole basis of the join. A filter that matched
        // loosely would credit this filing with a figure it never reported.
        val result = assertNotNull(
            FilingAnalysis.analyse(filing("10-Q", accession = "acc-current"), facts)
        )
        assertTrue(result.isAwaitingFacts)
    }
}
