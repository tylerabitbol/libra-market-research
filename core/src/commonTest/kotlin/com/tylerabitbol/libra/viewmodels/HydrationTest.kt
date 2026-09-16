package com.tylerabitbol.libra.viewmodels

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.persistence.FinancialFactRecord
import com.tylerabitbol.libra.persistence.LibraDatabase
import com.tylerabitbol.libra.persistence.Security
import com.tylerabitbol.libra.persistence.SnapshotStore
import com.tylerabitbol.libra.persistence.WatchlistEntry
import com.tylerabitbol.libra.persistence.inMemoryLibraDatabase
import com.tylerabitbol.libra.services.mock.SampleData
import com.tylerabitbol.libra.services.providers.CompanyProfileDTO
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import com.tylerabitbol.libra.services.providers.FundamentalsProvider
import com.tylerabitbol.libra.services.providers.InsiderTransactionDTO
import com.tylerabitbol.libra.services.providers.MarketDataProvider
import com.tylerabitbol.libra.services.providers.PriceBarDTO
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import com.tylerabitbol.libra.services.providers.QuoteDTO
import com.tylerabitbol.libra.services.providers.SECDataProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Reading the store back before reaching for the network.
 *
 * The store used to be write-only: bars, facts and filings were recorded on
 * every visit and never read. So every visit re-fetched five years of history
 * it already held — against Tiingo's 50-requests-per-hour budget — and a
 * security that had been opened a hundred times still showed nothing offline.
 *
 * These tests pin the two properties that follow from fixing it: a fresh held
 * copy costs no request, and a failed fetch degrades to the last good copy
 * instead of to an empty page.
 */
@OptIn(ExperimentalAtomicApi::class)
private class CallLog {
    private val quoteCount = AtomicInt(0)
    private val barCount = AtomicInt(0)
    val quotes: Int get() = quoteCount.load()
    val bars: Int get() = barCount.load()
    fun recordQuote() { quoteCount.fetchAndIncrement() }
    fun recordBars() { barCount.fetchAndIncrement() }
}

/** Counts what it is asked for, and can be told to fail — the offline case. */
private class StubMarketProvider(
    val log: CallLog,
    val isOffline: Boolean = false,
) : MarketDataProvider {
    override val id: DataProviderID = DataProviderID.Finnhub

    override suspend fun isConfigured(): Boolean = true

    override suspend fun quote(symbol: String): QuoteDTO {
        log.recordQuote()
        if (isOffline) throw APIError.Transport(DataProviderID.Finnhub, "offline")
        return QuoteDTO(
            symbol = symbol, last = 200.0, open = null, high = null, low = null,
            previousClose = 190.0, volume = null, quoteTime = Clock.System.now(),
        )
    }

    override suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        log.recordBars()
        if (isOffline) throw APIError.Transport(DataProviderID.Tiingo, "offline")
        return SampleData.bars(symbol, resolution, from, to)
    }

    override suspend fun profile(symbol: String): CompanyProfileDTO =
        throw APIError.NotFound(DataProviderID.Finnhub, "profile")

    override suspend fun search(query: String): List<CompanyProfileDTO> = emptyList()
}

private fun registry(log: CallLog, offline: Boolean = false) = ProviderRegistry(
    marketData = StubMarketProvider(log, offline),
    fundamentals = null, analyst = null, metrics = null, sec = null,
    macro = null, news = null, isUsingSampleData = false,
)

private fun bar(day: Int, close: Double) = PriceBarDTO(
    date = Instant.fromEpochSeconds(1_700_000_000 + day * 86_400L),
    open = close, high = close, low = close, close = close,
    volume = 1_000.0, adjustedClose = close,
)

class HydrationTest {

    private fun withDatabase(body: suspend (LibraDatabase) -> Unit) = runTest {
        val db = inMemoryLibraDatabase()
        try {
            db.securities().upsert(Security(symbol = "TEST", name = "Test Corp"))
            db.watchlist().upsert(WatchlistEntry(symbol = "TEST"))
            body(db)
        } finally {
            db.close()
        }
    }

    private fun model() = SecurityDetailViewModel("TEST", CoroutineScope(Dispatchers.Default))

    // MARK: - Detail page

    @Test
    fun freshBarsAreNotRefetched() = withDatabase { db ->
        val store = SnapshotStore(db)
        store.recordBars(
            (0 until 5).map { bar(it, 100.0 + it) },
            "TEST", BarResolution.Daily, DataProviderID.Tiingo,
        )

        val log = CallLog()
        val model = model()
        model.load(registry(log), store)
        model.awaitLoad()

        assertEquals(
            0, log.bars,
            "Bars observed moments ago are current; refetching buys nothing",
        )
        assertEquals(5, model.state.value.bars.size, "And the page is populated regardless")
    }

    @Test
    fun staleBarsAreRefetched() = withDatabase { db ->
        // Inserted directly so `observedAt` can be backdated past the six-hour
        // daily-bar window; the store always stamps its own writes with now.
        val old = Clock.System.now() - 48.hours
        db.priceBars().insertAll(
            listOf(
                PriceBar(
                    date = old, resolution = BarResolution.Daily, open = 100.0, high = 100.0,
                    low = 100.0, close = 100.0, volume = 1_000.0, adjustedClose = 100.0,
                    symbol = "TEST", observedAt = old, provider = DataProviderID.Tiingo,
                ),
            ),
        )

        val log = CallLog()
        val model = model()
        model.load(registry(log), SnapshotStore(db))
        model.awaitLoad()

        assertEquals(1, log.bars, "A two-day-old copy of a daily series is stale")
    }

    @Test
    fun forcedRefreshIgnoresFreshness() = withDatabase { db ->
        val store = SnapshotStore(db)
        store.recordBars(
            (0 until 5).map { bar(it, 100.0) },
            "TEST", BarResolution.Daily, DataProviderID.Tiingo,
        )

        val log = CallLog()
        val model = model()
        // Pull-to-refresh means "I want current data", not "check whether you
        // think it is current".
        model.load(registry(log), store, force = true)
        model.awaitLoad()

        assertEquals(1, log.bars)
    }

    @Test
    fun offlineFallsBackToStoredCopy() = withDatabase { db ->
        val store = SnapshotStore(db)
        store.recordBars(
            (0 until 5).map { bar(it, 100.0 + it) },
            "TEST", BarResolution.Daily, DataProviderID.Tiingo,
        )

        val log = CallLog()
        val model = model()
        model.load(registry(log, offline = true), store)
        model.awaitLoad()

        val state = model.state.value
        assertEquals(
            5, state.bars.size,
            "A failed request must not empty a page the store can fill",
        )
        assertTrue(state.hydratedFromStore)
        assertTrue(state.isShowingSavedCopy, "And the user is told what they are looking at")
        // Falls back to the last close, which the saved-copy notice dates.
        assertEquals(104.0, state.displayPrice)
        // The change comes from the last two stored closes, 103 → 104.
        val expected = (104.0 - 103.0) / 103.0 * 100
        assertTrue(abs((state.displayChangePercent ?: 0.0) - expected) < 0.0001)
    }

    @Test
    fun offlineWithNothingStoredIsHonest() = withDatabase { db ->
        val log = CallLog()
        val model = model()
        model.load(registry(log, offline = true), SnapshotStore(db))
        model.awaitLoad()

        val state = model.state.value
        assertTrue(state.bars.isEmpty())
        assertFalse(state.isShowingSavedCopy, "There is no saved copy to show")
        assertNull(state.displayPrice, "And no price may be invented")
    }

    @Test
    fun noStoreStillLoads() = runTest {
        val log = CallLog()
        val model = SecurityDetailViewModel("TEST", CoroutineScope(Dispatchers.Default))
        model.load(registry(log), snapshots = null)
        model.awaitLoad()

        assertEquals(1, log.bars)
        assertFalse(model.state.value.hydratedFromStore)
    }

    // MARK: - Watchlist

    @Test
    fun watchlistRowUsesStoredQuote() = withDatabase { db ->
        val store = SnapshotStore(db)
        store.recordQuote(
            QuoteDTO(
                symbol = "TEST", last = 150.0, open = null, high = null, low = null,
                previousClose = 100.0, volume = null, quoteTime = null,
            ),
            "TEST", DataProviderID.Finnhub,
        )

        val model = WatchlistViewModel(CoroutineScope(Dispatchers.Default))
        model.refresh(db.watchlist().members(), registry(CallLog(), offline = true), store)

        val row = assertNotNull(model.state.value.rows.firstOrNull())
        // A price from an hour ago beats a column of error text: the refresh
        // failing does not make the last known price untrue, only old.
        assertEquals(150.0, row.last)
        assertEquals(50.0, row.changePercent)
        assertTrue(row.isStoredCopy)
        assertNotNull(row.asOf, "And it is dated, so it cannot pass for live")
    }

    @Test
    fun liveQuoteWinsOverStored() = withDatabase { db ->
        val store = SnapshotStore(db)
        store.recordQuote(
            QuoteDTO(
                symbol = "TEST", last = 150.0, open = null, high = null, low = null,
                previousClose = 100.0, volume = null, quoteTime = null,
            ),
            "TEST", DataProviderID.Finnhub,
        )

        val model = WatchlistViewModel(CoroutineScope(Dispatchers.Default))
        model.refresh(db.watchlist().members(), registry(CallLog()), store)

        val row = assertNotNull(model.state.value.rows.firstOrNull())
        assertEquals(200.0, row.last, "The stub's live quote, not the stored 150")
        assertFalse(row.isStoredCopy)
        assertNull(row.asOf)
    }
}

// MARK: - Fundamental detection through the page

private class StubFundamentalsProvider(val facts: List<FinancialFactDTO>) : FundamentalsProvider {
    override val id: DataProviderID = DataProviderID.SEC
    override suspend fun isConfigured(): Boolean = true
    override suspend fun facts(
        symbol: String,
        cik: String?,
        concepts: List<FinancialConcept>,
        since: Instant?,
    ): List<FinancialFactDTO> = facts
}

private class StubSECProvider : SECDataProvider {
    override val id: DataProviderID = DataProviderID.SEC
    override suspend fun isConfigured(): Boolean = true
    override suspend fun resolveCIK(symbol: String): String = "0000000320"
    override suspend fun filings(
        cik: String,
        formTypes: List<String>,
        limit: Int,
    ): List<FilingDTO> = emptyList()

    override suspend fun insiderTransactions(
        cik: String,
        since: Instant?,
    ): List<InsiderTransactionDTO> = emptyList()
}

/** The detectors reaching the screen, not just passing in isolation. */
class FundamentalEventWiringTest {

    private fun quarterEnd(index: Int): Instant {
        val year = 2019 + index / 4
        val month = listOf(3, 6, 9, 12)[index % 4]
        val day = listOf(31, 30, 30, 31)[index % 4]
        return LocalDate(year, month, day).atStartOfDayIn(TimeZone.UTC)
    }

    private fun fact(
        concept: FinancialConcept,
        index: Int,
        value: Double,
        accession: String? = null,
        filedDays: Int = 30,
    ): FinancialFactDTO {
        val end = quarterEnd(index)
        return FinancialFactDTO(
            concept = concept, rawTag = null,
            periodStart = end - 90.days, periodEnd = end,
            fiscalYear = 2019 + index / 4, fiscalQuarter = index % 4 + 1,
            isAnnual = false, periodKind = FiscalPeriodKind.Quarter, value = value,
            unit = "USD", filedAt = end + filedDays.days,
            accessionNumber = accession ?: "acc-$index",
        )
    }

    /** Twenty quarters of stable gross margin, breaking in the last one. */
    private val breakingMarginFacts: List<FinancialFactDTO>
        get() {
            val margins = listOf(
                40.0, 40.2, 39.8, 40.1, 40.3, 39.9, 40.0, 40.2, 40.1, 39.8,
                40.2, 40.0, 39.9, 40.3, 40.1, 40.0, 40.2, 39.9, 40.1, 34.0,
            )
            return margins.flatMapIndexed { index, margin ->
                listOf(
                    fact(FinancialConcept.Revenue, index, 1_000.0),
                    fact(FinancialConcept.GrossProfit, index, 1_000 * margin / 100),
                )
            }
        }

    private fun factRegistry(facts: List<FinancialFactDTO>) = ProviderRegistry(
        marketData = StubMarketProvider(CallLog(), isOffline = true),
        fundamentals = StubFundamentalsProvider(facts),
        analyst = null, metrics = null, sec = StubSECProvider(),
        macro = null, news = null, isUsingSampleData = false,
    )

    private fun withDatabase(body: suspend (LibraDatabase) -> Unit) = runTest {
        val db = inMemoryLibraDatabase()
        try {
            db.securities().upsert(Security(symbol = "TEST", name = "Test Corp"))
            body(db)
        } finally {
            db.close()
        }
    }

    private fun model() = SecurityDetailViewModel("TEST", CoroutineScope(Dispatchers.Default))

    @Test
    fun marginEventReachesThePage() = withDatabase { db ->
        val model = model()
        model.load(factRegistry(breakingMarginFacts), SnapshotStore(db))
        model.awaitLoad()

        assertTrue(
            model.state.value.events.any { it.kind == EventKind.MarginChange },
            "The detectors are wired into the page, not only unit-tested",
        )
    }

    @Test
    fun restatementIsCaughtImmediately() = withDatabase { db ->
        val store = SnapshotStore(db)

        // The figure as originally reported, on disk from a visit three days
        // ago. Inserted directly so `observedAt` can be backdated: recorded
        // through the store it would be fresh, the staleness gate would
        // correctly skip the refetch, and the amendment would never arrive.
        val original = breakingMarginFacts +
            fact(FinancialConcept.Revenue, 20, 1_000.0, accession = "original")
        val threeDaysAgo = Clock.System.now() - 3.days
        db.financialFacts().insertAll(
            original.map { stored ->
                FinancialFactRecord(
                    symbol = "TEST", concept = stored.concept.raw, rawTag = stored.rawTag,
                    periodStart = stored.periodStart, periodEnd = stored.periodEnd,
                    fiscalYear = stored.fiscalYear, fiscalQuarter = stored.fiscalQuarter,
                    isAnnual = stored.isAnnual, value = stored.value, unit = stored.unit,
                    filedAt = stored.filedAt, accessionNumber = stored.accessionNumber,
                    observedAt = threeDaysAgo,
                )
            },
        )

        // The same period, refiled with a different number.
        val amended = breakingMarginFacts +
            fact(FinancialConcept.Revenue, 20, 1_150.0, accession = "amended", filedDays = 200)

        val model = model()
        model.load(factRegistry(amended), store)
        model.awaitLoad()

        // Detection runs before persistence, so reading the store alone would
        // miss the amendment until the next visit.
        assertTrue(
            model.state.value.events.any { it.kind == EventKind.FundamentalShift },
            "A restatement must not be delayed by one visit",
        )
    }

    @Test
    fun noFactsMeansNoEvents() = withDatabase { db ->
        val model = model()
        model.load(factRegistry(emptyList()), SnapshotStore(db))
        model.awaitLoad()

        val events = model.state.value.events
        assertFalse(events.any { it.kind == EventKind.MarginChange })
        assertFalse(events.any { it.kind == EventKind.FundamentalShift })
    }
}
