import Testing
import Foundation
@testable import Vantage

/// Joining a filing to the figures it reported.
///
/// The join is the whole feature: every XBRL fact carries the accession number
/// of the filing that reported it, and the submissions feed supplies the same
/// accession in the same dashed format. If those formats ever diverge this
/// returns nothing at all rather than failing loudly, so the fixture-backed
/// test below is the one that matters most.
/// UTC, because that is how `SECProvider.dayFormatter` parses EDGAR's dates.
/// A local-timezone calendar here builds a date four hours off the fixture's
/// and makes an exact comparison fail for reasons that have nothing to do with
/// the code under test.
private let calendar: Calendar = {
    var calendar = Calendar(identifier: .iso8601)
    calendar.timeZone = TimeZone(identifier: "UTC")!
    return calendar
}()

private func date(_ year: Int, _ month: Int, _ day: Int) -> Date {
    calendar.date(from: DateComponents(year: year, month: month, day: day))!
}

private func filing(
    _ form: String = "10-Q",
    accession: String = "acc-current",
    filed: Date = date(2025, 8, 1),
    period: Date? = nil
) -> FilingDTO {
    FilingDTO(accessionNumber: accession, formType: form, filedAt: filed,
              periodOfReport: period, primaryDocumentURL: nil, filingIndexURL: nil)
}

private func fact(
    _ concept: FinancialConcept,
    value: Double,
    periodEnd: Date,
    kind: FiscalPeriodKind = .quarter,
    accession: String
) -> FinancialFactDTO {
    FinancialFactDTO(
        concept: concept, rawTag: nil,
        periodStart: kind == .instant ? nil
            : periodEnd.addingTimeInterval(kind == .annual ? -365 * 86_400 : -90 * 86_400),
        periodEnd: periodEnd,
        fiscalYear: calendar.component(.year, from: periodEnd),
        fiscalQuarter: kind == .annual ? nil : 2,
        isAnnual: kind == .annual, periodKind: kind, value: value, unit: "USD",
        filedAt: periodEnd.addingTimeInterval(30 * 86_400), accessionNumber: accession)
}

@Suite("Filing analysis")
struct FilingAnalysisTests {

    // MARK: - The join, against a real payload

    private func appleFacts() throws -> [FinancialFactDTO] {
        let response = try Fixture.decode(CompanyFactsResponse.self,
                                          from: "sec_companyfacts_AAPL")
        let concepts: [FinancialConcept] = [
            .revenue, .netIncome, .grossProfit, .operatingIncome,
            .operatingCashFlow, .capitalExpenditures,
            .cashAndEquivalents, .totalDebt, .stockholdersEquity
        ]
        return concepts.flatMap {
            SECFundamentalsProvider.extract(concept: $0, from: response, since: nil)
        }
    }

    @Test("A real accession resolves to the figures that filing reported")
    func realAccessionJoins() throws {
        // Apple's FY2025 10-K. It reports FY2025 and restates FY2024 alongside
        // it, so this also pins that the period being reported is the latest of
        // the filing's rows rather than an arbitrary one.
        let result = try #require(FilingAnalysis.analyse(
            filing: filing("10-K", accession: "0000320193-25-000079",
                           filed: date(2025, 10, 31)),
            facts: try appleFacts()))

        #expect(!result.isAwaitingFacts)
        #expect(result.periodEnd == date(2025, 9, 27))

        let revenue = try #require(result.lines.first { $0.label == "Revenue" })
        #expect(revenue.formatted.contains("416"), "FY2025 revenue, $416.161B")
        // Against FY2024's $391.035B — a +6.4% year.
        #expect(revenue.comparison?.contains("6.4") == true)
        #expect(revenue.claim.kind == .calculation)
        #expect(revenue.claim.derivation != nil)
    }

    @Test("A 10-K is read from the annual series, not the quarterly one")
    func annualFormsUseAnnualFigures() throws {
        // Read as quarterly this returns nothing at all: a 10-K's revenue fact
        // has an annual duration and would not appear in the quarterly series.
        let result = try #require(FilingAnalysis.analyse(
            filing: filing("10-K", accession: "0000320193-25-000079",
                           filed: date(2025, 10, 31)),
            facts: try appleFacts()))
        #expect(result.lines.contains { $0.label == "Revenue" })
        #expect(result.isAnnual)
        #expect(result.periodLabel == "year")
    }

    // MARK: - Scope

    @Test("An 8-K is not analysed, because it carries no tagged financials")
    func eightKIsNotAnalysed() {
        #expect(FilingAnalysis.analyse(filing: filing("8-K"), facts: []) == nil)
        #expect(FilingAnalysis.analyse(filing: filing("4"), facts: []) == nil)
    }

    @Test("A filing whose figures are not published yet says so")
    func awaitingFactsIsDistinctFromNothingChanged() throws {
        // companyfacts lags the submissions feed by hours to days. Rendering
        // that gap as "nothing changed" would be a false statement about a
        // real document.
        let result = try #require(FilingAnalysis.analyse(
            filing: filing("10-Q", accession: "not-yet-published"),
            facts: [fact(.revenue, value: 100, periodEnd: date(2025, 6, 30),
                         accession: "some-older-filing")]))
        #expect(result.isAwaitingFacts)
        #expect(result.lines.isEmpty)
    }

    // MARK: - Comparison arithmetic

    @Test("A figure with a year-earlier counterpart is a calculation")
    func comparedFigureIsACalculation() throws {
        let facts = [
            fact(.revenue, value: 1_000, periodEnd: date(2024, 6, 30), accession: "prior"),
            fact(.revenue, value: 1_200, periodEnd: date(2025, 6, 30), accession: "acc-current")
        ]
        let result = try #require(FilingAnalysis.analyse(filing: filing(), facts: facts))
        let revenue = try #require(result.lines.first { $0.label == "Revenue" })

        #expect(revenue.claim.kind == .calculation)
        #expect(revenue.comparison?.contains("20.0") == true, "1,000 → 1,200 is +20%")
        #expect(revenue.claim.derivation?.inputs.count == 2)
    }

    @Test("A figure with nothing to compare against is a fact, not a calculation")
    func uncomparedFigureIsAFact() throws {
        let facts = [fact(.revenue, value: 1_200, periodEnd: date(2025, 6, 30),
                          accession: "acc-current")]
        let result = try #require(FilingAnalysis.analyse(filing: filing(), facts: facts))
        let revenue = try #require(result.lines.first { $0.label == "Revenue" })

        // A company's first year on file has no prior year. Badging that the
        // same as a compared figure would overstate what the app knows.
        #expect(revenue.claim.kind == .fact)
        #expect(revenue.comparison == nil)
        #expect(revenue.claim.derivation == nil)
    }

    @Test("A percentage change off a non-positive base is refused")
    func percentChangeNeedsAPositiveBase() throws {
        let facts = [
            fact(.revenue, value: 0, periodEnd: date(2024, 6, 30), accession: "prior"),
            fact(.revenue, value: 500, periodEnd: date(2025, 6, 30), accession: "acc-current")
        ]
        let result = try #require(FilingAnalysis.analyse(filing: filing(), facts: facts))
        let revenue = try #require(result.lines.first { $0.label == "Revenue" })

        // Division by zero would render as an infinite growth rate; the figure
        // is still reported, just without a comparison it cannot support.
        #expect(revenue.comparison == nil)
        #expect(revenue.claim.kind == .fact)
    }

    @Test("Free cash flow is compared in currency, never as a percentage")
    func cashFlowAvoidsPercentThroughZero() throws {
        let facts = [
            fact(.operatingCashFlow, value: -50, periodEnd: date(2024, 6, 30), accession: "prior"),
            fact(.capitalExpenditures, value: 50, periodEnd: date(2024, 6, 30), accession: "prior"),
            fact(.operatingCashFlow, value: 150, periodEnd: date(2025, 6, 30), accession: "acc-current"),
            fact(.capitalExpenditures, value: 50, periodEnd: date(2025, 6, 30), accession: "acc-current")
        ]
        let result = try #require(FilingAnalysis.analyse(filing: filing(), facts: facts))
        let fcf = try #require(result.lines.first { $0.label == "Free cash flow" })

        // -$100 to +$100. A percentage change here is either -200% or +200%
        // depending on the sign convention, and neither describes what happened.
        let comparison = try #require(fcf.comparison)
        #expect(comparison.contains("$"))
        #expect(!comparison.contains("%"))
    }

    @Test("Margins are compared in percentage points")
    func marginsUsePercentagePoints() throws {
        let facts = [
            fact(.revenue, value: 1_000, periodEnd: date(2024, 6, 30), accession: "prior"),
            fact(.grossProfit, value: 400, periodEnd: date(2024, 6, 30), accession: "prior"),
            fact(.revenue, value: 1_000, periodEnd: date(2025, 6, 30), accession: "acc-current"),
            fact(.grossProfit, value: 450, periodEnd: date(2025, 6, 30), accession: "acc-current")
        ]
        let result = try #require(FilingAnalysis.analyse(filing: filing(), facts: facts))
        let margin = try #require(result.lines.first { $0.label == "Gross margin" })

        #expect(margin.formatted.contains("45"))
        #expect(margin.comparison?.contains("pp") == true, "40% to 45% is 5pp, not 5%")
    }

    @Test("Balance-sheet figures are read as instants regardless of the form")
    func balanceSheetFiguresAreInstants() throws {
        let facts = [
            fact(.totalDebt, value: 900, periodEnd: date(2024, 6, 30),
                 kind: .instant, accession: "prior"),
            fact(.totalDebt, value: 1_100, periodEnd: date(2025, 6, 30),
                 kind: .instant, accession: "acc-current")
        ]
        let result = try #require(FilingAnalysis.analyse(filing: filing(), facts: facts))
        let debt = try #require(result.lines.first { $0.label == "Total debt" })
        #expect(debt.comparison?.contains("$") == true)
    }

    @Test("Figures from another filing are not attributed to this one")
    func otherFilingsFiguresAreExcluded() throws {
        let facts = [
            fact(.revenue, value: 999, periodEnd: date(2025, 6, 30), accession: "someone-else")
        ]
        // The accession is the whole basis of the join. A filter that matched
        // loosely would credit this filing with a figure it never reported.
        let result = try #require(FilingAnalysis.analyse(
            filing: filing("10-Q", accession: "acc-current"), facts: facts))
        #expect(result.isAwaitingFacts)
    }
}
