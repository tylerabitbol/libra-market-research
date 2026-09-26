package com.tylerabitbol.libra.viewmodels

import com.tylerabitbol.libra.models.core.Benchmark
import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.persistence.LibraDatabase
import com.tylerabitbol.libra.persistence.Security
import com.tylerabitbol.libra.persistence.SnapshotStore
import com.tylerabitbol.libra.persistence.WatchlistEntry
import com.tylerabitbol.libra.persistence.inMemoryLibraDatabase
import com.tylerabitbol.libra.services.providers.QuoteDTO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Section 15: the watchlist as a triage surface rather than a list of tickers.
 *
 * The binding constraint is the request budget. Tiingo's free tier refills
 * roughly one token every 80 seconds, so anything that costs a request *per
 * row* makes this screen unusable on a list of any size. Every figure added
 * here comes from the store or from a benchmark quote shared across rows, and
 * the budget test below is what keeps it that way.
 */
class WatchlistIntelligenceTest {

    private fun row(
        symbol: String,
        change: Double? = null,
        unusualness: Double = 0.0,
        eventAt: Instant? = null,
        priority: Int = 0,
    ) = WatchlistRow(
        symbol = symbol,
        name = symbol,
        priority = priority,
        quote = change?.let {
            QuoteDTO(
                symbol = symbol, last = 100 * (1 + it / 100), open = null, high = null,
                low = null, previousClose = 100.0, volume = null, quoteTime = null,
            )
        },
        latestEvent = eventAt?.let {
            DetectedEventDTO(
                kind = EventKind.UnusualVolume, occurredAt = it,
                headline = "Volume spike", unusualness = unusualness,
            )
        },
    )

    private fun withDatabase(body: suspend (LibraDatabase) -> Unit) = runTest {
        val db = inMemoryLibraDatabase()
        try {
            body(db)
        } finally {
            db.close()
        }
    }

    // MARK: - Request budget

    @Test
    fun benchmarkCostDoesNotScaleWithRows() = withDatabase { db ->
        // Six companies across two sectors.
        val sectors = listOf(
            "Information Technology", "Information Technology", "Information Technology",
            "Financials", "Financials", "Financials",
        )
        sectors.forEachIndexed { index, sector ->
            db.securities().upsert(
                Security(symbol = "SYM$index", name = "Company $index", sector = sector),
            )
            db.watchlist().upsert(WatchlistEntry(symbol = "SYM$index"))
        }

        val log = CallLog()
        val model = WatchlistViewModel(CoroutineScope(Dispatchers.Default))
        model.refresh(db.watchlist().members(), stubRegistry(log), SnapshotStore(db))

        val symbols = log.symbols
        // Six rows, one market proxy, two distinct sectors. Not six sector
        // quotes, and above all not six history requests.
        assertEquals(0, log.bars, "One bars request per row would exhaust Tiingo's hourly quota")
        assertEquals(6, symbols.count { it.startsWith("SYM") })
        assertTrue(Benchmark.marketProxySymbol in symbols)
        assertEquals(
            setOf("XLK", "XLF"),
            symbols.filter { it in listOf("XLK", "XLF") }.toSet(),
        )
        assertEquals(
            9, symbols.size,
            "6 rows + 1 market + 2 sectors, got ${symbols.size}",
        )
    }

    // MARK: - Sorting

    @Test
    fun unusualDiffersFromLargest() {
        val now = Clock.System.now()
        val rows = listOf(
            row("QUIET", change = 2.0, unusualness = 0.99, eventAt = now),
            row("LOUD", change = 9.0, unusualness = 0.10, eventAt = now),
        )
        // A 2% day can be extreme for a utility and unremarkable for a biotech.
        // Ranking by size alone loses exactly that distinction.
        assertEquals(
            "QUIET",
            WatchlistViewModel.sorted(rows, WatchlistViewModel.SortOrder.MostUnusual)
                .first().symbol,
        )
        assertEquals(
            "LOUD",
            WatchlistViewModel.sorted(rows, WatchlistViewModel.SortOrder.BiggestChange)
                .first().symbol,
        )
        assertEquals(5, WatchlistViewModel.SortOrder.entries.size)
    }

    @Test
    fun missingEventSortsLast() {
        val rows = listOf(
            row("NONE"),
            row("SOME", eventAt = Instant.fromEpochSeconds(1_700_000_000)),
        )
        val sorted = WatchlistViewModel.sorted(
            rows, WatchlistViewModel.SortOrder.NewestInformation,
        )
        assertEquals("SOME", sorted.first().symbol)
    }

    @Test
    fun prioritySort() {
        val rows = listOf(row("B", priority = 0), row("A", priority = 0), row("C", priority = -1))
        val sorted = WatchlistViewModel.sorted(rows, WatchlistViewModel.SortOrder.Priority)
        assertEquals(listOf("C", "A", "B"), sorted.map { it.symbol })
    }

    // MARK: - Relative movement

    @Test
    fun relativeMovementArithmetic() {
        val subject = row("AAPL", change = 3.0).copy(marketPercent = 1.2, sectorPercent = 2.0)

        assertTrue(abs((subject.versusMarket ?: 0.0) - 1.8) < 0.0001)
        assertTrue(abs((subject.versusSector ?: 0.0) - 1.0) < 0.0001)
    }

    @Test
    fun missingBenchmarkIsAbsent() {
        val subject = row("AAPL", change = 3.0)
        // A benchmark that failed to load must not read as "moved exactly with
        // the market".
        assertNull(subject.versusMarket)
        assertNull(subject.versusSector)
    }
}
