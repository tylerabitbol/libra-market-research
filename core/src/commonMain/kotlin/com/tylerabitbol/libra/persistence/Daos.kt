package com.tylerabitbol.libra.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.tylerabitbol.libra.models.core.PriceBar
import kotlin.time.Instant

/**
 * One DAO per entity.
 *
 * Every query here replaces a SwiftData relationship traversal or a
 * `FetchDescriptor`. Where Swift fetched a parent and walked its children,
 * these filter on the child's `symbol` column directly, which is both the same
 * result and one fewer round trip.
 */

@Dao
interface SecurityDao {
    @Upsert
    suspend fun upsert(security: Security)

    @Query("SELECT * FROM securities WHERE symbol = :symbol")
    suspend fun find(symbol: String): Security?

    @Query("SELECT * FROM securities ORDER BY symbol")
    suspend fun all(): List<Security>

    @Query("SELECT * FROM securities WHERE isBenchmark = 0 ORDER BY symbol")
    suspend fun companies(): List<Security>

    @Query("SELECT lastViewedAt FROM securities WHERE symbol = :symbol")
    suspend fun lastViewedAt(symbol: String): Instant?

    @Query("UPDATE securities SET lastViewedAt = :at WHERE symbol = :symbol")
    suspend fun markViewed(symbol: String, at: Instant)
}

@Dao
interface WatchlistDao {
    @Upsert
    suspend fun upsert(entry: WatchlistEntry)

    @Query("SELECT * FROM watchlist ORDER BY priority, symbol")
    suspend fun all(): List<WatchlistEntry>

    @Query("SELECT * FROM watchlist WHERE symbol = :symbol")
    suspend fun find(symbol: String): WatchlistEntry?

    @Query("DELETE FROM watchlist WHERE symbol = :symbol")
    suspend fun remove(symbol: String)

    /**
     * The watchlist joined to the securities it names.
     *
     * SwiftData gave `WatchlistEntry.security` for free through the object
     * graph. Room's entities carry plain foreign-key columns — see
     * `KNOWN_ISSUES.md` — so the join is written out, and it is an inner join
     * because a watchlist row naming a security the store has never seen has
     * no name to render.
     */
    @Query(
        """
        SELECT w.symbol AS symbol, s.name AS name, s.sector AS sector,
               w.priority AS priority
        FROM watchlist w
        INNER JOIN securities s ON s.symbol = w.symbol
        ORDER BY w.priority, w.symbol
        """
    )
    suspend fun members(): List<WatchlistMember>
}

/**
 * A watchlist row with the identity the list needs to render before any
 * request: the name and sector from the store, and the user's own ordering.
 */
data class WatchlistMember(
    val symbol: String,
    val name: String,
    val sector: String? = null,
    val priority: Int = 0
)

@Dao
interface QuoteDao {
    @Insert
    suspend fun insert(quote: QuoteObservation)

    @Query("SELECT COUNT(*) FROM quotes WHERE symbol = :symbol")
    suspend fun count(symbol: String): Int

    @Query(
        "SELECT * FROM quotes WHERE symbol = :symbol AND observedAt < :before " +
            "ORDER BY observedAt DESC LIMIT 1"
    )
    suspend fun lastBefore(symbol: String, before: Instant): QuoteObservation?

    @Query("SELECT * FROM quotes WHERE symbol = :symbol ORDER BY observedAt DESC LIMIT 1")
    suspend fun latest(symbol: String): QuoteObservation?

    @Query("SELECT MAX(observedAt) FROM quotes WHERE symbol = :symbol")
    suspend fun latestObservedAt(symbol: String): Instant?
}

@Dao
interface PriceBarDao {
    @Insert
    suspend fun insertAll(bars: List<PriceBar>)

    /**
     * Only the session timestamps, for the duplicate check on write. Reading
     * whole rows to compare one column each would pull five years of bars into
     * memory to decide whether to add one.
     */
    @Query("SELECT date FROM price_bars WHERE symbol = :symbol AND resolution = :resolution")
    suspend fun existingDates(symbol: String, resolution: String): List<Instant>

    @Query(
        "SELECT * FROM price_bars WHERE symbol = :symbol AND resolution = :resolution " +
            "AND date >= :from AND date <= :to ORDER BY date"
    )
    suspend fun inRange(
        symbol: String,
        resolution: String,
        from: Instant,
        to: Instant
    ): List<PriceBar>

    @Query(
        "SELECT * FROM price_bars WHERE symbol = :symbol AND resolution = :resolution " +
            "ORDER BY date"
    )
    suspend fun all(symbol: String, resolution: String): List<PriceBar>

    @Query("SELECT MAX(observedAt) FROM price_bars WHERE symbol = :symbol")
    suspend fun latestObservedAt(symbol: String): Instant?

    /** The eviction path: a bar with no provider is of unknown origin. */
    @Query("SELECT DISTINCT symbol FROM price_bars WHERE provider IS NULL AND symbol IS NOT NULL")
    suspend fun unattributedSymbols(): List<String>

    @Query("DELETE FROM price_bars WHERE provider IS NULL")
    suspend fun deleteUnattributed(): Int
}

@Dao
interface FinancialFactDao {
    @Insert
    suspend fun insertAll(facts: List<FinancialFactRecord>)

    @Query("SELECT * FROM financial_facts WHERE symbol = :symbol")
    suspend fun all(symbol: String): List<FinancialFactRecord>

    @Query(
        "SELECT * FROM financial_facts WHERE symbol = :symbol AND concept = :concept " +
            "ORDER BY periodEnd"
    )
    suspend fun forConcept(symbol: String, concept: String): List<FinancialFactRecord>

    @Query("SELECT MAX(observedAt) FROM financial_facts WHERE symbol = :symbol")
    suspend fun latestObservedAt(symbol: String): Instant?

    @Query(
        "SELECT DISTINCT symbol FROM financial_facts WHERE accessionNumber LIKE :prefix || '%'"
    )
    suspend fun symbolsWithAccessionPrefix(prefix: String): List<String>

    @Query("DELETE FROM financial_facts WHERE accessionNumber LIKE :prefix || '%'")
    suspend fun deleteWithAccessionPrefix(prefix: String): Int
}

@Dao
interface FilingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(filings: List<FilingRecord>): List<Long>

    @Query("SELECT accessionNumber FROM filings WHERE symbol = :symbol")
    suspend fun existingAccessions(symbol: String): List<String>

    @Query("SELECT * FROM filings WHERE symbol = :symbol ORDER BY filedAt DESC LIMIT :limit")
    suspend fun recent(symbol: String, limit: Int): List<FilingRecord>

    @Query("SELECT MAX(observedAt) FROM filings WHERE symbol = :symbol")
    suspend fun latestObservedAt(symbol: String): Instant?

    @Query("SELECT DISTINCT symbol FROM filings WHERE accessionNumber LIKE :prefix || '%'")
    suspend fun symbolsWithAccessionPrefix(prefix: String): List<String>

    @Query("DELETE FROM filings WHERE accessionNumber LIKE :prefix || '%'")
    suspend fun deleteWithAccessionPrefix(prefix: String): Int
}

@Dao
interface InsiderTransactionDao {
    @Insert
    suspend fun insertAll(transactions: List<InsiderTransaction>)

    @Query("SELECT * FROM insider_transactions WHERE symbol = :symbol ORDER BY filedAt DESC")
    suspend fun all(symbol: String): List<InsiderTransaction>

    @Query("SELECT MAX(observedAt) FROM insider_transactions WHERE symbol = :symbol")
    suspend fun latestObservedAt(symbol: String): Instant?

    @Query(
        "SELECT DISTINCT symbol FROM insider_transactions " +
            "WHERE accessionNumber LIKE :prefix || '%'"
    )
    suspend fun symbolsWithAccessionPrefix(prefix: String): List<String>

    @Query("DELETE FROM insider_transactions WHERE accessionNumber LIKE :prefix || '%'")
    suspend fun deleteWithAccessionPrefix(prefix: String): Int
}

@Dao
interface EventDao {
    /**
     * IGNORE, not REPLACE. The unique index on (symbol, naturalKey) is what
     * makes re-running detection over the same bars idempotent, and REPLACE
     * would delete and reinsert the row — losing an acknowledgement the user
     * had already given it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<DetectedEvent>): List<Long>

    @Query("SELECT * FROM events WHERE symbol = :symbol ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun forSymbol(symbol: String, limit: Int): List<DetectedEvent>

    @Query("SELECT * FROM events ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DetectedEvent>

    @Query("SELECT * FROM events WHERE symbol = :symbol ORDER BY occurredAt DESC LIMIT 1")
    suspend fun latest(symbol: String): DetectedEvent?

    @Query(
        "UPDATE events SET isAcknowledged = 1 WHERE symbol = :symbol AND naturalKey = :naturalKey"
    )
    suspend fun acknowledge(symbol: String, naturalKey: String)

    @Query("SELECT * FROM events WHERE symbol = :symbol AND naturalKey = :naturalKey")
    suspend fun find(symbol: String, naturalKey: String): DetectedEvent?

    @Query("DELETE FROM events WHERE symbol IN (:symbols) AND kindRaw IN (:kinds)")
    suspend fun deleteRederivable(symbols: List<String>, kinds: List<String>): Int
}

@Dao
interface MacroDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(observations: List<MacroObservation>): List<Long>

    @Query("SELECT * FROM macro_observations WHERE seriesID = :seriesID ORDER BY date")
    suspend fun series(seriesID: String): List<MacroObservation>

    @Query("SELECT MAX(observedAt) FROM macro_observations WHERE seriesID = :seriesID")
    suspend fun latestObservedAt(seriesID: String): Instant?
}
