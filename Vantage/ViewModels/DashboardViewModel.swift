import Foundation
import SwiftUI

/// One benchmark's current state, including the case where it failed to load.
///
/// Failure is modelled per row rather than for the screen as a whole. Section
/// 21 requires that one bad request degrade one cell, not the dashboard.
struct BenchmarkPerformance: Sendable, Identifiable {
    var id: String { benchmark.id }

    let benchmark: Benchmark
    let quote: QuoteDTO?
    let daily: PeriodReturn?
    let weekly: PeriodReturn?
    let monthly: PeriodReturn?
    let freshness: Freshness
    /// Set when this row failed to load; the row still renders, marked.
    let error: APIError?

    var last: Double? { quote?.last }

    /// Prefers the quote's own previous-close change over a computed one, since
    /// the provider's previous close accounts for corporate actions.
    var dailyPercent: Double? { quote?.changePercent ?? daily?.percent }
}

@Observable
@MainActor
final class DashboardViewModel {
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

        var changeFromPrevious: Double? {
            guard let latest, let previous else { return nil }
            return latest.value - previous.value
        }
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

    private func performLoad(using registry: ProviderRegistry) async {
        isLoading = true
        defer { isLoading = false }

        async let marketRows = Self.rows(for: Benchmark.broadMarket, registry: registry)
        async let volatilityRows = Self.rows(for: Benchmark.volatility, registry: registry)
        async let sectorRows = Self.rows(for: Benchmark.sectors, registry: registry)
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
        registry: ProviderRegistry
    ) async -> [BenchmarkPerformance] {
        await withTaskGroup(of: BenchmarkPerformance.self) { group in
            for benchmark in benchmarks {
                group.addTask { await row(for: benchmark, registry: registry) }
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
        registry: ProviderRegistry
    ) async -> BenchmarkPerformance {
        let provider = registry.marketData
        do {
            let quote = try await provider.quote(symbol: benchmark.symbol)
            let bars = try await provider.bars(
                symbol: benchmark.symbol,
                resolution: .daily,
                from: Date.now.addingTimeInterval(-70 * 86_400),
                to: .now
            )
            let models = bars.map {
                PriceBar(date: $0.date, resolution: .daily, open: $0.open, high: $0.high,
                         low: $0.low, close: $0.close, volume: $0.volume,
                         adjustedClose: $0.adjustedClose)
            }
            return BenchmarkPerformance(
                benchmark: benchmark,
                quote: quote,
                daily: ReturnCalculator.trailingReturn(bars: models, window: .init(day: -1)),
                weekly: ReturnCalculator.trailingReturn(bars: models, window: .init(day: -7)),
                monthly: ReturnCalculator.trailingReturn(bars: models, window: .init(month: -1)),
                freshness: .fresh(asOf: .now),
                error: nil
            )
        } catch let error as APIError {
            return BenchmarkPerformance(
                benchmark: benchmark, quote: nil, daily: nil, weekly: nil, monthly: nil,
                freshness: .failed(previous: nil, reason: error.shortDescription),
                error: error
            )
        } catch {
            return BenchmarkPerformance(
                benchmark: benchmark, quote: nil, daily: nil, weekly: nil, monthly: nil,
                freshness: .failed(previous: nil, reason: "unexpected error"),
                error: .transport(.finnhub, underlying: error.localizedDescription)
            )
        }
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
