import Testing
import Foundation
@testable import Vantage

@Suite("Historical valuation context")
struct ValuationTests {
    private func series(_ values: [Double]) -> [MetricPoint] {
        values.enumerated().map { index, value in
            MetricPoint(period: Date(timeIntervalSince1970: 1_500_000_000
                                     + Double(index) * 90 * 86_400),
                        value: value)
        }
    }

    @Test("Percentile is the share of past observations at or below the current value")
    func percentileDefinition() throws {
        // Ten observations, current above eight of them.
        let history = series([10, 12, 14, 16, 18, 20, 22, 24, 26, 40])
        let context = try #require(
            ValuationCalculator.historicalContext(current: 25, history: history)
        )
        #expect(context.percentile == 80)
        #expect(context.observationCount == 10)
    }

    @Test("A value at the bottom and top of the range read as 10th and 100th")
    func percentileExtremes() throws {
        let history = series(Array(stride(from: 10.0, through: 100.0, by: 10.0)))
        let low = try #require(ValuationCalculator.historicalContext(current: 10, history: history))
        let high = try #require(ValuationCalculator.historicalContext(current: 100, history: history))
        #expect(low.percentile == 10)
        #expect(high.percentile == 100)
    }

    @Test("Too few observations produce nothing rather than a weak percentile")
    func insufficientHistoryIsNil() {
        // Three points cannot support a percentile that invites confidence.
        #expect(ValuationCalculator.historicalContext(
            current: 20, history: series([10, 20, 30])) == nil)
    }

    @Test("Non-finite inputs are refused")
    func nonFiniteRefused() {
        let history = series([10, 12, 14, 16, 18, 20, 22, 24])
        #expect(ValuationCalculator.historicalContext(current: .nan, history: history) == nil)
        #expect(ValuationCalculator.historicalContext(current: .infinity, history: history) == nil)
    }

    @Test("Median is correct for both even and odd sample sizes")
    func medianCalculation() {
        #expect(ValuationCalculator.median(of: [1, 2, 3, 4]) == 2.5)
        #expect(ValuationCalculator.median(of: [1, 2, 3]) == 2)
    }

    @Test("Range and observation window are reported alongside the percentile")
    func contextCarriesRange() throws {
        let history = series([10, 12, 14, 16, 18, 20, 22, 24])
        let context = try #require(
            ValuationCalculator.historicalContext(current: 15, history: history)
        )
        #expect(context.minimum == 10)
        #expect(context.maximum == 24)
        #expect(context.median == 17)
        #expect(context.earliest < context.latest)
    }

    @Test("The multiple is a calculation; its position in history is an interpretation")
    func claimsAreSeparatelyLabelled() throws {
        let history = series([10, 12, 14, 16, 18, 20, 22, 24, 26, 40])
        let context = try #require(
            ValuationCalculator.historicalContext(current: 25, history: history)
        )
        let claims = ValuationCalculator.claims(
            metricName: "P/E", context: context, source: nil
        )
        // Bound outside the macro: allSatisfy is `rethrows`, which the #expect
        // expansion cannot see through.
        let allTraceable = claims.allSatisfy(\.isTraceable)

        #expect(claims.count == 2)
        #expect(claims[0].kind == .calculation)
        #expect(claims[1].kind == .interpretation,
                "A judgement about whether a multiple is high must not be labelled a fact")
        #expect(allTraceable)
        #expect(claims[1].text.contains("80th"))
    }

    @Test("Descriptors never recommend an action")
    func descriptorsAreNeutral() {
        let banned = ["buy", "sell", "cheap", "overvalued", "undervalued", "bargain"]
        for percentile in stride(from: 0, through: 100, by: 5) {
            let context = HistoricalContext(
                current: 20, percentile: percentile, median: 18, minimum: 10,
                maximum: 40, observationCount: 20,
                earliest: .distantPast, latest: .now
            )
            let descriptor = context.descriptor.lowercased()
            for word in banned {
                #expect(!descriptor.contains(word),
                        "Descriptor at \(percentile)th contains '\(word)'")
            }
        }
    }

    @Test("Metric definitions record direction without implying a recommendation")
    func metricDirection() throws {
        let pe = try #require(ValuationMetric.all.first { $0.key == "peTTM" })
        let margin = try #require(ValuationMetric.all.first { $0.key == "grossMargin" })
        #expect(pe.lowerIsCheaper)
        #expect(!margin.lowerIsCheaper)
        #expect(Set(ValuationMetric.all.map(\.key)).count == ValuationMetric.all.count)
    }
}

@Suite("Finnhub metrics decoding", .serialized)
struct FinnhubMetricsTests {
    @Test("A real metric payload decodes, keeping numbers and dropping date strings")
    func decodesRealMetrics() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/stock/metric", fixture: "finnhub_metric_AAPL")

        let provider = FinnhubProvider(
            client: HTTPClient(session: StubURLProtocol.makeSession()),
            secrets: InMemorySecretsStore(seed: [.finnhubAPIKey: "test"])
        )
        let metrics = try await provider.metrics(symbol: "AAPL")

        #expect(!metrics.current.isEmpty)
        // 52WeekHighDate is a date string; decoding as [String: Double] would
        // have thrown on it, and coercing it would have invented a number.
        #expect(metrics.current["52WeekHighDate"] == nil)
        #expect(metrics.current["52WeekHigh"] != nil)
    }

    @Test("Historical ratio series are parsed and sorted oldest first")
    func seriesParsed() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/stock/metric", fixture: "finnhub_metric_AAPL")

        let provider = FinnhubProvider(
            client: HTTPClient(session: StubURLProtocol.makeSession()),
            secrets: InMemorySecretsStore(seed: [.finnhubAPIKey: "test"])
        )
        let metrics = try await provider.metrics(symbol: "AAPL")

        #expect(!metrics.quarterly.isEmpty)
        for (_, points) in metrics.quarterly {
            #expect(points == points.sorted { $0.period < $1.period })
        }
    }

    @Test("A valuation series is long enough to support a percentile")
    func seriesSupportsPercentile() async throws {
        StubURLProtocol.reset()
        try StubURLProtocol.stub("/stock/metric", fixture: "finnhub_metric_AAPL")

        let provider = FinnhubProvider(
            client: HTTPClient(session: StubURLProtocol.makeSession()),
            secrets: InMemorySecretsStore(seed: [.finnhubAPIKey: "test"])
        )
        let metrics = try await provider.metrics(symbol: "AAPL")
        let history = metrics.history("peTTM")

        // The fixture is trimmed to 8 points per series, the minimum sample.
        #expect(history.count >= 8, "Expected enough P/E history to rank against")
        if let current = history.last?.value {
            #expect(ValuationCalculator.historicalContext(
                current: current, history: history) != nil)
        }
    }
}
