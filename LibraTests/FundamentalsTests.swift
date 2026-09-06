import Testing
import Foundation
@testable import Libra

/// Extraction from a real `companyfacts` payload, captured from EDGAR through
/// the app itself so no contact address passed through a shell command.
@Suite("XBRL fundamentals extraction")
struct FundamentalsTests {
    private func facts() throws -> CompanyFactsResponse {
        try Fixture.decode(CompanyFactsResponse.self, from: "sec_companyfacts_AAPL")
    }

    @Test("A real companyfacts payload decodes")
    func decodesRealPayload() throws {
        let response = try facts()
        #expect(response.entityName == "Apple Inc.")
        #expect(response.cik == 320193)
        #expect(response.facts["us-gaap"]?.isEmpty == false)
    }

    @Test("Revenue resolves through the candidate tag list")
    func revenueResolves() throws {
        let extracted = SECFundamentalsProvider.extract(
            concept: .revenue, from: try facts(), since: nil
        )
        #expect(!extracted.isEmpty)
        // Apple reports revenue in the hundreds of billions annually; a value
        // outside this range means the wrong tag or unit was chosen.
        let annual = extracted.filter(\.isAnnual)
        if let latest = annual.last {
            #expect(latest.value > 100_000_000_000)
            #expect(latest.unit == "USD")
        }
    }

    @Test("EPS is read from USD/shares, not USD")
    func epsUsesPerShareUnit() throws {
        let extracted = SECFundamentalsProvider.extract(
            concept: .earningsPerShareDiluted, from: try facts(), since: nil
        )
        #expect(!extracted.isEmpty)
        #expect(extracted.allSatisfy { $0.unit == "USD/shares" })
        // A per-share figure in single digits; picking USD would give billions.
        #expect(extracted.allSatisfy { abs($0.value) < 100 })
    }

    @Test("Share counts are read from the shares unit")
    func sharesUseShareUnit() throws {
        let extracted = SECFundamentalsProvider.extract(
            concept: .sharesOutstandingDiluted, from: try facts(), since: nil
        )
        if !extracted.isEmpty {
            #expect(extracted.allSatisfy { $0.unit == "shares" })
            #expect(extracted.allSatisfy { $0.value > 1_000_000_000 })
        }
    }

    @Test("Every extracted fact carries the tag it came from, for traceability")
    func factsCarryTheirTag() throws {
        let extracted = SECFundamentalsProvider.extract(
            concept: .netIncome, from: try facts(), since: nil
        )
        #expect(!extracted.isEmpty)
        #expect(extracted.allSatisfy { $0.rawTag?.hasPrefix("us-gaap:") == true })
        #expect(extracted.allSatisfy { $0.accessionNumber != nil })
    }

    @Test("Periods are classified by duration, not by the fp label alone")
    func periodsClassifiedByDuration() throws {
        let extracted = SECFundamentalsProvider.extract(
            concept: .revenue, from: try facts(), since: nil
        )
        for fact in extracted {
            guard let start = fact.periodStart else {
                #expect(fact.periodKind == .instant)
                continue
            }
            let days = fact.periodEnd.timeIntervalSince(start) / 86_400
            switch fact.periodKind {
            case .quarter: #expect(days < 140)
            case .annual: #expect(days > 310)
            default: Issue.record("Cumulative period \(fact.periodKind) should be filtered out")
            }
        }
    }

    @Test("Cumulative year-to-date rows are excluded from extraction")
    func cumulativeRowsExcluded() throws {
        // A Q3 10-Q files both a three-month and a nine-month revenue figure
        // under one tag. Treating the nine-month total as a quarter would make
        // Q3 look roughly three times Q2.
        let extracted = SECFundamentalsProvider.extract(
            concept: .revenue, from: try facts(), since: nil
        )
        // Bound outside the macro: `contains(where:)` is `rethrows`, and the
        // #expect expansion cannot infer that this closure does not throw.
        let allDiscrete = extracted.allSatisfy(\.periodKind.isDiscrete)
        let hasNineMonth = extracted.contains { $0.periodKind == .nineMonth }
        let hasHalfYear = extracted.contains { $0.periodKind == .halfYear }

        #expect(!extracted.isEmpty)
        #expect(allDiscrete)
        #expect(!hasNineMonth)
        #expect(!hasHalfYear)
    }

    @Test("Quarterly revenue stays within a plausible band of its neighbours")
    func quarterlyRevenueIsNotInflated() throws {
        let quarters = SECFundamentalsProvider.extract(
            concept: .revenue, from: try facts(), since: nil
        )
        .filter { $0.periodKind == .quarter }
        .sorted { $0.periodEnd < $1.periodEnd }

        guard quarters.count >= 4 else { return }
        // A cumulative row leaking in shows up as a quarter several times its
        // neighbour. Apple's quarters vary seasonally but never by 2.5x.
        for (previous, current) in zip(quarters, quarters.dropFirst()) {
            let ratio = current.value / previous.value
            #expect(ratio < 2.5 && ratio > 0.4,
                    "Quarter-over-quarter ratio \(ratio) suggests a cumulative row leaked in")
        }
    }

    @Test("Duration windows tolerate 52/53-week fiscal calendars")
    func classificationWindows() {
        #expect(FiscalPeriodKind.classify(days: 91) == .quarter)
        #expect(FiscalPeriodKind.classify(days: 98) == .quarter)      // 14-week quarter
        #expect(FiscalPeriodKind.classify(days: 182) == .halfYear)
        #expect(FiscalPeriodKind.classify(days: 272) == .nineMonth)   // the row that caught this
        #expect(FiscalPeriodKind.classify(days: 364) == .annual)
        #expect(FiscalPeriodKind.classify(days: 371) == .annual)      // 53-week year
        #expect(FiscalPeriodKind.classify(days: nil) == .instant)
    }

    @Test("Restatements collapse to the most recently filed figure per period")
    func restatementsDeduplicate() {
        let older = FinancialFactDTO(
            concept: .revenue, rawTag: "us-gaap:Revenues", periodStart: nil,
            periodEnd: Date(timeIntervalSince1970: 1_700_000_000),
            fiscalYear: 2025, fiscalQuarter: 2, isAnnual: false, periodKind: .quarter,
            value: 100, unit: "USD",
            filedAt: Date(timeIntervalSince1970: 1_700_100_000), accessionNumber: "old")
        let newer = FinancialFactDTO(
            concept: .revenue, rawTag: "us-gaap:Revenues", periodStart: nil,
            periodEnd: Date(timeIntervalSince1970: 1_700_000_000),
            fiscalYear: 2025, fiscalQuarter: 2, isAnnual: false, periodKind: .quarter,
            value: 110, unit: "USD",
            filedAt: Date(timeIntervalSince1970: 1_800_000_000), accessionNumber: "new")

        let result = SECFundamentalsProvider.deduplicated([older, newer])
        #expect(result.count == 1)
        #expect(result.first?.value == 110, "The corrected figure must win")
        #expect(result.first?.accessionNumber == "new")
    }

    @Test("Distinct periods are never collapsed into one another")
    func distinctPeriodsSurvive() {
        let q1 = FinancialFactDTO(
            concept: .revenue, rawTag: nil, periodStart: nil,
            periodEnd: Date(timeIntervalSince1970: 1_700_000_000),
            fiscalYear: 2025, fiscalQuarter: 1, isAnnual: false, periodKind: .quarter,
            value: 100, unit: "USD", filedAt: nil, accessionNumber: nil)
        let q2 = FinancialFactDTO(
            concept: .revenue, rawTag: nil, periodStart: nil,
            periodEnd: Date(timeIntervalSince1970: 1_708_000_000),
            fiscalYear: 2025, fiscalQuarter: 2, isAnnual: false, periodKind: .quarter,
            value: 120, unit: "USD", filedAt: nil, accessionNumber: nil)
        #expect(SECFundamentalsProvider.deduplicated([q1, q2]).count == 2)
    }

    @Test("A concept the issuer does not report yields nothing, never a zero")
    func unreportedConceptIsEmpty() throws {
        let response = try facts()
        for concept in FinancialConcept.allCases {
            let extracted = SECFundamentalsProvider.extract(
                concept: concept, from: response, since: nil
            )
            // The guarantee is that nothing is synthesised: a concept either
            // produces facts traceable to a real tag, or produces none at all.
            guard !extracted.isEmpty else { continue }
            #expect(extracted.allSatisfy { $0.rawTag != nil })
            #expect(extracted.allSatisfy { $0.accessionNumber != nil })
        }
    }

    @Test("The since filter excludes older periods")
    func sinceFilterApplies() throws {
        let cutoff = Date(timeIntervalSince1970: 1_750_000_000)
        let extracted = SECFundamentalsProvider.extract(
            concept: .revenue, from: try facts(), since: cutoff
        )
        #expect(extracted.allSatisfy { $0.periodEnd >= cutoff })
    }

    @Test("Unit preference maps each concept to the right XBRL unit key")
    func unitPreference() {
        #expect(FinancialConcept.earningsPerShareDiluted
            .preferredUnit(from: ["USD", "USD/shares"]) == "USD/shares")
        #expect(FinancialConcept.sharesOutstandingDiluted
            .preferredUnit(from: ["shares"]) == "shares")
        #expect(FinancialConcept.revenue.preferredUnit(from: ["USD"]) == "USD")
    }

    @Test("Every mapped concept has at least one candidate tag")
    func allConceptsHaveTags() {
        for concept in FinancialConcept.allCases {
            #expect(!concept.candidateTags.isEmpty, "\(concept) has no tag mapping")
        }
    }
}


/// Tag migration. Issuers change the XBRL tag they report a concept under,
/// and the naive "first candidate tag that returns anything" rule silently
/// truncates the history at the changeover — a chart that simply starts late,
/// which looks like a data limitation rather than a bug.
@Suite("Candidate tag merging")
struct CandidateTagMergingTests {

    private func payload() -> Data {
        // Old years under `Revenues`, recent years under the newer tag —
        // the shape NVIDIA's own filings take.
        let json = """
        {
          "cik": 1045810,
          "entityName": "NVIDIA CORP",
          "facts": {
            "us-gaap": {
              "RevenueFromContractWithCustomerExcludingAssessedTax": {
                "units": {
                  "USD": [
                    {"start": "2024-01-29", "end": "2025-01-26", "val": 130497000000,
                     "fy": 2025, "fp": "FY", "form": "10-K", "filed": "2025-02-26",
                     "accn": "0001045810-25-000023"},
                    {"start": "2025-01-27", "end": "2026-01-25", "val": 213000000000,
                     "fy": 2026, "fp": "FY", "form": "10-K", "filed": "2026-02-25",
                     "accn": "0001045810-26-000010"}
                  ]
                }
              },
              "Revenues": {
                "units": {
                  "USD": [
                    {"start": "2021-02-01", "end": "2022-01-30", "val": 26914000000,
                     "fy": 2022, "fp": "FY", "form": "10-K", "filed": "2022-03-18",
                     "accn": "0001045810-22-000036"},
                    {"start": "2022-01-31", "end": "2023-01-29", "val": 26974000000,
                     "fy": 2023, "fp": "FY", "form": "10-K", "filed": "2023-02-24",
                     "accn": "0001045810-23-000017"}
                  ]
                }
              }
            }
          }
        }
        """
        return Data(json.utf8)
    }

    @Test("History spanning a tag change is complete, not truncated")
    func tagMigrationKeepsEveryYear() throws {
        let response = try JSONDecoder().decode(CompanyFactsResponse.self, from: payload())
        let facts = SECFundamentalsProvider.extract(concept: .revenue, from: response, since: nil)

        // First-tag-wins returned only the two years under the newer tag.
        #expect(facts.count == 4)
        let years = facts.map { Calendar.current.component(.year, from: $0.periodEnd) }
        #expect(Set(years) == [2022, 2023, 2025, 2026])
    }

    @Test("Results are ordered oldest first regardless of which tag supplied them")
    func mergedFactsAreOrdered() throws {
        let response = try JSONDecoder().decode(CompanyFactsResponse.self, from: payload())
        let facts = SECFundamentalsProvider.extract(concept: .revenue, from: response, since: nil)
        let ends = facts.map(\.periodEnd)
        #expect(ends == ends.sorted(), "A merged series must not interleave by tag")
    }

    @Test("A period reported under two tags contributes one figure")
    func overlappingPeriodsAreNotDoubled() throws {
        let json = """
        {
          "cik": 1, "entityName": "T",
          "facts": { "us-gaap": {
            "RevenueFromContractWithCustomerExcludingAssessedTax": { "units": { "USD": [
              {"start": "2024-01-01", "end": "2024-12-31", "val": 100,
               "fy": 2024, "fp": "FY", "form": "10-K", "filed": "2025-01-15", "accn": "a"}
            ]}},
            "Revenues": { "units": { "USD": [
              {"start": "2024-01-01", "end": "2024-12-31", "val": 115,
               "fy": 2024, "fp": "FY", "form": "10-K", "filed": "2025-01-15", "accn": "b"}
            ]}}
          }}
        }
        """
        let response = try JSONDecoder().decode(CompanyFactsResponse.self, from: Data(json.utf8))
        let facts = SECFundamentalsProvider.extract(concept: .revenue, from: response, since: nil)

        // Concatenating would report 215 of revenue for one year.
        #expect(facts.count == 1)
        // The higher-priority tag wins: the two tags differ by assessed tax.
        #expect(facts.first?.value == 100)
    }
}
