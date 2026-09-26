package com.tylerabitbol.libra.persistence

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.services.providers.PriceBarDTO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * What is already on disk, and what must not be trusted.
 *
 * The eviction half of Swift's `SyntheticDataTests.swift`. The other half
 * tests the mock provider registry, which arrives in Phase 6.
 */
class SyntheticRowEvictionTest {

    private val epoch = Instant.fromEpochSeconds(1_700_000_000)

    private fun withDb(body: suspend (LibraDatabase) -> Unit) = runTest {
        val db = inMemoryLibraDatabase()
        try {
            db.securities().upsert(Security(symbol = "TEST", name = "Test Corp"))
            body(db)
        } finally {
            db.close()
        }
    }

    private fun bar(day: Int, provider: DataProviderID? = null) = PriceBar(
        date = epoch + day.days, resolution = BarResolution.Daily,
        open = 100.0, high = 100.0, low = 100.0, close = 100.0,
        symbol = "TEST", provider = provider
    )

    @Test
    fun aRealBarKeepsTheNameOfTheProviderThatSuppliedIt() = withDb { db ->
        SnapshotStore(db).recordBars(
            listOf(
                PriceBarDTO(
                    date = epoch, open = 100.0, high = 100.0, low = 100.0, close = 100.0
                )
            ),
            "TEST", BarResolution.Daily, DataProviderID.Tiingo
        )

        val stored = assertNotNull(
            db.priceBars().all("TEST", BarResolution.Daily.raw).firstOrNull()
        )
        assertEquals(DataProviderID.Tiingo, stored.provider)
    }

    @Test
    fun unattributedBarsAreEvictedAndAttributedOnesKept() = withDb { db ->
        // What a keyless run left: no provider, because the column did not
        // exist when it was written.
        db.priceBars().insertAll((0 until 3).map { bar(it) })
        db.priceBars().insertAll(listOf(bar(9, DataProviderID.Tiingo)))

        db.evictSyntheticRows()

        val remaining = db.priceBars().all("TEST", BarResolution.Daily.raw)
        assertEquals(
            1, remaining.size,
            "A bar of unknown origin cannot be trusted or refetched around"
        )
        assertEquals(DataProviderID.Tiingo, remaining.first().provider)
    }

    @Test
    fun mockFilingsAndFormFourLinesGoByTheirImpossibleAccession() = withDb { db ->
        // EDGAR builds an accession from the filer's own ten-digit CIK, and no
        // filer has CIK zero. Only the mock SEC provider emits this.
        db.filings().insertAll(
            listOf(
                FilingRecord(
                    accessionNumber = "0000000000-00-000000", symbol = "TEST",
                    formType = "10-Q", filedAt = epoch
                ),
                FilingRecord(
                    accessionNumber = "0000320193-24-000123", symbol = "TEST",
                    formType = "10-K", filedAt = epoch
                )
            )
        )
        db.insiderTransactions().insertAll(
            listOf(
                InsiderTransaction(
                    symbol = "TEST", accessionNumber = "0000000000-00-900000",
                    insiderName = "Sample Insider 1", insiderTitle = "Director",
                    isDirector = true, transactionDate = epoch, filedAt = epoch,
                    transactionCode = "P"
                )
            )
        )

        db.evictSyntheticRows()

        assertEquals(
            listOf("0000320193-24-000123"),
            db.filings().recent("TEST", 50).map { it.accessionNumber }
        )
        assertTrue(db.insiderTransactions().all("TEST").isEmpty())
    }

    @Test
    fun mockFactsGoByTheSameImpossibleAccessionNumber() = withDb { db ->
        db.financialFacts().insertAll(
            listOf(
                FinancialFactRecord(
                    symbol = "TEST", concept = "revenue", periodEnd = epoch,
                    fiscalYear = 2024, isAnnual = false, value = 1_000.0, unit = "USD",
                    accessionNumber = "0000000000-00-000001"
                ),
                FinancialFactRecord(
                    symbol = "TEST", concept = "revenue", periodEnd = epoch,
                    fiscalYear = 2023, isAnnual = true, value = 2_000.0, unit = "USD",
                    accessionNumber = "0000320193-24-000123"
                )
            )
        )

        db.evictSyntheticRows()

        assertEquals(
            listOf("0000320193-24-000123"),
            db.financialFacts().all("TEST").map { it.accessionNumber }
        )
    }

    @Test
    fun evictionRemovesDerivedEventsOnly() = withDb { db ->
        db.securities().upsert(Security(symbol = "OTHER", name = "Other Corp"))
        db.priceBars().insertAll(listOf(bar(0)))
        db.events().insertAll(
            listOf(
                DetectedEvent(
                    symbol = "TEST", naturalKey = "a", kindRaw = "unusualPriceMove",
                    occurredAt = epoch, headline = "Fell 4.2%"
                ),
                DetectedEvent(
                    symbol = "TEST", naturalKey = "b", kindRaw = "marginChange",
                    occurredAt = epoch, headline = "Gross margin fell"
                ),
                DetectedEvent(
                    symbol = "OTHER", naturalKey = "c", kindRaw = "unusualPriceMove",
                    occurredAt = epoch, headline = "Rose 3.1%"
                )
            )
        )

        db.evictSyntheticRows()

        // The price move is re-detected from bars on the next visit, so
        // deleting it costs nothing permanent. The margin change is not
        // backfilled by anything, and the sample registry cannot produce
        // fundamentals in the first place — it stays.
        assertEquals(
            setOf("Gross margin fell", "Rose 3.1%"),
            db.events().recent(50).map { it.headline }.toSet()
        )
    }

    @Test
    fun evictionIsANoOpOnAStoreOfAttributedRows() = withDb { db ->
        db.priceBars().insertAll(listOf(bar(0, DataProviderID.Tiingo)))

        assertEquals(0, db.evictSyntheticRows())
        assertEquals(0, db.evictSyntheticRows())
        assertEquals(1, db.priceBars().all("TEST", BarResolution.Daily.raw).size)
    }

    @Test
    fun onlyTheSampleIdentityIsSynthetic() = withDb { _ ->
        assertTrue(DataProviderID.Sample.isSynthetic)
        for (real in listOf(
            DataProviderID.Finnhub, DataProviderID.Tiingo, DataProviderID.FRED,
            DataProviderID.SEC, DataProviderID.Computed
        )) {
            assertFalse(real.isSynthetic, "$real must not read as synthetic")
        }
    }
}
