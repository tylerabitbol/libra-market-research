package com.tylerabitbol.libra.viewmodels

import co.touchlab.kermit.Logger
import com.tylerabitbol.libra.calculations.AnomalyMeasure
import com.tylerabitbol.libra.calculations.Beta
import com.tylerabitbol.libra.calculations.ChangeWindow
import com.tylerabitbol.libra.calculations.ChartSeriesBuilder
import com.tylerabitbol.libra.calculations.ClosePoint
import com.tylerabitbol.libra.calculations.EventDetector
import com.tylerabitbol.libra.calculations.FilingAnalysis
import com.tylerabitbol.libra.calculations.FundamentalDetector
import com.tylerabitbol.libra.calculations.HistoricalContext
import com.tylerabitbol.libra.calculations.InsiderActivity
import com.tylerabitbol.libra.calculations.MoveAttribution
import com.tylerabitbol.libra.calculations.PeriodReturn
import com.tylerabitbol.libra.calculations.PriceContext
import com.tylerabitbol.libra.calculations.RelativeAnalysis
import com.tylerabitbol.libra.calculations.RelativePerformance
import com.tylerabitbol.libra.calculations.ResearchProfile
import com.tylerabitbol.libra.calculations.ResearchProfileBuilder
import com.tylerabitbol.libra.calculations.ReturnCalculator
import com.tylerabitbol.libra.calculations.SectorFactor
import com.tylerabitbol.libra.calculations.ValuationMetric
import com.tylerabitbol.libra.calculations.ValuationCalculator
import com.tylerabitbol.libra.calculations.normalized
import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.Benchmark
import com.tylerabitbol.libra.models.core.ChartAvailability
import com.tylerabitbol.libra.models.core.ChartAxisTick
import com.tylerabitbol.libra.models.core.ChartPoint
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.models.core.ChartSegment
import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.models.core.EvidenceCategory
import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.persistence.SnapshotStore
import com.tylerabitbol.libra.persistence.StoredDataKind
import com.tylerabitbol.libra.services.providers.CompanyMetricsDTO
import com.tylerabitbol.libra.services.providers.CompanyProfileDTO
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import com.tylerabitbol.libra.services.providers.InsiderTransactionDTO
import com.tylerabitbol.libra.services.providers.PriceBarDTO
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import com.tylerabitbol.libra.services.providers.QuoteDTO
import com.tylerabitbol.libra.services.providers.RatingSnapshotDTO
import com.tylerabitbol.libra.services.providers.SECDataProvider
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.support.Freshness
import com.tylerabitbol.libra.support.StalenessPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Loads everything the Security Detail page shows.
 *
 * Each section loads independently and records its own failure. Section 21
 * requires that one provider failing degrades one section rather than the
 * screen — and with four providers on three different rate limits, partial
 * success is the normal case rather than an edge case.
 *
 * Ported 1:1 from Swift per `PLAN.md §7`, including its length. Splitting it
 * is a post-parity task.
 */
class SecurityDetailViewModel(
    symbol: String,
    private val scope: CoroutineScope,
) {
    val symbol: String = symbol.uppercase()

    private val _state = MutableStateFlow(SecurityDetailUiState(symbol = this.symbol))
    val state: StateFlow<SecurityDetailUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /**
     * The fetch a range change spawned, kept for the same reason [loadJob] is:
     * so a caller — in practice a test — can wait for it rather than guess.
     */
    private var selectJob: Job? = null

    /**
     * Set for the duration of a forced refresh, so pull-to-refresh reaches the
     * network even where the held copy would otherwise be considered fresh.
     */
    private var isForcingRefresh = false

    // MARK: - Selection

    fun setChangeWindow(window: ChangeWindow) {
        _state.update { it.copy(changeWindow = window) }
        applyChangeFilter()
    }

    fun setKindFilter(kinds: Set<EventKind>) {
        _state.update { it.copy(kindFilter = kinds) }
        applyChangeFilter()
    }

    fun select(range: ChartRange, registry: ProviderRegistry, snapshots: SnapshotStore? = null) {
        _state.update { it.copy(selectedRange = range) }
        // Intraday is a different series from a different vendor, so it has
        // its own fetch rather than widening the daily window.
        if (range.usesIntraday) {
            selectJob = scope.launch { loadIntraday(registry, snapshots) }
            return
        }
        // Only re-fetch when the new range reaches back further than what we hold.
        val needed = range.startDate()
        val window = _state.value.loadedBarWindow
        if (window != null && window.first <= needed) return
        selectJob = scope.launch { loadHistory(registry) }
    }

    // MARK: - Loading

    fun load(
        registry: ProviderRegistry,
        snapshots: SnapshotStore? = null,
        force: Boolean = false,
    ) {
        if (!force && _state.value.freshness is Freshness.Fresh) {
            _state.update { it.copy(hasCompletedLoad = true) }
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch { perform(registry, snapshots, force) }
    }

    /**
     * Pull-to-refresh, which must not return until the work is done.
     *
     * [load] spawns and returns, which is right for an on-appear task and
     * wrong for a refresh gesture: the control ended its spinner the moment
     * the gesture did, while the request was still in flight, so the gesture
     * reported a refresh that had not happened.
     */
    suspend fun refresh(registry: ProviderRegistry, snapshots: SnapshotStore? = null) {
        loadJob?.cancel()
        perform(registry, snapshots, force = true)
    }

    /**
     * Joins the load [load] spawned.
     *
     * Swift's tests slept for a fixed interval and hoped; a job to join is both
     * exact and faster.
     */
    internal suspend fun awaitLoad() {
        loadJob?.join()
        selectJob?.join()
    }

    private suspend fun perform(
        registry: ProviderRegistry,
        snapshots: SnapshotStore?,
        force: Boolean,
    ) {
        isForcingRefresh = force
        hydrate(snapshots)
        performLoad(registry, snapshots)
        detectChanges(snapshots)
        persist(snapshots, registry)
        isForcingRefresh = false
        _state.update { it.copy(hasCompletedLoad = true) }
    }

    /**
     * Fills the page from what the store already holds, before any request.
     *
     * Three things follow that did not before. A previously visited security
     * renders instantly and works offline. A failed fetch degrades to the last
     * good copy rather than to an empty section. And a section whose held copy
     * is still fresh costs no request at all — which is what makes Tiingo's
     * 50-requests-per-hour budget survivable on a page that reads five years
     * of history.
     */
    private suspend fun hydrate(snapshots: SnapshotStore?) {
        if (snapshots == null) return
        try {
            val freshness = StoredDataKind.entries.mapNotNull { kind ->
                snapshots.latestObservedAt(symbol, kind)?.let { kind to it }
            }.toMap()
            _state.update { it.copy(storedFreshness = freshness) }

            val storedBars = snapshots.bars(symbol, from = ChartRange.FiveYear.startDate())
            if (storedBars.isNotEmpty() && _state.value.bars.isEmpty()) {
                _state.update { current ->
                    current.copy(
                        bars = storedBars.map { priceBar(it) },
                        // What we actually hold, which is what `select` needs
                        // to decide whether a longer range requires a request.
                        loadedBarWindow = storedBars.first().date to storedBars.last().date,
                        hydratedFromStore = true,
                    )
                }
            }

            val storedFacts = snapshots.facts(symbol, trackedConcepts, fundamentalsSince())
            if (storedFacts.isNotEmpty() && _state.value.fundamentals.isEmpty()) {
                _state.update { it.copy(fundamentals = storedFacts, hydratedFromStore = true) }
            }

            val storedFilings = snapshots.filings(symbol, limit = 15)
            if (storedFilings.isNotEmpty() && _state.value.filings.isEmpty()) {
                _state.update { it.copy(filings = storedFilings, hydratedFromStore = true) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Hydration is an optimisation, not a requirement. A store that
            // cannot be read leaves the page exactly as it was before: empty,
            // and about to fetch.
            logger.e { "Hydration failed for $symbol: ${error.message}" }
        }
    }

    /** Whether the held copy is recent enough that fetching would buy nothing. */
    private fun isHeldCopyFresh(kind: StoredDataKind, policy: StalenessPolicy): Boolean {
        if (isForcingRefresh) return false
        val observed = _state.value.storedFreshness[kind] ?: return false
        return policy.evaluate(observed) is Freshness.Fresh
    }

    private suspend fun performLoad(registry: ProviderRegistry, snapshots: SnapshotStore?) {
        _state.update { it.copy(isLoading = true) }
        try {
            // Independent sections, run concurrently, each swallowing only its
            // own failure into its own error slot.
            coroutineScope {
                listOf(
                    async { loadProfile(registry) },
                    async { loadQuote(registry) },
                    async { loadHistory(registry) },
                    async { loadMetrics(registry) },
                    async { loadRatings(registry) },
                    async { loadMarketContext(registry) },
                ).awaitAll()
            }

            // Needs the profile's sector, so it follows the concurrent block
            // rather than running inside it.
            loadSectorHistory(registry, snapshots)
            computeBeta()

            // These need the CIK, which comes from the profile or a lookup, so
            // they follow rather than run alongside.
            loadSECSections(registry)

            _state.update { it.copy(lastRefreshedAt = Clock.System.now()) }
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadProfile(registry: ProviderRegistry) {
        val profile = runCatching { registry.marketData.profile(symbol) }.getOrNull()
        _state.update { it.copy(profile = profile) }
    }

    private suspend fun loadQuote(registry: ProviderRegistry) {
        try {
            val quote = registry.marketData.quote(symbol)
            _state.update { it.copy(quote = quote, quoteError = null) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _state.update { it.copy(quoteError = error.asAPIError()) }
        }
    }

    private suspend fun loadHistory(registry: ProviderRegistry) {
        // Bars are the scarcest request in the app. When hydration produced a
        // copy that is still fresh, the page is already correct and the request
        // is pure cost against a budget that refills one token every 80 seconds.
        if (_state.value.bars.isNotEmpty() &&
            isHeldCopyFresh(StoredDataKind.Bars, StalenessPolicy.dailyCandles)
        ) {
            _state.update { it.copy(historyError = null) }
            return
        }
        // Fetch the widest range once. Five years of daily bars is a single
        // request and covers every shorter range without another.
        val from = ChartRange.FiveYear.startDate()
        val to = Clock.System.now()
        try {
            val fetched = registry.marketData.bars(symbol, BarResolution.Daily, from, to)
            _state.update {
                it.copy(
                    bars = fetched.map { dto -> priceBar(dto) },
                    loadedBarWindow = from to to,
                    historyError = null,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val mapped = error as? APIError
                ?: APIError.Transport(DataProviderID.Tiingo, error.message ?: "unknown error")
            logger.e { "History failed for $symbol: ${mapped.shortDescription}" }
            _state.update { it.copy(historyError = mapped) }
        }
    }

    /**
     * Fetches the intraday series for the selected range.
     *
     * Alpaca allows 200 requests a minute, so unlike the daily bars this is not
     * a scarce request — but a five-minute-old copy is still current enough to
     * redraw from, which is what [StalenessPolicy.intradayCandles] was defined
     * for and never used on.
     */
    private suspend fun loadIntraday(registry: ProviderRegistry, snapshots: SnapshotStore?) {
        val range = _state.value.selectedRange
        val resolution = range.resolution
        val from = range.intradayFetchStart()
        val to = Clock.System.now()

        try {
            val fetchedAt = _state.value.intradayFetchedAt[resolution]
            if (fetchedAt != null && !isForcingRefresh &&
                _state.value.intradayByResolution[resolution].orEmpty().isNotEmpty() &&
                StalenessPolicy.intradayCandles.evaluate(fetchedAt) is Freshness.Fresh
            ) {
                _state.update { it.copy(intradayErrors = it.intradayErrors - resolution) }
                return
            }
            // Draw the held copy at once so the chart is not blank while the
            // request runs. It is never treated as current: `PriceBarDTO`
            // carries no observation time, so the age of a stored bar is
            // unknowable here and the fetch below goes ahead regardless.
            if (snapshots != null &&
                _state.value.intradayByResolution[resolution].orEmpty().isEmpty()
            ) {
                val held = runCatching { snapshots.bars(symbol, from, to, resolution) }
                    .getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        intradayByResolution = it.intradayByResolution +
                            (resolution to held.map { dto -> priceBar(dto, resolution) }),
                    )
                }
            }

            _state.update { it.copy(isLoadingIntraday = true) }
            try {
                val fetched = registry.marketData.bars(symbol, resolution, from, to)
                _state.update { current ->
                    // An empty response must not erase a series already held.
                    // Alpaca returns no bars for a window with no trades, and a
                    // public holiday is not a reason to blank yesterday's chart.
                    val keep = fetched.isEmpty() &&
                        current.intradayByResolution[resolution].orEmpty().isNotEmpty()
                    current.copy(
                        intradayByResolution = if (keep) {
                            current.intradayByResolution
                        } else {
                            current.intradayByResolution +
                                (resolution to fetched.map { priceBar(it, resolution) })
                        },
                        intradayFetchedAt = current.intradayFetchedAt +
                            (resolution to Clock.System.now()),
                        intradayErrors = current.intradayErrors - resolution,
                    )
                }
                if (snapshots != null) {
                    runCatching {
                        snapshots.recordBars(fetched, symbol, resolution, registry.marketData.id)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val mapped = error as? APIError
                    ?: APIError.Transport(DataProviderID.Alpaca, error.message ?: "unknown error")
                logger.e { "Intraday failed for $symbol: ${mapped.shortDescription}" }
                _state.update {
                    it.copy(intradayErrors = it.intradayErrors + (resolution to mapped))
                }
            } finally {
                _state.update { it.copy(isLoadingIntraday = false) }
            }
        } finally {
            _state.update { it.copy(attemptedIntraday = it.attemptedIntraday + resolution) }
        }
    }

    /**
     * Loads the market series the attribution rests on.
     *
     * Failures are swallowed on purpose: attribution is additional context, and
     * losing it must not mark the page as failed or hide the price move it
     * annotates. The UI shows attribution only when it exists.
     */
    private suspend fun loadMarketContext(registry: ProviderRegistry) {
        coroutineScope {
            listOf(
                async { loadMarketHistory(registry) },
                async { loadMarketIntraday(registry) },
            ).awaitAll()
        }
    }

    private suspend fun loadMarketHistory(registry: ProviderRegistry) {
        val macro = registry.macro ?: return
        val from = ChartRange.FiveYear.startDate()
        try {
            val observations = macro.observations(
                Benchmark.marketSeriesID, from, Clock.System.now(),
            )
            _state.update {
                it.copy(marketCloses = observations.map { o -> ClosePoint(o.date, o.value) })
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.e { "Market context unavailable for $symbol: ${error.message}" }
        }
    }

    /**
     * The index itself is end-of-day, so the current session comes from a
     * broad-market ETF. Declared a proxy everywhere it is shown.
     */
    private suspend fun loadMarketIntraday(registry: ProviderRegistry) {
        if (symbol == Benchmark.marketProxySymbol) return
        val proxy = runCatching { registry.marketData.quote(Benchmark.marketProxySymbol) }
            .getOrNull()
        _state.update { it.copy(marketIntradayMove = proxy?.changePercent) }
    }

    private fun computeBeta() {
        val current = _state.value
        if (current.bars.isEmpty() || current.marketCloses.isEmpty()) {
            _state.update { it.copy(beta = null, sectorFactor = null) }
            return
        }
        val beta = RelativeAnalysis.beta(
            RelativeAnalysis.align(current.bars, current.marketCloses),
        )
        _state.update { it.copy(beta = beta) }

        val benchmark = current.sectorBenchmark
        if (benchmark == null || current.sectorBars.isEmpty()) {
            _state.update { it.copy(sectorFactor = null) }
            return
        }
        val factor = RelativeAnalysis.sectorFactor(
            RelativeAnalysis.align(current.bars, current.marketCloses, current.sectorBars),
            name = benchmark.displayName,
            isProxy = benchmark.isProxy || benchmark.fredSeriesID == null,
        )
        _state.update { it.copy(sectorFactor = factor) }
    }

    /**
     * The sector's history, when the company maps to one we track.
     *
     * One bars request per distinct sector, cached in the store afterwards —
     * eleven companies in the same sector share one series, which is what makes
     * this affordable against Tiingo's hourly budget.
     */
    private suspend fun loadSectorHistory(
        registry: ProviderRegistry,
        snapshots: SnapshotStore?,
    ) {
        val benchmark = Benchmark.sector(_state.value.profile?.sector)
        val sectorSymbol = benchmark?.etfSymbol
        if (benchmark == null || sectorSymbol == null || sectorSymbol == symbol) {
            _state.update { it.copy(sectorBenchmark = null) }
            return
        }
        _state.update { it.copy(sectorBenchmark = benchmark) }

        if (snapshots != null) {
            runCatching { snapshots.ensureBenchmark(sectorSymbol, benchmark.displayName) }
            val stored = runCatching {
                snapshots.bars(sectorSymbol, from = ChartRange.FiveYear.startDate())
            }.getOrDefault(emptyList())
            val observed = runCatching {
                snapshots.latestObservedAt(sectorSymbol, StoredDataKind.Bars)
            }.getOrNull()
            if (stored.isNotEmpty() && observed != null && !isForcingRefresh &&
                StalenessPolicy.dailyCandles.evaluate(observed) is Freshness.Fresh
            ) {
                _state.update { it.copy(sectorBars = stored.map { dto -> priceBar(dto) }) }
                return
            }
        }

        try {
            val fetched = registry.marketData.bars(
                sectorSymbol, BarResolution.Daily, ChartRange.FiveYear.startDate(),
                Clock.System.now(),
            )
            _state.update { it.copy(sectorBars = fetched.map { dto -> priceBar(dto) }) }
            if (snapshots != null) {
                runCatching {
                    snapshots.recordBars(
                        fetched, sectorSymbol, BarResolution.Daily, registry.marketData.id,
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Sector context is additional, exactly like market attribution.
            // Losing it must not mark the page as failed.
            logger.e { "Sector history unavailable for $symbol: ${error.message}" }
        }
        val move = runCatching { registry.marketData.quote(sectorSymbol).changePercent }
            .getOrNull()
        _state.update { it.copy(sectorIntradayMove = move) }
    }

    private suspend fun loadMetrics(registry: ProviderRegistry) {
        val provider = registry.metrics ?: return
        try {
            val metrics = provider.metrics(symbol)
            _state.update { it.copy(metrics = metrics, metricsError = null) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _state.update { it.copy(metricsError = error.asAPIError()) }
        }
    }

    /**
     * Ratings, where the tier serves them.
     *
     * Swallows its failure like the other context sections: the rating mix is
     * one dimension of eleven, and losing it must not mark the page as failed.
     */
    private suspend fun loadRatings(registry: ProviderRegistry) {
        val analyst = registry.analyst ?: return
        val ratings = runCatching { analyst.ratings(symbol).lastOrNull() }.getOrNull()
        _state.update { it.copy(ratings = ratings) }
    }

    private suspend fun loadSECSections(registry: ProviderRegistry) {
        val sec = registry.sec ?: return

        // Resolving the CIK is itself a request. With nothing left to fetch it
        // buys nothing, so the check happens before it rather than inside each
        // section below.
        val current = _state.value
        val filingsHeld = current.filings.isNotEmpty() &&
            isHeldCopyFresh(StoredDataKind.Filings, StalenessPolicy.filings)
        val factsHeld = current.fundamentals.isNotEmpty() &&
            isHeldCopyFresh(StoredDataKind.Facts, StalenessPolicy.fundamentals)
        if (filingsHeld && factsHeld) {
            _state.update { it.copy(filingsError = null, fundamentalsError = null) }
            return
        }

        val cik = try {
            current.profile?.cik ?: sec.resolveCIK(symbol)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val mapped = error as? APIError ?: return
            _state.update { it.copy(fundamentalsError = mapped, filingsError = mapped) }
            return
        }

        coroutineScope {
            listOf(
                async { loadFilings(cik, sec) },
                async { loadFundamentals(cik, registry) },
                async { loadInsiders(cik, sec) },
            ).awaitAll()
        }
    }

    private suspend fun loadFilings(cik: String, sec: SECDataProvider) {
        if (_state.value.filings.isNotEmpty() &&
            isHeldCopyFresh(StoredDataKind.Filings, StalenessPolicy.filings)
        ) {
            _state.update { it.copy(filingsError = null) }
            return
        }
        try {
            val filings = sec.filings(cik, listOf("10-K", "10-Q", "8-K", "4"), limit = 15)
            _state.update { it.copy(filings = filings, filingsError = null) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    filingsError = error as? APIError
                        ?: APIError.Transport(DataProviderID.SEC, error.message ?: "unknown"),
                )
            }
        }
    }

    /**
     * Form 4 lines for the past year.
     *
     * Costs one EDGAR request per ownership document, which is why it is
     * bounded by a date rather than pulling a prolific filer's whole history.
     * Its failure is swallowed: insider activity is one dimension of eleven.
     */
    private suspend fun loadInsiders(cik: String, sec: SECDataProvider) {
        val since = Clock.System.now() - 365.days
        val transactions = runCatching { sec.insiderTransactions(cik, since) }
            .getOrDefault(emptyList())
        _state.update { it.copy(insiderTransactions = transactions) }
    }

    private suspend fun loadFundamentals(cik: String, registry: ProviderRegistry) {
        val provider = registry.fundamentals ?: return
        if (_state.value.fundamentals.isNotEmpty() &&
            isHeldCopyFresh(StoredDataKind.Facts, StalenessPolicy.fundamentals)
        ) {
            _state.update { it.copy(fundamentalsError = null) }
            return
        }
        try {
            val facts = provider.facts(symbol, cik, trackedConcepts, fundamentalsSince())
            _state.update { it.copy(fundamentals = facts, fundamentalsError = null) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    fundamentalsError = error as? APIError
                        ?: APIError.Transport(DataProviderID.SEC, error.message ?: "unknown"),
                )
            }
        }
    }

    // MARK: - Detection

    /**
     * Runs the Section 4 detectors over what was just loaded.
     *
     * The prior visit is read *before* anything is stamped: the question is
     * "what happened since last time", and marking this visit first would make
     * the answer permanently "nothing". Detection itself runs whether or not a
     * store is attached, so the panel works in previews and on a first launch;
     * only the persistence and the visit stamp need one.
     */
    private suspend fun detectChanges(snapshots: SnapshotStore?) {
        if (snapshots != null) {
            val lastVisit = runCatching { snapshots.lastViewed(symbol) }.getOrNull()
            _state.update { it.copy(lastVisit = lastVisit) }
        }
        analyseFilings()

        val current = _state.value
        // The quote carries today; the bars stop at the previous close.
        // These judge the present, so no window applies to them.
        val standing = EventDetector.detect(current.bars, current.quote) +
            // What the business did, as distinct from what the price did.
            // Judged against the company's own reported history, on the same
            // rank-not-probability terms as everything above.
            FundamentalDetector.detect(current.fundamentals) +
            restatementEvents(snapshots)

        // The backfill runs over everything loaded rather than over the gap
        // since the last visit, so widening the window is a filter rather than
        // a re-detection. The limits are raised to match: at the old default of
        // ten, a ninety-day window would silently stop at the tenth event.
        val backfilled = EventDetector.priceMoves(current.bars, Instant.DISTANT_PAST, limit = 50) +
            EventDetector.volumeAnomalies(current.bars, Instant.DISTANT_PAST, limit = 50) +
            EventDetector.newFilings(current.filings, Instant.DISTANT_PAST, limit = 50) +
            InsiderActivity.events(current.insiderTransactions, Instant.DISTANT_PAST, limit = 25)

        // Previously detected events, which is where fundamental changes older
        // than the latest reported period come from — `FundamentalDetector` has
        // no backfill, so that depth accrues with use.
        val stored = if (snapshots != null) {
            runCatching { snapshots.events(symbol, limit = 200) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        // The live detector and the backfill can both reach the most recent
        // closed session. They describe it identically, so the list would show
        // the same card twice — the store deduplicates on the same key, but the
        // screen has no such protection. Freshly detected wins over the stored
        // copy, which may predate a restatement.
        val seen = mutableSetOf<String>()
        val standingEvents = standing
            .filter { seen.add(it.naturalKey) }
            .sortedByDescending { it.occurredAt }
        val windowedEvents = (backfilled + stored)
            .filter { seen.add(it.naturalKey) }
            .sortedByDescending { it.occurredAt }

        _state.update {
            it.copy(standingEvents = standingEvents, windowedEvents = windowedEvents)
        }
        // A kind can disappear between loads — a filter pinned to one that is
        // no longer present would empty the panel with no way to tell why.
        _state.update { it.copy(kindFilter = it.kindFilter intersect it.availableKinds.toSet()) }
        applyChangeFilter()

        // Swift guards this with `#if DEBUG`, which Kotlin has no equivalent
        // of. Logged at debug level instead, so a release build drops it at
        // the sink rather than at compile time.
        val detected = _state.value.standingEvents.size + _state.value.windowedEvents.size
        val reading = EventDetector.latestReading(_state.value.bars, _state.value.quote)
        if (reading == null) {
            logger.d { "DETECT $symbol no reading available" }
        } else {
            val measure = AnomalyMeasure.measure(
                reading.reading.percent, reading.priors.map { it.percent },
            )
            logger.d {
                "DETECT $symbol bars=${_state.value.bars.size} " +
                    "intraday=${reading.reading.isIntraday} move=${reading.reading.percent} " +
                    "priors=${reading.priors.size} scale=${measure?.scale ?: -1} " +
                    "dev=${measure?.deviations ?: -1} rank=${measure?.unusualness ?: -1} " +
                    "events=$detected"
            }
        }
    }

    /**
     * Narrows the pools to the chosen window and kinds.
     *
     * Pure filtering over data already in hand, so the menus respond without a
     * request — the point of detecting over all history up front.
     */
    private fun applyChangeFilter() {
        _state.update { current ->
            val start = current.changeWindow.startDate(current.lastVisit)
            val kinds = current.kindFilter
            // With no window — a first visit, before any prior visit is
            // recorded — only the standing events show. Reporting five years of
            // history as "what changed" on first open would be false.
            val dated = start?.let { s -> current.windowedEvents.filter { it.occurredAt > s } }
                ?: emptyList()
            current.copy(
                events = (current.standingEvents + dated)
                    .filter { kinds.isEmpty() || it.kind in kinds }
                    .sortedByDescending { it.occurredAt },
            )
        }
    }

    /**
     * Joins each periodic filing to the figures it reported.
     *
     * Costs nothing: every XBRL fact already carries the accession number of
     * the filing that reported it, and the submissions feed supplies the same
     * accession in the same format, so this is a filter over data in hand.
     *
     * `analyzedAt` on `FilingRecord` is deliberately left unset. It exists so
     * expensive extraction is not repeated, and this is a pass over an array
     * already in memory — writing the stamp would create state nothing reads.
     */
    private fun analyseFilings() {
        _state.update { current ->
            val analyses = current.filings.mapNotNull { filing ->
                FilingAnalysis.analyse(filing, current.fundamentals)
                    ?.let { filing.accessionNumber to it }
            }.toMap()
            current.copy(filingAnalyses = analyses)
        }
    }

    /**
     * Restatements across the tracked concepts.
     *
     * Needs the superseded rows, which only the store holds: the provider
     * returns the current view of a company's history, and every other read
     * path in the app collapses revisions away on purpose.
     *
     * The freshly fetched facts are merged in rather than read back after
     * persisting, because [persist] runs after this and would otherwise delay
     * every restatement by one visit — the amendment would land, be stored, and
     * only be noticed the next time the page was opened.
     *
     * `naturalKey` is one event per kind per day, so several concepts revised
     * in the same amendment collapse to a single stored row regardless. The
     * most recent is chosen deliberately rather than letting an arbitrary one
     * win the race; the others remain visible in the fundamentals section.
     */
    private suspend fun restatementEvents(snapshots: SnapshotStore?): List<DetectedEventDTO> {
        if (snapshots == null) return emptyList()
        val fundamentals = _state.value.fundamentals
        val found = mutableListOf<DetectedEventDTO>()
        for (concept in trackedConcepts) {
            val stored = runCatching {
                snapshots.factRevisions(symbol, concept, fundamentalsSince())
            }.getOrDefault(emptyList())
            val merged = stored + fundamentals.filter { it.concept == concept }
            FundamentalDetector.restatements(merged, concept)?.let { found.add(it) }
        }
        return listOfNotNull(found.maxByOrNull { it.occurredAt })
    }

    /**
     * Records everything the load produced.
     *
     * Deliberately after the UI has its data: persistence must never delay what
     * is on screen, and a write failure must not blank a loaded page.
     *
     * Each write names where its data came from, per source rather than for the
     * page as a whole. A run with a Finnhub and Tiingo key but no SEC contact
     * email holds real prices beside mocked filings, and it is the filings
     * alone that must not be stored — refusing the whole page would throw away
     * real history over an unrelated missing key.
     */
    private suspend fun persist(snapshots: SnapshotStore?, registry: ProviderRegistry) {
        if (snapshots == null) return
        val current = _state.value
        val market = registry.marketData.id
        val documents = registry.sec?.id ?: DataProviderID.SEC
        try {
            current.quote?.let { snapshots.recordQuote(it, symbol, market) }
            if (current.bars.isNotEmpty()) {
                val dtos = current.bars.map {
                    PriceBarDTO(
                        date = it.date, open = it.open, high = it.high, low = it.low,
                        close = it.close, volume = it.volume, adjustedClose = it.adjustedClose,
                    )
                }
                snapshots.recordBars(dtos, symbol, BarResolution.Daily, market)
            }
            if (current.fundamentals.isNotEmpty()) {
                // `registry.fundamentals` is the real SEC extractor or nothing
                // at all, so a synthetic fact cannot reach here today. The
                // argument is passed anyway, and defaults to `Sample` rather
                // than `SEC`: an absent provider means facts of unknown origin,
                // and this is the same reasoning `accepts` was built on —
                // refusing at the door is the version that survives someone
                // adding a mock fundamentals provider later.
                snapshots.recordFacts(
                    current.fundamentals, symbol,
                    registry.fundamentals?.id ?: DataProviderID.Sample,
                )
            }
            if (current.filings.isNotEmpty()) {
                snapshots.recordFilings(current.filings, symbol, documents)
            }
            if (current.insiderTransactions.isNotEmpty()) {
                snapshots.recordInsiders(current.insiderTransactions, symbol, documents)
            }
            if (current.events.isNotEmpty()) {
                // Detection runs over bars and filings together, so an event is
                // only storable when both were real.
                val inputs = if (market.isSynthetic || documents.isSynthetic) {
                    DataProviderID.Sample
                } else {
                    DataProviderID.Computed
                }
                snapshots.recordEvents(current.events, symbol, inputs)
            }
            // Stamped last, and only when the load actually completed.
            // Cancelling mid-load leaves `lastRefreshedAt` null; stamping there
            // would advance the reference point past changes the user never
            // saw, and they would never be reported again.
            if (current.lastRefreshedAt != null) {
                snapshots.markViewed(symbol)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.e { "Persist failed for $symbol: ${error.message}" }
        }
    }

    /**
     * Injects fundamentals so the derived figures can be tested without a
     * provider. The stored value is otherwise only written by a load, and the
     * derivations — growth linking and the free-cash-flow subtraction — are
     * exactly the arithmetic worth pinning down.
     */
    fun applyFundamentalsForTesting(facts: List<FinancialFactDTO>) {
        _state.update { it.copy(fundamentals = facts) }
    }

    private fun priceBar(dto: PriceBarDTO, resolution: BarResolution = BarResolution.Daily) =
        PriceBar(
            date = dto.date, resolution = resolution, open = dto.open, high = dto.high,
            low = dto.low, close = dto.close, volume = dto.volume,
            adjustedClose = dto.adjustedClose,
        )

    companion object {
        internal val logger = Logger.withTag("detail")

        /**
         * The concepts this page works with, named once.
         *
         * The store read and the network fetch must ask for the same set, or a
         * hydrated page renders a series that the fetched page then drops.
         */
        val trackedConcepts: List<FinancialConcept> = listOf(
            FinancialConcept.Revenue,
            FinancialConcept.NetIncome,
            FinancialConcept.GrossProfit,
            FinancialConcept.OperatingIncome,
            FinancialConcept.OperatingCashFlow,
            FinancialConcept.CapitalExpenditures,
            FinancialConcept.CashAndEquivalents,
            FinancialConcept.TotalDebt,
            FinancialConcept.StockholdersEquity,
        )

        /** How far back fundamentals are asked for, on both paths. */
        fun fundamentalsSince(): Instant = Clock.System.now() - (365 * 6).days
    }
}

// MARK: - State

/** Everything the Security Detail page renders, as one value. */
data class SecurityDetailUiState(
    val symbol: String,
    val profile: CompanyProfileDTO? = null,
    val quote: QuoteDTO? = null,
    val bars: List<PriceBar> = emptyList(),
    val metrics: CompanyMetricsDTO? = null,
    val fundamentals: List<FinancialFactDTO> = emptyList(),
    val filings: List<FilingDTO> = emptyList(),
    /**
     * The market's daily closes, for separating "the market moved" from "this
     * company moved". FRED's real S&P 500 index, not an ETF.
     */
    val marketCloses: List<ClosePoint> = emptyList(),
    /**
     * The current session's market move, which FRED cannot supply because it
     * publishes at the close. An ETF stands in, and is labelled as one.
     */
    val marketIntradayMove: Double? = null,
    val beta: Beta? = null,
    /**
     * The sector this company maps to, and its history. Null when the profile
     * carries no sector, or names one we do not track — better than silently
     * comparing a bank against the technology sector.
     */
    val sectorBenchmark: Benchmark? = null,
    val sectorBars: List<PriceBar> = emptyList(),
    val sectorIntradayMove: Double? = null,
    val sectorFactor: SectorFactor? = null,
    /**
     * Published analyst ratings. Estimate revisions need a paid Finnhub tier
     * and are absent by design; what the free tier serves is the rating mix.
     */
    val ratings: RatingSnapshotDTO? = null,
    val insiderTransactions: List<InsiderTransactionDTO> = emptyList(),
    val quoteError: APIError? = null,
    val historyError: APIError? = null,
    val metricsError: APIError? = null,
    val fundamentalsError: APIError? = null,
    val filingsError: APIError? = null,
    /**
     * The changes on screen: the pools below, narrowed to the chosen window and
     * kinds, most recent first.
     */
    val events: List<DetectedEventDTO> = emptyList(),
    val lastVisit: Instant? = null,
    /**
     * Changes that describe the present rather than a moment — the open
     * session, the latest reported period, the newest restatement. They are
     * shown whatever window is chosen, including none, because they are not
     * dated into a window in the first place.
     */
    val standingEvents: List<DetectedEventDTO> = emptyList(),
    /**
     * Changes that happened at a point in time, over all the history loaded,
     * plus everything previously detected and stored. These are what a window
     * selects from.
     */
    val windowedEvents: List<DetectedEventDTO> = emptyList(),
    /**
     * How far back the panel looks. Changing it re-filters what is already in
     * hand; it never costs a request.
     */
    val changeWindow: ChangeWindow = ChangeWindow.LastVisit,
    /**
     * Which kinds to show. Empty means all — an explicit "no kinds" selection
     * would show an empty panel and is not a thing anyone means.
     */
    val kindFilter: Set<EventKind> = emptySet(),
    val isLoading: Boolean = false,
    val lastRefreshedAt: Instant? = null,
    val selectedRange: ChartRange = ChartRange.OneYear,
    /**
     * Intraday bars from Alpaca's IEX feed, for the 1D and 5D charts.
     *
     * Kept in its own property rather than merged into [bars] on purpose. IEX
     * is roughly 2.5% of US equity volume, so a volume anomaly measured against
     * it would be meaningless and a return computed from its prints can differ
     * from the consolidated tape. Nothing but the chart reads this:
     * [visibleBars], and therefore [priceContext], [rangeReturn] and every
     * detector, continue to see the daily series exclusively. The separation is
     * what makes that guarantee structural instead of a convention someone has
     * to remember.
     *
     * Kept per resolution rather than as one list. 1D and 5D ask for different
     * resolutions, and clearing the series on every switch meant toggling
     * between them threw away a good chart and re-fetched it — and left nothing
     * on screen if that fetch failed.
     */
    val intradayByResolution: Map<BarResolution, List<PriceBar>> = emptyMap(),
    val intradayFetchedAt: Map<BarResolution, Instant> = emptyMap(),
    val intradayErrors: Map<BarResolution, APIError> = emptyMap(),
    val isLoadingIntraday: Boolean = false,
    /**
     * Which resolutions have had a fetch attempt run to completion, and whether
     * the daily load has. Only after an attempt has finished can the chart
     * honestly say a range has nothing to draw: before that it has not looked,
     * and reporting "unavailable" from a state nobody has tested is how the
     * chart came to flash an error card on every open.
     */
    val attemptedIntraday: Set<BarResolution> = emptySet(),
    val hasCompletedLoad: Boolean = false,
    /**
     * Bars are the scarcest request in the app — Tiingo's free tier refills
     * about one token every 80 seconds — so a range change reuses what we
     * already fetched rather than re-requesting.
     */
    val loadedBarWindow: Pair<Instant, Instant>? = null,
    /**
     * Observation times of what the store already holds, consulted before a
     * request is spent.
     */
    val storedFreshness: Map<StoredDataKind, Instant> = emptyMap(),
    /** Whether any section on this page was filled from the store. */
    val hydratedFromStore: Boolean = false,
    /** What each periodic filing reported, keyed by accession number. */
    val filingAnalyses: Map<String, FilingAnalysis.Result> = emptyMap(),
)

// MARK: - Insiders

/** Section 10's summary rather than a raw list of Form 4 lines. */
val SecurityDetailUiState.insiderSummary: InsiderActivity.Summary?
    get() = InsiderActivity.summarize(insiderTransactions)

// MARK: - The change panel

/**
 * The subset the user has not seen — everything that occurred after their
 * previous visit. Empty on a first visit by design.
 *
 * Deliberately measured against the pools rather than [SecurityDetailUiState.events]:
 * what is new to the user does not change because they narrowed the panel to
 * one kind.
 */
val SecurityDetailUiState.newSinceLastVisit: List<DetectedEventDTO>
    get() {
        val visit = lastVisit ?: return emptyList()
        return (standingEvents + windowedEvents).filter { it.occurredAt > visit }
    }

/**
 * The kinds actually present in what was loaded, in enum order.
 *
 * Ten of the eighteen [EventKind]s have a producer today. Offering a toggle
 * that can never match is the same defect as a sort that cannot reorder
 * anything, so the menu is derived from the events in hand.
 */
val SecurityDetailUiState.availableKinds: List<EventKind>
    get() {
        val present = (standingEvents + windowedEvents).map { it.kind }.toSet()
        return EventKind.entries.filter { it in present }
    }

/** [availableKinds], grouped under the Section 12 evidence buckets. */
val SecurityDetailUiState.availableKindsByCategory: List<Pair<EvidenceCategory, List<EventKind>>>
    get() {
        val grouped = availableKinds.groupBy { it.evidenceCategory }
        return EvidenceCategory.entries.mapNotNull { category ->
            grouped[category]?.let { category to it }
        }
    }

/**
 * What the chosen window cannot reach, or `null` when it is fully covered.
 *
 * A window that starts before the earliest bar held returns less than was asked
 * for. Saying so is the difference between "the period was quiet" and "the
 * period was not examined".
 */
val SecurityDetailUiState.coverageNote: String?
    get() {
        val start = changeWindow.startDate(lastVisit) ?: return null
        val notes = mutableListOf<String>()
        val earliest = bars.minOfOrNull { it.date }
        if (earliest != null && start < earliest) {
            notes.add(
                "Price and volume history begins ${Format.shortDate(earliest)}, " +
                    "so nothing before that date was examined.",
            )
        }
        if (changeWindow.widensPast(lastVisit)) {
            notes.add(
                "Fundamental changes are judged on the latest reported period only, " +
                    "so earlier ones appear here as they are detected on future visits " +
                    "rather than retroactively.",
            )
        }
        return if (notes.isEmpty()) null else notes.joinToString(" ")
    }

/** Whether this event postdates the user's previous visit. */
fun SecurityDetailUiState.isNew(event: DetectedEventDTO): Boolean {
    val visit = lastVisit ?: return false
    return event.occurredAt > visit
}

/**
 * The analysis belonging to a filing event.
 *
 * Matched on `occurredAt`, which for a filing event is the filing's own
 * `filedAt`. EDGAR dates filings to the day, so an 8-K filed alongside a 10-Q
 * shares the timestamp — requiring an analysis to exist picks the periodic
 * report out of the pair.
 */
fun SecurityDetailUiState.analysis(event: DetectedEventDTO): FilingAnalysis.Result? {
    if (event.kind != EventKind.NewFiling) return null
    return filings
        .firstOrNull {
            it.filedAt == event.occurredAt && filingAnalyses[it.accessionNumber] != null
        }
        ?.let { filingAnalyses[it.accessionNumber] }
}

// MARK: - Freshness and the saved copy

val SecurityDetailUiState.freshness: Freshness
    get() = if (isLoading) {
        Freshness.Refreshing(lastRefreshedAt)
    } else {
        StalenessPolicy.quote.evaluate(lastRefreshedAt)
    }

/**
 * True when a fetch failed and the page fell back to what was on disk.
 *
 * Deliberately not "some of this came from the store": a section skipped
 * because the held copy is still fresh is not a degraded state and does not
 * need announcing. This is the case the user needs told about.
 */
val SecurityDetailUiState.isShowingSavedCopy: Boolean
    get() = hydratedFromStore && (historyError != null || quoteError != null)

/** When the saved copy on screen was observed. */
val SecurityDetailUiState.savedCopyAsOf: Instant?
    get() = storedFreshness[StoredDataKind.Bars]

/**
 * The price to show, falling back to the last stored close when no live quote
 * arrived. Standing alone that would misrepresent a close as a current price,
 * so it is only ever rendered beneath the saved-copy notice that dates it.
 */
val SecurityDetailUiState.displayPrice: Double?
    get() = quote?.last ?: bars.lastOrNull()?.analysisClose

val SecurityDetailUiState.displayChangePercent: Double?
    get() = quote?.changePercent ?: lastStoredSessionChange

/**
 * The day's move in dollars, from the same source as [displayChangePercent]:
 * the quote's when there is a quote, so the two never mix a live price with a
 * stored close.
 */
val SecurityDetailUiState.displayChange: Double?
    get() {
        quote?.let { return it.change }
        val closes = bars.takeLast(2).map { it.analysisClose }
        return if (closes.size == 2) closes[1] - closes[0] else null
    }

/** The most recent closed session's move, from bars alone. */
private val SecurityDetailUiState.lastStoredSessionChange: Double?
    get() {
        val closes = bars.takeLast(2).map { it.analysisClose }
        if (closes.size != 2 || closes[0] == 0.0) return null
        return (closes[1] - closes[0]) / closes[0] * 100
    }

// MARK: - The chart and the returns

/**
 * The market series as bars, so it can go through the same return machinery as
 * everything else.
 */
val SecurityDetailUiState.marketBars: List<PriceBar>
    get() = marketCloses.map {
        PriceBar(
            date = it.date, resolution = BarResolution.Daily, open = it.close, high = it.close,
            low = it.close, close = it.close, adjustedClose = it.close,
        )
    }

/**
 * Return over the selected range for the sector and the market, on the same
 * window as [rangeReturn].
 */
val SecurityDetailUiState.sectorRangeReturn: PeriodReturn?
    get() = ReturnCalculator.trailingReturn(sectorBars, selectedRange.datePeriod)

val SecurityDetailUiState.marketRangeReturn: PeriodReturn?
    get() = ReturnCalculator.trailingReturn(marketBars, selectedRange.datePeriod)

/**
 * Section 7, finally on screen: the arithmetic of out- or under-performance,
 * stated in percentage points rather than adjectives.
 */
val SecurityDetailUiState.relativeToSector: RelativePerformance?
    get() = ReturnCalculator.alignedRelativePerformance(bars, sectorBars, selectedRange.datePeriod)

/**
 * Against FRED's S&P 500, which publishes a session late — which is why this is
 * the aligned form rather than [rangeReturn] less [marketRangeReturn].
 */
val SecurityDetailUiState.relativeToMarket: RelativePerformance?
    get() = ReturnCalculator.alignedRelativePerformance(bars, marketBars, selectedRange.datePeriod)

/**
 * How the sector itself did against the market — Section 13's "sector
 * strength", which is about the industry rather than this company.
 */
val SecurityDetailUiState.sectorVersusMarket: RelativePerformance?
    get() = ReturnCalculator.alignedRelativePerformance(
        sectorBars, marketBars, selectedRange.datePeriod
    )

/**
 * The eleven dimensions of Section 13, and with them Section 12's
 * disconfirming evidence. Recomputed from what is loaded rather than stored, so
 * it can never disagree with the figures above it.
 */
val SecurityDetailUiState.researchProfile: ResearchProfile
    get() = ResearchProfileBuilder.build(
        ResearchProfileBuilder.Inputs(
            bars = bars,
            rangeReturn = rangeReturn,
            relativeToMarket = relativeToMarket,
            sectorRelativeToMarket = sectorVersusMarket,
            sectorName = sectorBenchmark?.displayName,
            fundamentals = fundamentals,
            metrics = metrics,
            ratings = ratings,
            insiderPurchases = insiderSummary?.purchaseCount,
            insiderSales = insiderSummary?.saleCount,
        ),
    )

/** Bars trimmed to the selected range, from the single wide fetch. */
val SecurityDetailUiState.visibleBars: List<PriceBar>
    get() = ChartSeriesBuilder.dailyWindow(bars, selectedRange)

val SecurityDetailUiState.intradayBars: List<PriceBar>
    get() = intradayByResolution[selectedRange.resolution].orEmpty()

val SecurityDetailUiState.intradayError: APIError?
    get() = intradayErrors[selectedRange.resolution]

/**
 * What the chart draws: the IEX series for 1D and 5D, the daily series
 * otherwise. The only reader of [intradayBars] in the app.
 *
 * The session and axis rules live in `ChartSeriesBuilder` so the benchmark
 * chart draws by the same arithmetic rather than a second copy of it.
 */
val SecurityDetailUiState.chartBars: List<PriceBar>
    get() = if (selectedRange.usesIntraday) {
        ChartSeriesBuilder.regularHoursBars(intradayBars, selectedRange)
    } else {
        visibleBars
    }

val SecurityDetailUiState.chartPoints: List<ChartPoint>
    get() = ChartSeriesBuilder.points(chartBars)

val SecurityDetailUiState.chartSegments: List<ChartSegment>
    get() = ChartSeriesBuilder.segments(chartPoints)

val SecurityDetailUiState.chartAxisTicks: List<ChartAxisTick>
    get() = ChartSeriesBuilder.axisTicks(chartPoints, selectedRange)

/**
 * Whether the chart can be drawn, and if not, why.
 *
 * Replaces a bare `visibleBars.count < 2` spinner that never resolved: with
 * Tiingo refusing intraday, 1D and 5D had nothing to load and spun forever. A
 * range with too little data must say so, and the spinner now turns only while
 * a request is genuinely in flight with nothing held.
 */
val SecurityDetailUiState.chartAvailability: ChartAvailability
    get() {
        // Anything drawable wins, and the error becomes a note underneath.
        // Checking the error first meant a failed refresh — a rate limit, a
        // dropped connection, a closed market — replaced a chart we could still
        // draw with an error card. That is the chart "disappearing".
        val drawable = chartBars
        if (drawable.size >= 2) return ChartAvailability.Ready

        if (selectedRange.usesIntraday) {
            if (isLoadingIntraday) return ChartAvailability.Loading
            val error = intradayError
            if (error != null) {
                return ChartAvailability.Unavailable(
                    error.recoverySuggestion ?: error.shortDescription,
                )
            }
            if (selectedRange.resolution !in attemptedIntraday) return ChartAvailability.Loading
            return ChartAvailability.Unavailable(
                "No regular-session bars in this window. The market may not have " +
                    "opened yet, or IEX carried no trades in this symbol.",
            )
        }
        if (isLoading) return ChartAvailability.Loading
        val error = historyError
        if (error != null) {
            return ChartAvailability.Unavailable(
                error.recoverySuggestion ?: error.shortDescription,
            )
        }
        if (!hasCompletedLoad) return ChartAvailability.Loading
        val plural = if (drawable.size == 1) "" else "s"
        return ChartAvailability.Unavailable(
            "Only ${drawable.size} bar$plural of price history is available for this range.",
        )
    }

/**
 * Said under a chart that is still drawable but out of date, so a failed
 * refresh is visible without the chart vanishing to report it.
 */
val SecurityDetailUiState.chartNote: String?
    get() {
        if (chartBars.size < 2) return null
        val error = if (selectedRange.usesIntraday) intradayError else historyError
        return error?.let { "Showing the last copy held — the refresh failed. ${it.shortDescription}" }
    }

/**
 * Price context over the visible range. "Down 18% from its peak" means a
 * different thing over a month than over five years, so it follows the range
 * the user chose rather than everything held.
 */
val SecurityDetailUiState.priceContext: PriceContext?
    get() = ReturnCalculator.priceContext(visibleBars)

/** Return over the selected range, computed from the visible bars. */
val SecurityDetailUiState.rangeReturn: PeriodReturn?
    get() = ReturnCalculator.trailingReturn(bars, selectedRange.datePeriod)

// MARK: - Attribution

/**
 * How much of the most recent move the market accounts for.
 *
 * Null rather than a guess when the market leg for that session is missing — an
 * attribution computed against the wrong day's market move would be worse than
 * none.
 */
val SecurityDetailUiState.latestAttribution: MoveAttribution?
    get() {
        val zone = TimeZone.currentSystemDefault()
        val reading = EventDetector.latestReading(bars, quote)?.reading ?: return null

        val marketMove: Double
        val isProxy: Boolean
        if (reading.isIntraday) {
            marketMove = marketIntradayMove ?: return null
            isProxy = true
        } else {
            val aligned = RelativeAnalysis.align(bars, marketCloses)
            val day = reading.date.toLocalDateTime(zone).date
            val match = aligned.lastOrNull { it.date.toLocalDateTime(zone).date == day }
                ?: return null
            marketMove = match.market
            isProxy = false
        }

        // The sector leg must come from the same session as the market leg. An
        // intraday reading needs the sector's live quote; a closed session needs
        // its bar for that day.
        val sectorMove: Double? = if (reading.isIntraday) {
            sectorIntradayMove
        } else {
            val day = reading.date.toLocalDateTime(zone).date
            val sorted = sectorBars.sortedBy { it.date }
            val index = sorted.indexOfLast { it.date.toLocalDateTime(zone).date == day }
            if (index > 0) {
                ReturnCalculator.simpleReturn(
                    sorted[index - 1].analysisClose, sorted[index].analysisClose,
                )
            } else {
                null
            }
        }

        return RelativeAnalysis.attribute(
            securityMove = reading.percent,
            marketMove = marketMove,
            marketName = if (isProxy) "S&P 500 (SPY)" else "S&P 500",
            beta = beta,
            sector = sectorFactor,
            sectorMove = sectorMove,
            isMarketProxy = isProxy,
        )
    }

// MARK: - Derived views of the data

/**
 * Valuation metrics that have enough history to be ranked. Metrics without it
 * are omitted rather than shown as a bare number implying context.
 */
val SecurityDetailUiState.valuationContexts: List<RankedMetric>
    get() {
        val current = metrics ?: return emptyList()
        return ValuationMetric.all.mapNotNull { metric ->
            // Both halves normalised to one scale before comparison; see
            // ValuationMetric for why that is load-bearing.
            val pair = current.normalized(metric) ?: return@mapNotNull null
            val context = ValuationCalculator.historicalContext(
                current = pair.current,
                history = pair.history,
                lowerIsCheaper = metric.lowerIsCheaper,
                currentIsFromHistory = pair.currentIsFromHistory,
            ) ?: return@mapNotNull null
            RankedMetric(metric = metric, context = context, asOf = pair.asOf)
        }
    }

/** Annual revenue with year-over-year growth, most recent first. */
val SecurityDetailUiState.annualRevenue: List<AnnualFigure>
    get() {
        val annual = fundamentals
            .filter { it.concept == FinancialConcept.Revenue && it.periodKind == FiscalPeriodKind.Annual }
            .sortedBy { it.periodEnd }
        return annual.mapIndexed { index, fact ->
            val previous = if (index > 0) annual[index - 1].value else null
            val growth = previous?.let { ReturnCalculator.simpleReturn(it, fact.value) }
            AnnualFigure(fact = fact, growth = growth)
        }.reversed()
    }

/**
 * Free cash flow per annual period: operating cash flow less capex.
 *
 * Computed rather than read, because issuers do not file an "FCF" concept. A
 * period missing either input yields no figure, never a partial one.
 *
 * Keyed on the period the figures describe, **not** on `fiscalYear`. That field
 * is the *filing's* fiscal context, so a restatement carries the filing's year
 * rather than the period's — which pairs cash-flow figures with the wrong dates
 * and puts a real number under a wrong year. The same mistake was already fixed
 * once in XBRL extraction; it survived here.
 */
val SecurityDetailUiState.annualFreeCashFlow: List<CashFlowPoint>
    get() {
        val ocf = annualValuesByPeriod(FinancialConcept.OperatingCashFlow)
        val capex = annualValuesByPeriod(FinancialConcept.CapitalExpenditures)
        return ocf.mapNotNull { (period, operating) ->
            val spend = capex[period] ?: return@mapNotNull null
            // Capex is filed as a positive outflow.
            CashFlowPoint(period = period, value = operating - abs(spend))
        }.sortedBy { it.period }
    }

/**
 * Annual values for one concept, keyed by the period they describe. Where a
 * period has been restated, the most recently filed figure wins — the original
 * is still in the store, which is the point of keeping it.
 */
private fun SecurityDetailUiState.annualValuesByPeriod(
    concept: FinancialConcept,
): Map<Instant, Double> = fundamentals
    .filter { it.concept == concept && it.periodKind == FiscalPeriodKind.Annual }
    .sortedBy { it.filedAt ?: Instant.DISTANT_PAST }
    .associate { it.periodEnd to it.value }

/** A valuation metric together with where it sits in its own history. */
data class RankedMetric(
    val metric: ValuationMetric,
    val context: HistoricalContext,
    /**
     * The date the current value refers to. Equal to now for live figures;
     * older for metrics sourced from the quarterly series.
     */
    val asOf: Instant,
) {
    val id: String get() = metric.id
}

/** One annual figure with its year-over-year growth, when a prior year exists. */
data class AnnualFigure(
    val fact: FinancialFactDTO,
    val growth: Double?,
) {
    val id: Instant get() = fact.periodEnd

    /**
     * The year the period actually ended in.
     *
     * Not `fact.fiscalYear`: that is the filing's fiscal context, and a restated
     * period carries the restating filing's year. Two different years then
     * render under the same label — observed on screen as two "2022" rows
     * holding FY2022 and FY2021 revenue.
     */
    val periodLabel: String
        get() = fact.periodEnd.toLocalDateTime(TimeZone.currentSystemDefault()).year.toString()
}

data class CashFlowPoint(
    val period: Instant,
    val value: Double,
) {
    val id: Instant get() = period
}
