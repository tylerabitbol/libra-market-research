import Testing
import Foundation
@testable import Libra

/// Records what was asked for, so the tests can assert on the request rather
/// than only on the result. Bars are the scarcest thing the app spends.
private actor RequestLog {
    private(set) var starts: [Date] = []
    func record(_ from: Date?) { starts.append(from ?? .distantPast) }
    var count: Int { starts.count }
}

private struct CountingMacroProvider: MacroDataProvider {
    let id: DataProviderID = .fred
    let log: RequestLog
    var failure: APIError?

    func isConfigured() async -> Bool { true }

    func observations(seriesID: String, from: Date?, to: Date?) async throws
        -> [MacroObservationDTO] {
        await log.record(from)
        if let failure { throw failure }
        // A daily close series, oldest first, ascending in value so the
        // direction of any computed return is unambiguous.
        let end = to ?? .now
        let start = from ?? end.addingTimeInterval(-400 * 86_400)
        var cursor = start
        var value = 100.0
        var result: [MacroObservationDTO] = []
        while cursor <= end {
            result.append(.init(seriesID: seriesID, date: cursor, value: value))
            value += 0.5
            cursor = cursor.addingTimeInterval(86_400)
        }
        return result
    }
}

private func registry(macro: any MacroDataProvider) -> ProviderRegistry {
    var registry = ProviderRegistry.sample
    registry.macro = macro
    return registry
}

@Suite("Benchmark detail")
@MainActor
struct BenchmarkDetailViewModelTests {

    private var sector: Benchmark { Benchmark.sectors[0] }

    @Test("An index offers only the ranges FRED can fill; a proxy offers all seven")
    func rangesFollowTheBacking() {
        let index = BenchmarkDetailViewModel(benchmark: Benchmark.market)
        #expect(index.availableRanges == [.oneMonth, .threeMonth, .sixMonth,
                                          .oneYear, .fiveYear],
                "FRED publishes end-of-day, so 1D and 5D can never draw")
        #expect(index.availableRanges.allSatisfy { !$0.usesIntraday })

        let etf = BenchmarkDetailViewModel(benchmark: Benchmark.sectors[0])
        #expect(etf.availableRanges == ChartRange.allCases,
                "A sector ETF follows the same provider path as any security")
    }

    @Test("A selection the picker does not offer is refused")
    func intradayIsNotSelectableOnAnIndex() {
        let model = BenchmarkDetailViewModel(benchmark: Benchmark.market)
        model.select(.oneDay, registry: registry(macro: CountingMacroProvider(log: RequestLog())))
        #expect(model.selectedRange != .oneDay)
    }

    @Test("FRED closes become bars that carry no range")
    func closeOnlyBarsHaveNoHighOrLow() {
        let now = Date.now
        let observations = [
            MacroObservationDTO(seriesID: "SP500", date: now, value: 7691.76),
            MacroObservationDTO(seriesID: "SP500",
                                date: now.addingTimeInterval(-86_400), value: 7650.10)
        ]
        let bars = PriceBar.closeOnly(from: observations)

        #expect(bars.map(\.close) == [7650.10, 7691.76], "Oldest first")
        #expect(bars.allSatisfy { $0.open == $0.close && $0.high == $0.close
                                  && $0.low == $0.close },
                "FRED publishes a close and nothing else")
    }

    @Test("A five-year window asks for more than the dashboard's 400 days")
    func fiveYearWidensTheRequest() async throws {
        let log = RequestLog()
        let model = BenchmarkDetailViewModel(benchmark: Benchmark.market)
        model.select(.fiveYear, registry: registry(macro: CountingMacroProvider(log: log)))
        try await Task.sleep(for: .milliseconds(400))

        let starts = await log.starts
        #expect(starts.count >= 1)
        let age = Date.now.timeIntervalSince(try #require(starts.last))
        #expect(age > 400 * 86_400,
                "The dashboard row's fixed 400-day window cannot fill a 5Y chart")
    }

    @Test("Re-selecting a range already held spends no request")
    func heldRangeIsNotRefetched() async throws {
        let log = RequestLog()
        let providers = registry(macro: CountingMacroProvider(log: log))
        let model = BenchmarkDetailViewModel(benchmark: Benchmark.market)

        model.load(registry: providers)
        try await Task.sleep(for: .milliseconds(400))
        model.select(.oneMonth, registry: providers)
        try await Task.sleep(for: .milliseconds(300))

        #expect(await log.count == 1,
                "A redundant refetch spends a token the free tier refills slowly")
    }

    @Test("A chart that has not looked yet says loading, not unavailable")
    func availabilityBeforeAnyAttempt() {
        let model = BenchmarkDetailViewModel(benchmark: Benchmark.market)
        #expect(model.chartAvailability == .loading,
                "Nothing held and nothing attempted is not a verdict")
    }

    @Test("A failed fetch says why rather than showing an empty chart")
    func failureIsExplained() async throws {
        let failing = CountingMacroProvider(
            log: RequestLog(),
            failure: .rateLimited(.fred, retryAfter: nil)
        )
        let model = BenchmarkDetailViewModel(benchmark: Benchmark.market)
        model.load(registry: registry(macro: failing))
        try await Task.sleep(for: .milliseconds(400))

        guard case .unavailable(let reason) = model.chartAvailability else {
            Issue.record("Expected .unavailable, got \(model.chartAvailability)")
            return
        }
        #expect(!reason.isEmpty)
    }

    @Test("An index level is points; a proxy's price is money")
    func levelsAreFormattedAsWhatTheyAre() {
        #expect(BenchmarkDetailViewModel(benchmark: Benchmark.market).valueFormat == .points,
                "The S&P 500 at 7691.76 is not $7,691.76")
        #expect(BenchmarkDetailViewModel(benchmark: Benchmark.sectors[0]).valueFormat
                == .currency)
    }

    @Test("Every screen that shows a proxy says it is one")
    func proxiesAreLabelled() {
        let sector = BenchmarkDetailViewModel(benchmark: Benchmark.sectors[0])
        #expect(sector.benchmark.isProxy)
        #expect(sector.sourceExplanation.lowercased().contains("proxy"))

        let index = BenchmarkDetailViewModel(benchmark: Benchmark.market)
        #expect(index.sourceLabel == "FRED SP500")
        #expect(index.sourceExplanation.contains("end-of-day"),
                "An index level that is a day old must not read as live")
    }
}
