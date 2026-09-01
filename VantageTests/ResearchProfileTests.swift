import Testing
import Foundation
@testable import Vantage

/// Sections 12 and 13: the components, and the search for evidence that argues
/// the other way.
///
/// The property that matters most is symmetry. An engine that finds
/// disconfirming evidence only when the recent picture is good is a bull-case
/// finder wearing a skeptic's label, and the tests below check both directions
/// of every rule rather than only the one that reads well.
private let calendar = Calendar(identifier: .iso8601)

private func quarterEnd(_ index: Int) -> Date {
    let year = 2019 + index / 4
    let month = [3, 6, 9, 12][index % 4]
    return calendar.date(from: DateComponents(year: year, month: month, day: 28))!
}

private func fact(_ concept: FinancialConcept, index: Int, value: Double,
                  kind: FiscalPeriodKind = .quarter) -> FinancialFactDTO {
    let end = quarterEnd(index)
    return FinancialFactDTO(
        concept: concept, rawTag: nil,
        periodStart: kind == .instant ? nil : end.addingTimeInterval(-90 * 86_400),
        periodEnd: end, fiscalYear: calendar.component(.year, from: end),
        fiscalQuarter: index % 4 + 1, isAnnual: false, periodKind: kind,
        value: value, unit: "USD", filedAt: end, accessionNumber: "acc-\(index)")
}

private func periodReturn(_ percent: Double) -> PeriodReturn {
    PeriodReturn(percent: percent, startDate: quarterEnd(0), endDate: quarterEnd(4),
                 startPrice: 100, endPrice: 100 * (1 + percent / 100), isFullWindow: true)
}

private func relative(_ points: Double) -> RelativePerformance {
    RelativePerformance(securityReturn: points, benchmarkReturn: 0,
                        differencePoints: points,
                        startDate: quarterEnd(0), endDate: quarterEnd(4))
}

@Suite("Research profile")
struct ResearchProfileTests {

    @Test("There is no score, by construction")
    func noCompositeExists() {
        // Section 13 asked for "Research Signal: 82/100". The components are
        // the deliverable; the number is refused. If a score is ever added,
        // this test is the place the decision has to be re-argued.
        let mirror = Mirror(reflecting: ResearchProfile(components: []))
        let names = mirror.children.compactMap(\.label)
        #expect(names == ["components"])
        #expect(!names.contains { $0.lowercased().contains("score") })
    }

    @Test("A dimension with no data is unavailable, never neutral")
    func absentDataIsNotReassurance() {
        let profile = ResearchProfileBuilder.build(.init())
        #expect(profile.measured.isEmpty)
        #expect(profile.unavailable.count == ResearchDimension.allCases.count)
        // Treating an absent figure as "nothing to worry about" is the
        // fabrication Section 21 forbids, applied to judgement.
        #expect(!profile.components.contains { $0.direction == .neutral })
    }

    @Test("Momentum and relative strength classify both ways")
    func priceDimensionsAreSymmetric() {
        let up = ResearchProfileBuilder.build(.init(
            rangeReturn: periodReturn(12), relativeToMarket: relative(7)))
        #expect(up.components.first { $0.dimension == .momentum }?.direction == .supportive)
        #expect(up.components.first { $0.dimension == .relativeStrength }?.direction == .supportive)

        let down = ResearchProfileBuilder.build(.init(
            rangeReturn: periodReturn(-12), relativeToMarket: relative(-7)))
        #expect(down.components.first { $0.dimension == .momentum }?.direction == .challenging)
        #expect(down.components.first { $0.dimension == .relativeStrength }?.direction == .challenging)
    }

    @Test("A move inside the band is neither supportive nor challenging")
    func smallMovesAreNeutral() {
        let flat = ResearchProfileBuilder.build(.init(rangeReturn: periodReturn(0.2)))
        #expect(flat.components.first { $0.dimension == .momentum }?.direction == .neutral)
    }

    @Test("Revenue growth is measured year-over-year and classified both ways")
    func revenueTrendIsSymmetric() {
        var growing: [FinancialFactDTO] = []
        var shrinking: [FinancialFactDTO] = []
        for index in 0..<8 {
            let base = 1_000.0
            growing.append(fact(.revenue, index: index, value: base * (index >= 4 ? 1.2 : 1.0)))
            shrinking.append(fact(.revenue, index: index, value: base * (index >= 4 ? 0.8 : 1.0)))
        }
        #expect(ResearchProfileBuilder.build(.init(fundamentals: growing))
            .components.first { $0.dimension == .revenueTrend }?.direction == .supportive)
        #expect(ResearchProfileBuilder.build(.init(fundamentals: shrinking))
            .components.first { $0.dimension == .revenueTrend }?.direction == .challenging)
    }

    @Test("Growth from a loss is described in figures, not as a percentage")
    func lossToProfitIsNotAPercentage() throws {
        var facts: [FinancialFactDTO] = []
        for index in 0..<8 {
            facts.append(fact(.netIncome, index: index, value: index >= 4 ? 50 : -50))
        }
        let component = try #require(ResearchProfileBuilder.build(.init(fundamentals: facts))
            .components.first { $0.dimension == .earningsTrend })

        // Crossing from a loss to a profit is real news, and "+200%" is not the
        // way to say it. The direction is still recognised as supportive.
        #expect(component.direction == .supportive)
        #expect(!component.summary.contains("%"))
        #expect(component.claim?.text.contains("non-positive base") == true)
    }

    @Test("Net debt challenges and net cash supports")
    func balanceSheetIsSymmetric() {
        let indebted = [fact(.totalDebt, index: 0, value: 5_000, kind: .instant),
                        fact(.cashAndEquivalents, index: 0, value: 1_000, kind: .instant)]
        let liquid = [fact(.totalDebt, index: 0, value: 1_000, kind: .instant),
                      fact(.cashAndEquivalents, index: 0, value: 5_000, kind: .instant)]

        let a = ResearchProfileBuilder.build(.init(fundamentals: indebted))
            .components.first { $0.dimension == .balanceSheet }
        let b = ResearchProfileBuilder.build(.init(fundamentals: liquid))
            .components.first { $0.dimension == .balanceSheet }

        #expect(a?.direction == .challenging)
        #expect(a?.summary.contains("net debt") == true)
        #expect(b?.direction == .supportive)
        #expect(b?.summary.contains("net cash") == true)
        // Net debt is a constraint, not a verdict, and the claim says so.
        #expect(a?.claim?.text.contains("not by itself a problem") == true)
    }

    @Test("Analyst posture reads both directions and disclaims what it is")
    func analystPostureIsSymmetric() throws {
        let bullish = RatingSnapshotDTO(asOf: .now, strongBuy: 10, buy: 10, hold: 2,
                                        sell: 0, strongSell: 0)
        let bearish = RatingSnapshotDTO(asOf: .now, strongBuy: 0, buy: 0, hold: 2,
                                        sell: 10, strongSell: 10)
        let up = try #require(ResearchProfileBuilder.build(.init(ratings: bullish))
            .components.first { $0.dimension == .analystPosture })
        let down = ResearchProfileBuilder.build(.init(ratings: bearish))
            .components.first { $0.dimension == .analystPosture }

        #expect(up.direction == .supportive)
        #expect(down?.direction == .challenging)
        #expect(up.claim?.text.contains("not evidence about the business") == true)
    }

    @Test("Insider activity excludes scheduled plans and disclaims prediction")
    func insiderActivityIsSymmetric() throws {
        let buying = try #require(
            ResearchProfileBuilder.build(.init(insiderPurchases: 4, insiderSales: 1))
                .components.first { $0.dimension == .insiderActivity })
        let selling = ResearchProfileBuilder.build(.init(insiderPurchases: 0, insiderSales: 5))
            .components.first { $0.dimension == .insiderActivity }

        #expect(buying.direction == .supportive)
        #expect(selling?.direction == .challenging)
        #expect(buying.claim?.text.contains("does not predict returns") == true)
        #expect(buying.claim?.text.contains("Scheduled plans") == true)
    }

    @Test("Conflicting evidence is named rather than averaged away")
    func conflictIsSurfaced() {
        let profile = ResearchProfileBuilder.build(.init(
            rangeReturn: periodReturn(20),            // supports
            relativeToMarket: relative(-8),           // challenges
            fundamentals: [fact(.totalDebt, index: 0, value: 5_000, kind: .instant),
                           fact(.cashAndEquivalents, index: 0, value: 1_000, kind: .instant)]))

        #expect(profile.isConflicted)
        #expect(profile.shapeClaim.kind == .interpretation)
        #expect(profile.shapeClaim.text.contains("both ways"))
        // Counting is not weighing, and the text refuses to pretend otherwise.
        #expect(profile.shapeClaim.text.contains("not a count"))
    }

    @Test("An all-supportive profile still refuses to call it an absence of risk")
    func oneSidedProfileIsQualified() {
        let profile = ResearchProfileBuilder.build(.init(
            rangeReturn: periodReturn(20), relativeToMarket: relative(8)))
        #expect(!profile.isConflicted)
        #expect(profile.challenging.isEmpty)
        #expect(profile.shapeClaim.text.contains("not an absence of risk"))
    }
}
