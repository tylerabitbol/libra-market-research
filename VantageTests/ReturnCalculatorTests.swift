import Testing
import Foundation
@testable import Vantage

@Suite("Return calculations")
struct ReturnCalculatorTests {
    /// Daily bars ending today, with an explicit closing price per day.
    private func bars(_ closes: [Double], endingAt end: Date) -> [PriceBar] {
        closes.enumerated().map { offset, close in
            let date = end.addingTimeInterval(-Double(closes.count - 1 - offset) * 86_400)
            return PriceBar(date: date, resolution: .daily, open: close, high: close,
                            low: close, close: close, adjustedClose: close)
        }
    }

    private let end = Date(timeIntervalSince1970: 1_756_000_000)

    @Test("A simple return is the percentage change between two prices")
    func simpleReturn() {
        #expect(ReturnCalculator.simpleReturn(from: 100, to: 112) == 12)
        #expect(ReturnCalculator.simpleReturn(from: 100, to: 88) == -12)
    }

    @Test("A zero base returns nil rather than infinity")
    func zeroBaseIsNil() {
        #expect(ReturnCalculator.simpleReturn(from: 0, to: 50) == nil)
    }

    @Test("Non-finite inputs return nil")
    func nonFiniteIsNil() {
        #expect(ReturnCalculator.simpleReturn(from: 100, to: .infinity) == nil)
        #expect(ReturnCalculator.simpleReturn(from: .nan, to: 100) == nil)
    }

    @Test("A trailing return measures from the bar at the window start")
    func trailingReturnUsesWindowStart() throws {
        // 8 daily bars; 7 days back from the last is the first.
        let series = bars([100, 101, 102, 103, 104, 105, 106, 110], endingAt: end)
        let result = try #require(
            ReturnCalculator.trailingReturn(bars: series, window: .init(day: -7))
        )
        #expect(result.startPrice == 100)
        #expect(result.endPrice == 110)
        #expect(abs(result.percent - 10) < 0.0001)
        #expect(result.isFullWindow)
    }

    @Test("A window longer than the available history is flagged as partial")
    func shortHistoryIsFlaggedPartial() throws {
        // Only 5 days of history, but a 1-month window was requested.
        let series = bars([100, 102, 104, 106, 108], endingAt: end)
        let result = try #require(
            ReturnCalculator.trailingReturn(bars: series, window: .init(month: -1))
        )
        #expect(!result.isFullWindow,
                "A short read must be labelled, not presented as a full month")
        #expect(result.startPrice == 100)
    }

    @Test("A weekend gap still counts as a full window")
    func weekendGapToleratedAsFull() throws {
        let series = bars(Array(repeating: 100, count: 10).enumerated().map { Double(100 + $0.offset) },
                          endingAt: end)
        let result = try #require(
            ReturnCalculator.trailingReturn(bars: series, window: .init(day: -7))
        )
        #expect(result.isFullWindow)
    }

    @Test("Insufficient bars return nil rather than a fabricated zero")
    func singleBarReturnsNil() {
        let series = bars([100], endingAt: end)
        #expect(ReturnCalculator.trailingReturn(bars: series, window: .init(day: -7)) == nil)
    }

    @Test("An empty series returns nil")
    func emptySeriesReturnsNil() {
        #expect(ReturnCalculator.trailingReturn(bars: [], window: .init(day: -7)) == nil)
    }

    @Test("Bars in arbitrary order are sorted before measuring")
    func unorderedBarsAreHandled() throws {
        let series = bars([100, 101, 102, 103, 104, 105, 106, 110], endingAt: end).shuffled()
        let result = try #require(
            ReturnCalculator.trailingReturn(bars: series, window: .init(day: -7))
        )
        #expect(result.startPrice == 100)
        #expect(result.endPrice == 110)
    }

    @Test("Adjusted close is preferred over raw close")
    func adjustedCloseIsUsed() throws {
        let raw = [PriceBar(date: end.addingTimeInterval(-7 * 86_400), resolution: .daily,
                            open: 200, high: 200, low: 200, close: 200, adjustedClose: 100),
                   PriceBar(date: end, resolution: .daily,
                            open: 110, high: 110, low: 110, close: 110, adjustedClose: 110)]
        let result = try #require(
            ReturnCalculator.trailingReturn(bars: raw, window: .init(day: -7))
        )
        #expect(result.startPrice == 100, "Split-adjusted series must drive the calculation")
        #expect(abs(result.percent - 10) < 0.0001)
    }
}

@Suite("Relative performance")
struct RelativePerformanceTests {
    private let end = Date(timeIntervalSince1970: 1_756_000_000)

    private func period(_ percent: Double, startOffsetDays: Double) -> PeriodReturn {
        PeriodReturn(
            percent: percent,
            startDate: end.addingTimeInterval(-startOffsetDays * 86_400),
            endDate: end, startPrice: 100, endPrice: 100 * (1 + percent / 100),
            isFullWindow: true
        )
    }

    @Test("Outperformance is stated in percentage points")
    func differenceInPercentagePoints() throws {
        let result = try #require(ReturnCalculator.relativePerformance(
            security: period(12, startOffsetDays: 30),
            benchmark: period(5, startOffsetDays: 30)
        ))
        #expect(abs(result.differencePoints - 7) < 0.0001)
    }

    @Test("Mismatched windows refuse to produce a comparison")
    func mismatchedWindowsAreRejected() {
        let result = ReturnCalculator.relativePerformance(
            security: period(12, startOffsetDays: 30),
            benchmark: period(5, startOffsetDays: 90)
        )
        #expect(result == nil, "Comparing a 1-month return against a 3-month return is meaningless")
    }

    @Test("A missing leg produces no comparison")
    func missingLegIsNil() {
        #expect(ReturnCalculator.relativePerformance(
            security: period(12, startOffsetDays: 30), benchmark: nil) == nil)
        #expect(ReturnCalculator.relativePerformance(
            security: nil, benchmark: period(5, startOffsetDays: 30)) == nil)
    }

    @Test("The generated claim is a calculation, never an interpretation")
    func claimIsLabelledCalculation() throws {
        let result = try #require(ReturnCalculator.relativePerformance(
            security: period(12, startOffsetDays: 30),
            benchmark: period(5, startOffsetDays: 30)
        ))
        let claim = result.claim(securityName: "NVDA", benchmarkName: "Information Technology")
        #expect(claim.kind == .calculation)
        #expect(claim.isTraceable)
        #expect(claim.text.contains("outperformed"))
        #expect(claim.text.contains("pp"), "Percentage points must be distinguished from percent")
    }

    @Test("Underperformance is described without euphemism")
    func underperformanceIsStatedPlainly() throws {
        let result = try #require(ReturnCalculator.relativePerformance(
            security: period(2, startOffsetDays: 30),
            benchmark: period(9, startOffsetDays: 30)
        ))
        let claim = result.claim(securityName: "X", benchmarkName: "Y")
        #expect(claim.text.contains("underperformed"))
    }
}
