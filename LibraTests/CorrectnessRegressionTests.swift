import Testing
import Foundation
@testable import Libra

private func points(_ values: [Double]) -> [MetricPoint] {
    values.enumerated().map { index, value in
        MetricPoint(period: Date(timeIntervalSince1970: 1_500_000_000
                                 + Double(index) * 90 * 86_400),
                    value: value)
    }
}

@Suite("Metric key mapping")
struct MetricMappingTests {
    @Test("EV/EBITDA reads EV/EBITDA, not EV/free-cash-flow")
    func evEbitdaMapsToItself() throws {
        let metric = try #require(ValuationMetric.all.first { $0.key == "evEbitdaTTM" })
        #expect(metric.currentKey == "evEbitdaTTM",
                "An earlier revision pointed this at currentEv/freeCashFlowTTM, a different metric shown under this label")
    }

    @Test("No metric borrows another metric's current value")
    func noCrossedWires() {
        // Every current key must either match its history key or be a declared
        // alias of the same concept. A mismatch of the EV/EBITDA kind must not
        // be able to slip in unnoticed again.
        let knownAliases: [String: String] = [
            "grossMargin": "grossMarginTTM",
            "operatingMargin": "operatingMarginTTM",
            "netMargin": "netProfitMarginTTM",
            "roe": "roeTTM"
        ]
        for metric in ValuationMetric.all {
            guard let currentKey = metric.currentKey else { continue }
            let valid = currentKey == metric.key || knownAliases[metric.key] == currentKey
            #expect(valid, "\(metric.key) reads its current value from \(currentKey)")
        }
    }

    @Test("P/FCF has no current key and is therefore history-sourced")
    func pfcfIsHistorySourced() throws {
        // Finnhub's metric block carries no P/FCF; the value shown comes from
        // the quarterly series and must be date-stamped rather than implied current.
        let metric = try #require(ValuationMetric.all.first { $0.key == "pfcfTTM" })
        #expect(metric.currentKey == nil)

        let metrics = CompanyMetricsDTO(
            current: [:], annual: [:],
            quarterly: ["pfcfTTM": points([20, 21, 22, 23, 24, 25, 26, 27, 28, 29])],
            asOf: .now
        )
        let normalized = try #require(metrics.normalized(for: metric))
        #expect(normalized.currentIsFromHistory)
        #expect(normalized.asOf < Date.now.addingTimeInterval(-86_400),
                "The as-of date must be the observation's, not now")
    }
}

@Suite("Unrankable multiples")
struct MeaningfulnessTests {
    @Test("A negative multiple is flagged rather than ranked at the bottom")
    func negativeMultipleIsFlagged() throws {
        let history = points([20, 22, 24, 26, 28, 30, 32, 34, 36, 38])
        let context = try #require(ValuationCalculator.historicalContext(
            current: -15, history: history, lowerIsCheaper: true))

        #expect(!context.meaningfulness.isRankable)
        let descriptor = context.descriptor.lowercased()
        #expect(descriptor.contains("negative earnings"))
        #expect(!descriptor.contains("low end of its own range"),
                "That phrasing reads as cheap when the company is losing money")
    }

    @Test("A history straddling zero cannot be ranked")
    func straddlingHistoryIsFlagged() throws {
        let history = points([-10, -5, 5, 12, 18, 22, 26, 30, 33, 36])
        let context = try #require(ValuationCalculator.historicalContext(
            current: 25, history: history, lowerIsCheaper: true))
        #expect(!context.meaningfulness.isRankable)
    }

    @Test("A negative margin is still rankable — losses are a real position in its range")
    func negativeMarginRemainsRankable() throws {
        let history = points([-5, -2, 1, 4, 7, 10, 13, 16, 19, 22])
        let context = try #require(ValuationCalculator.historicalContext(
            current: -3, history: history, lowerIsCheaper: false))
        #expect(context.meaningfulness.isRankable,
                "A negative margin means what it says; a negative P/E does not")
    }

    @Test("Ordinary positive multiples stay rankable")
    func positiveMultipleRankable() throws {
        let history = points([20, 22, 24, 26, 28, 30, 32, 34, 36, 38])
        let context = try #require(ValuationCalculator.historicalContext(
            current: 31, history: history, lowerIsCheaper: true))
        #expect(context.meaningfulness.isRankable)
        #expect(context.percentile > 0 && context.percentile < 100)
    }
}

@Suite("Percentile self-exclusion")
struct SelfExclusionTests {
    @Test("A value taken from history is not counted in its own ranking")
    func currentExcludedFromOwnRanking() throws {
        let history = points([10, 20, 30, 40, 50, 60, 70, 80, 90, 100])

        let included = try #require(ValuationCalculator.historicalContext(
            current: 100, history: history, currentIsFromHistory: false))
        let excluded = try #require(ValuationCalculator.historicalContext(
            current: 100, history: history, currentIsFromHistory: true))

        #expect(included.observationCount == 10)
        #expect(excluded.observationCount == 9, "The current observation is dropped")
        // Both still rank at the top; the point is the sample, not the result.
        #expect(excluded.percentile == 100)
        #expect(excluded.maximum == 90, "The value must not appear in its own range")
    }

    @Test("Self-exclusion still respects the minimum sample")
    func exclusionRespectsMinimum() {
        let history = points([1, 2, 3, 4, 5, 6, 7, 8])
        // Eight observations minus the current leaves seven, which is the floor.
        #expect(ValuationCalculator.historicalContext(
            current: 8, history: history, currentIsFromHistory: true) != nil)
        #expect(ValuationCalculator.historicalContext(
            current: 7, history: points([1, 2, 3, 4, 5, 6, 7]),
            currentIsFromHistory: true) == nil)
    }
}

@Suite("Scale validation")
struct ScaleValidationTests {
    @Test("A ratio series is scaled to percent")
    func ratioSeriesIsScaled() {
        let scale = CompanyMetricsDTO.resolvedHistoryScale(
            declared: 100, values: [0.42, 0.46, 0.48])
        #expect(scale == 100)
    }

    @Test("A series already in percent is not scaled again")
    func percentSeriesNotDoubleScaled() {
        // If Finnhub ever returns percent here, blindly multiplying would give
        // 4622% — plausible-looking and catastrophically wrong.
        let scale = CompanyMetricsDTO.resolvedHistoryScale(
            declared: 100, values: [42.0, 46.2, 48.6])
        #expect(scale == 1)
    }

    @Test("A high but genuine ratio is still treated as a ratio")
    func highRatioStillScaled() {
        // Apple's ROE is around 1.37 as a ratio — well below the threshold.
        let scale = CompanyMetricsDTO.resolvedHistoryScale(
            declared: 100, values: [1.10, 1.37, 1.42])
        #expect(scale == 100)
    }

    @Test("Metrics declaring no scaling are never rescaled")
    func multiplesNeverRescaled() {
        #expect(CompanyMetricsDTO.resolvedHistoryScale(
            declared: 1, values: [30, 40, 50]) == 1)
    }
}

@Suite("SEC identifier handling")
struct CIKTests {
    @Test("A valid CIK is zero-padded to ten digits")
    func padsCorrectly() throws {
        #expect(try SECProvider.normalizedCIK("320193") == "0000320193")
        #expect(try SECProvider.normalizedCIK("0000320193") == "0000320193")
    }

    @Test("An unparseable CIK throws instead of silently becoming zero")
    func rejectsGarbage() {
        // A `?? 0` fallback builds a well-formed request for CIK 0000000000 —
        // a silent wrong question rather than a visible failure.
        #expect(throws: APIError.self) { try SECProvider.normalizedCIK("not-a-cik") }
        #expect(throws: APIError.self) { try SECProvider.normalizedCIK("") }
        #expect(throws: APIError.self) { try SECProvider.normalizedCIK("0") }
    }

    @Test("A CIK that cannot build an archive URL yields no filings rather than wrong links")
    func badCIKYieldsNoFilings() throws {
        let response = try Fixture.decode(
            SubmissionsResponse.self, from: "sec_submissions_synthetic")
        #expect(response.filings.recent.filings(cik: "not-a-cik").isEmpty,
                "A link to the wrong filer is worse than no link")
    }
}

@Suite("Watchlist sort options")
struct SortOptionTests {
    @Test("Every offered sort produces a distinct ordering")
    func noDuplicateSorts() {
        // "Most unusual" was once byte-identical to "Biggest change" — two menu
        // entries doing the same thing — and was withdrawn until the detectors
        // could tell them apart. They can now, so the guard tests the property
        // it always cared about rather than the absence of the option.
        var rows: [WatchlistRow] = []
        for (index, spec) in [("DELTA", 1.0, 0.99, 4, 3), ("ALPHA", 9.0, 0.10, 1, 2),
                              ("CHARLIE", 5.0, 0.50, 3, 0), ("BRAVO", 2.0, 0.75, 2, 1)]
            .enumerated() {
            var row = WatchlistRow(symbol: spec.0, name: spec.0)
            row.priority = spec.4
            row.quote = QuoteDTO(symbol: spec.0, last: 100 * (1 + spec.1 / 100), open: nil,
                                 high: nil, low: nil, previousClose: 100, volume: nil,
                                 quoteTime: nil)
            row.latestEvent = DetectedEventDTO(
                kind: .unusualVolume,
                occurredAt: Date(timeIntervalSince1970: 1_700_000_000 + Double(spec.3) * 86_400),
                headline: "Event \(index)", unusualness: spec.2)
            rows.append(row)
        }

        let orderings = WatchlistViewModel.SortOrder.allCases.map { order in
            WatchlistViewModel.sorted(rows, by: order).map(\.symbol)
        }
        #expect(Set(orderings).count == WatchlistViewModel.SortOrder.allCases.count,
                "Two menu entries producing the same order is worse than one honest entry")
    }
}


/// Annual figures keyed on the period they describe, not on the filing's
/// fiscal context. Observed on screen as two rows both labelled "2022",
/// holding FY2022 and FY2021 revenue, and as free cash flow figures paired
/// with dates two years off.
@Suite("Annual period keying")
@MainActor
struct AnnualPeriodKeyingTests {

    private func fact(
        _ concept: FinancialConcept,
        periodEnd: String,
        fiscalYear: Int,
        value: Double,
        filed: String = "2026-01-01"
    ) -> FinancialFactDTO {
        let parse: (String) -> Date = { text in
            try! Date(text + "T00:00:00Z", strategy: .iso8601)
        }
        return FinancialFactDTO(
            concept: concept, rawTag: nil,
            periodStart: nil, periodEnd: parse(periodEnd),
            fiscalYear: fiscalYear, fiscalQuarter: nil, isAnnual: true,
            periodKind: .annual, value: value, unit: "USD",
            filedAt: parse(filed), accessionNumber: nil
        )
    }

    @Test("Two periods sharing a fiscal year stay distinct")
    func distinctPeriodsSurvive() {
        // A restated FY2021 carries fy=2022 from the filing that restated it,
        // colliding with the genuine FY2022 in any fiscalYear-keyed map.
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.applyFundamentalsForTesting([
            fact(.revenue, periodEnd: "2022-01-30", fiscalYear: 2022, value: 26_914_000_000),
            fact(.revenue, periodEnd: "2021-01-31", fiscalYear: 2022, value: 16_675_000_000)
        ])

        let labels = model.annualRevenue.map(\.periodLabel)
        #expect(Set(labels) == ["2022", "2021"],
                "Both rows previously rendered as 2022")
    }

    @Test("Free cash flow pairs each period with its own date")
    func freeCashFlowPairsCorrectly() throws {
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.applyFundamentalsForTesting([
            fact(.operatingCashFlow, periodEnd: "2024-01-28", fiscalYear: 2024, value: 28_090_000_000),
            fact(.capitalExpenditures, periodEnd: "2024-01-28", fiscalYear: 2024, value: 1_069_000_000),
            fact(.operatingCashFlow, periodEnd: "2023-01-29", fiscalYear: 2024, value: 5_641_000_000),
            fact(.capitalExpenditures, periodEnd: "2023-01-29", fiscalYear: 2024, value: 1_833_000_000)
        ])

        let points = model.annualFreeCashFlow
        #expect(points.count == 2, "A shared fiscalYear previously collapsed these to one")

        let newest = try #require(points.last)
        // 28.090B - 1.069B, matched to its own period end.
        #expect(abs(newest.value - 27_021_000_000) < 1)
        #expect(Calendar.current.component(.year, from: newest.period) == 2024)
    }

    @Test("A period missing either input yields no figure")
    func partialPeriodIsOmitted() {
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.applyFundamentalsForTesting([
            fact(.operatingCashFlow, periodEnd: "2024-01-28", fiscalYear: 2024, value: 28_090_000_000)
        ])
        // Free cash flow without capex is not free cash flow.
        #expect(model.annualFreeCashFlow.isEmpty)
    }
}
