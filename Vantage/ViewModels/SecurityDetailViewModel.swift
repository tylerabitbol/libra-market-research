import Foundation
import SwiftUI
import OSLog

/// Loads everything the Security Detail page shows.
///
/// Each section loads independently and records its own failure. Section 21
/// requires that one provider failing degrades one section rather than the
/// screen — and with four providers on three different rate limits, partial
/// success is the normal case rather than an edge case.
@Observable
@MainActor
final class SecurityDetailViewModel {
    nonisolated static let logger = Logger(
        subsystem: "com.tylerabitbol.vantage", category: "detail"
    )

    let symbol: String

    private(set) var profile: CompanyProfileDTO?
    private(set) var quote: QuoteDTO?
    private(set) var bars: [PriceBar] = []
    private(set) var metrics: CompanyMetricsDTO?
    private(set) var fundamentals: [FinancialFactDTO] = []
    private(set) var filings: [FilingDTO] = []

    private(set) var quoteError: APIError?
    private(set) var historyError: APIError?
    private(set) var metricsError: APIError?
    private(set) var fundamentalsError: APIError?
    private(set) var filingsError: APIError?

    private(set) var isLoading = false
    private(set) var lastRefreshedAt: Date?
    private(set) var selectedRange: ChartRange = .oneYear

    private var loadTask: Task<Void, Never>?
    /// Bars are the scarcest request in the app — Tiingo's free tier refills
    /// about one token every 80 seconds — so a range change reuses what we
    /// already fetched rather than re-requesting.
    private var loadedBarWindow: (from: Date, to: Date)?

    init(symbol: String) {
        self.symbol = symbol.uppercased()
    }

    var freshness: Freshness {
        if isLoading { return .refreshing(previous: lastRefreshedAt) }
        return StalenessPolicy.quote.evaluate(lastUpdated: lastRefreshedAt)
    }

    /// Bars trimmed to the selected range, from the single wide fetch.
    var visibleBars: [PriceBar] {
        let start = selectedRange.startDate()
        return bars.filter { $0.date >= start }.sorted { $0.date < $1.date }
    }

    /// Return over the selected range, computed from the visible bars.
    var rangeReturn: PeriodReturn? {
        ReturnCalculator.trailingReturn(bars: bars, window: selectedRange.dateInterval)
    }

    func select(_ range: ChartRange, registry: ProviderRegistry) {
        selectedRange = range
        // Only re-fetch when the new range reaches back further than what we hold.
        let needed = range.startDate()
        if let window = loadedBarWindow, window.from <= needed { return }
        Task { await loadHistory(using: registry) }
    }

    func load(using registry: ProviderRegistry, force: Bool = false) {
        if !force, case .fresh = freshness { return }
        loadTask?.cancel()
        loadTask = Task { [weak self] in await self?.performLoad(using: registry) }
    }

    private func performLoad(using registry: ProviderRegistry) async {
        isLoading = true
        defer { isLoading = false }

        // Independent sections, run concurrently, each swallowing only its own
        // failure into its own error slot.
        async let profileWork: Void = loadProfile(using: registry)
        async let quoteWork: Void = loadQuote(using: registry)
        async let historyWork: Void = loadHistory(using: registry)
        async let metricsWork: Void = loadMetrics(using: registry)
        _ = await (profileWork, quoteWork, historyWork, metricsWork)

        // These need the CIK, which comes from the profile or a lookup, so they
        // follow rather than run alongside.
        await loadSECSections(using: registry)

        guard !Task.isCancelled else { return }
        lastRefreshedAt = .now
    }

    private func loadProfile(using registry: ProviderRegistry) async {
        profile = try? await registry.marketData.profile(symbol: symbol)
    }

    private func loadQuote(using registry: ProviderRegistry) async {
        do {
            quote = try await registry.marketData.quote(symbol: symbol)
            quoteError = nil
        } catch let error as APIError {
            quoteError = error
        } catch {
            quoteError = .transport(.finnhub, underlying: error.localizedDescription)
        }
    }

    private func loadHistory(using registry: ProviderRegistry) async {
        // Fetch the widest range once. Five years of daily bars is a single
        // request and covers every shorter range without another.
        let from = ChartRange.fiveYear.startDate()
        let to = Date.now
        do {
            let fetched = try await registry.marketData.bars(
                symbol: symbol, resolution: .daily, from: from, to: to
            )
            bars = fetched.map {
                PriceBar(date: $0.date, resolution: .daily, open: $0.open, high: $0.high,
                         low: $0.low, close: $0.close, volume: $0.volume,
                         adjustedClose: $0.adjustedClose)
            }
            loadedBarWindow = (from, to)
            historyError = nil
        } catch let error as APIError {
            historyError = error
            Self.logger.error("History failed for \(self.symbol, privacy: .public): \(error.shortDescription, privacy: .public)")
        } catch {
            historyError = .transport(.tiingo, underlying: error.localizedDescription)
        }
    }

    private func loadMetrics(using registry: ProviderRegistry) async {
        guard let provider = registry.metrics else { return }
        do {
            metrics = try await provider.metrics(symbol: symbol)
            metricsError = nil
        } catch let error as APIError {
            metricsError = error
        } catch {
            metricsError = .transport(.finnhub, underlying: error.localizedDescription)
        }
    }

    private func loadSECSections(using registry: ProviderRegistry) async {
        guard let sec = registry.sec else { return }
        let cik: String
        do {
            // `??` takes an autoclosure, which cannot contain an await.
            if let known = profile?.cik {
                cik = known
            } else {
                cik = try await sec.resolveCIK(symbol: symbol)
            }
        } catch let error as APIError {
            fundamentalsError = error
            filingsError = error
            return
        } catch {
            return
        }

        async let filingWork: Void = loadFilings(cik: cik, sec: sec)
        async let factWork: Void = loadFundamentals(cik: cik, registry: registry)
        _ = await (filingWork, factWork)
    }

    private func loadFilings(cik: String, sec: any SECDataProvider) async {
        do {
            filings = try await sec.filings(
                cik: cik,
                formTypes: ["10-K", "10-Q", "8-K", "4"],
                limit: 15
            )
            filingsError = nil
        } catch let error as APIError {
            filingsError = error
        } catch {
            filingsError = .transport(.sec, underlying: error.localizedDescription)
        }
    }

    private func loadFundamentals(cik: String, registry: ProviderRegistry) async {
        guard let provider = registry.fundamentals else { return }
        do {
            fundamentals = try await provider.facts(
                symbol: symbol, cik: cik,
                concepts: [.revenue, .netIncome, .grossProfit, .operatingIncome,
                           .operatingCashFlow, .capitalExpenditures,
                           .cashAndEquivalents, .totalDebt, .stockholdersEquity],
                since: Calendar.current.date(byAdding: .year, value: -6, to: .now)
            )
            fundamentalsError = nil
        } catch let error as APIError {
            fundamentalsError = error
        } catch {
            fundamentalsError = .transport(.sec, underlying: error.localizedDescription)
        }
    }

    #if DEBUG
    /// Injects fundamentals so the derived figures can be tested without a
    /// provider. The stored property is private(set), and the derivations —
    /// growth linking and the free-cash-flow subtraction — are exactly the
    /// arithmetic worth pinning down.
    func applyFundamentalsForTesting(_ facts: [FinancialFactDTO]) {
        fundamentals = facts
    }
    #endif

    // MARK: - Derived views of the data

    /// Valuation metrics that have enough history to be ranked. Metrics without
    /// it are omitted rather than shown as a bare number implying context.
    var valuationContexts: [RankedMetric] {
        guard let metrics else { return [] }
        return ValuationMetric.all.compactMap { metric in
            // Both halves normalised to one scale before comparison; see
            // ValuationMetric for why that is load-bearing.
            guard let pair = metrics.normalized(for: metric),
                  let context = ValuationCalculator.historicalContext(
                    current: pair.current, history: pair.history)
            else { return nil }
            return RankedMetric(metric: metric, context: context)
        }
    }

    /// Annual revenue with year-over-year growth, most recent first.
    var annualRevenue: [AnnualFigure] {
        let annual = fundamentals
            .filter { $0.concept == .revenue && $0.periodKind == .annual }
            .sorted { $0.periodEnd < $1.periodEnd }
        return annual.enumerated().reversed().map { index, fact in
            let previous = index > 0 ? annual[index - 1].value : nil
            let growth = previous.flatMap { ReturnCalculator.simpleReturn(from: $0, to: fact.value) }
            return AnnualFigure(fact: fact, growth: growth)
        }
    }

    /// Free cash flow per annual period: operating cash flow less capex.
    ///
    /// Computed rather than read, because issuers do not file an "FCF" concept.
    /// A period missing either input yields no figure, never a partial one.
    var annualFreeCashFlow: [CashFlowPoint] {
        let ocf = Dictionary(
            fundamentals.filter { $0.concept == .operatingCashFlow && $0.periodKind == .annual }
                .map { ($0.fiscalYear, $0.value) },
            uniquingKeysWith: { _, latest in latest })
        let capex = Dictionary(
            fundamentals.filter { $0.concept == .capitalExpenditures && $0.periodKind == .annual }
                .map { ($0.fiscalYear, $0.value) },
            uniquingKeysWith: { _, latest in latest })
        let dates = Dictionary(
            fundamentals.filter { $0.periodKind == .annual }.map { ($0.fiscalYear, $0.periodEnd) },
            uniquingKeysWith: { first, _ in first })

        return ocf.keys.compactMap { year -> (Date, Double)? in
            guard let operating = ocf[year], let spend = capex[year], let date = dates[year]
            else { return nil }
            // Capex is filed as a positive outflow.
            return (date, operating - abs(spend))
        }
        .sorted { $0.0 < $1.0 }
        .map { CashFlowPoint(period: $0.0, value: $0.1) }
    }
}

/// A valuation metric together with where it sits in its own history.
struct RankedMetric: Identifiable, Sendable {
    var id: String { metric.id }
    let metric: ValuationMetric
    let context: HistoricalContext
}

/// One annual figure with its year-over-year growth, when a prior year exists.
struct AnnualFigure: Identifiable, Sendable {
    var id: Date { fact.periodEnd }
    let fact: FinancialFactDTO
    let growth: Double?
}

struct CashFlowPoint: Identifiable, Sendable {
    var id: Date { period }
    let period: Date
    let value: Double
}
