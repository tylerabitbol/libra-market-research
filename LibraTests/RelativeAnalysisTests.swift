import Testing
import Foundation
@testable import Libra

/// Separating "the market moved" from "this company moved".
///
/// The failure this guards against is subtle: an attribution built on
/// misaligned sessions, or on a beta fitted to noise, reads exactly like a
/// correct one. Every test here is about refusing to answer rather than
/// answering confidently.
@Suite("Relative analysis")
struct RelativeAnalysisTests {

    private static let epoch = Date(timeIntervalSince1970: 1_700_000_000)

    private func day(_ index: Int) -> Date {
        Self.epoch.addingTimeInterval(Double(index) * 86_400)
    }

    /// Security bars whose daily moves are `beta × market` plus an optional
    /// per-session residual.
    private func bars(marketMoves: [Double], beta: Double, residuals: [Double] = []) -> [PriceBar] {
        var close = 100.0
        var result = [PriceBar(date: day(0), resolution: .daily, open: close, high: close,
                               low: close, close: close, volume: 1000, adjustedClose: close)]
        for (index, move) in marketMoves.enumerated() {
            let residual = index < residuals.count ? residuals[index] : 0
            close *= (1 + (beta * move + residual) / 100)
            result.append(PriceBar(date: day(index + 1), resolution: .daily, open: close,
                                   high: close, low: close, close: close, volume: 1000,
                                   adjustedClose: close))
        }
        return result
    }

    private func marketCloses(_ moves: [Double]) -> [(date: Date, close: Double)] {
        var close = 1000.0
        var result = [(date: day(0), close: close)]
        for (index, move) in moves.enumerated() {
            close *= (1 + move / 100)
            result.append((date: day(index + 1), close: close))
        }
        return result
    }

    private func alternating(_ count: Int, magnitude: Double = 1.0) -> [Double] {
        (0..<count).map { $0.isMultiple(of: 2) ? magnitude : -magnitude }
    }

    // MARK: - Alignment

    @Test("Only sessions present in both series are compared")
    func alignmentDropsUnmatchedSessions() {
        // A holiday in one calendar and not the other would otherwise pair a
        // two-day move against a one-day move and call the gap company-specific.
        let moves = alternating(80)
        let security = bars(marketMoves: moves, beta: 1)
        var market = marketCloses(moves)
        market.removeAll { Calendar.current.isDate($0.date, inSameDayAs: day(40)) }

        let aligned = RelativeAnalysis.align(security: security, market: market)
        let hasGapDay = aligned.contains { Calendar.current.isDate($0.date, inSameDayAs: day(40)) }
        #expect(!hasGapDay)
        // Day 41's market return would span two days; it must be dropped too.
        let hasSpanningDay = aligned.contains {
            Calendar.current.isDate($0.date, inSameDayAs: day(41))
        }
        #expect(!hasSpanningDay, "A two-day market move must not be paired with a one-day move")
    }

    @Test("Aligned returns come back in chronological order")
    func alignmentIsOrdered() {
        let moves = alternating(80)
        let aligned = RelativeAnalysis.align(security: bars(marketMoves: moves, beta: 1),
                                             market: marketCloses(moves))
        #expect(aligned.map(\.date) == aligned.map(\.date).sorted())
    }

    // MARK: - Beta

    @Test("Beta recovers a known sensitivity")
    func betaRecoversKnownValue() throws {
        let moves = (0..<120).map { Double(($0 % 7)) - 3 }
        let aligned = RelativeAnalysis.align(security: bars(marketMoves: moves, beta: 2),
                                             market: marketCloses(moves))
        let beta = try #require(RelativeAnalysis.beta(aligned))

        // Compounding makes this approximate rather than exact.
        #expect(abs(beta.value - 2) < 0.1)
        #expect(beta.observationCount == aligned.count)
    }

    @Test("Beta is fitted over a bounded recent window, not all of history")
    func betaWindowIsBounded() throws {
        // Beta is not a constant. A five-year fit averages a sensitivity the
        // company no longer has into the one being applied today — and it
        // looks more rigorous while being less informative.
        let moves = (0..<900).map { Double(($0 % 7)) - 3 }
        let aligned = RelativeAnalysis.align(security: bars(marketMoves: moves, beta: 2),
                                             market: marketCloses(moves))
        let beta = try #require(RelativeAnalysis.beta(aligned))

        #expect(aligned.count > RelativeAnalysis.betaWindow)
        #expect(beta.observationCount == RelativeAnalysis.betaWindow)
        #expect(beta.latest == aligned.last?.date, "The window must end at the present")
    }

    @Test("Too few overlapping sessions yields no beta")
    func betaNeedsASample() {
        let moves = alternating(20)
        let aligned = RelativeAnalysis.align(security: bars(marketMoves: moves, beta: 1),
                                             market: marketCloses(moves))
        // A beta from a handful of sessions is noise with a Greek letter on it.
        #expect(RelativeAnalysis.beta(aligned) == nil)
    }

    @Test("A motionless market yields no beta")
    func betaNeedsMarketVariance() {
        let flat = Array(repeating: 0.0, count: 100)
        let aligned = RelativeAnalysis.align(security: bars(marketMoves: flat, beta: 1,
                                                            residuals: alternating(100)),
                                             market: marketCloses(flat))
        // Dividing by a near-zero variance produces an enormous beta from nothing.
        #expect(RelativeAnalysis.beta(aligned) == nil)
    }

    // MARK: - Attribution

    @Test("A high-beta name's market share is scaled, not taken raw")
    func attributionUsesBeta() {
        let beta = Beta(value: 2.24, observationCount: 250, earliest: day(0), latest: day(250))
        let attribution = RelativeAnalysis.attribute(
            securityMove: 8.74, marketMove: 0.31, marketName: "S&P 500", beta: beta
        )

        // Raw difference would call 8.43 pp company-specific; beta accounts
        // for more than twice as much of the move.
        #expect(abs(attribution.explainedByMarket - 0.6944) < 0.001)
        #expect(abs(attribution.residual - 8.0456) < 0.001)
        #expect(attribution.leaning == .companySpecific)
    }

    @Test("Without a beta the comparison is the plain difference, and says so")
    func attributionWithoutBetaIsLabelled() {
        let attribution = RelativeAnalysis.attribute(
            securityMove: 3, marketMove: 1, marketName: "S&P 500", beta: nil
        )
        #expect(attribution.residual == 2)
        let disclosed = attribution.detailLines.contains { $0.contains("not beta-adjusted") }
        #expect(disclosed, "A high-beta name reads very differently unadjusted")
    }

    @Test("A move the market accounts for leans market-wide")
    func marketWideLeaning() {
        let beta = Beta(value: 1.0, observationCount: 250, earliest: day(0), latest: day(250))
        let attribution = RelativeAnalysis.attribute(
            securityMove: 2.2, marketMove: 2.0, marketName: "S&P 500", beta: beta
        )
        #expect(attribution.leaning == .marketWide)
        #expect(attribution.claim.kind == .interpretation,
                "A one-factor model's verdict is a judgement, not a fact")
    }

    @Test("An ETF standing in for the index is disclosed")
    func proxyIsDisclosed() {
        let attribution = RelativeAnalysis.attribute(
            securityMove: 5, marketMove: 1, marketName: "S&P 500 (SPY)",
            beta: nil, isMarketProxy: true
        )
        let disclosed = attribution.detailLines.contains { $0.contains("ETF proxy") }
        #expect(disclosed)
    }

    @Test("Opposite directions are not forced into a share of one move")
    func oppositeDirectionsSurvive() {
        // A stock rising on a falling market breaks any "percentage of the
        // move" framing, so the two parts are reported separately and signed.
        let beta = Beta(value: 1.0, observationCount: 250, earliest: day(0), latest: day(250))
        let attribution = RelativeAnalysis.attribute(
            securityMove: 3, marketMove: -2, marketName: "S&P 500", beta: beta
        )
        #expect(attribution.explainedByMarket == -2)
        #expect(attribution.residual == 5)
    }
}
