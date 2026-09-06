import Testing
import Foundation
@testable import Libra

private func makeSecrets() -> InMemorySecretsStore {
    InMemorySecretsStore(seed: [
        .finnhubAPIKey: "test-finnhub-key",
        .tiingoAPIKey: "test-tiingo-key",
        .fredAPIKey: "test-fred-key"
    ])
}

private func makeClient() -> HTTPClient {
    HTTPClient(session: StubURLProtocol.makeSession())
}

@Suite("Tiingo provider", .serialized)
struct TiingoProviderTests {
    @Test("Real daily bars decode and map")
    func decodesRealBars() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/tiingo/daily/aapl/prices", fixture: "tiingo_prices_AAPL")

        let provider = TiingoProvider(client: makeClient(), secrets: makeSecrets())
        let bars = try await provider.bars(
            symbol: "AAPL", resolution: .daily,
            from: Date(timeIntervalSince1970: 1_780_000_000), to: .now
        )
        #expect(!bars.isEmpty)
        #expect(bars.allSatisfy { $0.high >= $0.low })
        #expect(bars.allSatisfy { $0.close > 0 })
    }

    @Test("Adjusted close survives a split, and raw close does not")
    func splitAdjustmentIsCarried() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/tiingo/daily/aapl/prices", fixture: "tiingo_prices_AAPL_split")

        let provider = TiingoProvider(client: makeClient(), secrets: makeSecrets())
        let dtos = try await provider.bars(
            symbol: "AAPL", resolution: .daily,
            from: Date(timeIntervalSince1970: 1_598_000_000),
            to: Date(timeIntervalSince1970: 1_599_300_000)
        )
        let first = try #require(dtos.first)
        // AAPL's 4-for-1 split fell inside this window: raw close ~499, adjusted ~121.
        #expect(first.close > 400)
        let adjusted = try #require(first.adjustedClose)
        #expect(adjusted < 200)

        // PriceBar must analyse on the adjusted series or every return spanning
        // the split is wrong by ~4x.
        let bar = PriceBar(date: first.date, resolution: .daily, open: first.open,
                           high: first.high, low: first.low, close: first.close,
                           adjustedClose: first.adjustedClose)
        #expect(bar.analysisClose == adjusted)
    }

    @Test("Intraday resolutions are refused rather than silently served as daily")
    func intradayIsRefused() async {
        StubURLProtocol.reset()
        let provider = TiingoProvider(client: makeClient(), secrets: makeSecrets())
        await #expect(throws: APIError.notEntitled(.tiingo, endpoint: "intraday prices")) {
            try await provider.bars(symbol: "AAPL", resolution: .fiveMinute,
                                    from: .now.addingTimeInterval(-86_400), to: .now)
        }
    }

    @Test("The token travels in a header, never in the URL")
    func tokenNotInURL() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/tiingo/daily/aapl/prices", fixture: "tiingo_prices_AAPL")

        let provider = TiingoProvider(client: makeClient(), secrets: makeSecrets())
        _ = try await provider.bars(symbol: "AAPL", resolution: .daily,
                                    from: .now.addingTimeInterval(-86_400), to: .now)
        let request = try #require(StubURLProtocol.requests.first)
        #expect(request.url?.absoluteString.contains("test-tiingo-key") == false)
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Token test-tiingo-key")
    }

    @Test("A missing key throws before any request is made")
    func missingKeyThrowsEarly() async {
        StubURLProtocol.reset()
        let provider = TiingoProvider(client: makeClient(), secrets: InMemorySecretsStore())
        await #expect(throws: APIError.missingCredentials(.tiingo)) {
            try await provider.bars(symbol: "AAPL", resolution: .daily,
                                    from: .now.addingTimeInterval(-86_400), to: .now)
        }
        #expect(StubURLProtocol.requests.isEmpty, "No credential means no outbound request")
    }
}

@Suite("Finnhub provider", .serialized)
struct FinnhubProviderTests {
    @Test("A real quote decodes and maps")
    func decodesRealQuote() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/quote", fixture: "finnhub_quote_AAPL")

        let provider = FinnhubProvider(client: makeClient(), secrets: makeSecrets())
        let quote = try await provider.quote(symbol: "AAPL")
        #expect(quote.symbol == "AAPL")
        #expect(quote.last > 0)
        #expect(quote.previousClose != nil)
        #expect(quote.changePercent != nil)
    }

    @Test("An all-zero quote is treated as no data, not as a price of zero")
    func zeroQuoteIsNoData() async throws {
        StubURLProtocol.reset()
        // Finnhub answers unknown symbols with 200 and zeros rather than a 404.
        let zeros = Data(#"{"c":0,"d":null,"dp":null,"h":0,"l":0,"o":0,"pc":0,"t":0}"#.utf8)
        StubURLProtocol.stub("/quote", with: .init(body: zeros))

        let provider = FinnhubProvider(client: makeClient(), secrets: makeSecrets())
        await #expect(throws: APIError.noData(.finnhub, endpoint: "quote")) {
            try await provider.quote(symbol: "NOTAREALTICKER")
        }
    }

    @Test("A real profile decodes, with market cap scaled from millions")
    func decodesProfile() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/stock/profile2", fixture: "finnhub_profile2_AAPL")

        let provider = FinnhubProvider(client: makeClient(), secrets: makeSecrets())
        let profile = try await provider.profile(symbol: "AAPL")
        #expect(profile.symbol == "AAPL")
        #expect(profile.name.contains("Apple"))
        // Finnhub reports millions; a trillion-dollar company must read as such.
        let marketCap = try #require(profile.marketCap)
        #expect(marketCap > 1_000_000_000_000)
    }

    @Test("Rating snapshots decode and sort oldest first")
    func decodesRatings() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/stock/recommendation", fixture: "finnhub_recommendation_AAPL")

        let provider = FinnhubProvider(client: makeClient(), secrets: makeSecrets())
        let ratings = try await provider.ratings(symbol: "AAPL")
        #expect(!ratings.isEmpty)
        #expect(ratings == ratings.sorted { $0.asOf < $1.asOf })
        #expect(ratings.allSatisfy { $0.total > 0 })
    }

    @Test("Earnings surprises decode with estimate and actual")
    func decodesEarnings() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/stock/earnings", fixture: "finnhub_earnings_AAPL")

        let provider = FinnhubProvider(client: makeClient(), secrets: makeSecrets())
        let surprises = try await provider.earningsSurprises(symbol: "AAPL")
        #expect(!surprises.isEmpty)
        #expect(surprises.contains { $0.actual != nil && $0.estimate != nil })
    }

    @Test("News decodes and sorts newest first")
    func decodesNews() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/company-news", fixture: "finnhub_news_AAPL")

        let provider = FinnhubProvider(client: makeClient(), secrets: makeSecrets())
        let news = try await provider.companyNews(
            symbol: "AAPL", from: .now.addingTimeInterval(-14 * 86_400), to: .now
        )
        #expect(!news.isEmpty)
        #expect(news == news.sorted { $0.publishedAt > $1.publishedAt })
    }

    @Test("A 403 becomes .notEntitled so the UI shows a plan gap, not an error")
    func forbiddenBecomesNotEntitled() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/stock/recommendation",
                                 fixture: "finnhub_403_notEntitled", statusCode: 403)

        let provider = FinnhubProvider(client: makeClient(), secrets: makeSecrets())
        await #expect(throws: APIError.notEntitled(.finnhub, endpoint: "recommendation")) {
            try await provider.ratings(symbol: "AAPL")
        }
    }

    @Test("Estimate endpoints report the tier gap without making a request")
    func estimatesAreNotEntitled() async {
        StubURLProtocol.reset()
        let provider = FinnhubProvider(client: makeClient(), secrets: makeSecrets())
        await #expect(throws: APIError.self) {
            try await provider.estimates(symbol: "AAPL", metric: .eps)
        }
        #expect(StubURLProtocol.requests.isEmpty)
    }
}

@Suite("FRED provider", .serialized)
struct FREDProviderTests {
    @Test("Observations decode, with holiday gaps removed")
    func decodesObservations() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/fred/series/observations", fixture: "fred_observations_SP500")

        let provider = FREDProvider(client: makeClient(), secrets: makeSecrets())
        let observations = try await provider.observations(seriesID: "SP500", from: nil, to: nil)
        // 12 rows in the fixture, two of which are "." holidays.
        #expect(observations.count == 10)
        #expect(observations.allSatisfy { $0.value > 0 })
        #expect(observations == observations.sorted { $0.date < $1.date })
    }

    @Test("A series with no usable values reports no data rather than an empty success")
    func allMissingIsNoData() async {
        StubURLProtocol.reset()
        let allDots = Data(#"{"observations":[{"date":"2026-01-01","value":"."}]}"#.utf8)
        StubURLProtocol.stub("/fred/series/observations", with: .init(body: allDots))

        let provider = FREDProvider(client: makeClient(), secrets: makeSecrets())
        await #expect(throws: APIError.noData(.fred, endpoint: "fred observations")) {
            try await provider.observations(seriesID: "SP500", from: nil, to: nil)
        }
    }
}

@Suite("Credential hygiene")
struct CredentialHygieneTests {
    @Test("Finnhub's token query parameter never reaches a cache key")
    func finnhubTokenStrippedFromCacheKey() {
        let endpoint = Endpoint(
            provider: .finnhub,
            baseURL: URL(string: "https://finnhub.io/api/v1")!,
            path: "/quote",
            queryItems: [.init(name: "symbol", value: "AAPL"),
                         .init(name: "token", value: "super-secret-key")],
            label: "quote"
        )
        #expect(!endpoint.cacheKey.contains("super-secret-key"))
        #expect(endpoint.cacheKey.contains("AAPL"))
    }

    @Test("FRED's api_key parameter never reaches a cache key")
    func fredKeyStrippedFromCacheKey() {
        let endpoint = Endpoint(
            provider: .fred,
            baseURL: URL(string: "https://api.stlouisfed.org")!,
            path: "/fred/series/observations",
            queryItems: [.init(name: "series_id", value: "SP500"),
                         .init(name: "api_key", value: "super-secret-key")],
            label: "observations"
        )
        #expect(!endpoint.cacheKey.contains("super-secret-key"))
        #expect(endpoint.cacheKey.contains("SP500"))
    }

    @Test("Two requests differing only by credential share one cache key")
    func cacheKeyIgnoresCredentialRotation() {
        func endpoint(token: String) -> Endpoint {
            Endpoint(provider: .finnhub, baseURL: URL(string: "https://finnhub.io")!,
                     path: "/quote",
                     queryItems: [.init(name: "symbol", value: "AAPL"),
                                  .init(name: "token", value: token)],
                     label: "quote")
        }
        #expect(endpoint(token: "old").cacheKey == endpoint(token: "rotated").cacheKey)
    }
}
