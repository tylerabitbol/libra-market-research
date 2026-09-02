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

    @Test("A chart that has not looked yet says loading, not unavailable")
    func availabilityBeforeAnyAttempt() {
        let model = SecurityDetailViewModel(symbol: "TEST")
        // Nothing held, no error, nothing in flight — the state on the frame
        // between the view appearing and its `.task` starting. Reporting
        // "unavailable" from here is a verdict on a fetch nobody has run, and
        // it rendered as an error card flashing on every open.
        #expect(model.chartAvailability == .loading)

        model.select(.oneDay, registry: .sample)
        #expect(model.chartAvailability == .loading)
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

/// Fails every bars request, so a chart that already holds data can be asked
/// what it does when a refresh goes wrong.
private struct FailingAfterFirstProvider: MarketDataProvider {
    let id: DataProviderID = .finnhub
    let shouldFail: @Sendable () -> Bool

    func isConfigured() async -> Bool { true }
    func quote(symbol: String) async throws -> QuoteDTO {
        throw APIError.transport(.finnhub, underlying: "not part of this test")
    }
    func bars(symbol: String, resolution: BarResolution,
              from: Date, to: Date) async throws -> [PriceBarDTO] {
        if shouldFail() { throw APIError.transport(.alpaca, underlying: "offline") }
        let end = Self.lastRegularClose()
        return (0..<60).map { index in
            let close = 100 + Double(index % 5)
            return PriceBarDTO(
                date: end.addingTimeInterval(-Double(59 - index) * 300),
                open: close, high: close + 1, low: close - 1, close: close,
                volume: 7, adjustedClose: nil)
        }
    }
    func profile(symbol: String) async throws -> CompanyProfileDTO {
        throw APIError.notFound(.finnhub, endpoint: "profile")
    }
    func search(query: String) async throws -> [CompanyProfileDTO] { [] }

    /// The most recent 15:55 in New York at or before now.
    ///
    /// The chart draws regular-session bars only, so a series anchored to
    /// `Date.now` would be filtered away entirely whenever the suite runs
    /// outside market hours — which is most of the time.
    static func lastRegularClose() -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York") ?? .gmt
        var day = Date.now
        for _ in 0..<8 {
            if let close = calendar.date(bySettingHour: 15, minute: 55, second: 0, of: day),
               close <= .now {
                return close
            }
            day = calendar.date(byAdding: .day, value: -1, to: day) ?? day
        }
        return .now
    }
}

@Suite("The chart does not disappear", .serialized)
@MainActor
struct ChartPersistenceTests {

    @Test("A failed refresh leaves the chart drawn, with the failure noted")
    func failedRefreshKeepsTheChart() async throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()

        let failing = LockedFlag()
        let registry = ProviderRegistry(
            marketData: FailingAfterFirstProvider(shouldFail: { failing.value }),
            fundamentals: nil, analyst: nil, metrics: nil, sec: nil,
            macro: nil, news: nil, isUsingSampleData: false)

        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry, snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(700))
        model.select(.oneDay, registry: registry)
        try await Task.sleep(for: .milliseconds(500))
        #expect(model.chartAvailability == .ready, "Precondition: a chart is drawn")

        // Now break the network and force a refresh.
        failing.value = true
        model.load(using: registry, snapshots: nil, force: true)
        try await Task.sleep(for: .milliseconds(500))
        model.select(.fiveDay, registry: registry)
        try await Task.sleep(for: .milliseconds(500))
        model.select(.oneDay, registry: registry)
        try await Task.sleep(for: .milliseconds(500))

        // The bars we already hold are still perfectly drawable. Replacing
        // them with an error card is the chart "disappearing".
        #expect(model.chartAvailability == .ready)
        #expect(model.chartBars.count >= 2)
    }

    @Test("Switching between 1D and 5D keeps each series rather than wiping it")
    func switchingRangesKeepsBothSeries() async throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()

        let failing = LockedFlag()
        let registry = ProviderRegistry(
            marketData: FailingAfterFirstProvider(shouldFail: { failing.value }),
            fundamentals: nil, analyst: nil, metrics: nil, sec: nil,
            macro: nil, news: nil, isUsingSampleData: false)

        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry, snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(700))
        model.select(.oneDay, registry: registry)
        try await Task.sleep(for: .milliseconds(500))
        model.select(.fiveDay, registry: registry)
        try await Task.sleep(for: .milliseconds(500))

        // Going back must not re-fetch into an emptied array — which, with the
        // network down, would leave 1D blank despite having been drawn a
        // moment ago.
        failing.value = true
        model.select(.oneDay, registry: registry)
        try await Task.sleep(for: .milliseconds(400))
        #expect(model.chartAvailability == .ready)
    }

    @Test("Pre- and post-market bars are left off the chart")
    func extendedHoursBarsAreExcluded() async throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()

        let registry = ProviderRegistry(
            marketData: ExtendedHoursProvider(),
            fundamentals: nil, analyst: nil, metrics: nil, sec: nil,
            macro: nil, news: nil, isUsingSampleData: false)

        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry, snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(700))
        model.select(.oneDay, registry: registry)
        try await Task.sleep(for: .milliseconds(600))

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York") ?? .gmt
        let minutes = model.chartBars.map { bar -> Int in
            let parts = calendar.dateComponents([.hour, .minute], from: bar.date)
            return (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        }

        #expect(!minutes.isEmpty, "Precondition: the session bars survived")
        // The thin 7 a.m. print was the widest segment on the chart and an
        // artefact of two sparse bars, not a move anyone could have traded.
        #expect(minutes.allSatisfy { $0 >= 9 * 60 + 30 && $0 < 16 * 60 })
    }

    @Test("5D counts trading sessions, not calendar days")
    func fiveDayCountsSessions() async throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()

        let registry = ProviderRegistry(
            marketData: SessionSeriesProvider(sessions: 8),
            fundamentals: nil, analyst: nil, metrics: nil, sec: nil,
            macro: nil, news: nil, isUsingSampleData: false)

        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry, snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(700))
        model.select(.fiveDay, registry: registry)
        try await Task.sleep(for: .milliseconds(600))

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York") ?? .gmt
        let days = Set(model.chartBars.map { calendar.startOfDay(for: $0.date) })

        // Five calendar days back from a Monday reaches the previous
        // Wednesday, and the chart drew three sessions while calling itself
        // five. Sessions are what the range counts.
        #expect(days.count == 5)

        model.select(.oneDay, registry: registry)
        try await Task.sleep(for: .milliseconds(400))
        let oneDay = Set(model.chartBars.map { calendar.startOfDay(for: $0.date) })
        #expect(oneDay.count == 1)
    }

    @Test("The intraday axis is positional, and every tick is labelled")
    func axisTicksSitOnBars() async throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()

        let registry = ProviderRegistry(
            marketData: SessionSeriesProvider(sessions: 8),
            fundamentals: nil, analyst: nil, metrics: nil, sec: nil,
            macro: nil, news: nil, isUsingSampleData: false)

        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry, snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(700))
        model.select(.fiveDay, registry: registry)
        try await Task.sleep(for: .milliseconds(600))

        let points = model.chartPoints
        let ticks = model.chartAxisTicks

        // Positions run 0..<count with no holes: the axis spends its width on
        // trading rather than on the seventeen hours a day that are not.
        #expect(points.map(\.id) == Array(0..<points.count))
        #expect(ticks.count == 5, "One label per session")
        #expect(ticks.allSatisfy { tick in points.contains { $0.id == tick.id } })
        #expect(ticks.allSatisfy { !$0.label.isEmpty })
    }

    @Test("Sessions are joined by a link exactly one bar wide")
    func overnightLinksAreOneBarWide() async throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()

        let registry = ProviderRegistry(
            marketData: SessionSeriesProvider(sessions: 8),
            fundamentals: nil, analyst: nil, metrics: nil, sec: nil,
            macro: nil, news: nil, isUsingSampleData: false)

        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry, snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(700))
        model.select(.fiveDay, registry: registry)
        try await Task.sleep(for: .milliseconds(600))

        let traded = model.chartSegments.filter { $0.kind == .traded }
        let overnight = model.chartSegments.filter { $0.kind == .overnight }

        #expect(traded.count == 5)
        #expect(overnight.count == 4, "One link between each pair of sessions")

        // A link spans a single position. That is the whole argument for
        // drawing it: a stroke that narrow reads as the jump it is, where the
        // same move across a wall-clock weekend read as a steady decline.
        for link in overnight {
            #expect(link.points.count == 2)
            guard link.points.count == 2 else { continue }
            #expect(link.points[1].id - link.points[0].id == 1)
            #expect(link.points[0].session != link.points[1].session)
        }

        // Nothing is dropped or duplicated by the grouping.
        let rejoined = traded.flatMap(\.points).map(\.id)
        #expect(rejoined == model.chartPoints.map(\.id))
    }

    @Test("One session has nothing to link across")
    func oneDayHasNoOvernightLink() async throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()

        let registry = ProviderRegistry(
            marketData: SessionSeriesProvider(sessions: 8),
            fundamentals: nil, analyst: nil, metrics: nil, sec: nil,
            macro: nil, news: nil, isUsingSampleData: false)

        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: registry, snapshots: SnapshotStore(modelContainer: container))
        try await Task.sleep(for: .milliseconds(700))
        model.select(.oneDay, registry: registry)
        try await Task.sleep(for: .milliseconds(600))

        #expect(model.chartSegments.filter { $0.kind == .traded }.count == 1)
        #expect(model.chartSegments.filter { $0.kind == .overnight }.isEmpty)
    }
}

/// Serves a whole number of regular sessions, most recent last.
private struct SessionSeriesProvider: MarketDataProvider {
    let id: DataProviderID = .finnhub
    let sessions: Int

    func isConfigured() async -> Bool { true }
    func quote(symbol: String) async throws -> QuoteDTO {
        throw APIError.transport(.finnhub, underlying: "not part of this test")
    }
    func bars(symbol: String, resolution: BarResolution,
              from: Date, to: Date) async throws -> [PriceBarDTO] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York") ?? .gmt
        let latest = FailingAfterFirstProvider.lastRegularClose()

        var bars: [PriceBarDTO] = []
        for session in 0..<sessions {
            guard let day = calendar.date(byAdding: .day, value: -session, to: latest),
                  let open = calendar.date(bySettingHour: 9, minute: 30, second: 0, of: day)
            else { continue }
            // 09:30 to 15:45 in fifteen-minute steps.
            for step in 0..<26 {
                let price = 100 + Double((session + step) % 5)
                bars.append(PriceBarDTO(
                    date: open.addingTimeInterval(Double(step) * 900),
                    open: price, high: price + 1, low: price - 1, close: price,
                    volume: 7, adjustedClose: nil))
            }
        }
        return bars.sorted { $0.date < $1.date }
    }
    func profile(symbol: String) async throws -> CompanyProfileDTO {
        throw APIError.notFound(.finnhub, endpoint: "profile")
    }
    func search(query: String) async throws -> [CompanyProfileDTO] { [] }
}

/// Serves one session that begins before the open and runs past the close.
private struct ExtendedHoursProvider: MarketDataProvider {
    let id: DataProviderID = .finnhub

    func isConfigured() async -> Bool { true }
    func quote(symbol: String) async throws -> QuoteDTO {
        throw APIError.transport(.finnhub, underlying: "not part of this test")
    }
    func bars(symbol: String, resolution: BarResolution,
              from: Date, to: Date) async throws -> [PriceBarDTO] {
        let close = FailingAfterFirstProvider.lastRegularClose()
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York") ?? .gmt
        // 07:00 through 19:55 in five-minute steps, spanning both edges.
        guard let open = calendar.date(bySettingHour: 7, minute: 0, second: 0, of: close) else {
            return []
        }
        return (0..<156).map { index in
            let price = 100 + Double(index % 5)
            return PriceBarDTO(
                date: open.addingTimeInterval(Double(index) * 300),
                open: price, high: price + 1, low: price - 1, close: price,
                volume: 7, adjustedClose: nil)
        }
    }
    func profile(symbol: String) async throws -> CompanyProfileDTO {
        throw APIError.notFound(.finnhub, endpoint: "profile")
    }
    func search(query: String) async throws -> [CompanyProfileDTO] { [] }
}

/// Minimal mutable flag usable from a `@Sendable` closure.
private final class LockedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = false
    var value: Bool {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
}
