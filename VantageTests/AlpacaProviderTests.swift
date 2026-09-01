import Testing
import Foundation
import SwiftData
@testable import Vantage

/// Intraday bars, and the boundary that keeps them out of every calculation.
///
/// Alpaca's free plan serves the IEX feed — roughly 2.5% of US equity volume.
/// That is fine for the shape of a one-day chart and disqualifying for
/// anything measured: a volume anomaly computed against 2.5% of the tape would
/// be meaningless, which is why the ROADMAP declined Alpaca as a history
/// source. The tests below pin the mapping and then pin the boundary, because
/// the boundary is the part that will be broken by accident.
private func alpacaSecrets() -> InMemorySecretsStore {
    InMemorySecretsStore(seed: [
        .alpacaKeyID: "test-alpaca-key-id",
        .alpacaSecretKey: "test-alpaca-secret"
    ])
}

private func makeClient() -> HTTPClient {
    HTTPClient(session: StubURLProtocol.makeSession())
}

@Suite("Alpaca provider", .serialized)
struct AlpacaProviderTests {

    @Test("Bars decode and map, with the unusable row dropped")
    func decodesBars() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/v2/stocks/bars", fixture: "alpaca_bars_AAPL_synthetic")

        let provider = AlpacaProvider(client: makeClient(), secrets: alpacaSecrets())
        let bars = try await provider.bars(
            symbol: "AAPL", resolution: .fiveMinute,
            from: Date(timeIntervalSince1970: 1_787_000_000), to: .now
        )

        // Five rows in, one without a close. A bar with no close cannot be
        // drawn, and interpolating one would invent price history.
        #expect(bars.count == 4)
        #expect(bars.allSatisfy { $0.high >= $0.low })
        #expect(bars.allSatisfy { $0.close > 0 })
    }

    @Test("Both timestamp forms parse, and the series comes back in order")
    func parsesAndSorts() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/v2/stocks/bars", fixture: "alpaca_bars_AAPL_synthetic")

        let provider = AlpacaProvider(client: makeClient(), secrets: alpacaSecrets())
        let bars = try await provider.bars(
            symbol: "AAPL", resolution: .fiveMinute,
            from: Date(timeIntervalSince1970: 1_787_000_000), to: .now
        )
        // The fixture lists 13:45 before 13:40. A chart drawn from an unsorted
        // series doubles back on itself.
        #expect(bars.map(\.date) == bars.map(\.date).sorted())
        // The fractional-seconds row is the 13:35 one; losing it would leave 3.
        #expect(bars.count == 4)
    }

    @Test("Closes are already split-adjusted, so no separate adjusted series is claimed")
    func reportsNoAdjustedSeries() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/v2/stocks/bars", fixture: "alpaca_bars_AAPL_synthetic")

        let provider = AlpacaProvider(client: makeClient(), secrets: alpacaSecrets())
        let bars = try await provider.bars(
            symbol: "AAPL", resolution: .fiveMinute,
            from: Date(timeIntervalSince1970: 1_787_000_000), to: .now
        )
        // The request asks for adjustment=split, so `close` is the adjusted
        // value. Reporting it twice would imply a raw series we did not fetch.
        #expect(bars.allSatisfy { $0.adjustedClose == nil })
    }

    @Test("A window with no trades decodes as empty rather than failing")
    func handlesAbsentBarsMap() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/v2/stocks/bars", fixture: "alpaca_bars_empty_synthetic")

        let provider = AlpacaProvider(client: makeClient(), secrets: alpacaSecrets())
        let bars = try await provider.bars(
            symbol: "AAPL", resolution: .fiveMinute,
            from: Date(timeIntervalSince1970: 1_787_000_000), to: .now
        )
        // A weekend is not an error. The distinction matters on screen: one
        // says "no trades", the other says "something went wrong".
        #expect(bars.isEmpty)
    }

    @Test("Daily and coarser bars are refused rather than served from IEX")
    func refusesDailyBars() async throws {
        StubURLProtocol.reset()
        let provider = AlpacaProvider(client: makeClient(), secrets: alpacaSecrets())

        for resolution: BarResolution in [.daily, .weekly, .monthly] {
            await #expect(throws: APIError.self) {
                _ = try await provider.bars(
                    symbol: "AAPL", resolution: resolution,
                    from: Date(timeIntervalSince1970: 1_787_000_000), to: .now
                )
            }
        }
    }

    @Test("Half a key pair is not configured")
    func requiresBothHalves() async throws {
        let idOnly = AlpacaProvider(
            client: makeClient(),
            secrets: InMemorySecretsStore(seed: [.alpacaKeyID: "id"]))
        // One half authenticates nothing. Treating it as ready would turn a
        // setup mistake into what looks like an outage.
        #expect(await idOnly.isConfigured() == false)

        let both = AlpacaProvider(client: makeClient(), secrets: alpacaSecrets())
        #expect(await both.isConfigured())
    }
}

@Suite("Intraday routing", .serialized)
struct IntradayRoutingTests {

    /// Records which resolutions it was asked for, so routing can be observed
    /// rather than inferred.
    private actor ResolutionLog {
        private(set) var seen: [BarResolution] = []
        func record(_ resolution: BarResolution) { seen.append(resolution) }
    }

    @Test("Daily goes to Tiingo and intraday to Alpaca, with no fallback across the line")
    func routesByResolution() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/tiingo/daily/aapl/prices", fixture: "tiingo_prices_AAPL")
        try StubURLProtocol.stub("/v2/stocks/bars", fixture: "alpaca_bars_AAPL_synthetic")

        let secrets = InMemorySecretsStore(seed: [
            .finnhubAPIKey: "f", .tiingoAPIKey: "t",
            .alpacaKeyID: "id", .alpacaSecretKey: "secret"
        ])
        let client = makeClient()
        let composite = CompositeMarketDataProvider(
            quotes: FinnhubProvider(client: client, secrets: secrets),
            history: TiingoProvider(client: client, secrets: secrets),
            intraday: AlpacaProvider(client: client, secrets: secrets)
        )

        let window = (from: Date(timeIntervalSince1970: 1_787_000_000), to: Date.now)
        let daily = try await composite.bars(symbol: "AAPL", resolution: .daily,
                                             from: window.from, to: window.to)
        let intraday = try await composite.bars(symbol: "AAPL", resolution: .fiveMinute,
                                                from: window.from, to: window.to)

        // The Tiingo fixture is a long daily series; the Alpaca one is four
        // usable five-minute bars. Different counts prove different vendors
        // answered, which a shared stub path could not show.
        #expect(daily.count > 4)
        #expect(intraday.count == 4)
    }

    @Test("Without an Alpaca key, intraday says so instead of returning daily bars")
    func refusesIntradayWithoutAKey() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/tiingo/daily/aapl/prices", fixture: "tiingo_prices_AAPL")

        let secrets = InMemorySecretsStore(seed: [.finnhubAPIKey: "f", .tiingoAPIKey: "t"])
        let client = makeClient()
        let composite = CompositeMarketDataProvider(
            quotes: FinnhubProvider(client: client, secrets: secrets),
            history: TiingoProvider(client: client, secrets: secrets),
            intraday: nil
        )

        // Quietly serving daily bars would mislabel the resolution, and every
        // figure computed on top of it would be wrong while looking fine.
        await #expect(throws: APIError.self) {
            _ = try await composite.bars(
                symbol: "AAPL", resolution: .fiveMinute,
                from: Date(timeIntervalSince1970: 1_787_000_000), to: .now)
        }
    }
}

/// Serves daily and intraday bars that are trivially distinguishable, so the
/// boundary between them can be asserted rather than assumed.
private struct TwoSeriesProvider: MarketDataProvider {
    let id: DataProviderID = .finnhub

    /// The marker. Real IEX volume is small but not 7; nothing else in the
    /// test produces this number.
    static let intradayVolumeMarker: Double = 7
    static let dailyVolumeMarker: Double = 1_000_000

    func isConfigured() async -> Bool { true }

    func quote(symbol: String) async throws -> QuoteDTO {
        throw APIError.transport(.finnhub, underlying: "not part of this test")
    }

    func bars(symbol: String, resolution: BarResolution,
              from: Date, to: Date) async throws -> [PriceBarDTO] {
        let isIntraday = !resolution.isDailyOrCoarser
        let count = isIntraday ? 60 : 200
        let step: TimeInterval = isIntraday ? 300 : 86_400
        let end = Date.now.addingTimeInterval(-60)
        return (0..<count).map { index in
            let close = 100 + Double(index % 5)
            return PriceBarDTO(
                date: end.addingTimeInterval(-Double(count - 1 - index) * step),
                open: close, high: close + 1, low: close - 1, close: close,
                volume: isIntraday ? Self.intradayVolumeMarker : Self.dailyVolumeMarker,
                adjustedClose: isIntraday ? nil : close
            )
        }
    }

    func profile(symbol: String) async throws -> CompanyProfileDTO {
        throw APIError.notFound(.finnhub, endpoint: "profile")
    }

    func search(query: String) async throws -> [CompanyProfileDTO] { [] }
}

@Suite("Intraday stays out of the calculations", .serialized)
@MainActor
struct IntradayBoundaryTests {

    private func loadedModel() async throws -> SecurityDetailViewModel {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()

        let registry = ProviderRegistry(
            marketData: TwoSeriesProvider(), fundamentals: nil, analyst: nil,
            metrics: nil, sec: nil, macro: nil, news: nil, isUsingSampleData: false)

        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry, snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(800))

        model.select(.oneDay, registry: registry)
        try await Task.sleep(for: .milliseconds(600))
        return model
    }

    @Test("Selecting 1D fetches an intraday series and draws it")
    func intradayIsFetchedAndDrawn() async throws {
        let model = try await loadedModel()
        #expect(!model.intradayBars.isEmpty)
        #expect(model.chartAvailability == .ready)
        #expect(model.chartBars.allSatisfy { $0.volume == TwoSeriesProvider.intradayVolumeMarker },
                "The 1D chart draws the intraday series")
    }

    @Test("No IEX bar ever reaches the daily series the calculations read")
    func intradayNeverEntersBars() async throws {
        let model = try await loadedModel()

        // `bars` feeds EventDetector, RelativeAnalysis and rangeReturn;
        // `visibleBars` feeds ReturnCalculator.priceContext. A single IEX bar
        // in either would make a volume anomaly a measurement of 2.5% of the
        // tape, which is the reason Alpaca was declined as a history source.
        #expect(!model.bars.isEmpty)
        #expect(model.bars.allSatisfy { $0.volume == TwoSeriesProvider.dailyVolumeMarker })
        #expect(model.visibleBars.allSatisfy { $0.volume == TwoSeriesProvider.dailyVolumeMarker })
        #expect(model.bars.allSatisfy { $0.resolution == .daily })
    }

    @Test("The volume detector's input is unchanged by selecting an intraday range")
    func detectorInputIsUnchanged() async throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()
        let registry = ProviderRegistry(
            marketData: TwoSeriesProvider(), fundamentals: nil, analyst: nil,
            metrics: nil, sec: nil, macro: nil, news: nil, isUsingSampleData: false)

        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry, snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(800))
        let before = model.bars.map(\.date)

        model.select(.oneDay, registry: registry)
        try await Task.sleep(for: .milliseconds(600))

        #expect(model.bars.map(\.date) == before,
                "Switching to 1D must not disturb what the detectors read")
    }
}
