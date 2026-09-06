import Foundation
import SwiftUI
import OSLog

/// One benchmark's price history, for the page behind a dashboard row.
///
/// Deliberately much smaller than `SecurityDetailViewModel`. A benchmark has a
/// price and nothing else — no filings, no fundamentals, no detectors — so the
/// page is a chart, a range picker, and the sentence explaining what the
/// number actually is.
///
/// Which series backs it decides what the page can offer, and the two are not
/// equally capable:
///
/// - `fredSeriesID` is the real index. Daily closes back decades, published at
///   the close, so there is no intraday to show. 1D and 5D are not offered
///   rather than quietly served from the ETF — putting SPY's intraday under
///   the heading "S&P 500" is the substitution the proxy labelling exists to
///   prevent.
/// - `etfSymbol` is a tradable proxy, and follows the same provider path as
///   any security, intraday included.
@Observable
@MainActor
final class BenchmarkDetailViewModel {
    nonisolated static let logger = Logger(
        subsystem: "com.tylerabitbol.libra", category: "benchmark-detail"
    )

    let benchmark: Benchmark
    private(set) var selectedRange: ChartRange

    /// Bars held per resolution, so switching 1D↔5D or 1M↔3M reuses what was
    /// already fetched. Bars are the scarcest request in the app — Tiingo's
    /// free tier refills about one token every 80 seconds — and the sector
    /// tiles skip history on the dashboard precisely to protect that quota.
    private var barsByResolution: [BarResolution: [PriceBar]] = [:]
    /// How far back each held series reaches, so a wider range refetches and a
    /// narrower one does not.
    private var windowStart: [BarResolution: Date] = [:]
    private var errorsByResolution: [BarResolution: APIError] = [:]
    private(set) var isLoading = false
    /// Which resolutions have had a fetch attempt run to completion. Before
    /// one has, the chart has not looked, and "unavailable" would be a verdict
    /// on a state nobody tested — which rendered as an error card on the frame
    /// between the view appearing and its `.task` starting.
    private var attempted: Set<BarResolution> = []

    private var loadTask: Task<Void, Never>?

    init(benchmark: Benchmark) {
        self.benchmark = benchmark
        // The default range must be one the picker actually offers; an index
        // opening on 1D would show a picker with nothing selected.
        self.selectedRange = Self.ranges(for: benchmark).contains(.oneMonth)
            ? .oneMonth
            : (Self.ranges(for: benchmark).first ?? .oneMonth)
    }

    // MARK: - What this benchmark can show

    /// A filter that can never match is not offered, so index-backed rows show
    /// five ranges rather than seven with two of them permanently empty.
    static func ranges(for benchmark: Benchmark) -> [ChartRange] {
        benchmark.hasRealIndex
            ? ChartRange.allCases.filter { !$0.usesIntraday }
            : ChartRange.allCases
    }

    var availableRanges: [ChartRange] { Self.ranges(for: benchmark) }

    /// FRED is a daily series whatever the range, so a five-year window is
    /// five years of daily closes rather than the weekly bars a security's 5Y
    /// would request from a bar provider.
    var resolution: BarResolution {
        benchmark.hasRealIndex ? .daily : selectedRange.resolution
    }

    /// Index levels are points, not dollars. The S&P 500 at 7691.76 is not
    /// $7,691.76, and the chart's spoken value has to agree with the row.
    var valueFormat: PriceChartView.ValueFormat {
        benchmark.hasRealIndex ? .points : .currency
    }

    /// Names what is actually being displayed, matching the dashboard row.
    var sourceLabel: String {
        if let series = benchmark.fredSeriesID { return "FRED \(series)" }
        return benchmark.etfSymbol ?? "—"
    }

    /// The sentence that keeps the page honest about what it drew.
    var sourceExplanation: String {
        if let note = benchmark.proxyNote { return note }
        let asOf = latestBar.map { Format.shortDate($0.date) } ?? "an unknown date"
        return "The actual index, from FRED. Published end-of-day, so this reflects "
             + "the close on \(asOf) rather than the current level."
    }

    // MARK: - Chart

    private var heldBars: [PriceBar] { barsByResolution[resolution] ?? [] }
    private var currentError: APIError? { errorsByResolution[resolution] }

    var chartBars: [PriceBar] {
        guard selectedRange.usesIntraday else {
            let start = selectedRange.startDate()
            return heldBars.filter { $0.date >= start }.sorted { $0.date < $1.date }
        }
        return ChartSeriesBuilder.regularHoursBars(heldBars, range: selectedRange)
    }

    var chartPoints: [ChartPoint] { ChartSeriesBuilder.points(chartBars) }
    var chartSegments: [ChartSegment] { ChartSeriesBuilder.segments(chartPoints) }
    var chartAxisTicks: [ChartAxisTick] {
        ChartSeriesBuilder.axisTicks(chartPoints, range: selectedRange)
    }

    /// Anything drawable wins, and a failed refresh becomes a note underneath
    /// rather than replacing a chart that could still be drawn.
    var chartAvailability: ChartAvailability {
        if chartBars.count >= 2 { return .ready }
        if isLoading { return .loading }
        if let currentError {
            return .unavailable(currentError.recoverySuggestion
                                ?? currentError.shortDescription)
        }
        guard attempted.contains(resolution) else { return .loading }
        return .unavailable("No history available for this range.")
    }

    /// Shown under a drawable chart when the last refresh failed anyway.
    var chartNote: String? {
        guard chartBars.count >= 2, let currentError else { return nil }
        return "Showing held data — the last refresh failed: "
            + currentError.shortDescription
    }

    /// The headline level comes from the daily series whatever the chart is
    /// showing. Reading it off the chart put an IEX print in the headline on
    /// 5D — a cent away from the close, and about 2.5% of the volume behind
    /// it. The caption under the chart says IEX is shape and not levels; the
    /// number above it has to agree.
    var latestBar: PriceBar? { dailyBars.last }

    var level: Double? { latestBar?.analysisClose }

    var formattedLevel: String {
        guard let level else { return Format.notAvailable }
        return benchmark.hasRealIndex
            ? Format.ratio(level, precision: 2)
            : Format.currency(level)
    }

    // MARK: - Returns

    /// Trailing figures are computed from the daily series only. Reading them
    /// off the intraday bars would measure IEX's ~2.5% of volume against
    /// itself, and a 1M return cannot be computed from five sessions anyway.
    private var dailyBars: [PriceBar] {
        (barsByResolution[.daily] ?? []).sorted { $0.date < $1.date }
    }

    var daily: PeriodReturn? {
        ReturnCalculator.trailingReturn(bars: dailyBars, window: .init(day: -1))
    }
    var weekly: PeriodReturn? {
        ReturnCalculator.trailingReturn(bars: dailyBars, window: .init(day: -7))
    }
    var monthly: PeriodReturn? {
        ReturnCalculator.trailingReturn(bars: dailyBars, window: .init(month: -1))
    }
    var rangeReturn: PeriodReturn? {
        ReturnCalculator.trailingReturn(bars: dailyBars, window: selectedRange.dateInterval)
    }

    // MARK: - Loading

    func select(_ range: ChartRange, registry: ProviderRegistry,
                snapshots: SnapshotStore? = nil) {
        guard availableRanges.contains(range) else { return }
        selectedRange = range
        load(registry: registry, snapshots: snapshots)
    }

    func load(registry: ProviderRegistry, snapshots: SnapshotStore? = nil,
              force: Bool = false) {
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            await self?.performLoad(registry: registry, snapshots: snapshots, force: force)
        }
    }

    /// Pull-to-refresh, which must not return until the work is done. `load`
    /// spawns and returns, so awaiting it would end the spinner while the
    /// request was still in flight.
    func refresh(registry: ProviderRegistry, snapshots: SnapshotStore? = nil) async {
        loadTask?.cancel()
        await performLoad(registry: registry, snapshots: snapshots, force: true)
    }

    private func performLoad(registry: ProviderRegistry, snapshots: SnapshotStore?,
                             force: Bool) async {
        let resolution = self.resolution
        let from = fetchStart()
        defer { attempted.insert(resolution) }

        // Already held, and reaching at least as far back as this range needs.
        if !force, let held = windowStart[resolution], held <= from,
           !(barsByResolution[resolution] ?? []).isEmpty {
            errorsByResolution[resolution] = nil
            return
        }

        isLoading = true
        defer { isLoading = false }

        do {
            let fetched: [PriceBar]
            if let seriesID = benchmark.fredSeriesID {
                guard let macro = registry.macro else {
                    throw APIError.noData(.fred, endpoint: seriesID)
                }
                fetched = PriceBar.closeOnly(from: try await macro.observations(
                    seriesID: seriesID, from: from, to: .now
                ))
            } else {
                fetched = try await etfBars(registry: registry, snapshots: snapshots,
                                            resolution: resolution, from: from)
            }
            guard !Task.isCancelled else { return }

            // An empty response must not erase a series already held: a market
            // closed all window is not a reason to blank the chart.
            if !fetched.isEmpty || (barsByResolution[resolution] ?? []).isEmpty {
                barsByResolution[resolution] = fetched
                windowStart[resolution] = from
            }
            errorsByResolution[resolution] = nil
        } catch let error as APIError {
            errorsByResolution[resolution] = error
            Self.logger.error(
                "Benchmark \(self.benchmark.id, privacy: .public) failed: \(error.shortDescription, privacy: .public)"
            )
        } catch {
            errorsByResolution[resolution] = .transport(
                .tiingo, underlying: error.localizedDescription
            )
        }
    }

    /// The store first, then the network. Opening a sector tile costs one
    /// request the first time and none the second.
    private func etfBars(registry: ProviderRegistry, snapshots: SnapshotStore?,
                         resolution: BarResolution, from: Date) async throws -> [PriceBar] {
        guard let symbol = benchmark.etfSymbol else {
            throw APIError.noData(.tiingo, endpoint: benchmark.displayName)
        }
        let to = Date.now

        if let snapshots {
            _ = try? await snapshots.ensureBenchmark(symbol: symbol,
                                                     name: benchmark.displayName)
            let held = (try? await snapshots.bars(symbol: symbol, from: from, to: to,
                                                  resolution: resolution)) ?? []
            // Draw the held copy at once so the chart is not blank while the
            // request runs; the fetch below still goes ahead.
            if !held.isEmpty, (barsByResolution[resolution] ?? []).isEmpty {
                barsByResolution[resolution] = held.map { bar($0, resolution) }
            }
        }

        let fetched = try await registry.marketData.bars(
            symbol: symbol, resolution: resolution, from: from, to: to
        )
        if let snapshots {
            _ = try? await snapshots.record(bars: fetched, symbol: symbol,
                                            resolution: resolution,
                                            provider: registry.marketData.id)
        }
        return fetched.map { bar($0, resolution) }
    }

    private func bar(_ dto: PriceBarDTO, _ resolution: BarResolution) -> PriceBar {
        PriceBar(date: dto.date, resolution: resolution, open: dto.open, high: dto.high,
                 low: dto.low, close: dto.close, volume: dto.volume,
                 adjustedClose: dto.adjustedClose)
    }

    /// Intraday counts sessions rather than calendar days; daily ranges take
    /// the range's own start, widened so the trailing figures below the chart
    /// have a month of history even on a one-month view.
    private func fetchStart() -> Date {
        if selectedRange.usesIntraday { return selectedRange.intradayFetchStart() }
        let start = selectedRange.startDate()
        let monthAgo = Date.now.addingTimeInterval(-70 * 86_400)
        return min(start, monthAgo)
    }
}
