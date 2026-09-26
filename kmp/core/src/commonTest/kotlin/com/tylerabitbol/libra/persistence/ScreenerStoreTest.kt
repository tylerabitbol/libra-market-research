package com.tylerabitbol.libra.persistence

import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import com.tylerabitbol.libra.services.providers.QuoteDTO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * The screener reading from disk.
 *
 * The two cases held back from `ScreenerTest` in Phase 2, both of which need
 * the store: the screener's whole point is that it issues no requests.
 */
class ScreenerStoreTest {

    private val zone = TimeZone.UTC

    private fun withDb(body: suspend (LibraDatabase) -> Unit) = runTest {
        val db = inMemoryLibraDatabase()
        try {
            body(db)
        } finally {
            db.close()
        }
    }

    private fun fact(
        concept: FinancialConcept,
        index: Int,
        value: Double,
        kind: FiscalPeriodKind = FiscalPeriodKind.Quarter
    ): FinancialFactDTO {
        val end: Instant = LocalDate(
            2024 + index / 4, listOf(3, 6, 9, 12)[index % 4], 28
        ).atStartOfDayIn(zone)
        return FinancialFactDTO(
            concept = concept,
            periodStart = if (kind == FiscalPeriodKind.Instant) null else end - 90.days,
            periodEnd = end,
            fiscalYear = 2024 + index / 4,
            fiscalQuarter = index % 4 + 1,
            isAnnual = false,
            periodKind = kind,
            value = value,
            unit = "USD",
            filedAt = end,
            accessionNumber = "acc-${concept.raw}-$index"
        )
    }

    @Test
    fun benchmarksAreNotScreened() = withDb { db ->
        db.securities().upsert(Security(symbol = "AAPL", name = "Apple Inc."))
        db.securities().upsert(
            Security(
                symbol = "XLK", name = "Technology Select Sector SPDR", isBenchmark = true
            )
        )

        // A sector ETF is not a company; screening one on revenue growth would
        // return nothing while looking like a result.
        assertEquals(listOf("AAPL"), SnapshotStore(db).screenSubjects().map { it.symbol })
    }

    @Test
    fun storedFiguresPopulateSubjects() = withDb { db ->
        db.securities().upsert(Security(symbol = "TEST", name = "Test Corp"))
        val store = SnapshotStore(db)

        store.recordQuote(
            QuoteDTO(symbol = "TEST", last = 110.0, previousClose = 100.0),
            "TEST", DataProviderID.Finnhub
        )

        val facts = (0 until 8).map {
            fact(FinancialConcept.Revenue, it, if (it >= 4) 1_200.0 else 1_000.0)
        } + listOf(
            fact(FinancialConcept.TotalDebt, 7, 500.0, FiscalPeriodKind.Instant),
            fact(FinancialConcept.CashAndEquivalents, 7, 900.0, FiscalPeriodKind.Instant)
        )
        store.recordFacts(facts, "TEST", DataProviderID.SEC)

        val subject = assertNotNull(store.screenSubjects().firstOrNull())
        assertEquals(10.0, subject.dailyChangePercent)
        assertTrue(abs(assertNotNull(subject.revenueGrowth) - 20.0) < 0.001)
        assertEquals(400.0, subject.netCash)
    }
}
