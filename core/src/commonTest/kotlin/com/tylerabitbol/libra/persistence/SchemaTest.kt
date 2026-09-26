package com.tylerabitbol.libra.persistence

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.DataProviderID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * That the schema opens, round-trips and converts.
 *
 * No Swift counterpart — SwiftData needed none of this proved. Room does: the
 * column types are chosen here rather than inferred, and a converter that
 * loses precision or sorts wrongly would corrupt every reading built on it
 * while looking entirely plausible.
 */
class SchemaTest {

    private val epoch = Instant.fromEpochSeconds(1_700_000_000)

    @Test
    fun theDatabaseOpensWithEveryTable() = runTest {
        val db = inMemoryLibraDatabase()
        try {
            // Touching each DAO is what proves the table exists; a missing
            // entity fails here rather than on first write in production.
            db.securities().all()
            db.watchlist().all()
            db.quotes().count("AAPL")
            db.priceBars().all("AAPL", BarResolution.Daily.raw)
            db.financialFacts().all("AAPL")
            db.filings().recent("AAPL", 10)
            db.insiderTransactions().all("AAPL")
            db.events().recent(10)
            db.macro().series("UNRATE")
        } finally {
            db.close()
        }
    }

    @Test
    fun aSecurityRoundTrips() = runTest {
        val db = inMemoryLibraDatabase()
        try {
            db.securities().upsert(
                Security(symbol = "AAPL", name = "Apple Inc.", cik = "0000320193")
            )
            val found = assertNotNull(db.securities().find("AAPL"))
            assertEquals("Apple Inc.", found.name)
            assertEquals("0000320193", found.cik)
            assertNull(found.lastViewedAt)
        } finally {
            db.close()
        }
    }

    @Test
    fun instantsKeepSubSecondPrecision() = runTest {
        // Milliseconds would have been the obvious column type and would have
        // silently truncated this, making a round-tripped timestamp compare
        // unequal to the one written.
        val db = inMemoryLibraDatabase()
        try {
            val exact = Instant.fromEpochSeconds(1_700_000_000, 123_456_789)
            db.securities().upsert(Security(symbol = "T", name = "T", createdAt = exact))
            assertEquals(exact, assertNotNull(db.securities().find("T")).createdAt)
        } finally {
            db.close()
        }
    }

    @Test
    fun instantsSortInTimeOrderNotTextOrder() = runTest {
        // The reason the column is a number. As ISO-8601 text, "…:20.5Z" sorts
        // below "…:20Z" because '.' precedes 'Z', so every ORDER BY on a
        // timestamp would have been subtly wrong.
        val db = inMemoryLibraDatabase()
        try {
            val whole = Instant.fromEpochSeconds(1_700_000_020)
            val fractional = Instant.fromEpochSeconds(1_700_000_020, 500_000_000)
            db.securities().upsert(Security(symbol = "X", name = "X"))
            db.quotes().insert(QuoteObservation(symbol = "X", observedAt = whole, last = 1.0))
            db.quotes().insert(
                QuoteObservation(symbol = "X", observedAt = fractional, last = 2.0)
            )

            assertEquals(2.0, assertNotNull(db.quotes().latest("X")).last)
        } finally {
            db.close()
        }
    }

    @Test
    fun enumsAreStoredByRawValueNotOrdinal() = runTest {
        val db = inMemoryLibraDatabase()
        try {
            db.securities().upsert(Security(symbol = "X", name = "X"))
            db.priceBars().insertAll(
                listOf(
                    PriceBar(
                        date = epoch, resolution = BarResolution.FiveMinute,
                        open = 1.0, high = 2.0, low = 0.5, close = 1.5,
                        symbol = "X", provider = DataProviderID.Alpaca
                    )
                )
            )
            val bars = db.priceBars().all("X", BarResolution.FiveMinute.raw)
            assertEquals(1, bars.size)
            assertEquals(BarResolution.FiveMinute, bars.first().resolution)
            assertEquals(DataProviderID.Alpaca, bars.first().provider)
            // The daily series is a different series, not the same rows.
            assertEquals(0, db.priceBars().all("X", BarResolution.Daily.raw).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun stringListsRoundTrip() = runTest {
        val db = inMemoryLibraDatabase()
        try {
            db.securities().upsert(Security(symbol = "X", name = "X"))
            db.events().insertAll(
                listOf(
                    DetectedEvent(
                        symbol = "X", naturalKey = "k", kindRaw = "unusualVolume",
                        occurredAt = epoch, headline = "Volume 3.0×",
                        detailLines = listOf("one", "two, with a comma"),
                        sourceDetails = listOf("Daily bars"),
                        sourceURLs = listOf("https://example.test/a")
                    )
                )
            )
            val stored = assertNotNull(db.events().find("X", "k"))
            assertEquals(listOf("one", "two, with a comma"), stored.detailLines)
            assertEquals(listOf("https://example.test/a"), stored.sourceURLs)
        } finally {
            db.close()
        }
    }

    @Test
    fun theNaturalKeyMakesAnEventIdempotent() = runTest {
        // The constraint the whole re-detection story rests on: running the
        // detectors again over the same bars must not multiply rows.
        val db = inMemoryLibraDatabase()
        try {
            db.securities().upsert(Security(symbol = "X", name = "X"))
            val event = DetectedEvent(
                symbol = "X", naturalKey = "unusualVolume|2023-11-14",
                kindRaw = "unusualVolume", occurredAt = epoch, headline = "first"
            )
            db.events().insertAll(listOf(event))
            db.events().insertAll(listOf(event.copy(headline = "reworded")))

            val all = db.events().forSymbol("X", 50)
            assertEquals(1, all.size)
            assertEquals("first", all.first().headline, "IGNORE, not REPLACE")
        } finally {
            db.close()
        }
    }

    @Test
    fun anAcknowledgementSurvivesRedetection() = runTest {
        // Why the conflict strategy is IGNORE: REPLACE deletes and reinserts,
        // which would quietly un-acknowledge an event the user had dismissed.
        val db = inMemoryLibraDatabase()
        try {
            db.securities().upsert(Security(symbol = "X", name = "X"))
            val event = DetectedEvent(
                symbol = "X", naturalKey = "k", kindRaw = "unusualVolume",
                occurredAt = epoch, headline = "Volume 3.0×"
            )
            db.events().insertAll(listOf(event))
            db.events().acknowledge("X", "k")
            db.events().insertAll(listOf(event))

            assertEquals(true, assertNotNull(db.events().find("X", "k")).isAcknowledged)
        } finally {
            db.close()
        }
    }

    @Test
    fun barsAreRangeQueriedByDate() = runTest {
        val db = inMemoryLibraDatabase()
        try {
            db.securities().upsert(Security(symbol = "X", name = "X"))
            db.priceBars().insertAll(
                (0 until 10).map { index ->
                    PriceBar(
                        date = epoch + index.days, open = 1.0, high = 1.0, low = 1.0,
                        close = 1.0 + index, symbol = "X", provider = DataProviderID.Tiingo
                    )
                }
            )
            val window = db.priceBars().inRange(
                "X", BarResolution.Daily.raw, epoch + 2.days, epoch + 4.days
            )
            assertEquals(3, window.size)
            assertEquals(listOf(3.0, 4.0, 5.0), window.map { it.close })
        } finally {
            db.close()
        }
    }
}
