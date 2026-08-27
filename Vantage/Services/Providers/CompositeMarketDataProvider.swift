import Foundation

/// Presents Finnhub and Tiingo as one `MarketDataProvider`.
///
/// The split exists because no single free tier covers both needs: Finnhub has
/// live quotes but no history, Tiingo has deep adjusted history but no
/// intraday. That is a data-sourcing detail, not something view models should
/// know about — `DashboardViewModel` and every view continue to depend only on
/// `MarketDataProvider`, exactly as they did against the mocks.
struct CompositeMarketDataProvider: MarketDataProvider {
    let id: DataProviderID = .finnhub

    /// Live prices, company profiles, symbol search.
    let quotes: FinnhubProvider
    /// Daily and coarser price history.
    let history: TiingoProvider

    func isConfigured() async -> Bool {
        // Sequenced rather than `&&`, whose right operand is an autoclosure and
        // cannot contain an await.
        let hasQuotes = await quotes.isConfigured()
        let hasHistory = await history.isConfigured()
        return hasQuotes && hasHistory
    }

    func quote(symbol: String) async throws -> QuoteDTO {
        try await quotes.quote(symbol: symbol)
    }

    func bars(
        symbol: String,
        resolution: BarResolution,
        from: Date,
        to: Date
    ) async throws -> [PriceBarDTO] {
        try await history.bars(symbol: symbol, resolution: resolution, from: from, to: to)
    }

    /// Prefers Finnhub, which carries sector, market cap and shares
    /// outstanding. Falls back to Tiingo's thinner metadata only when Finnhub
    /// has nothing, so a symbol Finnhub doesn't cover still resolves to a name.
    func profile(symbol: String) async throws -> CompanyProfileDTO {
        do {
            return try await quotes.profile(symbol: symbol)
        } catch let error as APIError {
            switch error {
            case .notFound, .noData:
                return try await history.profile(symbol: symbol)
            default:
                throw error
            }
        }
    }

    func search(query: String) async throws -> [CompanyProfileDTO] {
        try await quotes.search(query: query)
    }
}

/// Adapts `FinnhubProvider` to the analyst and news protocols.
///
/// Kept separate from the provider itself so that the protocol conformances
/// stay declarative and the 403-only endpoints are visible in one place.
struct FinnhubAnalystProvider: AnalystDataProvider {
    let id: DataProviderID = .finnhub
    let provider: FinnhubProvider

    func isConfigured() async -> Bool { await provider.isConfigured() }

    /// Not available on the free tier. Throwing `.notEntitled` is what lets the
    /// UI say "not in your plan" instead of rendering an empty chart.
    func estimates(symbol: String, metric: EstimateMetric) async throws -> [AnalystEstimateDTO] {
        try await provider.estimates(symbol: symbol, metric: metric)
    }

    func ratings(symbol: String) async throws -> [RatingSnapshotDTO] {
        try await provider.ratings(symbol: symbol)
    }
}

struct FinnhubMetricsProvider: CompanyMetricsProvider {
    let id: DataProviderID = .finnhub
    let provider: FinnhubProvider

    func isConfigured() async -> Bool { await provider.isConfigured() }

    func metrics(symbol: String) async throws -> CompanyMetricsDTO {
        try await provider.metrics(symbol: symbol)
    }
}

struct FinnhubNewsProvider: NewsProvider {
    let id: DataProviderID = .finnhub
    let provider: FinnhubProvider

    func isConfigured() async -> Bool { await provider.isConfigured() }

    func companyNews(symbol: String, from: Date, to: Date) async throws -> [NewsItemDTO] {
        try await provider.companyNews(symbol: symbol, from: from, to: to)
    }
}
