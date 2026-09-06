import Foundation
import SwiftUI
import OSLog

/// One benchmark's current state, including the case where it failed to load.
///
/// Failure is modelled per row rather than for the screen as a whole. Section
/// 21 requires that one bad request degrade one cell, not the dashboard.
struct BenchmarkPerformance: Sendable, Identifiable {
    var id: String { benchmark.id }

    let benchmark: Benchmark
    /// The headline value: an index level for FRED-backed rows, a share price
    /// for ETF-backed rows. Which one it is changes how it must be formatted —
    /// the S&P 500 at 7691.76 is points, not dollars.
    let level: Double?
    let isIndexLevel: Bool
    /// The date the level refers to. FRED is end-of-day, so this is often the
    /// prior session and the UI must say so rather than implying live data.
    let asOf: Date?

    let daily: PeriodReturn?
    let weekly: PeriodReturn?
    let monthly: PeriodReturn?
    let freshness: Freshness
    /// Set when this row failed to load; the row still renders, marked.
    let error: APIError?
    /// Set when the live price arrived but price history did not, so the UI can
    /// distinguish "this security has no history" from "history request failed".
    var historyError: APIError?

    var dailyPercent: Double? { daily?.percent }

    /// Formatted for display, respecting whether this is points or currency.
    var formattedLevel: String {
        guard let level else { return Format.notAvailable }
        return isIndexLevel ? Format.ratio(level, precision: 2) : Format.currency(level)
    }

    static func failed(_ benchmark: Benchmark, _ error: APIError) -> BenchmarkPerformance {
        BenchmarkPerformance(
            benchmark: benchmark, level: nil, isIndexLevel: benchmark.hasRealIndex,
            asOf: nil, daily: nil, weekly: nil, monthly: nil,
            freshness: .failed(previous: nil, reason: error.shortDescription),
            error: error, historyError: nil
        )
    }
}

@Observable
@MainActor
final class DashboardViewModel {
    nonisolated static let logger = Logger(
        subsystem: "com.tylerabitbol.libra", category: "dashboard"
    )

    private(set) var market: [BenchmarkPerformance] = []
    private(set) var sectors: [BenchmarkPerformance] = []
    private(set) var volatility: [BenchmarkPerformance] = []
    private(set) var macro: [MacroReading] = []

    private(set) var isLoading = false
    private(set) var lastRefreshedAt: Date?
    /// Set only when the whole refresh could not start, e.g. no provider at all.
    private(set) var globalError: APIError?

    private var loadTask: Task<Void, Never>?

    struct MacroReading: Sendable, Identifiable {
        var id: String { indicator.id }
        let indicator: MacroIndicator
        let latest: MacroObservationDTO?
        let previous: MacroObservationDTO?
        let error: APIError?
    }

    var overallFreshness: Freshness {
        if isLoading { return .refreshing(previous: lastRefreshedAt) }
        return StalenessPolicy.quote.evaluate(lastUpdated: lastRefreshedAt)
    }

    func load(using registry: ProviderRegistry, force: Bool = false) {
        // Skip a redundant refresh unless the user explicitly pulled to refresh.
        if !force, case .fresh = overallFreshness { return }

        loadTask?.cancel()
        loadTask = Task { [weak self] in
            await self?.performLoad(using: registry)
        }
    }

    /// Pull-to-refresh, which must not return until the rows have actually
    /// landed. `load` spawns and returns, so the spinner ended with the
    /// gesture rather than with the data.
    func refresh(using registry: ProviderRegistry) async {
        loadTask?.cancel()
        await performLoad(using: registry)
    }

    private func performLoad(using registry: ProviderRegistry) async {
        isLoading = true
        defer { isLoading = false }

        async let marketRows = Self.rows(for: Benchmark.broadMarket, registry: registry,
                                         needsHistory: true)
        async let volatilityRows = Self.rows(for: Benchmark.volatility, registry: registry,
                                             needsHistory: true)
        // Sector tiles render a daily change and nothing else, so fetching 60
        // days of bars for each was 11 wasted requests against Tiingo's ~50/hour
        // free tier — enough on its own to stall the whole screen.
        async let sectorRows = Self.rows(for: Benchmark.sectors, registry: registry,
                                         needsHistory: false)
        async let macroRows = Self.macroReadings(registry: registry)

        let (marketResult, volatilityResult, sectorResult, macroResult) =
            await (marketRows, volatilityRows, sectorRows, macroRows)

        guard !Task.isCancelled else { return }

        market = marketResult
        volatility = volatilityResult
        sectors = sectorResult
        macro = macroResult
        lastRefreshedAt = .now
        globalError = nil
    }

    /// Fetches each benchmark concurrently, isolating failures to their own row.
    private static func rows(
        for benchmarks: [Benchmark],
        registry: ProviderRegistry,
        needsHistory: Bool
    ) async -> [BenchmarkPerformance] {
        await withTaskGroup(of: BenchmarkPerformance.self) { group in
            for benchmark in benchmarks {
                group.addTask {
                    await row(for: benchmark, registry: registry, needsHistory: needsHistory)
                }
            }
            var results: [BenchmarkPerformance] = []
            for await result in group { results.append(result) }
            // Restore the declared order; task groups complete out of order.
            let order = Dictionary(uniqueKeysWithValues: benchmarks.enumerated().map { ($1.id, $0) })
            return results.sorted { (order[$0.id] ?? 0) < (order[$1.id] ?? 0) }
        }
    }

    private static func row(
        for benchmark: Benchmark,
        registry: ProviderRegistry,
        needsHistory: Bool
    ) async -> BenchmarkPerformance {
        do {
            // Prefer the genuine index series where one exists. FRED gives the
            // real S&P 500 and the real VIX for free; the ETF is only a
            // fallback for benchmarks FRED does not publish.
            if let seriesID = benchmark.fredSeriesID, let macro = registry.macro {
                return try await indexRow(benchmark, seriesID: seriesID, macro: macro)
            }
            guard let symbol = benchmark.etfSymbol else {
                return .failed(benchmark, .noData(.fred, endpoint: benchmark.displayName))
            }
            return try await etfRow(benchmark, symbol: symbol, provider: registry.marketData,
                                    needsHistory: needsHistory)
        } catch let error as APIError {
            return .failed(benchmark, error)
        } catch {
            return .failed(benchmark, .transport(.fred, underlying: error.localizedDescription))
        }
    }

    /// A real index, built from a FRED daily series.
    ///
    /// FRED publishes closes only, so the synthetic bars carry the same value
    /// for open/high/low. That is fine for the return arithmetic here, which
    /// reads `analysisClose` — but it means these bars must never be used for
    /// range or candlestick display.
    private static func indexRow(
        _ benchmark: Benchmark,
        seriesID: String,
        macro: any MacroDataProvider
    ) async throws -> BenchmarkPerformance {
        let observations = try await macro.observations(
            seriesID: seriesID,
            from: Date.now.addingTimeInterval(-400 * 86_400),
            to: .now
        )
        guard let latest = observations.last else {
            throw APIError.noData(.fred, endpoint: seriesID)
        }

        let bars = PriceBar.closeOnly(from: observations)

        return BenchmarkPerformance(
            benchmark: benchmark,
            level: latest.value,
            isIndexLevel: true,
            asOf: latest.date,
            daily: ReturnCalculator.trailingReturn(bars: bars, window: .init(day: -1)),
            weekly: ReturnCalculator.trailingReturn(bars: bars, window: .init(day: -7)),
            monthly: ReturnCalculator.trailingReturn(bars: bars, window: .init(month: -1)),
            freshness: .fresh(asOf: latest.date),
            error: nil, historyError: nil
        )
    }

    /// A tradable proxy, quoted live and backed by Tiingo history.
    private static func etfRow(
        _ benchmark: Benchmark,
        symbol: String,
        provider: any MarketDataProvider,
        needsHistory: Bool
    ) async throws -> BenchmarkPerformance {
        let quote = try await provider.quote(symbol: symbol)
        var historyError: APIError?

        // History is a separate provider, a separate failure domain, and by far
        // the scarcer quota. Fetch it only when the row will show it, and never
        // let its failure take down a price we already have.
        var bars: [PriceBar] = []
        if needsHistory {
            do {
                bars = try await provider.bars(
                    symbol: symbol, resolution: .daily,
                    from: Date.now.addingTimeInterval(-70 * 86_400), to: .now
                ).map {
                    PriceBar(date: $0.date, resolution: .daily, open: $0.open, high: $0.high,
                             low: $0.low, close: $0.close, volume: $0.volume,
                             adjustedClose: $0.adjustedClose)
                }
            } catch {
                // Swallowing this silently made a real failure look like
                // "no history exists". Record it so the cause is visible.
                historyError = error as? APIError
                    ?? .transport(.tiingo, underlying: error.localizedDescription)
                Self.logger.error(
                    "History unavailable for \(symbol, privacy: .public): \(String(describing: historyError), privacy: .public)"
                )
                bars = []
            }
        }

        // The provider's own previous close accounts for corporate actions, so
        // it beats a computed daily figure when available.
        let daily = quote.changePercent.map {
            PeriodReturn(percent: $0,
                         startDate: quote.quoteTime ?? .now, endDate: quote.quoteTime ?? .now,
                         startPrice: quote.previousClose ?? 0, endPrice: quote.last,
                         isFullWindow: true)
        } ?? ReturnCalculator.trailingReturn(bars: bars, window: .init(day: -1))

        return BenchmarkPerformance(
            benchmark: benchmark,
            level: quote.last,
            isIndexLevel: false,
            asOf: quote.quoteTime ?? .now,
            daily: daily,
            weekly: ReturnCalculator.trailingReturn(bars: bars, window: .init(day: -7)),
            monthly: ReturnCalculator.trailingReturn(bars: bars, window: .init(month: -1)),
            freshness: .fresh(asOf: .now),
            error: nil,
            historyError: historyError
        )
    }

    private static func macroReadings(registry: ProviderRegistry) async -> [MacroReading] {
        guard let macro = registry.macro else { return [] }
        // A deliberately short list — Section 3 warns against overloading the
        // dashboard. The full set lives in the Research area.
        let headline = MacroIndicator.defaults.filter {
            ["fedFunds", "tenYear", "cpi", "unemployment"].contains($0.id)
        }

        return await withTaskGroup(of: MacroReading.self) { group in
            for indicator in headline {
                group.addTask {
                    do {
                        let observations = try await macro.observations(
                            seriesID: indicator.seriesID,
                            from: Date.now.addingTimeInterval(-400 * 86_400),
                            to: .now
                        ).sorted { $0.date < $1.date }
                        return MacroReading(
                            indicator: indicator,
                            latest: observations.last,
                            previous: observations.dropLast().last,
                            error: nil
                        )
                    } catch let error as APIError {
                        return MacroReading(indicator: indicator, latest: nil, previous: nil, error: error)
                    } catch {
                        return MacroReading(
                            indicator: indicator, latest: nil, previous: nil,
                            error: .transport(.fred, underlying: error.localizedDescription)
                        )
                    }
                }
            }
            var results: [MacroReading] = []
            for await result in group { results.append(result) }
            let order = Dictionary(uniqueKeysWithValues: headline.enumerated().map { ($1.id, $0) })
            return results.sorted { (order[$0.id] ?? 0) < (order[$1.id] ?? 0) }
        }
    }
}
