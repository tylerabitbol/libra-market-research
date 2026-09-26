package com.tylerabitbol.libra.persistence

import co.touchlab.kermit.Logger
import com.tylerabitbol.libra.calculations.FundamentalDetector
import com.tylerabitbol.libra.calculations.ScreenSubject
import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.services.providers.FactPeriods
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import com.tylerabitbol.libra.services.providers.InsiderTransactionDTO
import com.tylerabitbol.libra.services.providers.PriceBarDTO
import com.tylerabitbol.libra.services.providers.QuoteDTO
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json

/**
 * Writes observations into the append-only store.
 *
 * Two rules govern everything here:
 *
 * - **Append, never update.** A restatement or a re-quote inserts a new row
 *   carrying its own `observedAt`. Overwriting would destroy exactly the
 *   record that makes historical comparison possible (Section 17).
 * - **Never synthetic.** Every write names the provider it came from, and a
 *   provider that invents its numbers is refused. A sample bar that reaches
 *   disk is indistinguishable from a real one afterwards, and the hydration
 *   path will then serve it in preference to fetching the real thing.
 * - **Idempotent on natural keys.** Refreshing a screen must not multiply
 *   rows. Observations that are genuinely new-in-time (quotes) always insert;
 *   observations describing a fixed past fact (a bar for a given session, a
 *   filing) insert once.
 *
 * Swift made this a `@ModelActor` so writes happened off the main actor. Room
 * already runs its queries on the dispatcher the database was built with, so
 * every method here is simply `suspend` and no actor is needed.
 *
 * The writes are named `recordBars`, `recordFacts` and so on rather than six
 * overloads of `record`. Swift distinguished them by argument label; on the
 * JVM the list element type erases, so `record(List<PriceBarDTO>, …)` and
 * `record(List<FilingDTO>, …)` are the same signature. The names carry what
 * the labels did.
 */
class SnapshotStore(private val db: LibraDatabase) {

    private val logger = Logger.withTag("persistence")
    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - Quotes

    /**
     * Records a quote observation. Always inserts: a quote is a reading at a
     * moment, and two readings of the same price at different times are two
     * facts, not one.
     */
    suspend fun recordQuote(quote: QuoteDTO, symbol: String, provider: DataProviderID) {
        if (!accepts(provider, "quote", symbol)) return
        val security = security(symbol) ?: return
        db.quotes().insert(
            QuoteObservation(
                symbol = security.symbol,
                quoteTime = quote.quoteTime,
                last = quote.last,
                open = quote.open,
                high = quote.high,
                low = quote.low,
                previousClose = quote.previousClose,
                volume = quote.volume,
                providerRaw = provider.raw
            )
        )
    }

    // MARK: - Price bars

    /**
     * Records bars, skipping sessions already stored.
     *
     * A bar describes a closed session and does not change, so re-fetching a
     * range must not duplicate it. Existing dates are read once and compared
     * in memory rather than issuing a query per bar.
     */
    suspend fun recordBars(
        bars: List<PriceBarDTO>,
        symbol: String,
        resolution: BarResolution,
        provider: DataProviderID
    ): Int {
        if (!accepts(provider, "bars", symbol)) return 0
        if (bars.isEmpty()) return 0
        val security = security(symbol) ?: return 0

        val existing = db.priceBars().existingDates(security.symbol, resolution.raw).toSet()
        val fresh = bars.filter { it.date !in existing }.map { bar ->
            PriceBar(
                date = bar.date,
                resolution = resolution,
                open = bar.open,
                high = bar.high,
                low = bar.low,
                close = bar.close,
                volume = bar.volume,
                adjustedClose = bar.adjustedClose,
                symbol = security.symbol,
                observedAt = Clock.System.now(),
                provider = provider
            )
        }
        if (fresh.isEmpty()) return 0
        db.priceBars().insertAll(fresh)
        return fresh.size
    }

    // MARK: - Fundamentals

    /**
     * Records reported figures, keeping restatements alongside originals.
     *
     * A fact is considered already-stored only when the same concept, period
     * and accession number are present. A new accession for a period the app
     * already holds is a restatement and is inserted, which is what allows
     * "what did this look like before it was corrected".
     */
    suspend fun recordFacts(
        facts: List<FinancialFactDTO>,
        symbol: String,
        provider: DataProviderID
    ): Int {
        if (!accepts(provider, "financial facts", symbol)) return 0
        if (facts.isEmpty()) return 0
        val security = security(symbol) ?: return 0

        val existing = db.financialFacts().all(security.symbol).map { factKey(it) }.toSet()
        val fresh = facts.filter { factKey(it) !in existing }.map { fact ->
            FinancialFactRecord(
                symbol = security.symbol,
                concept = fact.concept.raw,
                rawTag = fact.rawTag,
                periodStart = fact.periodStart,
                periodEnd = fact.periodEnd,
                fiscalYear = fact.fiscalYear,
                fiscalQuarter = fact.fiscalQuarter,
                isAnnual = fact.isAnnual,
                value = fact.value,
                unit = fact.unit,
                filedAt = fact.filedAt,
                accessionNumber = fact.accessionNumber
            )
        }
        if (fresh.isEmpty()) return 0
        db.financialFacts().insertAll(fresh)
        return fresh.size
    }

    // MARK: - Filings

    /**
     * Records filings. `accessionNumber` is the primary key, so a duplicate
     * insert would fail — existing rows are filtered out first rather than
     * relying on the constraint to catch them.
     */
    suspend fun recordFilings(
        filings: List<FilingDTO>,
        symbol: String,
        provider: DataProviderID
    ): Int {
        if (!accepts(provider, "filings", symbol)) return 0
        if (filings.isEmpty()) return 0
        val security = security(symbol) ?: return 0

        val existing = db.filings().existingAccessions(security.symbol).toSet()
        val fresh = filings.filter { it.accessionNumber !in existing }.map { filing ->
            FilingRecord(
                accessionNumber = filing.accessionNumber,
                symbol = security.symbol,
                formType = filing.formType,
                filedAt = filing.filedAt,
                periodOfReport = filing.periodOfReport,
                primaryDocumentURL = filing.primaryDocumentURL,
                filingIndexURL = filing.filingIndexURL
            )
        }
        if (fresh.isEmpty()) return 0
        db.filings().insertAll(fresh)
        return fresh.size
    }

    // MARK: - Insider transactions

    /**
     * Records insider transactions, one row per Form 4 line.
     *
     * Keyed on the accession *and* the line's own date and code: a single
     * filing reports several transactions, so deduplicating on the accession
     * alone would keep one line and discard the rest.
     */
    suspend fun recordInsiders(
        insiders: List<InsiderTransactionDTO>,
        symbol: String,
        provider: DataProviderID
    ): Int {
        if (!accepts(provider, "insider transactions", symbol)) return 0
        if (insiders.isEmpty()) return 0
        val security = security(symbol) ?: return 0

        val existing = db.insiderTransactions().all(security.symbol)
            .map { "${it.accessionNumber}|${it.transactionDate}|${it.transactionCode}" }
            .toSet()

        val fresh = insiders.filter { transaction ->
            val key = "${transaction.accessionNumber}|${transaction.transactionDate}|" +
                transaction.transactionCode
            key !in existing
        }.map { transaction ->
            InsiderTransaction(
                symbol = security.symbol,
                accessionNumber = transaction.accessionNumber,
                insiderName = transaction.insiderName,
                insiderTitle = transaction.insiderTitle,
                isDirector = transaction.isDirector,
                isOfficer = transaction.isOfficer,
                isTenPercentOwner = transaction.isTenPercentOwner,
                transactionDate = transaction.transactionDate,
                filedAt = transaction.filedAt,
                transactionCode = transaction.transactionCode,
                isUnderTradingPlan = transaction.isUnderTradingPlan,
                shares = transaction.shares,
                pricePerShare = transaction.pricePerShare,
                sharesOwnedAfter = transaction.sharesOwnedAfter
            )
        }
        if (fresh.isEmpty()) return 0
        db.insiderTransactions().insertAll(fresh)
        return fresh.size
    }

    // MARK: - Detected events

    /**
     * Records detected events, one per kind per day per security.
     *
     * Detection re-runs on every visit over the same bars, so the same Tuesday
     * volume spike would otherwise accumulate a row per refresh. Deduplication
     * is on `naturalKey` rather than on the headline text: the wording of a
     * headline can change with a code edit, and that must not resurrect an
     * event the user has already acknowledged.
     */
    suspend fun recordEvents(
        events: List<DetectedEventDTO>,
        symbol: String,
        provider: DataProviderID
    ): Int {
        // An event is only as real as the series it was detected over. The
        // caller passes the origin of those inputs, not the event's own
        // provider — a price move computed from sample bars is a calculation
        // over invented numbers, and storing it would put an invented headline
        // in the Research feed with nothing to mark it as such.
        if (!accepts(provider, "events", symbol)) return 0
        // An unfinished session is not yet a fact. A +8% reading at midday can
        // close at +2%, and the permanent record must not keep the midday
        // figure as what happened — the closed session is picked up from the
        // bars on a later visit. Enforced here rather than at the call site so
        // no future caller can store one by forgetting.
        val settled = events.filter { !it.isProvisional }
        if (settled.isEmpty()) return 0
        val security = security(symbol) ?: return 0

        val existing = db.events().forSymbol(security.symbol, Int.MAX_VALUE)
            .map { it.naturalKey }
            .toSet()

        val fresh = settled.filter { it.naturalKey !in existing }
            .distinctBy { it.naturalKey }
            .map { event ->
                DetectedEvent(
                    symbol = security.symbol,
                    naturalKey = event.naturalKey,
                    kindRaw = event.kind.raw,
                    occurredAt = event.occurredAt,
                    headline = event.headline,
                    detailLines = event.detailLines,
                    context = event.context,
                    unusualness = event.unusualness,
                    sourceDetails = event.sourceDetails,
                    sourceURLs = event.sourceURLs,
                    derivationJSON = event.derivation?.let {
                        json.encodeToString(Derivation.serializer(), it)
                    }
                )
            }
        if (fresh.isEmpty()) return 0
        db.events().insertAll(fresh)
        return fresh.size
    }

    /** Stored events for one security, most recent occurrence first. */
    suspend fun events(symbol: String, limit: Int = 50): List<DetectedEventDTO> =
        db.events().forSymbol(symbol.uppercase(), limit).map { it.toDTO() }

    /** Events across every security in the store, for the Research feed. */
    suspend fun recentEvents(limit: Int = 100): List<SecurityEvent> {
        val rows = db.events().recent(limit)
        val names = db.securities().all().associate { it.symbol to it.name }
        return rows.mapNotNull { row ->
            val name = names[row.symbol] ?: return@mapNotNull null
            SecurityEvent(symbol = row.symbol, name = name, event = row.toDTO())
        }
    }

    suspend fun acknowledge(symbol: String, naturalKey: String) {
        db.events().acknowledge(symbol.uppercase(), naturalKey)
    }

    // MARK: - Screening

    /**
     * Every security the app holds data for, with the figures a screen can
     * test — read entirely from disk, issuing no requests.
     *
     * Benchmarks are excluded: a sector ETF is not a company, and screening
     * one on revenue growth would return nothing while looking like a result.
     */
    suspend fun screenSubjects(): List<ScreenSubject> {
        val now = Clock.System.now()
        return db.securities().companies().map { security ->
            val quote = lastQuote(security.symbol, before = now)
            val facts = facts(security.symbol)

            val growth = FundamentalDetector
                .yearOverYear(FundamentalDetector.quarterly(facts, FinancialConcept.Revenue))
                .lastOrNull()
                ?.takeIf { it.prior > 0 }
                ?.let { (it.current - it.prior) / it.prior * 100 }

            val debt = FundamentalDetector
                .instant(facts, FinancialConcept.TotalDebt).lastOrNull()?.value
            val cash = FundamentalDetector
                .instant(facts, FinancialConcept.CashAndEquivalents).lastOrNull()?.value

            val latestEvent = events(security.symbol, limit = 1).firstOrNull()

            ScreenSubject(
                symbol = security.symbol,
                name = security.name,
                sector = security.sector,
                price = quote?.last,
                dailyChangePercent = quote?.changePercent,
                revenueGrowth = growth,
                grossMargin = FundamentalDetector
                    .marginSeries(facts, FinancialConcept.GrossProfit).lastOrNull()?.value,
                operatingMargin = FundamentalDetector
                    .marginSeries(facts, FinancialConcept.OperatingIncome).lastOrNull()?.value,
                netCash = if (debt != null && cash != null) cash - debt else null,
                latestUnusualness = latestEvent?.unusualness,
                daysSinceLastEvent = latestEvent?.let {
                    (now - it.occurredAt).inWholeSeconds / 86_400.0
                }
            )
        }.sortedBy { it.symbol }
    }

    // MARK: - Visits

    /**
     * When the user last opened this security, before the current visit.
     *
     * Must be read *before* [markViewed] — the whole point is the previous
     * timestamp, and stamping first would make every visit report no changes.
     */
    suspend fun lastViewed(symbol: String): Instant? =
        db.securities().lastViewedAt(symbol.uppercase())

    suspend fun markViewed(symbol: String, at: Instant = Clock.System.now()) {
        db.securities().markViewed(symbol.uppercase(), at)
    }

    // MARK: - Reading history back

    /**
     * The most recent quote observation recorded before [before].
     *
     * Returns a value type rather than the stored row, which keeps the store's
     * own types from leaking into the analysis layer.
     */
    suspend fun lastQuote(symbol: String, before: Instant): QuoteSnapshot? =
        db.quotes().lastBefore(symbol.uppercase(), before)?.let {
            QuoteSnapshot(
                observedAt = it.observedAt,
                last = it.last,
                previousClose = it.previousClose,
                changePercent = it.changePercent
            )
        }

    suspend fun observationCount(symbol: String): Int =
        db.quotes().count(symbol.uppercase())

    // MARK: - Reading the store back

    /**
     * Bars already held for a symbol, oldest first.
     *
     * Bars are the scarcest request in the app — Tiingo's free tier refills
     * roughly one token every 80 seconds — so a screen that can answer from
     * disk must not spend one. Bounds are inclusive and default to everything
     * stored.
     */
    suspend fun bars(
        symbol: String,
        from: Instant? = null,
        to: Instant? = null,
        resolution: BarResolution = BarResolution.Daily
    ): List<PriceBarDTO> {
        val key = symbol.uppercase()
        val rows = if (from == null && to == null) {
            db.priceBars().all(key, resolution.raw)
        } else {
            db.priceBars().inRange(
                key, resolution.raw,
                from ?: Instant.DISTANT_PAST,
                to ?: Instant.DISTANT_FUTURE
            )
        }
        return rows.map {
            PriceBarDTO(
                date = it.date, open = it.open, high = it.high, low = it.low,
                close = it.close, volume = it.volume, adjustedClose = it.adjustedClose
            )
        }
    }

    /**
     * Reported figures held for a symbol, oldest period first, with
     * restatements collapsed to the most recently filed figure per period.
     *
     * This is the *current* view of the company's history, which is what the
     * analysis layer wants. [factRevisions] returns the superseded rows
     * alongside it — the question the append-only design exists to answer, and
     * the input the restatement detector needs.
     *
     * [since] bounds the period a figure describes, not when it was observed:
     * a 2024 quarter restated last week is still a 2024 quarter.
     */
    suspend fun facts(
        symbol: String,
        concepts: List<FinancialConcept> = FinancialConcept.entries,
        since: Instant? = null
    ): List<FinancialFactDTO> {
        val rows = factRows(symbol, concepts, since)

        // `deduplicated` keys on the period alone, because the provider calls
        // it inside a single concept's loop where the concept is already fixed.
        // Handing it a mixed-concept list would collapse revenue and net income
        // for the same quarter into one row and silently discard the loser, so
        // the rows are grouped by concept before it ever sees them.
        return rows.groupBy { it.concept }
            .values
            .flatMap { FactPeriods.deduplicated(it) }
            .sortedWith(compareBy({ it.periodEnd }, { it.concept.raw }))
    }

    /**
     * Every stored version of one concept, restatements included, ordered by
     * the period described and then by when each version was filed.
     *
     * Two rows sharing a period are an issuer revising a figure it had already
     * reported. Nothing else in the app can see that, because every other read
     * path deliberately collapses it away.
     */
    suspend fun factRevisions(
        symbol: String,
        concept: FinancialConcept,
        since: Instant? = null
    ): List<FinancialFactDTO> = factRows(symbol, listOf(concept), since)
        .sortedWith(compareBy({ it.periodEnd }, { it.filedAt ?: Instant.DISTANT_PAST }))

    /** Filings held for a symbol, most recently filed first. */
    suspend fun filings(symbol: String, limit: Int = 50): List<FilingDTO> =
        db.filings().recent(symbol.uppercase(), limit).map {
            FilingDTO(
                accessionNumber = it.accessionNumber, formType = it.formType,
                filedAt = it.filedAt, periodOfReport = it.periodOfReport,
                primaryDocumentURL = it.primaryDocumentURL,
                filingIndexURL = it.filingIndexURL
            )
        }

    /**
     * When a stored series was last written, so a caller can decide whether to
     * spend a request before it makes one.
     *
     * Deliberately the *observation* time rather than the period a record
     * describes: the question is how old the held copy is, and a freshly
     * fetched five-year-old annual figure is not stale data.
     */
    suspend fun latestObservedAt(symbol: String, kind: StoredDataKind): Instant? {
        val key = symbol.uppercase()
        return when (kind) {
            StoredDataKind.Quote -> db.quotes().latestObservedAt(key)
            StoredDataKind.Bars -> db.priceBars().latestObservedAt(key)
            StoredDataKind.Facts -> db.financialFacts().latestObservedAt(key)
            StoredDataKind.Filings -> db.filings().latestObservedAt(key)
            StoredDataKind.Events -> db.events().latest(key)?.detectedAt
        }
    }

    // MARK: - Benchmarks

    /**
     * Ensures a row exists for a benchmark symbol so its history can be stored
     * and hydrated like any other.
     *
     * [security] deliberately creates nothing — history is only recorded for
     * companies the user actually follows, so a stray symbol cannot quietly
     * populate the store. A sector ETF is the exception the `isBenchmark` flag
     * was added for: the app needs its bars to answer "how did this company do
     * against its sector", and re-fetching five years of XLK on every visit
     * would spend the scarcest request in the app on a series eleven companies
     * share.
     */
    suspend fun ensureBenchmark(symbol: String, name: String) {
        val key = symbol.uppercase()
        if (security(key) != null) return
        db.securities().upsert(Security(symbol = key, name = name, isBenchmark = true))
    }

    // MARK: - Helpers

    /**
     * Whether a write from this provider may be stored at all.
     *
     * Synthetic data is refused rather than flagged. A flag would have to be
     * honoured by every read path forever, and the one that mattered —
     * [bars] — is the path a page uses precisely when it is trying not to
     * spend a request. Refusing at the door is the only version of this that
     * cannot be forgotten later.
     */
    private fun accepts(provider: DataProviderID, what: String, symbol: String): Boolean {
        if (!provider.isSynthetic) return true
        logger.d { "Refused synthetic $what for $symbol" }
        return false
    }

    /**
     * Looks up the security, creating nothing: history is only recorded for
     * companies the user has actually added, so a stray symbol cannot quietly
     * populate the store.
     */
    private suspend fun security(symbol: String): Security? =
        db.securities().find(symbol.uppercase())

    private suspend fun factRows(
        symbol: String,
        concepts: List<FinancialConcept>,
        since: Instant?
    ): List<FinancialFactDTO> {
        if (concepts.isEmpty()) return emptyList()
        val wanted = concepts.map { it.raw }.toSet()
        val lower = since ?: Instant.DISTANT_PAST
        return db.financialFacts().all(symbol.uppercase())
            .filter { it.concept in wanted && it.periodEnd >= lower }
            .sortedBy { it.periodEnd }
            .mapNotNull { it.toDTO() }
    }

    private fun factKey(record: FinancialFactRecord): String =
        factKey(record.concept, record.periodEnd, record.accessionNumber)

    private fun factKey(fact: FinancialFactDTO): String =
        factKey(fact.concept.raw, fact.periodEnd, fact.accessionNumber)

    private fun factKey(concept: String, periodEnd: Instant, accession: String?): String =
        "$concept|${FactPeriods.periodKey(periodEnd)}|${accession ?: "-"}"

    /**
     * Rebuilds the transport type from a stored row.
     *
     * Null for a concept this build no longer understands: a row written by an
     * older version with a since-renamed concept is skipped rather than
     * crashing or being coerced into a neighbouring case.
     */
    private fun FinancialFactRecord.toDTO(): FinancialFactDTO? {
        val resolved = FinancialConcept.entries.firstOrNull { it.raw == concept } ?: return null
        return FinancialFactDTO(
            concept = resolved,
            rawTag = rawTag,
            periodStart = periodStart,
            periodEnd = periodEnd,
            fiscalYear = fiscalYear,
            fiscalQuarter = fiscalQuarter,
            isAnnual = isAnnual,
            // `periodKind` is not a stored column. It is re-derived from the
            // period's own duration exactly as extraction derived it, so a row
            // reconstitutes with the classification it was filtered on — and a
            // cumulative figure could not have been stored in the first place.
            periodKind = FiscalPeriodKind.classify(
                periodStart?.let { (periodEnd - it).inWholeSeconds / 86_400.0 }
            ),
            value = value,
            unit = unit,
            filedAt = filedAt,
            accessionNumber = accessionNumber
        )
    }

    private fun DetectedEvent.toDTO(): DetectedEventDTO = DetectedEventDTO.create(
        kind = kind,
        occurredAt = occurredAt,
        headline = headline,
        detailLines = detailLines,
        context = context,
        unusualness = unusualness,
        sourceDetails = sourceDetails,
        sourceURLs = sourceURLs,
        derivation = derivationJSON?.let {
            runCatching { json.decodeFromString(Derivation.serializer(), it) }.getOrNull()
        },
        isProvisional = false
    )
}

/**
 * A stored series, for asking how old the held copy is.
 *
 * Named per series rather than per model because that is the granularity a
 * refresh decision is made at: quotes go stale in a minute and filings in an
 * hour, and `StalenessPolicy` already encodes exactly that difference.
 */
enum class StoredDataKind(val raw: String) {
    Quote("quote"),
    Bars("bars"),
    Facts("facts"),
    Filings("filings"),
    Events("events")
}

/**
 * A quote observation lifted out of the store as a value.
 *
 * The comparison primitive "what changed since last time" is built on.
 */
data class QuoteSnapshot(
    val observedAt: Instant,
    val last: Double,
    val previousClose: Double?,
    val changePercent: Double?
)

/**
 * One event together with the security it belongs to, for feeds that mix
 * securities. The stored row carries a symbol but not a name, and a feed row
 * has to be able to label itself.
 */
data class SecurityEvent(
    val symbol: String,
    val name: String,
    val event: DetectedEventDTO
) {
    val id: String get() = "$symbol|${event.naturalKey}"
}
