import Testing
import Foundation
@testable import Vantage

@Suite("Sample data determinism")
struct SampleDataTests {
    @Test("The same symbol always produces the same series")
    func seededGeneratorIsDeterministic() {
        let from = Date(timeIntervalSince1970: 1_700_000_000)
        let to = from.addingTimeInterval(30 * 86_400)
        let first = SampleData.bars(symbol: "NVDA", resolution: .daily, from: from, to: to)
        let second = SampleData.bars(symbol: "NVDA", resolution: .daily, from: from, to: to)
        #expect(first == second, "Tests depend on sample data being reproducible")
    }

    @Test("Different symbols produce different series")
    func differentSymbolsDiffer() {
        let from = Date(timeIntervalSince1970: 1_700_000_000)
        let to = from.addingTimeInterval(30 * 86_400)
        let nvda = SampleData.bars(symbol: "NVDA", resolution: .daily, from: from, to: to)
        let aapl = SampleData.bars(symbol: "AAPL", resolution: .daily, from: from, to: to)
        #expect(nvda != aapl)
    }

    @Test("Generated bars are internally consistent")
    func barsAreWellFormed() {
        let from = Date(timeIntervalSince1970: 1_700_000_000)
        let bars = SampleData.bars(symbol: "MSFT", resolution: .daily,
                                   from: from, to: from.addingTimeInterval(60 * 86_400))
        #expect(!bars.isEmpty)
        for bar in bars {
            #expect(bar.high >= bar.low)
            #expect(bar.high >= bar.close)
            #expect(bar.low <= bar.close)
            #expect(bar.close > 0)
        }
    }

    @Test("The mock registry is flagged as sample data")
    func sampleRegistryIsFlagged() {
        #expect(ProviderRegistry.sample.isUsingSampleData,
                "Synthetic prices must never render without the banner")
    }
}

@Suite("Provider failure handling")
struct ProviderFailureTests {
    @Test("An injected failure surfaces as the typed error, not a crash")
    func injectedFailurePropagates() async {
        let provider = MockMarketDataProvider(failure: .rateLimited(.finnhub, retryAfter: 30))
        await #expect(throws: APIError.rateLimited(.finnhub, retryAfter: 30)) {
            try await provider.quote(symbol: "NVDA")
        }
    }

    @Test("An unknown symbol is not found rather than fabricated")
    func unknownSymbolIsNotFabricated() async {
        let provider = MockMarketDataProvider()
        // `.sample`, not `.finnhub`. The mock used to borrow a real vendor's
        // identity, which is how its output reached the store looking like
        // measured data. It now names itself, and the store refuses it.
        await #expect(throws: APIError.notFound(.sample, endpoint: "profile")) {
            try await provider.profile(symbol: "ZZZZ")
        }
    }
}

@Suite("Chart ranges")
struct ChartRangeTests {
    @Test("Intraday ranges use intraday resolution, long ranges do not")
    func resolutionsMatchRanges() {
        #expect(ChartRange.oneDay.resolution == .fiveMinute)
        #expect(ChartRange.oneMonth.resolution == .daily)
        #expect(ChartRange.fiveYear.resolution == .weekly)
    }

    @Test("Start dates precede the end date for every range")
    func startDatesArePrior() {
        let end = Date(timeIntervalSince1970: 1_700_000_000)
        for range in ChartRange.allCases {
            #expect(range.startDate(from: end) < end, "\(range.rawValue) produced a non-past start")
        }
    }
}
