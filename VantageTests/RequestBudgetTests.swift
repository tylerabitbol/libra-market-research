import Testing
import Foundation
@testable import Vantage

/// Counts calls per endpoint so a screen's request budget can be asserted.
///
/// Quota is a correctness concern here, not an optimisation: Tiingo's free tier
/// refills roughly one token every 80 seconds, so a screen that asks for twelve
/// history requests blocks for eight minutes. That bug was invisible to every
/// existing test because none of them counted.
private actor CallCounter {
    private(set) var quotes = 0
    private(set) var bars = 0

    func recordQuote() { quotes += 1 }
    func recordBars() { bars += 1 }
}

private struct CountingMarketDataProvider: MarketDataProvider {
    let id: DataProviderID = .finnhub
    let counter: CallCounter

    func isConfigured() async -> Bool { true }

    func quote(symbol: String) async throws -> QuoteDTO {
        await counter.recordQuote()
        return QuoteDTO(symbol: symbol, last: 100, open: 99, high: 101, low: 98,
                        previousClose: 99, volume: 1_000, quoteTime: .now)
    }

    func bars(symbol: String, resolution: BarResolution, from: Date, to: Date) async throws -> [PriceBarDTO] {
        await counter.recordBars()
        return SampleData.bars(symbol: symbol, resolution: resolution, from: from, to: to)
    }

    func profile(symbol: String) async throws -> CompanyProfileDTO {
        throw APIError.notFound(.finnhub, endpoint: "profile")
    }

    func search(query: String) async throws -> [CompanyProfileDTO] { [] }
}

@Suite("Dashboard request budget")
@MainActor
struct RequestBudgetTests {
    private func registry(_ counter: CallCounter) -> ProviderRegistry {
        ProviderRegistry(
            marketData: CountingMarketDataProvider(counter: counter),
            fundamentals: nil, analyst: nil, sec: nil,
            macro: MockMacroDataProvider(), news: nil,
            isUsingSampleData: false
        )
    }

    @Test("Sector tiles cost no price-history requests")
    func sectorsDoNotFetchHistory() async throws {
        let counter = CallCounter()
        let model = DashboardViewModel()
        model.load(using: registry(counter), force: true)

        // Let the load finish; every provider here returns immediately.
        try await Task.sleep(for: .milliseconds(600))

        let bars = await counter.bars
        // Only Russell 2000 lacks a FRED series and shows 1W/1M, so exactly one
        // history request is justified. Eleven sector tiles show a daily change
        // only and must cost none.
        #expect(bars <= 1, "Expected at most 1 history request, got \(bars)")
    }

    @Test("Every ETF-backed row still gets a live quote")
    func quotesStillFetched() async throws {
        let counter = CallCounter()
        let model = DashboardViewModel()
        model.load(using: registry(counter), force: true)
        try await Task.sleep(for: .milliseconds(600))

        let quotes = await counter.quotes
        // 11 sectors + Russell 2000; the other three indexes and the VIX come
        // from FRED and cost no quote.
        #expect(quotes == 12, "Expected 12 quotes, got \(quotes)")
    }

    @Test("Sector tiles still show a daily change despite fetching no history")
    func sectorsStillRenderDailyChange() async throws {
        let counter = CallCounter()
        let model = DashboardViewModel()
        model.load(using: registry(counter), force: true)
        try await Task.sleep(for: .milliseconds(600))

        #expect(model.sectors.count == 11)
        #expect(model.sectors.allSatisfy { $0.dailyPercent != nil },
                "Daily change comes from the quote's previous close, not from bars")
    }
}

@Suite("Rate limiter back-pressure")
struct RateLimiterBackPressureTests {
    @Test("A wait longer than the budget is refused instead of blocking silently")
    func longWaitIsRefused() async throws {
        // Tiingo's real shape: ~1 token per 80 seconds once the burst is spent.
        let limiter = RateLimiter(requests: 45, per: 3600, burst: 2, provider: .tiingo)
        for _ in 0..<2 { try await limiter.waitForSlot() }

        // The third request would wait ~80s. With a 5s budget it must give up.
        await #expect(throws: APIError.self) {
            try await limiter.waitForSlot(maxWait: 5)
        }
    }

    @Test("The refusal names the provider so the UI can explain which limit was hit")
    func refusalNamesProvider() async throws {
        let limiter = RateLimiter(requests: 45, per: 3600, burst: 1, provider: .tiingo)
        try await limiter.waitForSlot()
        do {
            try await limiter.waitForSlot(maxWait: 1)
            Issue.record("Expected a rate-limit refusal")
        } catch let error as APIError {
            #expect(error.provider == .tiingo)
            #expect(error.isRetryable)
        }
    }

    @Test("estimatedWait reports zero while burst capacity remains")
    func estimatedWaitIsZeroWithCapacity() async throws {
        let limiter = RateLimiter(requests: 45, per: 3600, burst: 3, provider: .tiingo)
        #expect(await limiter.estimatedWait() == 0)
    }

    @Test("A generous budget still allows a slow refill to proceed")
    func generousBudgetStillWaits() async throws {
        // 10/sec: the third request after a burst of 2 waits ~100ms, well
        // inside a default budget, so background backfills keep working.
        let limiter = RateLimiter(requests: 10, per: 1, burst: 2, provider: .tiingo)
        for _ in 0..<2 { try await limiter.waitForSlot() }
        try await limiter.waitForSlot(maxWait: 5)
    }
}
