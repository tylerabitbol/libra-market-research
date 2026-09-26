package com.tylerabitbol.libra.viewmodels

import co.touchlab.kermit.Logger
import com.tylerabitbol.libra.calculations.PeriodReturn
import com.tylerabitbol.libra.calculations.ReturnCalculator
import com.tylerabitbol.libra.models.core.Benchmark
import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.MacroIndicator
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.core.closeOnly
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.services.providers.MacroDataProvider
import com.tylerabitbol.libra.services.providers.MacroObservationDTO
import com.tylerabitbol.libra.services.providers.MarketDataProvider
import com.tylerabitbol.libra.services.providers.ProviderRegistry
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
import kotlinx.datetime.DatePeriod
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * One benchmark's current state, including the case where it failed to load.
 *
 * Failure is modelled per row rather than for the screen as a whole. Section
 * 21 requires that one bad request degrade one cell, not the dashboard.
 */
data class BenchmarkPerformance(
    val benchmark: Benchmark,
    /**
     * The headline value: an index level for FRED-backed rows, a share price
     * for ETF-backed rows. Which one it is changes how it must be formatted —
     * the S&P 500 at 7691.76 is points, not dollars.
     */
    val level: Double? = null,
    val isIndexLevel: Boolean = false,
    /**
     * The date the level refers to. FRED is end-of-day, so this is often the
     * prior session and the UI must say so rather than implying live data.
     */
    val asOf: Instant? = null,
    val daily: PeriodReturn? = null,
    val weekly: PeriodReturn? = null,
    val monthly: PeriodReturn? = null,
    val freshness: Freshness = Freshness.Missing,
    /** Set when this row failed to load; the row still renders, marked. */
    val error: APIError? = null,
    /**
     * Set when the live price arrived but price history did not, so the UI can
     * distinguish "this security has no history" from "history request failed".
     */
    val historyError: APIError? = null,
) {
    val id: String get() = benchmark.id

    val dailyPercent: Double? get() = daily?.percent

    /** Formatted for display, respecting whether this is points or currency. */
    val formattedLevel: String
        get() {
            val level = level ?: return Format.notAvailable
            return if (isIndexLevel) Format.ratio(level, precision = 2) else Format.currency(level)
        }

    companion object {
        fun failed(benchmark: Benchmark, error: APIError) = BenchmarkPerformance(
            benchmark = benchmark,
            level = null,
            isIndexLevel = benchmark.hasRealIndex,
            asOf = null,
            freshness = Freshness.Failed(previous = null, reason = error.shortDescription),
            error = error,
        )
    }
}

class DashboardViewModel(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /**
     * The registry the rows on screen came from. The model outlives the tab,
     * so entering a key no longer builds a fresh one; without this, rows loaded
     * from the sample providers would count as fresh against the real ones.
     */
    private var loadedRegistry: ProviderRegistry? = null

    data class MacroReading(
        val indicator: MacroIndicator,
        val latest: MacroObservationDTO? = null,
        val previous: MacroObservationDTO? = null,
        val error: APIError? = null,
    ) {
        val id: String get() = indicator.id
    }

    fun load(registry: ProviderRegistry, force: Boolean = false) {
        val sameSource = registry === loadedRegistry
        // Skip a redundant refresh unless the user explicitly pulled to refresh.
        // A load still in flight is left to finish: coming back to the tab
        // restarted it, and spent the Tiingo allowance a second time.
        if (!force && sameSource) {
            if (loadJob?.isActive == true) return
            if (_state.value.overallFreshness is Freshness.Fresh) return
        }
        switchSource(registry)

        loadJob?.cancel()
        loadJob = scope.launch { performLoad(registry) }
    }

    /**
     * Pull-to-refresh, which must not return until the rows have actually
     * landed. [load] spawns and returns, so the spinner ended with the gesture
     * rather than with the data.
     */
    suspend fun refresh(registry: ProviderRegistry) {
        loadJob?.cancel()
        switchSource(registry)
        performLoad(registry)
    }

    /** Drops rows that came from a different set of providers. */
    private fun switchSource(registry: ProviderRegistry) {
        if (registry === loadedRegistry) return
        if (loadedRegistry != null) _state.value = DashboardUiState()
        loadedRegistry = registry
    }

    /**
     * Each group is published the moment it lands. This awaited all four
     * and updated once, so a single slow request held back every row: the
     * Russell 2000's Tiingo history can wait 15 s for a token, and each of
     * its attempts up to 20 s more.
     */
    private suspend fun performLoad(registry: ProviderRegistry) {
        _state.update { it.copy(isLoading = true) }
        try {
            coroutineScope {
                launch {
                    rows(Benchmark.broadMarket, registry, needsHistory = true) { rows ->
                        _state.update { it.copy(market = rows) }
                    }
                }
                launch {
                    rows(Benchmark.volatility, registry, needsHistory = true) { rows ->
                        _state.update { it.copy(volatility = rows) }
                    }
                }
                // Sector tiles render a daily change and nothing else, so
                // fetching 60 days of bars for each was 11 wasted requests
                // against Tiingo's ~50/hour free tier — enough on its own to
                // stall the whole screen.
                launch {
                    rows(Benchmark.sectors, registry, needsHistory = false) { rows ->
                        _state.update { it.copy(sectors = rows) }
                    }
                }
                launch {
                    val macro = macroReadings(registry)
                    _state.update { it.copy(macro = macro) }
                }
            }
            _state.update {
                it.copy(lastRefreshedAt = Clock.System.now(), globalError = null)
            }
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }

    companion object {
        internal val logger = Logger.withTag("dashboard")

        private val macroWindow = 120.days

        /**
         * Fetches each benchmark concurrently, isolating failures to their own
         * row, and hands the group to [publish] once the prices are in.
         *
         * History comes second. A row that needs it is published with its
         * price and daily change first, then again with the week and month,
         * so the scarcest quota no longer decides when the group appears.
         */
        private suspend fun rows(
            benchmarks: List<Benchmark>,
            registry: ProviderRegistry,
            needsHistory: Boolean,
            publish: (List<BenchmarkPerformance>) -> Unit,
        ) = coroutineScope {
            // Awaited in declaration order, so the restore-the-order step
            // Swift's task group needed is unnecessary here.
            val priced = benchmarks.map { benchmark ->
                async { row(benchmark, registry) }
            }.awaitAll()
            publish(priced)

            if (!needsHistory) return@coroutineScope
            val pending = priced.filter { it.awaitsHistory(registry) }
            if (pending.isEmpty()) return@coroutineScope
            val filled = pending.map { row ->
                async { withHistory(row, row.benchmark.etfSymbol!!, registry.marketData) }
            }.awaitAll().associateBy { it.id }
            publish(priced.map { filled[it.id] ?: it })
        }

        /** An ETF row whose price landed; FRED rows carry their own history. */
        private fun BenchmarkPerformance.awaitsHistory(registry: ProviderRegistry): Boolean =
            error == null && benchmark.etfSymbol != null &&
                (benchmark.fredSeriesID == null || registry.macro == null)

        private suspend fun row(
            benchmark: Benchmark,
            registry: ProviderRegistry,
        ): BenchmarkPerformance = try {
            // Prefer the genuine index series where one exists. FRED gives the
            // real S&P 500 and the real VIX for free; the ETF is only a
            // fallback for benchmarks FRED does not publish.
            val seriesID = benchmark.fredSeriesID
            val macro = registry.macro
            if (seriesID != null && macro != null) {
                indexRow(benchmark, seriesID, macro)
            } else {
                val symbol = benchmark.etfSymbol
                if (symbol == null) {
                    BenchmarkPerformance.failed(
                        benchmark,
                        APIError.NoData(DataProviderID.FRED, endpoint = benchmark.displayName),
                    )
                } else {
                    etfRow(benchmark, symbol, registry.marketData)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            BenchmarkPerformance.failed(
                benchmark,
                error as? APIError ?: APIError.Transport(
                    DataProviderID.FRED,
                    underlying = error.message ?: "unknown error",
                ),
            )
        }

        /**
         * A real index, built from a FRED daily series.
         *
         * FRED publishes closes only, so the synthetic bars carry the same
         * value for open/high/low. That is fine for the return arithmetic here,
         * which reads `analysisClose` — but it means these bars must never be
         * used for range or candlestick display.
         */
        private suspend fun indexRow(
            benchmark: Benchmark,
            seriesID: String,
            macro: MacroDataProvider,
        ): BenchmarkPerformance {
            val now = Clock.System.now()
            val observations = macro.observations(seriesID, now - 400.days, now)
            val latest = observations.lastOrNull()
                ?: throw APIError.NoData(DataProviderID.FRED, endpoint = seriesID)

            val bars = PriceBar.closeOnly(observations)

            return BenchmarkPerformance(
                benchmark = benchmark,
                level = latest.value,
                isIndexLevel = true,
                asOf = latest.date,
                daily = ReturnCalculator.trailingReturn(bars, DatePeriod(days = -1)),
                weekly = ReturnCalculator.trailingReturn(bars, DatePeriod(days = -7)),
                monthly = ReturnCalculator.trailingReturn(bars, DatePeriod(months = -1)),
                freshness = Freshness.Fresh(asOf = latest.date),
            )
        }

        /**
         * A tradable proxy, quoted live. Its week and month come from Tiingo
         * history, which [withHistory] adds once the price is on screen.
         */
        private suspend fun etfRow(
            benchmark: Benchmark,
            symbol: String,
            provider: MarketDataProvider,
        ): BenchmarkPerformance {
            val quote = provider.quote(symbol)
            val now = Clock.System.now()

            // The provider's own previous close accounts for corporate actions,
            // so it beats a computed daily figure when available.
            val daily = quote.changePercent?.let { percent ->
                PeriodReturn(
                    percent = percent,
                    startDate = quote.quoteTime ?: now,
                    endDate = quote.quoteTime ?: now,
                    startPrice = quote.previousClose ?: 0.0,
                    endPrice = quote.last,
                    isFullWindow = true,
                )
            }

            return BenchmarkPerformance(
                benchmark = benchmark,
                level = quote.last,
                isIndexLevel = false,
                asOf = quote.quoteTime ?: now,
                daily = daily,
                freshness = Freshness.Fresh(asOf = now),
            )
        }

        /**
         * History is a separate provider, a separate failure domain, and by
         * far the scarcer quota. Fetch it only when the row will show it, and
         * never let its failure take down a price we already have.
         */
        private suspend fun withHistory(
            row: BenchmarkPerformance,
            symbol: String,
            provider: MarketDataProvider,
        ): BenchmarkPerformance {
            val now = Clock.System.now()
            val bars = try {
                provider.bars(symbol, BarResolution.Daily, now - 70.days, now).map {
                    PriceBar(
                        date = it.date, resolution = BarResolution.Daily,
                        open = it.open, high = it.high, low = it.low, close = it.close,
                        volume = it.volume, adjustedClose = it.adjustedClose,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Swallowing this silently made a real failure look like
                // "no history exists". Record it so the cause is visible.
                val historyError = error as? APIError ?: APIError.Transport(
                    DataProviderID.Tiingo,
                    underlying = error.message ?: "unknown error",
                )
                logger.e { "History unavailable for $symbol: $historyError" }
                return row.copy(historyError = historyError)
            }

            return row.copy(
                daily = row.daily ?: ReturnCalculator.trailingReturn(bars, DatePeriod(days = -1)),
                weekly = ReturnCalculator.trailingReturn(bars, DatePeriod(days = -7)),
                monthly = ReturnCalculator.trailingReturn(bars, DatePeriod(months = -1)),
            )
        }

        private suspend fun macroReadings(registry: ProviderRegistry): List<MacroReading> {
            val macro = registry.macro ?: return emptyList()
            // A deliberately short list — Section 3 warns against overloading
            // the dashboard. The full set lives in the Research area.
            val headline = MacroIndicator.defaults.filter {
                it.id in setOf("fedFunds", "tenYear", "cpi", "unemployment")
            }
            val now = Clock.System.now()

            return coroutineScope {
                headline.map { indicator ->
                    async {
                        try {
                            // Only the last two points are read. 120 days
                            // still holds two of a monthly series published
                            // six weeks late, where 400 fetched a year of
                            // daily rates to throw away.
                            val observations = macro
                                .observations(indicator.seriesID, now - macroWindow, now)
                                .sortedBy { it.date }
                            MacroReading(
                                indicator = indicator,
                                latest = observations.lastOrNull(),
                                previous = observations.dropLast(1).lastOrNull(),
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            MacroReading(
                                indicator = indicator,
                                error = error as? APIError ?: APIError.Transport(
                                    DataProviderID.FRED,
                                    underlying = error.message ?: "unknown error",
                                ),
                            )
                        }
                    }
                }.awaitAll()
            }
        }
    }
}

data class DashboardUiState(
    val market: List<BenchmarkPerformance> = emptyList(),
    val sectors: List<BenchmarkPerformance> = emptyList(),
    val volatility: List<BenchmarkPerformance> = emptyList(),
    val macro: List<DashboardViewModel.MacroReading> = emptyList(),
    val isLoading: Boolean = false,
    val lastRefreshedAt: Instant? = null,
    /** Set only when the whole refresh could not start, e.g. no provider at all. */
    val globalError: APIError? = null,
) {
    val overallFreshness: Freshness
        get() = if (isLoading) {
            Freshness.Refreshing(previous = lastRefreshedAt)
        } else {
            StalenessPolicy.quote.evaluate(lastRefreshedAt)
        }
}
