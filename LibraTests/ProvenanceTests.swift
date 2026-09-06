import Testing
import Foundation
@testable import Libra

@Suite("Claim provenance")
struct ProvenanceTests {
    @Test("Claim kinds order from most to least certain")
    func kindOrdering() {
        #expect(ClaimKind.fact < ClaimKind.calculation)
        #expect(ClaimKind.calculation < ClaimKind.interpretation)
        #expect(ClaimKind.interpretation < ClaimKind.hypothesis)
    }

    @Test("A fact with a source reference is traceable")
    func factWithSourceIsTraceable() {
        let claim = Claim(
            kind: .fact,
            text: "Revenue was $30.0B in Q2 FY2026.",
            sources: [.init(provider: .sec, detail: "10-Q filed 2026-07-28",
                            url: URL(string: "https://www.sec.gov/"), retrievedAt: .now)]
        )
        #expect(claim.isTraceable)
    }

    @Test("A calculation is traceable through its derivation alone")
    func calculationTraceableViaDerivation() {
        let claim = Claim(
            kind: .calculation,
            text: "Revenue grew 14.2% year-over-year.",
            derivation: .init(
                formula: "(revenue - revenuePriorYear) / revenuePriorYear",
                inputs: [
                    .init(name: "revenue", value: "30,040,000,000", source: nil),
                    .init(name: "revenuePriorYear", value: "26,300,000,000", source: nil)
                ],
                result: "14.2%"
            )
        )
        #expect(claim.isTraceable)
    }

    @Test("A bare claim with no source and no derivation is not traceable")
    func untracedClaimIsFlagged() {
        let claim = Claim(kind: .hypothesis, text: "This may indicate improving demand.")
        #expect(!claim.isTraceable, "Untraceable claims must be detectable before display")
    }

    @Test("SEC is the only provider marked as a primary source")
    func onlySECIsPrimary() {
        let primaries = DataProviderID.allCases.filter(\.isPrimarySource)
        #expect(primaries == [.sec])
    }
}

@Suite("Insider transaction classification")
struct InsiderClassificationTests {
    private func transaction(code: String, plan: Bool = false) -> InsiderTransaction {
        InsiderTransaction(
            accessionNumber: "x", insiderName: "Test",
            transactionDate: .now, filedAt: .now,
            transactionCode: code, isUnderTradingPlan: plan
        )
    }

    @Test("Open-market purchases and sales are distinguished from grants")
    func codesMapToNature() {
        #expect(transaction(code: "P").nature == .openMarketPurchase)
        #expect(transaction(code: "S").nature == .openMarketSale)
        #expect(transaction(code: "A").nature == .grant)
        #expect(transaction(code: "M").nature == .optionExercise)
        #expect(transaction(code: "F").nature == .taxWithholding)
    }

    @Test("A 10b5-1 plan sale is not treated as a discretionary sale")
    func planSalesAreNotDiscretionary() {
        let planned = transaction(code: "S", plan: true)
        #expect(planned.nature == .scheduledPlan)
        #expect(!planned.nature.isDiscretionary,
                "Scheduled sales carry no signal about current sentiment")
    }

    @Test("Grants and tax withholding are not discretionary decisions")
    func nonDiscretionaryNatures() {
        #expect(!InsiderTransactionNature.grant.isDiscretionary)
        #expect(!InsiderTransactionNature.taxWithholding.isDiscretionary)
        #expect(InsiderTransactionNature.openMarketPurchase.isDiscretionary)
    }

    @Test("Value is nil rather than zero when price or size is missing")
    func missingValueIsNotZero() {
        let partial = InsiderTransaction(
            accessionNumber: "x", insiderName: "Test",
            transactionDate: .now, filedAt: .now, transactionCode: "P",
            shares: 1000, pricePerShare: nil
        )
        #expect(partial.approximateValue == nil)
    }
}
