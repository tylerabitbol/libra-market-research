package com.tylerabitbol.libra.viewmodels

import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import com.tylerabitbol.libra.services.providers.QuoteDTO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun fact(
    concept: FinancialConcept,
    year: Int,
    value: Double,
    kind: FiscalPeriodKind = FiscalPeriodKind.Annual,
): FinancialFactDTO = FinancialFactDTO(
    concept = concept,
    rawTag = "us-gaap:Test",
    periodStart = null,
    periodEnd = LocalDate(year, 12, 31).atStartOfDayIn(TimeZone.UTC),
    fiscalYear = year,
    fiscalQuarter = null,
    isAnnual = kind == FiscalPeriodKind.Annual,
    periodKind = kind,
    value = value,
    unit = "USD",
    filedAt = null,
    accessionNumber = "acc-$year",
)

private fun detail(): SecurityDetailViewModel =
    SecurityDetailViewModel("TEST", CoroutineScope(Dispatchers.Default))

class WatchlistOrderingTest {
    private fun row(symbol: String, change: Double?): WatchlistRow = WatchlistRow(
        symbol = symbol,
        name = symbol,
        quote = change?.let {
            QuoteDTO(
                symbol = symbol, last = 100 * (1 + it / 100), open = null, high = null,
                low = null, previousClose = 100.0, volume = null, quoteTime = null,
            )
        },
    )

    @Test
    fun biggestChangeUsesMagnitude() {
        val model = WatchlistViewModel(CoroutineScope(Dispatchers.Default))
        model.setSort(WatchlistViewModel.SortOrder.BiggestChange)
        val rows = listOf(row("A", 1.2), row("B", -8.4), row("C", 3.0))
        val sorted = WatchlistViewModel.sorted(rows, WatchlistViewModel.SortOrder.BiggestChange)
        assertEquals(
            listOf("B", "C", "A"), sorted.map { it.symbol },
            "This screen is about what moved, not about what went up",
        )
    }

    @Test
    fun missingQuoteSortsLast() {
        val rows = listOf(row("A", null), row("B", 2.0))
        val sorted = WatchlistViewModel.sorted(rows, WatchlistViewModel.SortOrder.BiggestChange)
        assertEquals("B", sorted.first().symbol)
    }

    @Test
    fun missingQuoteHasNoChange() {
        assertNull(row("A", null).changePercent)
    }
}

class SecurityDetailDerivedTest {
    @Test
    fun revenueGrowth() {
        val model = detail()
        model.applyFundamentalsForTesting(
            listOf(
                fact(FinancialConcept.Revenue, 2023, 100.0),
                fact(FinancialConcept.Revenue, 2024, 120.0),
                fact(FinancialConcept.Revenue, 2025, 150.0),
            ),
        )

        val annual = model.state.value.annualRevenue
        assertEquals(3, annual.size)
        assertEquals(2025, annual.first().fact.fiscalYear, "Newest first")

        val growth = assertNotNull(annual.first().growth)
        assertTrue(abs(growth - 25) < 0.001)
        // The earliest year has no prior year to compare against.
        assertNull(
            annual.last().growth,
            "Growth with no prior period must be absent, not zero",
        )
    }

    @Test
    fun freeCashFlowComputed() {
        val model = detail()
        model.applyFundamentalsForTesting(
            listOf(
                fact(FinancialConcept.OperatingCashFlow, 2025, 100_000.0),
                fact(FinancialConcept.CapitalExpenditures, 2025, 30_000.0),
            ),
        )
        val fcf = model.state.value.annualFreeCashFlow
        assertEquals(1, fcf.size)
        assertEquals(70_000.0, fcf.first().value)
    }

    @Test
    fun capexSignHandled() {
        val model = detail()
        model.applyFundamentalsForTesting(
            listOf(
                fact(FinancialConcept.OperatingCashFlow, 2025, 100_000.0),
                fact(FinancialConcept.CapitalExpenditures, 2025, -30_000.0),
            ),
        )
        // Issuers file capex positive; a negative filing must not add to FCF.
        assertEquals(70_000.0, model.state.value.annualFreeCashFlow.first().value)
    }

    @Test
    fun partialInputsYieldNothing() {
        val model = detail()
        model.applyFundamentalsForTesting(
            listOf(fact(FinancialConcept.OperatingCashFlow, 2025, 100_000.0)),
        )
        assertTrue(
            model.state.value.annualFreeCashFlow.isEmpty(),
            "Half a calculation is worse than none",
        )
    }

    @Test
    fun quarterlyExcludedFromAnnual() {
        val model = detail()
        model.applyFundamentalsForTesting(
            listOf(
                fact(FinancialConcept.Revenue, 2025, 150.0),
                fact(FinancialConcept.Revenue, 2025, 40.0, FiscalPeriodKind.Quarter),
            ),
        )
        assertEquals(1, model.state.value.annualRevenue.size)
        assertEquals(150.0, model.state.value.annualRevenue.first().fact.value)
    }

    @Test
    fun symbolNormalised() {
        assertEquals(
            "AAPL",
            SecurityDetailViewModel("aapl", CoroutineScope(Dispatchers.Default)).symbol,
        )
    }
}

class SortOptionTest {
    @Test
    fun noDuplicateSorts() {
        // "Most unusual" was once byte-identical to "Biggest change" — two menu
        // entries doing the same thing — and was withdrawn until the detectors
        // could tell them apart. They can now, so the guard tests the property
        // it always cared about rather than the absence of the option.
        val specs = listOf(
            Spec("DELTA", 1.0, 0.99, 4, 3),
            Spec("ALPHA", 9.0, 0.10, 1, 2),
            Spec("CHARLIE", 5.0, 0.50, 3, 0),
            Spec("BRAVO", 2.0, 0.75, 2, 1),
        )
        val rows = specs.mapIndexed { index, spec ->
            WatchlistRow(
                symbol = spec.symbol,
                name = spec.symbol,
                priority = spec.priority,
                quote = QuoteDTO(
                    symbol = spec.symbol, last = 100 * (1 + spec.change / 100), open = null,
                    high = null, low = null, previousClose = 100.0, volume = null,
                    quoteTime = null,
                ),
                latestEvent = DetectedEventDTO(
                    kind = EventKind.UnusualVolume,
                    occurredAt = Instant.fromEpochSeconds(
                        1_700_000_000 + spec.eventDay * 86_400L,
                    ),
                    headline = "Event $index",
                    unusualness = spec.unusualness,
                ),
            )
        }

        val orderings = WatchlistViewModel.SortOrder.entries.map { order ->
            WatchlistViewModel.sorted(rows, order).map { it.symbol }
        }
        assertEquals(
            WatchlistViewModel.SortOrder.entries.size, orderings.toSet().size,
            "Two menu entries producing the same order is worse than one honest entry",
        )
    }

    private data class Spec(
        val symbol: String,
        val change: Double,
        val unusualness: Double,
        val eventDay: Int,
        val priority: Int,
    )
}

/**
 * Annual figures keyed on the period they describe, not on the filing's fiscal
 * context. Observed on screen as two rows both labelled "2022", holding FY2022
 * and FY2021 revenue, and as free cash flow figures paired with dates two years
 * off.
 */
class AnnualPeriodKeyingTest {

    private fun fact(
        concept: FinancialConcept,
        periodEnd: String,
        fiscalYear: Int,
        value: Double,
        filed: String = "2026-01-01",
    ): FinancialFactDTO {
        fun parse(text: String) = LocalDate.parse(text).atStartOfDayIn(TimeZone.UTC)
        return FinancialFactDTO(
            concept = concept, rawTag = null, periodStart = null, periodEnd = parse(periodEnd),
            fiscalYear = fiscalYear, fiscalQuarter = null, isAnnual = true,
            periodKind = FiscalPeriodKind.Annual, value = value, unit = "USD",
            filedAt = parse(filed), accessionNumber = null,
        )
    }

    @Test
    fun distinctPeriodsSurvive() {
        // A restated FY2021 carries fy=2022 from the filing that restated it,
        // colliding with the genuine FY2022 in any fiscalYear-keyed map.
        val model = detail()
        model.applyFundamentalsForTesting(
            listOf(
                fact(FinancialConcept.Revenue, "2022-01-30", 2022, 26_914_000_000.0),
                fact(FinancialConcept.Revenue, "2021-01-31", 2022, 16_675_000_000.0),
            ),
        )

        val labels = model.state.value.annualRevenue.map { it.periodLabel }.toSet()
        assertEquals(setOf("2022", "2021"), labels, "Both rows previously rendered as 2022")
    }

    @Test
    fun freeCashFlowPairsCorrectly() {
        val model = detail()
        model.applyFundamentalsForTesting(
            listOf(
                fact(FinancialConcept.OperatingCashFlow, "2024-01-28", 2024, 28_090_000_000.0),
                fact(FinancialConcept.CapitalExpenditures, "2024-01-28", 2024, 1_069_000_000.0),
                fact(FinancialConcept.OperatingCashFlow, "2023-01-29", 2024, 5_641_000_000.0),
                fact(FinancialConcept.CapitalExpenditures, "2023-01-29", 2024, 1_833_000_000.0),
            ),
        )

        val points = model.state.value.annualFreeCashFlow
        assertEquals(2, points.size, "A shared fiscalYear previously collapsed these to one")

        val newest = assertNotNull(points.last())
        // 28.090B - 1.069B, matched to its own period end.
        assertTrue(abs(newest.value - 27_021_000_000.0) < 1)
        assertEquals(
            2024,
            newest.period.toLocalDateTime(TimeZone.currentSystemDefault()).year,
        )
    }

    @Test
    fun partialPeriodIsOmitted() {
        val model = detail()
        model.applyFundamentalsForTesting(
            listOf(
                fact(FinancialConcept.OperatingCashFlow, "2024-01-28", 2024, 28_090_000_000.0),
            ),
        )
        // Free cash flow without capex is not free cash flow.
        assertTrue(model.state.value.annualFreeCashFlow.isEmpty())
    }
}
