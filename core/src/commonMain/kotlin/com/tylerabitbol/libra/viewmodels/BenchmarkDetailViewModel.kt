package com.tylerabitbol.libra.viewmodels

import co.touchlab.kermit.Logger
import com.tylerabitbol.libra.calculations.ChartSeriesBuilder
import com.tylerabitbol.libra.calculations.PeriodReturn
import com.tylerabitbol.libra.calculations.ReturnCalculator
import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.Benchmark
import com.tylerabitbol.libra.models.core.ChartAvailability
import com.tylerabitbol.libra.models.core.ChartAxisTick
import com.tylerabitbol.libra.models.core.ChartPoint
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.models.core.ChartSegment
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.core.closeOnly
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.persistence.SnapshotStore
import com.tylerabitbol.libra.services.providers.PriceBarDTO
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import com.tylerabitbol.libra.support.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * How a chart's values should be read: points for an index, dollars for a
 * tradable security.
 *
 * Swift nested this on `PriceChartView`. The UI does not exist until Phase 8
 * and the view model has to decide it, so it lives here and the chart reads it.
 */
enum class ChartValueFormat { Points, Currency }

/**
 * One benchmark's price history, for the page behind a dashboard row.
 *
 * Deliberately much smaller than [SecurityDetailViewModel]. A benchmark has a
 * price and nothing else — no filings, no fundamentals, no detectors — so the
 * page is a chart, a range picker, and the sentence explaining what the number
 * actually is.
 *
 * Which series backs it decides what the page can offer, and the two are not
 * equally capable:
 *
 * - `fredSeriesID` is the real index. Daily closes back decades, published at
 *   the close, so there is no intraday to show. 1D and 5D are not offered
 *   rather than quietly served from the ETF — putting SPY's intraday under the
 *   heading "S&P 500" is the substitution the proxy labelling exists to
 *   prevent.
 * - `etfSymbol` is a tradable proxy, and follows the same provider path as any
 *   security, intraday included.
 */
class BenchmarkDetailViewModel(
    val benchmark: Benchmark,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        BenchmarkDetailUiState(
            benchmark = benchmark,
            // The default range must be one the picker actually offers; an
            // index opening on 1D would show a picker with nothing selected.
            selectedRange = ranges(benchmark).let { available ->
                if (ChartRange.OneMonth in available) {
                    ChartRange.OneMonth
                } else {
                    available.firstOrNull() ?: ChartRange.OneMonth
                }
            },
        ),
    )
    val state: StateFlow<BenchmarkDetailUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    // MARK: - Loading

    fun select(range: ChartRange, registry: ProviderRegistry, snapshots: SnapshotStore? = null) {
        if (range !in _state.value.availableRanges) return
        _state.update { it.copy(selectedRange = range) }
        load(registry, snapshots)
    }

    fun load(registry: ProviderRegistry, snapshots: SnapshotStore? = null, force: Boolean = false) {
        loadJob?.cancel()
        loadJob = scope.launch { performLoad(registry, snapshots, force) }
    }

    /**
     * Pull-to-refresh, which must not return until the work is done. [load]
     * spawns and returns, so awaiting it would end the spinner while the
     * request was still in flight.
     */
    suspend fun refresh(registry: ProviderRegistry, snapshots: SnapshotStore? = null) {
        loadJob?.cancel()
        performLoad(registry, snapshots, force = true)
    }

    private suspend fun performLoad(
        registry: ProviderRegistry,
        snapshots: SnapshotStore?,
        force: Boolean,
    ) {
        val resolution = _state.value.resolution
        val from = fetchStart()
        try {
            // Already held, and reaching at least as far back as this range needs.
            val heldStart = _state.value.windowStart[resolution]
            if (!force && heldStart != null && heldStart <= from &&
                _state.value.barsByResolution[resolution].orEmpty().isNotEmpty()
            ) {
                _state.update { it.copy(errorsByResolution = it.errorsByResolution - resolution) }
                return
            }

            _state.update { it.copy(isLoading = true) }
            try {
                val seriesID = benchmark.fredSeriesID
                val fetched: List<PriceBar> = if (seriesID != null) {
                    val macro = registry.macro
                        ?: throw APIError.NoData(DataProviderID.FRED, endpoint = seriesID)
                    PriceBar.closeOnly(macro.observations(seriesID, from, Clock.System.now()))
                } else {
                    etfBars(registry, snapshots, resolution, from)
                }

                _state.update { current ->
                    // An empty response must not erase a series already held: a
                    // market closed all window is not a reason to blank the chart.
                    val keep = fetched.isEmpty() &&
                        current.barsByResolution[resolution].orEmpty().isNotEmpty()
                    current.copy(
                        barsByResolution = if (keep) {
                            current.barsByResolution
                        } else {
                            current.barsByResolution + (resolution to fetched)
                        },
                        windowStart = if (keep) {
                            current.windowStart
                        } else {
                            current.windowStart + (resolution to from)
                        },
                        errorsByResolution = current.errorsByResolution - resolution,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val mapped = error as? APIError ?: APIError.Transport(
                    DataProviderID.Tiingo,
                    underlying = error.message ?: "unknown error",
                )
                logger.e { "Benchmark ${benchmark.id} failed: ${mapped.shortDescription}" }
                _state.update {
                    it.copy(errorsByResolution = it.errorsByResolution + (resolution to mapped))
                }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        } finally {
            _state.update { it.copy(attempted = it.attempted + resolution) }
        }
    }

    /**
     * The store first, then the network. Opening a sector tile costs one
     * request the first time and none the second.
     */
    private suspend fun etfBars(
        registry: ProviderRegistry,
        snapshots: SnapshotStore?,
        resolution: BarResolution,
        from: Instant,
    ): List<PriceBar> {
        val symbol = benchmark.etfSymbol
            ?: throw APIError.NoData(DataProviderID.Tiingo, endpoint = benchmark.displayName)
        val to = Clock.System.now()

        if (snapshots != null) {
            runCatching { snapshots.ensureBenchmark(symbol, benchmark.displayName) }
            val held = runCatching { snapshots.bars(symbol, from, to, resolution) }
                .getOrDefault(emptyList())
            // Draw the held copy at once so the chart is not blank while the
            // request runs; the fetch below still goes ahead.
            if (held.isNotEmpty() &&
                _state.value.barsByResolution[resolution].orEmpty().isEmpty()
            ) {
                _state.update {
                    it.copy(
                        barsByResolution = it.barsByResolution +
                            (resolution to held.map { dto -> bar(dto, resolution) }),
                    )
                }
            }
        }

        val fetched = registry.marketData.bars(symbol, resolution, from, to)
        if (snapshots != null) {
            runCatching {
                snapshots.recordBars(fetched, symbol, resolution, registry.marketData.id)
            }
        }
        return fetched.map { bar(it, resolution) }
    }

    private fun bar(dto: PriceBarDTO, resolution: BarResolution) = PriceBar(
        date = dto.date, resolution = resolution, open = dto.open, high = dto.high,
        low = dto.low, close = dto.close, volume = dto.volume,
        adjustedClose = dto.adjustedClose,
    )

    /**
     * Intraday counts sessions rather than calendar days; daily ranges take
     * the range's own start, widened so the trailing figures below the chart
     * have a month of history even on a one-month view.
     */
    private fun fetchStart(): Instant {
        val range = _state.value.selectedRange
        if (range.usesIntraday) return range.intradayFetchStart()
        val start = range.startDate()
        val monthAgo = Clock.System.now() - 70.days
        return minOf(start, monthAgo)
    }

    companion object {
        internal val logger = Logger.withTag("benchmark-detail")

        /**
         * A filter that can never match is not offered, so index-backed rows
         * show five ranges rather than seven with two of them permanently
         * empty.
         */
        fun ranges(benchmark: Benchmark): List<ChartRange> = if (benchmark.hasRealIndex) {
            ChartRange.entries.filter { !it.usesIntraday }
        } else {
            ChartRange.entries
        }
    }
}

data class BenchmarkDetailUiState(
    val benchmark: Benchmark,
    val selectedRange: ChartRange = ChartRange.OneMonth,
    /**
     * Bars held per resolution, so switching 1D↔5D or 1M↔3M reuses what was
     * already fetched. Bars are the scarcest request in the app — Tiingo's free
     * tier refills about one token every 80 seconds — and the sector tiles skip
     * history on the dashboard precisely to protect that quota.
     */
    val barsByResolution: Map<BarResolution, List<PriceBar>> = emptyMap(),
    /**
     * How far back each held series reaches, so a wider range refetches and a
     * narrower one does not.
     */
    val windowStart: Map<BarResolution, Instant> = emptyMap(),
    val errorsByResolution: Map<BarResolution, APIError> = emptyMap(),
    val isLoading: Boolean = false,
    /**
     * Which resolutions have had a fetch attempt run to completion. Before one
     * has, the chart has not looked, and "unavailable" would be a verdict on a
     * state nobody tested — which rendered as an error card on the frame
     * between the view appearing and its load starting.
     */
    val attempted: Set<BarResolution> = emptySet(),
)

// MARK: - What this benchmark can show

val BenchmarkDetailUiState.availableRanges: List<ChartRange>
    get() = BenchmarkDetailViewModel.ranges(benchmark)

/**
 * FRED is a daily series whatever the range, so a five-year window is five
 * years of daily closes rather than the weekly bars a security's 5Y would
 * request from a bar provider.
 */
val BenchmarkDetailUiState.resolution: BarResolution
    get() = if (benchmark.hasRealIndex) BarResolution.Daily else selectedRange.resolution

/**
 * Index levels are points, not dollars. The S&P 500 at 7691.76 is not
 * $7,691.76, and the chart's spoken value has to agree with the row.
 */
val BenchmarkDetailUiState.valueFormat: ChartValueFormat
    get() = if (benchmark.hasRealIndex) ChartValueFormat.Points else ChartValueFormat.Currency

/** Names what is actually being displayed, matching the dashboard row. */
val BenchmarkDetailUiState.sourceLabel: String
    get() = benchmark.fredSeriesID?.let { "FRED $it" } ?: benchmark.etfSymbol ?: "—"

/** The sentence that keeps the page honest about what it drew. */
val BenchmarkDetailUiState.sourceExplanation: String
    get() {
        benchmark.proxyNote?.let { return it }
        val asOf = latestBar?.let { Format.shortDate(it.date) } ?: "an unknown date"
        return "The actual index, from FRED. Published end-of-day, so this reflects " +
            "the close on $asOf rather than the current level."
    }

// MARK: - Chart

private val BenchmarkDetailUiState.heldBars: List<PriceBar>
    get() = barsByResolution[resolution].orEmpty()

private val BenchmarkDetailUiState.currentError: APIError?
    get() = errorsByResolution[resolution]

val BenchmarkDetailUiState.chartBars: List<PriceBar>
    get() {
        if (!selectedRange.usesIntraday) {
            val start = selectedRange.startDate()
            return heldBars.filter { it.date >= start }.sortedBy { it.date }
        }
        return ChartSeriesBuilder.regularHoursBars(heldBars, selectedRange)
    }

val BenchmarkDetailUiState.chartPoints: List<ChartPoint>
    get() = ChartSeriesBuilder.points(chartBars)

val BenchmarkDetailUiState.chartSegments: List<ChartSegment>
    get() = ChartSeriesBuilder.segments(chartPoints)

val BenchmarkDetailUiState.chartAxisTicks: List<ChartAxisTick>
    get() = ChartSeriesBuilder.axisTicks(chartPoints, selectedRange)

/**
 * Anything drawable wins, and a failed refresh becomes a note underneath
 * rather than replacing a chart that could still be drawn.
 */
val BenchmarkDetailUiState.chartAvailability: ChartAvailability
    get() {
        if (chartBars.size >= 2) return ChartAvailability.Ready
        if (isLoading) return ChartAvailability.Loading
        currentError?.let {
            return ChartAvailability.Unavailable(it.recoverySuggestion ?: it.shortDescription)
        }
        if (resolution !in attempted) return ChartAvailability.Loading
        return ChartAvailability.Unavailable("No history available for this range.")
    }

/** Shown under a drawable chart when the last refresh failed anyway. */
val BenchmarkDetailUiState.chartNote: String?
    get() {
        if (chartBars.size < 2) return null
        val error = currentError ?: return null
        return "Showing held data — the last refresh failed: ${error.shortDescription}"
    }

/**
 * The headline level comes from the daily series whatever the chart is
 * showing. Reading it off the chart put an IEX print in the headline on 5D — a
 * cent away from the close, and about 2.5% of the volume behind it. The
 * caption under the chart says IEX is shape and not levels; the number above
 * it has to agree.
 */
val BenchmarkDetailUiState.latestBar: PriceBar? get() = dailyBars.lastOrNull()

val BenchmarkDetailUiState.level: Double? get() = latestBar?.analysisClose

val BenchmarkDetailUiState.formattedLevel: String
    get() {
        val level = level ?: return Format.notAvailable
        return if (benchmark.hasRealIndex) {
            Format.ratio(level, precision = 2)
        } else {
            Format.currency(level)
        }
    }

/** The latest session's move, close against the close before it. */
val BenchmarkDetailUiState.sessionChange: Double?
    get() {
        val closes = dailyBars.takeLast(2).map { it.analysisClose }
        return if (closes.size == 2) closes[1] - closes[0] else null
    }

/** [sessionChange] as a percentage of the earlier close; null off a zero base. */
val BenchmarkDetailUiState.sessionChangePercent: Double?
    get() {
        val closes = dailyBars.takeLast(2).map { it.analysisClose }
        if (closes.size != 2 || closes[0] <= 0) return null
        return (closes[1] - closes[0]) / closes[0] * 100
    }

// MARK: - Returns

/**
 * Trailing figures are computed from the daily series only. Reading them off
 * the intraday bars would measure IEX's ~2.5% of volume against itself, and a
 * 1M return cannot be computed from five sessions anyway.
 */
private val BenchmarkDetailUiState.dailyBars: List<PriceBar>
    get() = barsByResolution[BarResolution.Daily].orEmpty().sortedBy { it.date }

val BenchmarkDetailUiState.daily: PeriodReturn?
    get() = ReturnCalculator.trailingReturn(dailyBars, DatePeriod(days = -1))

val BenchmarkDetailUiState.weekly: PeriodReturn?
    get() = ReturnCalculator.trailingReturn(dailyBars, DatePeriod(days = -7))

val BenchmarkDetailUiState.monthly: PeriodReturn?
    get() = ReturnCalculator.trailingReturn(dailyBars, DatePeriod(months = -1))

val BenchmarkDetailUiState.rangeReturn: PeriodReturn?
    get() = ReturnCalculator.trailingReturn(dailyBars, selectedRange.datePeriod)
