package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Detection over reported figures.
 *
 * The statistical rules are the same as the price detectors' — rank rather
 * than probability, median and MAD rather than mean and standard deviation, an
 * observation excluded from its own sample — but the cadence and the failure
 * modes are entirely different. These tests pin the ones that would produce a
 * confident wrong number: seasonality read as change, a cumulative period read
 * as a quarter, and a percentage change taken through zero.
 *
 * The restatement cases are here too: `periodKey` moved to `FactPeriods` in
 * Phase 4, when the store turned out to need it for its own read path.
 */
class FundamentalDetectionTest {

    private val zone = TimeZone.UTC

    private fun quarterEnd(index: Int, startYear: Int = 2019, dayDrift: Int = 0): Instant {
        val year = startYear + index / 4
        val month = listOf(3, 6, 9, 12)[index % 4]
        val day = listOf(31, 30, 30, 31)[index % 4]
        return LocalDate(year, month, day).atStartOfDayIn(zone) + dayDrift.days
    }

    private fun fact(
        concept: FinancialConcept,
        index: Int,
        value: Double,
        kind: FiscalPeriodKind = FiscalPeriodKind.Quarter,
        accession: String? = null,
        filedOffsetDays: Int = 30,
        dayDrift: Int = 0
    ): FinancialFactDTO {
        val end = quarterEnd(index, dayDrift = dayDrift)
        return FinancialFactDTO(
            concept = concept,
            periodStart = if (kind == FiscalPeriodKind.Instant) null else end - 90.days,
            periodEnd = end,
            fiscalYear = startYearOf(end),
            fiscalQuarter = index % 4 + 1,
            isAnnual = false,
            periodKind = kind,
            value = value,
            unit = "USD",
            filedAt = end + filedOffsetDays.days,
            accessionNumber = accession ?: "acc-$index"
        )
    }

    private fun startYearOf(instant: Instant): Int =
        instant.toLocalDateTime(zone).year

    /** Builds revenue and a numerator that produce the given margin series. */
    private fun marginFacts(
        concept: FinancialConcept,
        margins: List<Double>,
        revenue: Double = 1_000.0
    ): List<FinancialFactDTO> = margins.flatMapIndexed { index, margin ->
        listOf(
            fact(FinancialConcept.Revenue, index, revenue),
            fact(concept, index, revenue * margin / 100)
        )
    }

    /** Twenty quarters of a stable gross margin, wiggling by a few tenths. */
    private val stableMargins = listOf(
        40.0, 40.2, 39.8, 40.1, 40.3, 39.9, 40.0, 40.2, 40.1, 39.8,
        40.2, 40.0, 39.9, 40.3, 40.1, 40.0, 40.2, 39.9, 40.1, 40.0
    )

    // MARK: - Margins

    @Test
    fun marginBreakIsDetected() {
        val margins = stableMargins.dropLast(1) + 35.0

        val event = assertNotNull(
            FundamentalDetector.marginChange(
                marginFacts(FinancialConcept.GrossProfit, margins)
            )
        )

        assertEquals(EventKind.MarginChange, event.kind)
        assertTrue(event.headline.contains("narrowed"))
        assertTrue(event.unusualness >= FundamentalDetector.rankThreshold)
        assertNotNull(event.derivation, "A measured claim must carry its arithmetic")
    }

    @Test
    fun stableMarginIsSilent() {
        assertNull(
            FundamentalDetector.marginChange(
                marginFacts(FinancialConcept.GrossProfit, stableMargins)
            )
        )
    }

    @Test
    fun seasonalityIsNotChange() {
        // Q4 runs 5pp below the other quarters every single year. Compared with
        // the preceding quarter this fires every December and calls the
        // calendar news; compared with the same quarter a year earlier — which
        // is what the detector does — there is nothing to report.
        val margins = (0 until 20).map { index ->
            val seasonal = if (index % 4 == 3) 35.0 else 40.0
            seasonal + (index % 3) * 0.1
        }
        assertNull(
            FundamentalDetector.marginChange(
                marginFacts(FinancialConcept.GrossProfit, margins)
            )
        )
    }

    @Test
    fun smallMoveIsSuppressed() {
        val margins = stableMargins.dropLast(1) + 39.0 // ~1pp against a ~40pp history
        assertNull(
            FundamentalDetector.marginChange(
                marginFacts(FinancialConcept.GrossProfit, margins),
                minimumChangePoints = 1.5
            )
        )
    }

    @Test
    fun shortHistoryIsSilent() {
        val margins = listOf(40.0, 40.2, 39.8, 40.1, 35.0)
        assertNull(
            FundamentalDetector.marginChange(
                marginFacts(FinancialConcept.GrossProfit, margins)
            )
        )
    }

    @Test
    fun eventIsDatedToFiling() {
        val margins = stableMargins.dropLast(1) + 35.0

        val event = assertNotNull(
            FundamentalDetector.marginChange(
                marginFacts(FinancialConcept.GrossProfit, margins)
            )
        )

        // A June quarter disclosed in August is news in August. Dating it to
        // June would file it behind price events the user has already seen.
        val period = quarterEnd(margins.size - 1)
        assertTrue(event.occurredAt > period)
        assertEquals(period + 30.days, event.occurredAt)
    }

    // MARK: - Revenue growth

    @Test
    fun revenueGrowthSlowdown() {
        // Four base quarters, then five years compounding at ~12% — until the
        // last quarter, which grows 1%.
        val values = mutableListOf(100.0, 110.0, 120.0, 130.0)
        for (index in 4 until 24) {
            val rate = if (index == 23) 1.01 else 1.12
            values.add(values[index - 4] * rate)
        }
        val facts = values.mapIndexed { index, value ->
            fact(FinancialConcept.Revenue, index, value)
        }

        val event = assertNotNull(FundamentalDetector.revenueGrowthChange(facts))
        assertEquals(EventKind.RevenueGrowthChange, event.kind)
        assertTrue(event.headline.contains("slowed"))
        assertTrue(event.unusualness >= FundamentalDetector.rankThreshold)
    }

    @Test
    fun steadyGrowthIsSilent() {
        val values = mutableListOf(100.0, 110.0, 120.0, 130.0)
        for (index in 4 until 24) values.add(values[index - 4] * 1.12)
        val facts = values.mapIndexed { index, value ->
            fact(FinancialConcept.Revenue, index, value)
        }
        assertNull(FundamentalDetector.revenueGrowthChange(facts))
    }

    // MARK: - Free cash flow

    @Test
    fun freeCashFlowThroughZero() {
        // A percentage change from -10 to +10 is either -200% or +200%
        // depending on which sign convention you pick, and neither describes
        // what happened. Measuring the margin in percentage points does.
        val facts = (0 until 12).flatMap { index ->
            listOf(
                fact(FinancialConcept.Revenue, index, 1_000.0),
                fact(FinancialConcept.OperatingCashFlow, index, index * 20.0 - 100),
                fact(FinancialConcept.CapitalExpenditures, index, 20.0)
            )
        }
        val series = FundamentalDetector.freeCashFlowMarginSeries(facts)
        assertEquals(12, series.size)
        assertTrue(series.all { it.value.isFinite() })
        // -100 OCF less 20 capex, over 1000 revenue.
        assertEquals(-12.0, series.first().value)
    }

    @Test
    fun capexSignIsNormalised() {
        val positive = FundamentalDetector.freeCashFlowSeries(
            listOf(
                fact(FinancialConcept.OperatingCashFlow, 0, 500.0),
                fact(FinancialConcept.CapitalExpenditures, 0, 100.0)
            )
        )
        val negative = FundamentalDetector.freeCashFlowSeries(
            listOf(
                fact(FinancialConcept.OperatingCashFlow, 0, 500.0),
                fact(FinancialConcept.CapitalExpenditures, 0, -100.0)
            )
        )
        assertEquals(400.0, positive.first().value)
        assertEquals(
            400.0, negative.first().value,
            "A negative filing must not add capex to cash flow"
        )
    }

    // MARK: - Debt

    @Test
    fun debtStepIsDetected() {
        val levels = (0 until 20).map { 1_000 + (it % 3) * 5.0 }.toMutableList()
        levels[levels.size - 1] = 1_600.0

        val facts = levels.mapIndexed { index, value ->
            fact(FinancialConcept.TotalDebt, index, value, kind = FiscalPeriodKind.Instant)
        }
        val event = assertNotNull(FundamentalDetector.debtChange(facts))
        assertEquals(EventKind.DebtChange, event.kind)
        assertTrue(event.headline.contains("rose"))
    }

    @Test
    fun debtUsesInstants() {
        val facts = (0 until 20).map {
            fact(FinancialConcept.TotalDebt, it, 1_000.0, kind = FiscalPeriodKind.Quarter)
        }
        // Filed with a duration, these are not balance-sheet figures and must
        // not be treated as though they were.
        assertTrue(FundamentalDetector.instant(facts, FinancialConcept.TotalDebt).isEmpty())
    }

    // MARK: - Restatements

    private fun version(
        period: Instant,
        value: Double,
        accession: String,
        filedDays: Int,
        kind: FiscalPeriodKind = FiscalPeriodKind.Quarter
    ): FinancialFactDTO = FinancialFactDTO(
        concept = FinancialConcept.Revenue,
        periodStart = period - (if (kind == FiscalPeriodKind.Annual) 365 else 90).days,
        periodEnd = period,
        fiscalYear = 2020,
        fiscalQuarter = if (kind == FiscalPeriodKind.Annual) null else 1,
        isAnnual = kind == FiscalPeriodKind.Annual,
        periodKind = kind,
        value = value,
        unit = "USD",
        filedAt = period + filedDays.days,
        accessionNumber = accession
    )

    @Test
    fun restatementIsDetected() {
        val period = quarterEnd(4)
        val event = assertNotNull(
            FundamentalDetector.restatements(
                revisions = listOf(
                    version(period, 1_000.0, "original", 30),
                    version(period, 1_100.0, "amended", 200)
                ),
                concept = FinancialConcept.Revenue
            )
        )

        assertEquals(EventKind.FundamentalShift, event.kind)
        assertTrue(event.headline.contains("restated"))
        assertEquals(
            period + 200.days, event.occurredAt,
            "Dated to the amendment, which is when it became knowable"
        )
        // Reported, never characterised: an issuer restates for reasons ranging
        // from adopting a standard to correcting an error.
        assertEquals(0.0, event.unusualness)
    }

    @Test
    fun annualAndQuarterlyPeriodsAreNotConflated() {
        // Found by looking at a rendered screen, not by a test: Apple's Q4
        // FY2020 revenue of \$64.7B was being paired against its FY2020 revenue
        // of \$275B and reported as a +324% restatement. Both periods end on
        // 26 September 2020, and the grouping key was the date alone.
        val period = LocalDate(2020, 9, 26).atStartOfDayIn(zone)
        assertNull(
            FundamentalDetector.restatements(
                revisions = listOf(
                    version(period, 64_700.0, "10-K-q4", 30, FiscalPeriodKind.Quarter),
                    version(period, 274_500.0, "10-K-fy", 30, FiscalPeriodKind.Annual)
                ),
                concept = FinancialConcept.Revenue
            )
        )
    }

    @Test
    fun singleFilingIsNotRestatement() {
        val period = quarterEnd(4)
        assertNull(
            FundamentalDetector.restatements(
                revisions = listOf(version(period, 1_000.0, "only", 0)),
                concept = FinancialConcept.Revenue
            )
        )
    }

    @Test
    fun trivialRevisionIsIgnored() {
        val period = quarterEnd(4)
        assertNull(
            FundamentalDetector.restatements(
                revisions = listOf(
                    version(period, 1_000.0, "a", 0),
                    version(period, 1_001.0, "b", 0)
                ),
                concept = FinancialConcept.Revenue,
                minimumChangePercent = 1.0
            )
        )
    }

    // MARK: - Period handling

    @Test
    fun cumulativePeriodsExcluded() {
        val facts = listOf(
            fact(FinancialConcept.Revenue, 0, 100.0),
            fact(FinancialConcept.Revenue, 1, 110.0),
            // The nine-month running total an issuer files under the same tag.
            // Read as a quarter it makes Q3 look roughly three times Q2.
            fact(FinancialConcept.Revenue, 2, 330.0, kind = FiscalPeriodKind.NineMonth)
        )
        val series = FundamentalDetector.quarterly(facts, FinancialConcept.Revenue)
        assertEquals(listOf(100.0, 110.0), series.map { it.value })
    }

    @Test
    fun yearOverYearToleratesDrift() {
        // A 52/53-week calendar moves the closing date by a few days a year.
        val series = (0 until 8).map { index ->
            FundamentalDetector.SeriesPoint(
                quarterEnd(index, dayDrift = index), index.toDouble()
            )
        }
        val paired = FundamentalDetector.yearOverYear(series)
        assertEquals(4, paired.size, "Every quarter from the second year on has a match")
        assertEquals(0.0, paired.first().prior)
    }

    @Test
    fun yearOverYearRefusesDistantMatches() {
        // Two quarters two years apart. Pairing them would compare periods the
        // detector would then describe as year-over-year.
        val series = listOf(
            FundamentalDetector.SeriesPoint(quarterEnd(0), 100.0),
            FundamentalDetector.SeriesPoint(quarterEnd(8), 200.0)
        )
        assertTrue(FundamentalDetector.yearOverYear(series).isEmpty())
    }

    @Test
    fun marginNeedsBothLegs() {
        val facts = listOf(
            fact(FinancialConcept.Revenue, 0, 1_000.0),
            fact(FinancialConcept.GrossProfit, 1, 400.0)
        )
        assertTrue(
            FundamentalDetector.marginSeries(
                facts, numerator = FinancialConcept.GrossProfit
            ).isEmpty(),
            "Dividing a Q2 profit by Q1 revenue would produce a plausible wrong margin"
        )
    }

    @Test
    fun flatHistoryIsSilent() {
        // Deliberate, and inherited from the price detectors: with zero
        // dispersion every deviation is infinite, which would render an
        // absence of movement as an extraordinary event. Real filings always
        // wobble; a series that does not is synthetic.
        val margins = List(19) { 40.0 } + 35.0
        assertNull(
            FundamentalDetector.marginChange(
                marginFacts(FinancialConcept.GrossProfit, margins)
            )
        )
    }
}
