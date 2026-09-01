import Testing
import Foundation
@testable import Vantage

/// Detection over reported figures.
///
/// The statistical rules are the same as the price detectors' — rank rather
/// than probability, median and MAD rather than mean and standard deviation, an
/// observation excluded from its own sample — but the cadence and the failure
/// modes are entirely different. These tests pin the ones that would produce a
/// confident wrong number: seasonality read as change, a cumulative period read
/// as a quarter, and a percentage change taken through zero.
private let calendar = Calendar(identifier: .iso8601)

private func quarterEnd(_ index: Int, startYear: Int = 2019, dayDrift: Int = 0) -> Date {
    let year = startYear + index / 4
    let month = [3, 6, 9, 12][index % 4]
    let day = [31, 30, 30, 31][index % 4]
    let base = calendar.date(from: DateComponents(year: year, month: month, day: day))!
    return base.addingTimeInterval(Double(dayDrift) * 86_400)
}

private func fact(
    _ concept: FinancialConcept,
    index: Int,
    value: Double,
    kind: FiscalPeriodKind = .quarter,
    accession: String? = nil,
    filedOffsetDays: Double = 30,
    dayDrift: Int = 0
) -> FinancialFactDTO {
    let end = quarterEnd(index, dayDrift: dayDrift)
    return FinancialFactDTO(
        concept: concept, rawTag: nil,
        periodStart: kind == .instant ? nil : end.addingTimeInterval(-90 * 86_400),
        periodEnd: end,
        fiscalYear: calendar.component(.year, from: end),
        fiscalQuarter: index % 4 + 1,
        isAnnual: false, periodKind: kind, value: value, unit: "USD",
        filedAt: end.addingTimeInterval(filedOffsetDays * 86_400),
        accessionNumber: accession ?? "acc-\(index)")
}

/// Builds revenue and a numerator that produce the given margin series.
private func marginFacts(
    _ concept: FinancialConcept,
    margins: [Double],
    revenue: Double = 1_000
) -> [FinancialFactDTO] {
    margins.enumerated().flatMap { index, margin in
        [fact(.revenue, index: index, value: revenue),
         fact(concept, index: index, value: revenue * margin / 100)]
    }
}

@Suite("Fundamental detection")
struct FundamentalDetectionTests {

    /// Twenty quarters of a stable gross margin, wiggling by a few tenths.
    private let stableMargins: [Double] = [
        40.0, 40.2, 39.8, 40.1, 40.3, 39.9, 40.0, 40.2, 40.1, 39.8,
        40.2, 40.0, 39.9, 40.3, 40.1, 40.0, 40.2, 39.9, 40.1, 40.0
    ]

    // MARK: - Margins

    @Test("A margin break against a stable history is reported")
    func marginBreakIsDetected() throws {
        var margins = stableMargins
        margins[margins.count - 1] = 35.0

        let event = try #require(
            FundamentalDetector.marginChange(facts: marginFacts(.grossProfit, margins: margins)))

        #expect(event.kind == .marginChange)
        #expect(event.headline.contains("narrowed"))
        #expect(event.unusualness >= FundamentalDetector.rankThreshold)
        #expect(event.derivation != nil, "A measured claim must carry its arithmetic")
    }

    @Test("A stable margin is not an event")
    func stableMarginIsSilent() {
        #expect(FundamentalDetector.marginChange(
            facts: marginFacts(.grossProfit, margins: stableMargins)) == nil)
    }

    @Test("A seasonal margin swing is not reported as a change")
    func seasonalityIsNotChange() {
        // Q4 runs 5pp below the other quarters every single year. Compared with
        // the preceding quarter this fires every December and calls the
        // calendar news; compared with the same quarter a year earlier — which
        // is what the detector does — there is nothing to report.
        let margins = (0..<20).map { index -> Double in
            let seasonal = index % 4 == 3 ? 35.0 : 40.0
            return seasonal + Double(index % 3) * 0.1
        }
        #expect(FundamentalDetector.marginChange(
            facts: marginFacts(.grossProfit, margins: margins)) == nil)
    }

    @Test("A move smaller than the attention threshold is not reported")
    func smallMoveIsSuppressed() {
        var margins = stableMargins
        margins[margins.count - 1] = 39.0   // ~1pp against a ~40pp history
        #expect(FundamentalDetector.marginChange(
            facts: marginFacts(.grossProfit, margins: margins),
            minimumChangePoints: 1.5) == nil)
    }

    @Test("Too little history means silence, not a weak verdict")
    func shortHistoryIsSilent() {
        let margins: [Double] = [40, 40.2, 39.8, 40.1, 35.0]
        #expect(FundamentalDetector.marginChange(
            facts: marginFacts(.grossProfit, margins: margins)) == nil)
    }

    @Test("The event is dated to when the figure was filed, not to the period")
    func eventIsDatedToFiling() throws {
        var margins = stableMargins
        margins[margins.count - 1] = 35.0

        let event = try #require(
            FundamentalDetector.marginChange(facts: marginFacts(.grossProfit, margins: margins)))

        // A June quarter disclosed in August is news in August. Dating it to
        // June would file it behind price events the user has already seen.
        let period = quarterEnd(margins.count - 1)
        #expect(event.occurredAt > period)
        #expect(event.occurredAt == period.addingTimeInterval(30 * 86_400))
    }

    // MARK: - Revenue growth

    @Test("Growth is ranked against the company's own growth history")
    func revenueGrowthSlowdown() throws {
        // Four base quarters, then five years compounding at ~12% — until the
        // last quarter, which grows 1%.
        var values: [Double] = [100, 110, 120, 130]
        for index in 4..<24 {
            let rate = index == 23 ? 1.01 : 1.12
            values.append(values[index - 4] * rate)
        }
        let facts = values.enumerated().map { fact(.revenue, index: $0.offset, value: $0.element) }

        let event = try #require(FundamentalDetector.revenueGrowthChange(facts: facts))
        #expect(event.kind == .revenueGrowthChange)
        #expect(event.headline.contains("slowed"))
        #expect(event.unusualness >= FundamentalDetector.rankThreshold)
    }

    @Test("Steady growth is not an event")
    func steadyGrowthIsSilent() {
        var values: [Double] = [100, 110, 120, 130]
        for index in 4..<24 { values.append(values[index - 4] * 1.12) }
        let facts = values.enumerated().map { fact(.revenue, index: $0.offset, value: $0.element) }
        #expect(FundamentalDetector.revenueGrowthChange(facts: facts) == nil)
    }

    // MARK: - Free cash flow

    @Test("Free cash flow crossing zero produces finite figures, not a sign flip")
    func freeCashFlowThroughZero() {
        // A percentage change from -10 to +10 is either -200% or +200%
        // depending on which sign convention you pick, and neither describes
        // what happened. Measuring the margin in percentage points does.
        let facts = (0..<12).flatMap { index -> [FinancialFactDTO] in
            [fact(.revenue, index: index, value: 1_000),
             fact(.operatingCashFlow, index: index, value: Double(index) * 20 - 100),
             fact(.capitalExpenditures, index: index, value: 20)]
        }
        let series = FundamentalDetector.freeCashFlowMarginSeries(facts: facts)
        #expect(series.count == 12)
        #expect(series.allSatisfy { $0.value.isFinite })
        // -100 OCF less 20 capex, over 1000 revenue.
        #expect(series.first?.value == -12)
    }

    @Test("Capital expenditure is subtracted whichever sign the issuer filed it with")
    func capexSignIsNormalised() {
        let positive = FundamentalDetector.freeCashFlowSeries(facts: [
            fact(.operatingCashFlow, index: 0, value: 500),
            fact(.capitalExpenditures, index: 0, value: 100)
        ])
        let negative = FundamentalDetector.freeCashFlowSeries(facts: [
            fact(.operatingCashFlow, index: 0, value: 500),
            fact(.capitalExpenditures, index: 0, value: -100)
        ])
        #expect(positive.first?.value == 400)
        #expect(negative.first?.value == 400, "A negative filing must not add capex to cash flow")
    }

    // MARK: - Debt

    @Test("A step change in borrowing is reported")
    func debtStepIsDetected() throws {
        var levels: [Double] = []
        for index in 0..<20 { levels.append(1_000 + Double(index % 3) * 5) }
        levels[levels.count - 1] = 1_600

        let facts = levels.enumerated().map {
            fact(.totalDebt, index: $0.offset, value: $0.element, kind: .instant)
        }
        let event = try #require(FundamentalDetector.debtChange(facts: facts))
        #expect(event.kind == .debtChange)
        #expect(event.headline.contains("rose"))
    }

    @Test("Debt is read from balance-sheet instants, not from duration rows")
    func debtUsesInstants() {
        let facts = (0..<20).map {
            fact(.totalDebt, index: $0, value: 1_000, kind: .quarter)
        }
        // Filed with a duration, these are not balance-sheet figures and must
        // not be treated as though they were.
        #expect(FundamentalDetector.instant(facts, .totalDebt).isEmpty)
    }

    // MARK: - Restatements

    @Test("A period reported twice with different figures is surfaced")
    func restatementIsDetected() throws {
        let period = quarterEnd(4)
        func version(_ value: Double, accession: String, filedDays: Double) -> FinancialFactDTO {
            FinancialFactDTO(
                concept: .revenue, rawTag: nil,
                periodStart: period.addingTimeInterval(-90 * 86_400), periodEnd: period,
                fiscalYear: 2020, fiscalQuarter: 1, isAnnual: false, periodKind: .quarter,
                value: value, unit: "USD",
                filedAt: period.addingTimeInterval(filedDays * 86_400),
                accessionNumber: accession)
        }

        let event = try #require(FundamentalDetector.restatements(
            revisions: [version(1_000, accession: "original", filedDays: 30),
                        version(1_100, accession: "amended", filedDays: 200)],
            concept: .revenue))

        #expect(event.kind == .fundamentalShift)
        #expect(event.headline.contains("restated"))
        #expect(event.occurredAt == period.addingTimeInterval(200 * 86_400),
                "Dated to the amendment, which is when it became knowable")
        // Reported, never characterised: an issuer restates for reasons ranging
        // from adopting a standard to correcting an error.
        #expect(event.unusualness == 0)
    }

    @Test("A quarter and a fiscal year ending the same day are not a restatement of each other")
    func annualAndQuarterlyPeriodsAreNotConflated() {
        // Found by looking at a rendered screen, not by a test: Apple's Q4
        // FY2020 revenue of $64.7B was being paired against its FY2020 revenue
        // of $275B and reported as a +324% restatement. Both periods end on
        // 26 September 2020, and the grouping key was the date alone.
        let period = calendar.date(from: DateComponents(year: 2020, month: 9, day: 26))!
        func version(_ value: Double, kind: FiscalPeriodKind, accession: String) -> FinancialFactDTO {
            FinancialFactDTO(
                concept: .revenue, rawTag: nil,
                periodStart: period.addingTimeInterval(kind == .annual ? -365 * 86_400
                                                                       : -90 * 86_400),
                periodEnd: period, fiscalYear: 2020, fiscalQuarter: kind == .annual ? nil : 4,
                isAnnual: kind == .annual, periodKind: kind, value: value, unit: "USD",
                filedAt: period.addingTimeInterval(30 * 86_400), accessionNumber: accession)
        }

        #expect(FundamentalDetector.restatements(
            revisions: [version(64_700, kind: .quarter, accession: "10-K-q4"),
                        version(274_500, kind: .annual, accession: "10-K-fy")],
            concept: .revenue) == nil)
    }

    @Test("A single filing of a period is not a restatement")
    func singleFilingIsNotRestatement() {
        let period = quarterEnd(4)
        #expect(FundamentalDetector.restatements(revisions: [FinancialFactDTO(
            concept: .revenue, rawTag: nil, periodStart: nil, periodEnd: period,
            fiscalYear: 2020, fiscalQuarter: 1, isAnnual: false, periodKind: .quarter,
            value: 1_000, unit: "USD", filedAt: period, accessionNumber: "only")],
            concept: .revenue) == nil)
    }

    @Test("A rounding-level revision is not worth reporting")
    func trivialRevisionIsIgnored() {
        let period = quarterEnd(4)
        func version(_ value: Double, accession: String) -> FinancialFactDTO {
            FinancialFactDTO(
                concept: .revenue, rawTag: nil, periodStart: nil, periodEnd: period,
                fiscalYear: 2020, fiscalQuarter: 1, isAnnual: false, periodKind: .quarter,
                value: value, unit: "USD", filedAt: period, accessionNumber: accession)
        }
        #expect(FundamentalDetector.restatements(
            revisions: [version(1_000, accession: "a"), version(1_001, accession: "b")],
            concept: .revenue, minimumChangePercent: 1) == nil)
    }

    // MARK: - Period handling

    @Test("Cumulative periods are excluded from the quarterly series")
    func cumulativePeriodsExcluded() {
        let facts = [
            fact(.revenue, index: 0, value: 100),
            fact(.revenue, index: 1, value: 110),
            // The nine-month running total an issuer files under the same tag.
            // Read as a quarter it makes Q3 look roughly three times Q2.
            fact(.revenue, index: 2, value: 330, kind: .nineMonth)
        ]
        let series = FundamentalDetector.quarterly(facts, .revenue)
        #expect(series.map(\.value) == [100, 110])
    }

    @Test("Year-over-year pairing survives a drifting fiscal calendar")
    func yearOverYearToleratesDrift() {
        // A 52/53-week calendar moves the closing date by a few days a year.
        let series = (0..<8).map { index in
            (period: quarterEnd(index, dayDrift: index), value: Double(index))
        }
        let paired = FundamentalDetector.yearOverYear(series)
        #expect(paired.count == 4, "Every quarter from the second year on has a match")
        #expect(paired.first?.prior == 0)
    }

    @Test("A period with no counterpart a year earlier is dropped, not paired wrongly")
    func yearOverYearRefusesDistantMatches() {
        // Two quarters two years apart. Pairing them would compare periods the
        // detector would then describe as year-over-year.
        let series = [
            (period: quarterEnd(0), value: 100.0),
            (period: quarterEnd(8), value: 200.0)
        ]
        #expect(FundamentalDetector.yearOverYear(series).isEmpty)
    }

    @Test("A margin needs both legs from the same period")
    func marginNeedsBothLegs() {
        let facts = [
            fact(.revenue, index: 0, value: 1_000),
            fact(.grossProfit, index: 1, value: 400)
        ]
        #expect(FundamentalDetector.marginSeries(facts: facts, numerator: .grossProfit).isEmpty,
                "Dividing a Q2 profit by Q1 revenue would produce a plausible wrong margin")
    }

    @Test("A perfectly flat history yields no dispersion and so no verdict")
    func flatHistoryIsSilent() {
        // Deliberate, and inherited from the price detectors: with zero
        // dispersion every deviation is infinite, which would render an
        // absence of movement as an extraordinary event. Real filings always
        // wobble; a series that does not is synthetic.
        let margins = Array(repeating: 40.0, count: 19) + [35.0]
        #expect(FundamentalDetector.marginChange(
            facts: marginFacts(.grossProfit, margins: margins)) == nil)
    }
}
