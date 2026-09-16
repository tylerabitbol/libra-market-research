package com.tylerabitbol.libra.viewmodels

import co.touchlab.kermit.Logger
import com.tylerabitbol.libra.models.core.Benchmark
import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.persistence.QuoteSnapshot
import com.tylerabitbol.libra.persistence.SnapshotStore
import com.tylerabitbol.libra.persistence.WatchlistMember
import com.tylerabitbol.libra.services.providers.CompanyProfileDTO
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import com.tylerabitbol.libra.services.providers.QuoteDTO
import com.tylerabitbol.libra.support.Freshness
import com.tylerabitbol.libra.support.StalenessPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** One watchlist row's live state. */
data class WatchlistRow(
    val symbol: String,
    val name: String,
    /** The user's own ordering, from `WatchlistEntry.priority`. Lower first. */
    val priority: Int = 0,
    val quote: QuoteDTO? = null,
    /**
     * The last reading recorded on disk. Shown until a live quote arrives, and
     * kept on screen if none does — a row that has a price from an hour ago is
     * more useful than a row showing only an error.
     */
    val stored: QuoteSnapshot? = null,
    val error: APIError? = null,
    /**
     * The sector this security belongs to, from the stored profile. Null until
     * the detail page has been opened once and written one.
     */
    val sector: String? = null,
    /** The most recent change the detectors have recorded for this security. */
    val latestEvent: DetectedEventDTO? = null,
    /** The market's move for the same session, fetched once for the whole list. */
    val marketPercent: Double? = null,
    /** This row's sector's move, fetched once per distinct sector on the list. */
    val sectorPercent: Double? = null,
) {
    val id: String get() = symbol

    val last: Double? get() = quote?.last ?: stored?.last
    val changePercent: Double? get() = quote?.changePercent ?: stored?.changePercent

    /**
     * Difference in percentage points against the market and the sector.
     *
     * A plain difference, not beta-adjusted — that belongs on the detail page
     * where a beta can be fitted and shown. Here it is labelled for what it
     * is: how much more or less this moved than the benchmark today.
     */
    val versusMarket: Double?
        get() {
            val change = changePercent ?: return null
            val market = marketPercent ?: return null
            return change - market
        }

    val versusSector: Double?
        get() {
            val change = changePercent ?: return null
            val sector = sectorPercent ?: return null
            return change - sector
        }

    /** How unusual the most recent recorded change was, for sorting. */
    val unusualness: Double get() = latestEvent?.unusualness ?: 0.0

    /** When the most recent recorded change occurred. */
    val latestEventAt: Instant? get() = latestEvent?.occurredAt

    /** True when what is on screen came from disk rather than this refresh. */
    val isStoredCopy: Boolean get() = quote == null && stored != null
    val asOf: Instant? get() = if (quote == null) stored?.observedAt else null
    val hasValue: Boolean get() = last != null
}

/**
 * Backs the watchlist (Section 15).
 *
 * Membership is persisted in Room; prices are fetched live. The two are
 * deliberately separate: the list must render instantly from disk and remain
 * usable when the network doesn't, with prices filling in as they arrive.
 */
class WatchlistViewModel(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(WatchlistUiState())
    val state: StateFlow<WatchlistUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Section 15's sort options.
     *
     * "Most unusual" and "newest information" read the detectors' output from
     * the store rather than recomputing it — a watchlist that re-ran detection
     * per row would need every row's history, which is the one thing this
     * screen must never fetch.
     */
    enum class SortOrder(val raw: String, val displayName: String) {
        Symbol("symbol", "Symbol"),
        BiggestChange("biggestChange", "Biggest change"),
        MostUnusual("mostUnusual", "Most unusual"),
        NewestInformation("newestInformation", "Newest information"),
        Priority("priority", "Your priority");

        val id: String get() = raw
    }

    fun setSort(order: SortOrder) {
        _state.update { it.copy(sort = order) }
    }

    fun load(
        members: List<WatchlistMember>,
        registry: ProviderRegistry,
        snapshots: SnapshotStore? = null,
        force: Boolean = false,
    ) {
        // Everything the store already knows, read before any request: names,
        // sectors and the user's own ordering all render immediately.
        val rows = members.map { member ->
            WatchlistRow(
                symbol = member.symbol,
                name = member.name,
                sector = member.sector,
                priority = member.priority,
            )
        }
        _state.update { it.copy(rows = rows) }
        if (rows.isEmpty()) return

        if (!force && _state.value.freshness is Freshness.Fresh) return
        loadJob?.cancel()
        loadJob = scope.launch {
            perform(registry, snapshots)
        }
    }

    /**
     * Pull-to-refresh, which must not return until the work is done. [load]
     * spawns and returns, so the control's spinner ended with the gesture
     * while the quotes were still in flight.
     */
    suspend fun refresh(
        members: List<WatchlistMember>,
        registry: ProviderRegistry,
        snapshots: SnapshotStore? = null,
    ) {
        load(members, registry, snapshots, force = true)
        loadJob?.join()
    }

    private suspend fun perform(registry: ProviderRegistry, snapshots: SnapshotStore?) {
        hydrate(snapshots)
        refreshQuotes(registry)
        loadBenchmarkMoves(registry)
        persist(snapshots, registry.marketData.id)
    }

    /**
     * Fills each row with the last price recorded on disk, before any request.
     *
     * The watchlist already rendered names from disk while prices loaded; this
     * extends the same idea to the prices themselves, so the list is readable
     * offline and shows a stale number rather than a column of errors.
     */
    private suspend fun hydrate(snapshots: SnapshotStore?) {
        if (snapshots == null) return
        val stored = mutableMapOf<String, QuoteSnapshot>()
        val events = mutableMapOf<String, DetectedEventDTO>()
        for (symbol in _state.value.rows.map { it.symbol }) {
            runCatching { snapshots.lastQuote(symbol, Clock.System.now()) }
                .getOrNull()?.let { stored[symbol] = it }
            // The feed of recorded changes, read rather than recomputed.
            runCatching { snapshots.events(symbol, limit = 1) }
                .getOrNull()?.firstOrNull()?.let { events[symbol] = it }
        }
        _state.update { current ->
            current.copy(
                rows = current.rows.map { row ->
                    row.copy(stored = stored[row.symbol], latestEvent = events[row.symbol])
                },
            )
        }
    }

    private suspend fun refreshQuotes(registry: ProviderRegistry) {
        _state.update { it.copy(isLoading = true) }
        try {
            val symbols = _state.value.rows.map { it.symbol }
            val provider = registry.marketData

            // Quotes only. Deliberately no history: the watchlist shows a daily
            // change, and fetching bars per row would exhaust Tiingo's hourly
            // quota on a list of any size.
            val results: Map<String, Result<QuoteDTO>> = coroutineScope {
                symbols.map { symbol ->
                    async {
                        symbol to runCatching { provider.quote(symbol) }
                    }
                }.awaitAll().toMap()
            }

            _state.update { current ->
                current.copy(
                    rows = current.rows.map { row ->
                        val result = results[row.symbol] ?: return@map row
                        result.fold(
                            onSuccess = { row.copy(quote = it, error = null) },
                            onFailure = { row.copy(error = it.asAPIError()) },
                        )
                    },
                    lastRefreshedAt = Clock.System.now(),
                )
            }
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }

    /**
     * The market's move, and one move per distinct sector on the list.
     *
     * Bounded by the number of *sectors*, not by the number of rows: eleven
     * sectors exist, so a hundred-row watchlist costs at most twelve extra
     * quotes. A per-row benchmark would cost a hundred, which is the mistake
     * that makes this screen unusable on a free tier.
     *
     * Quotes only, never history — the same rule the rows themselves follow.
     */
    private suspend fun loadBenchmarkMoves(registry: ProviderRegistry) {
        val sectors = _state.value.rows
            .mapNotNull { Benchmark.sector(it.sector)?.etfSymbol }
            .toSet()
        val provider = registry.marketData

        val (market, sectorMoves) = coroutineScope {
            val marketWork = async {
                runCatching { provider.quote(Benchmark.marketProxySymbol).changePercent }
                    .getOrNull()
            }
            val sectorWork = async {
                sectors.map { symbol ->
                    async {
                        symbol to runCatching { provider.quote(symbol).changePercent }.getOrNull()
                    }
                }.awaitAll().toMap()
            }
            marketWork.await() to sectorWork.await()
        }

        _state.update { current ->
            current.copy(
                rows = current.rows.map { row ->
                    val symbol = Benchmark.sector(row.sector)?.etfSymbol
                    row.copy(
                        marketPercent = market,
                        sectorPercent = symbol?.let { sectorMoves[it] },
                    )
                },
            )
        }
    }

    /**
     * Records each quote so the watchlist accrues history simply by being
     * opened — which is what gives the detectors something to compare against.
     */
    private suspend fun persist(snapshots: SnapshotStore?, provider: DataProviderID) {
        if (snapshots == null) return
        for (row in _state.value.rows) {
            val quote = row.quote ?: continue
            try {
                snapshots.recordQuote(quote, row.symbol, provider)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logger.e { "Persist failed for ${row.symbol}: ${error.message}" }
            }
        }
    }

    companion object {
        internal val logger = Logger.withTag("watchlist")

        /**
         * The orderings, as a free function so tests exercise the shipped
         * comparison rather than a copy of it. They used to re-implement it,
         * which meant a change here would not have been caught there.
         */
        fun sorted(rows: List<WatchlistRow>, order: SortOrder): List<WatchlistRow> = when (order) {
            SortOrder.Symbol -> rows.sortedBy { it.symbol }
            // Absolute magnitude: a 5% fall is as notable as a 5% rise, and
            // this screen is about what moved, not about what went up.
            SortOrder.BiggestChange -> rows.sortedByDescending { abs(it.changePercent ?: 0.0) }
            // Unusual relative to each security's own history, which is not the
            // same ordering as the biggest move: a 2% day can be extreme for a
            // utility and unremarkable for a small-cap biotech.
            SortOrder.MostUnusual -> rows.sortedByDescending { it.unusualness }
            SortOrder.NewestInformation ->
                rows.sortedByDescending { it.latestEventAt ?: Instant.DISTANT_PAST }
            SortOrder.Priority ->
                rows.sortedWith(compareBy({ it.priority }, { it.symbol }))
        }
    }
}

/** Maps a thrown failure back to the typed error the UI knows how to render. */
internal fun Throwable.asAPIError(): APIError = this as? APIError
    ?: APIError.Transport(DataProviderID.Finnhub, underlying = message ?: "unknown error")

data class WatchlistUiState(
    val rows: List<WatchlistRow> = emptyList(),
    val isLoading: Boolean = false,
    val lastRefreshedAt: Instant? = null,
    val sort: WatchlistViewModel.SortOrder = WatchlistViewModel.SortOrder.Symbol,
) {
    val freshness: Freshness
        get() = if (isLoading) {
            Freshness.Refreshing(previous = lastRefreshedAt)
        } else {
            StalenessPolicy.quote.evaluate(lastRefreshedAt)
        }

    val sortedRows: List<WatchlistRow> get() = WatchlistViewModel.sorted(rows, sort)
}

/** Symbol search for adding to the watchlist. */
class SymbolSearchViewModel(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(SymbolSearchUiState())
    val state: StateFlow<SymbolSearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun search(query: String, registry: ProviderRegistry) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(results = emptyList()) }
            return
        }

        searchJob = scope.launch {
            // Debounce: search fires per keystroke, and each one is a request
            // against a rate-limited provider.
            delay(300.milliseconds)
            performSearch(trimmed, registry)
        }
    }

    private suspend fun performSearch(query: String, registry: ProviderRegistry) {
        _state.update { it.copy(isSearching = true) }
        try {
            val results = registry.marketData.search(query)
            _state.update { it.copy(results = results, error = null) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _state.update { it.copy(error = error.asAPIError(), results = emptyList()) }
        } finally {
            _state.update { it.copy(isSearching = false) }
        }
    }
}

data class SymbolSearchUiState(
    val results: List<CompanyProfileDTO> = emptyList(),
    val isSearching: Boolean = false,
    val error: APIError? = null,
)
