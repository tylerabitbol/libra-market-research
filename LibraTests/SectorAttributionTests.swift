import Testing
import Foundation
@testable import Libra

/// The sector leg of attribution.
///
/// The whole risk here is double-counting. A sector ETF moves with the market —
/// XLK and the S&P share most of their variance — so subtracting a raw sector
/// move from what the market already explained counts the market twice and
/// leaves a residual that is mostly sign noise. The sector is orthogonalised
/// against the market before it is used as a factor, and the first test below
/// is what proves it.
private let sessionStart = Date(timeIntervalSince1970: 1_600_000_000)

private func day(_ index: Int) -> Date {
    sessionStart.addingTimeInterval(Double(index) * 86_400)
}

/// Turns a return series (percent) into closes starting at 100.
private func closes(_ returns: [Double]) -> [Double] {
    var level = 100.0
    var result = [level]
    for percent in returns {
        level *= (1 + percent / 100)
        result.append(level)
    }
    return result
}

private func bars(_ returns: [Double]) -> [PriceBar] {
    closes(returns).enumerated().map { index, close in
        PriceBar(date: day(index), resolution: .daily, open: close, high: close,
                 low: close, close: close, adjustedClose: close)
    }
}

private func series(_ returns: [Double]) -> [(date: Date, close: Double)] {
    closes(returns).enumerated().map { (date: day($0.offset), close: $0.element) }
}

/// A deterministic generator, so a fit is stable across runs.
///
/// Sums of sinusoids were tried first and are a trap here: two such series at
/// different frequencies are still measurably correlated over a few hundred
/// points, which is precisely the thing these tests need to be free of. A
/// linear congruential generator gives independent series with no such
/// accidental structure.
private struct Deterministic {
    private var state: UInt64
    init(seed: UInt64) { state = seed }

    private mutating func next() -> Double {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return Double(state >> 11) / Double(1 << 53)
    }

    /// Roughly standard normal by the central limit theorem.
    mutating func normal() -> Double {
        (0..<12).reduce(0.0) { sum, _ in sum + next() } - 6.0
    }

    static func returns(_ count: Int, seed: UInt64, scale: Double = 1.0) -> [Double] {
        var generator = Deterministic(seed: seed)
        return (0..<count).map { _ in generator.normal() * scale }
    }
}

@Suite("Sector attribution")
struct SectorAttributionTests {

    @Test("A sector barely distinct from the market explains almost none of a move")
    func sectorTrackingTheMarketExplainsAlmostNothing() throws {
        let market = Deterministic.returns(400, seed: 11)
        let sectorOwn = Deterministic.returns(400, seed: 22)
        // A sector that is 99% the market. Whatever it adds, the security does
        // not respond to it — so the sector leg must be negligible and the
        // residual must land where the one-factor model already put it. This is
        // the double-counting test: without orthogonalisation the sector's
        // market component would be subtracted a second time here.
        let sector = zip(market, sectorOwn).map { $0 + $1 * 0.01 }
        let securityReturns = market.map { $0 * 1.5 }

        let aligned = RelativeAnalysis.align(
            security: bars(securityReturns), market: series(market), sector: bars(sector))
        let factor = try #require(RelativeAnalysis.sectorFactor(
            aligned, name: "Information Technology", isProxy: true))

        #expect(abs(factor.marketBeta - 1.0) < 0.02)
        #expect(abs(factor.sensitivity) < 0.2, "The security does not track the sector's own move")

        let attribution = RelativeAnalysis.attribute(
            securityMove: 3.0, marketMove: 2.0, marketName: "S&P 500",
            beta: Beta(value: 1.5, observationCount: 400, earliest: day(0), latest: day(400)),
            sector: factor, sectorMove: 2.0)

        let leg = try #require(attribution.sector)
        #expect(abs(leg.excess) < 0.05, "Almost none of the sector's move is beyond the market")
        // 3.0 - 1.5 x 2.0 = 0, the one-factor answer, undisturbed.
        #expect(abs(attribution.residual) < 0.05)
    }

    @Test("A sector identical to the market is refused rather than fitted")
    func degenerateSectorIsRefused() {
        let market = Deterministic.returns(200, seed: 33)
        // Zero variance beyond the market means there is no second factor to
        // fit. Returning nil drops the leg and leaves the one-factor model,
        // which is the honest outcome.
        let aligned = RelativeAnalysis.align(
            security: bars(market.map { $0 * 1.5 }), market: series(market), sector: bars(market))
        #expect(RelativeAnalysis.sectorFactor(aligned, name: "Tech", isProxy: true) == nil)
    }

    @Test("A sector that moves on its own carries the part the market cannot")
    func sectorBeyondMarketIsAttributed() throws {
        let market = Deterministic.returns(400, seed: 44)
        let sectorOwn = Deterministic.returns(400, seed: 55, scale: 0.8)
        // Sector = market + its own independent component. The security follows
        // the market at 1.0 and that component at 2.0, and the fit must recover
        // both without confusing one for the other.
        let sector = zip(market, sectorOwn).map { $0 + $1 }
        let securityReturns = zip(market, sectorOwn).map { $0 + $1 * 2.0 }

        let aligned = RelativeAnalysis.align(
            security: bars(securityReturns), market: series(market), sector: bars(sector))
        let factor = try #require(RelativeAnalysis.sectorFactor(
            aligned, name: "Information Technology", isProxy: true))

        #expect(abs(factor.marketBeta - 1.0) < 0.05)
        #expect(abs(factor.sensitivity - 2.0) < 0.15,
                "Recovered the security's sensitivity to the sector's own move")
    }

    @Test("Too little overlapping history produces no factor rather than a weak one")
    func shortHistoryHasNoFactor() {
        let market = Deterministic.returns(20, seed: 66)
        let aligned = RelativeAnalysis.align(
            security: bars(market), market: series(market), sector: bars(market))
        #expect(RelativeAnalysis.sectorFactor(aligned, name: "Tech", isProxy: true) == nil)
    }

    @Test("A flat market produces no factor rather than an enormous one")
    func flatMarketHasNoFactor() {
        let flat = Array(repeating: 0.0, count: 200)
        let aligned = RelativeAnalysis.align(
            security: bars(Deterministic.returns(200, seed: 77)), market: series(flat), sector: bars(flat))
        // Dividing by a near-zero variance manufactures a coefficient from
        // nothing at all.
        #expect(RelativeAnalysis.sectorFactor(aligned, name: "Tech", isProxy: true) == nil)
    }

    @Test("Three-way alignment keeps only sessions every leg shares")
    func alignmentRequiresAllThreeLegs() {
        let full = bars(Deterministic.returns(10, seed: 88))
        let market = series(Deterministic.returns(10, seed: 88))
        // The sector is missing its later sessions entirely.
        let shortSector = Array(bars(Deterministic.returns(10, seed: 88)).prefix(5))

        let aligned = RelativeAnalysis.align(
            security: full, market: market, sector: shortSector)
        #expect(aligned.count <= 4, "A session the sector never traded cannot be compared")
        #expect(aligned.allSatisfy { $0.date <= shortSector.last!.date })
    }

    @Test("The leaning names the industry when the sector carries the move")
    func leaningPointsAtTheIndustry() {
        let attribution = RelativeAnalysis.attribute(
            securityMove: 5.0, marketMove: 0.1, marketName: "S&P 500",
            beta: Beta(value: 1.0, observationCount: 200, earliest: day(0), latest: day(200)),
            sector: SectorFactor(name: "Semiconductors", marketBeta: 1.0, sensitivity: 1.0,
                                 observationCount: 200, isProxy: true,
                                 earliest: day(0), latest: day(200)),
            sectorMove: 4.9)

        // Sector beyond market is 4.8 of a 5.0 move; the market explains 0.1.
        #expect(attribution.leaning == .industry)
        #expect(attribution.claim.text.contains("Semiconductors"))
        // Never a share of one move — the parts can point opposite ways.
        #expect(!attribution.claim.text.contains("%of"))
    }

    @Test("Without a sector the attribution is unchanged and says so")
    func noSectorKeepsTheOneFactorModel() {
        let attribution = RelativeAnalysis.attribute(
            securityMove: 3.0, marketMove: 1.0, marketName: "S&P 500",
            beta: Beta(value: 2.0, observationCount: 200, earliest: day(0), latest: day(200)),
            sector: nil, sectorMove: nil)

        #expect(attribution.sector == nil)
        #expect(attribution.residual == 1.0, "3.0 - 2.0 x 1.0")
        #expect(attribution.claim.text.contains("one-factor"))
    }

    @Test("A sector move with no fitted factor is not guessed at")
    func sectorMoveWithoutFactorIsIgnored() {
        let attribution = RelativeAnalysis.attribute(
            securityMove: 3.0, marketMove: 1.0, marketName: "S&P 500",
            beta: nil, sector: nil, sectorMove: 4.0)
        #expect(attribution.sector == nil,
                "A sector move without a sensitivity to apply it at explains nothing")
    }
}
