package com.tylerabitbol.libra.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.models.provenance.DataProviderID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The stored schema.
 *
 * SwiftData modelled these as an object graph: a `Security` owned arrays of
 * bars, facts and events, and a child reached its parent through a
 * `@Relationship`. Room does not traverse object graphs, so each child carries
 * the parent's `symbol` as a plain indexed column and every relationship in the
 * Swift code becomes a query. Nothing is lost — the graph was only ever walked
 * one way, parent to children, filtered by symbol.
 *
 * The append-only rule from `SnapshotStore` is unchanged: a restatement or a
 * re-quote inserts a row carrying its own `observedAt` rather than overwriting
 * the one before it.
 */

/**
 * A tradable security the user follows or has looked at.
 *
 * The one genuinely mutable row in the store — a company's name, sector or CIK
 * can be corrected and there is no research value in keeping the old spelling.
 * Everything that *describes* the company at a point in time is an append-only
 * observation elsewhere.
 */
@Entity(tableName = "securities")
data class Security(
    /** Ticker, uppercased. The natural key, so it is the primary key. */
    @PrimaryKey val symbol: String,
    val name: String,
    val exchange: String? = null,
    val sector: String? = null,
    val industry: String? = null,
    /**
     * SEC Central Index Key, zero-padded to 10 digits. Required for EDGAR
     * lookups and absent for non-US listings and most ETFs.
     */
    val cik: String? = null,
    val currency: String? = null,
    /**
     * Set for index/ETF rows used as benchmarks so they can be excluded from
     * company-style analysis that would be meaningless for them.
     */
    val isBenchmark: Boolean = false,
    val profileUpdatedAt: Instant? = null,
    val createdAt: Instant = Clock.System.now(),
    /**
     * When the user last opened this security's detail page.
     *
     * The reference point for "what changed since I last looked" (Section 4).
     * Deliberately distinct from the last *fetch*: the app may refresh a
     * watchlist row many times between visits, and a change the user has not
     * seen is still new to them. Null until the first visit, which is why
     * detectors report nothing rather than presenting a company's entire
     * history as new.
     */
    val lastViewedAt: Instant? = null
) {
    companion object {
        /** Mirrors the Swift initialiser, which uppercased on the way in. */
        fun create(symbol: String, name: String, isBenchmark: Boolean = false): Security =
            Security(symbol = symbol.uppercase(), name = name, isBenchmark = isBenchmark)
    }
}

/** A security's place on the user's watchlist, plus their own priority ordering. */
@Entity(tableName = "watchlist", indices = [Index("symbol", unique = true)])
data class WatchlistEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val addedAt: Instant = Clock.System.now(),
    /**
     * User-defined priority for the "user-defined priority" sort in Section 15.
     * Lower sorts first.
     */
    val priority: Int = 0,
    val notes: String? = null
)

/**
 * A point-in-time quote observation.
 *
 * Append-only. Each fetch inserts a new row rather than overwriting the last,
 * which is what makes "what did this look like when I last opened the app"
 * answerable without a separate snapshot mechanism.
 */
@Entity(tableName = "quotes", indices = [Index("symbol"), Index("symbol", "observedAt")])
data class QuoteObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    /**
     * When we recorded this. Distinct from [quoteTime], which is the exchange's
     * timestamp — they differ, and the gap matters for stale-data detection.
     */
    val observedAt: Instant = Clock.System.now(),
    val quoteTime: Instant? = null,
    val last: Double,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val previousClose: Double? = null,
    val volume: Double? = null,
    val providerRaw: String = DataProviderID.Finnhub.raw
) {
    val provider: DataProviderID
        get() = DataProviderID.fromRaw(providerRaw) ?: DataProviderID.Finnhub

    /**
     * Absolute and percentage change against the prior session's close.
     * Null when the provider didn't supply a previous close — the spec forbids
     * inventing a value to fill the gap.
     */
    val change: Double? get() = previousClose?.let { last - it }

    val changePercent: Double?
        get() = previousClose?.takeIf { it != 0.0 }?.let { (last - it) / it * 100 }
}

/**
 * A single reported financial figure for one period, as filed.
 *
 * Modelled as narrow rows rather than a wide "income statement" object on
 * purpose. SEC XBRL data arrives fact-by-fact, companies report different
 * subsets of concepts, and issuers restate prior periods. A row per fact lets
 * us store exactly what was reported, keep restatements alongside the
 * originals, and show "not available" honestly when a concept is absent —
 * rather than defaulting a missing field to zero, which Section 21 forbids.
 */
@Entity(
    tableName = "financial_facts",
    indices = [Index("symbol"), Index("symbol", "concept"), Index("accessionNumber")]
)
data class FinancialFactRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    /** Normalised concept name, e.g. "revenue", "netIncome". */
    val concept: String,
    /**
     * The raw taxonomy tag as filed, e.g. "us-gaap:RevenueFromContractWith…".
     * Kept so a surprising number can be traced back to the exact XBRL element.
     */
    val rawTag: String? = null,
    val periodStart: Instant? = null,
    val periodEnd: Instant,
    val fiscalYear: Int,
    /** 1–4, or null for annual figures. */
    val fiscalQuarter: Int? = null,
    val isAnnual: Boolean,
    val value: Double,
    val unit: String,
    /** When the figure reached the public record. */
    val filedAt: Instant? = null,
    /** Accession number of the filing it came from, linking to [FilingRecord]. */
    val accessionNumber: String? = null,
    /**
     * When we retrieved it. A restatement produces a second row for the same
     * period with a later [observedAt] — we never overwrite the original.
     */
    val observedAt: Instant = Clock.System.now(),
    val providerRaw: String = DataProviderID.SEC.raw
) {
    val provider: DataProviderID
        get() = DataProviderID.fromRaw(providerRaw) ?: DataProviderID.SEC
}

/**
 * An SEC filing. The spec treats EDGAR as a primary source, so every filing
 * keeps its original document URL — the app summarises, it never replaces the
 * document.
 */
@Entity(tableName = "filings", indices = [Index("symbol"), Index("symbol", "filedAt")])
data class FilingRecord(
    /** Unique in the schema, and a natural key, so it is the primary key. */
    @PrimaryKey val accessionNumber: String,
    val symbol: String,
    val formType: String,
    val filedAt: Instant,
    val periodOfReport: Instant? = null,
    val primaryDocumentURL: String? = null,
    val filingIndexURL: String? = null,
    val observedAt: Instant = Clock.System.now(),
    /**
     * Set once the app has extracted figures from this filing, so the
     * "what changed in this filing" work isn't repeated on every refresh.
     */
    val analyzedAt: Instant? = null
) {
    val isPeriodicReport: Boolean
        get() = formType in setOf("10-K", "10-Q", "20-F", "40-F")

    val isCurrentReport: Boolean get() = formType.startsWith("8-K")

    val isInsiderForm: Boolean get() = formType.trim() in setOf("3", "4", "5")
}

/**
 * A Form 4 insider transaction line.
 *
 * Section 10 requires distinguishing routine automatic sales from genuine
 * open-market decisions — conflating them is the single most common way
 * insider data gets misread.
 */
@Entity(
    tableName = "insider_transactions",
    indices = [Index("symbol"), Index("accessionNumber")]
)
data class InsiderTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val accessionNumber: String,
    val insiderName: String,
    val insiderTitle: String? = null,
    val isDirector: Boolean = false,
    val isOfficer: Boolean = false,
    val isTenPercentOwner: Boolean = false,
    val transactionDate: Instant,
    val filedAt: Instant,
    /**
     * Form 4 transaction code: P purchase, S sale, A grant/award, M option
     * exercise, F tax withholding, G gift, and others.
     */
    val transactionCode: String,
    /**
     * True when the filing footnotes a Rule 10b5-1 plan, meaning the trade was
     * scheduled in advance and carries no signal about current sentiment.
     */
    val isUnderTradingPlan: Boolean = false,
    val shares: Double? = null,
    val pricePerShare: Double? = null,
    val sharesOwnedAfter: Double? = null,
    val observedAt: Instant = Clock.System.now()
)

/**
 * A detected change worth the user's attention — the atom of the
 * "What Changed?" system in Section 4.
 *
 * Events are generated by deterministic detectors comparing the current state
 * against a prior snapshot. They are stored rather than recomputed so the app
 * can answer "what appeared since I last opened this" and so the user can
 * scroll back through a security's history of notable changes.
 */
@Entity(
    tableName = "events",
    indices = [Index("symbol"), Index("symbol", "naturalKey", unique = true)]
)
data class DetectedEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    /**
     * One event per kind per day per security. Stored rather than derived so
     * the uniqueness constraint can be enforced by the database, which is what
     * makes re-running detection over the same bars idempotent.
     */
    val naturalKey: String,
    val kindRaw: String,
    val detectedAt: Instant = Clock.System.now(),
    /**
     * When the underlying change actually happened, which may be well before
     * we noticed it.
     */
    val occurredAt: Instant,
    val headline: String,
    /** The objective supporting numbers, e.g. "Volume 1.9× 60-day average". */
    val detailLines: List<String> = emptyList(),
    /**
     * The "Why this matters" text from Section 4 — an explanation of the
     * relationships between the data, never a recommendation.
     */
    val context: String? = null,
    /**
     * 0–1. How unusual this is relative to the security's own history, used
     * for the "most unusual" sort. Not a measure of importance or of
     * direction, and explicitly not a prediction.
     */
    val unusualness: Double = 0.0,
    val isAcknowledged: Boolean = false,
    /** Serialised source references so an event can always be traced back. */
    val sourceDetails: List<String> = emptyList(),
    val sourceURLs: List<String> = emptyList(),
    /**
     * The arithmetic, encoded.
     *
     * Optional so an event that genuinely had no derivation — a filing, which
     * is a FACT — stays without one rather than acquiring an empty shell.
     */
    val derivationJSON: String? = null
) {
    val kind: EventKind get() = EventKind.fromRaw(kindRaw) ?: EventKind.Other
}

/** One observation of a FRED economic series. */
@Entity(
    tableName = "macro_observations",
    indices = [Index("seriesID"), Index("seriesID", "date", unique = true)]
)
data class MacroObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** FRED series ID, e.g. "CPIAUCSL", "UNRATE", "DFF". */
    val seriesID: String,
    val date: Instant,
    val value: Double,
    val observedAt: Instant = Clock.System.now()
)
