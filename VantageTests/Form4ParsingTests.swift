import Testing
import Foundation
@testable import Vantage

/// Form 4 parsing, and the distinction the whole feature turns on.
///
/// A sale made under a pre-arranged plan carries no opinion about the company —
/// it was scheduled months earlier — and mixing it in with discretionary
/// selling is the single most misleading thing this feature could do. Half
/// these tests are about that one classification.
@Suite("Form 4 parsing")
struct Form4ParsingTests {
    private let filedAt = Date(timeIntervalSince1970: 1_756_000_000)

    private func parsed() throws -> [InsiderTransactionDTO] {
        try Form4Parser.parse(
            Fixture.data("sec_form4_synthetic", extension: "xml"),
            accessionNumber: "0001214156-26-000042",
            filedAt: filedAt)
    }

    @Test("Every transaction line in the document is read")
    func allTransactionsParse() throws {
        let transactions = try parsed()
        #expect(transactions.count == 5, "Four non-derivative lines and one derivative")
        #expect(transactions.allSatisfy { $0.accessionNumber == "0001214156-26-000042" })
        #expect(transactions.allSatisfy { $0.insiderName == "DOE JANE" })
    }

    @Test("The reporting person's relationship is carried through")
    func relationshipParses() throws {
        let transaction = try #require(try parsed().first)
        #expect(transaction.isOfficer)
        #expect(!transaction.isDirector)
        #expect(!transaction.isTenPercentOwner)
        #expect(transaction.insiderTitle == "Chief Financial Officer")
    }

    @Test("A sale flagged only by footnote is recognised as scheduled")
    func footnotePlanIsDetected() throws {
        let sale = try #require(try parsed().first { $0.transactionCode == "S" })
        // Filings predating the 2023 checkbox indicate the plan in a footnote
        // and nowhere else. Missing it would present a scheduled sale as a
        // discretionary one, which is the reading this feature must not produce.
        #expect(sale.isUnderTradingPlan)
        #expect(sale.nature == .scheduledPlan)
        #expect(!sale.nature.isDiscretionary)
    }

    @Test("A sale flagged by the modern checkbox is recognised too")
    func explicitPlanFlagIsDetected() throws {
        let exercise = try #require(try parsed().first { $0.transactionCode == "M" })
        #expect(exercise.isUnderTradingPlan, "The aff10b5One element marks this one")
    }

    @Test("An open-market purchase is discretionary")
    func purchaseIsDiscretionary() throws {
        let purchase = try #require(try parsed().first { $0.transactionCode == "P" })
        #expect(!purchase.isUnderTradingPlan)
        #expect(purchase.nature == .openMarketPurchase)
        #expect(purchase.nature.isDiscretionary)
        #expect(purchase.shares == 4_000)
        #expect(purchase.pricePerShare == 205.00)
        #expect(purchase.approximateValue == 820_000)
    }

    @Test("Tax withholding and awards are not market decisions")
    func routineCodesAreNotDiscretionary() throws {
        let transactions = try parsed()
        let withholding = try #require(transactions.first { $0.transactionCode == "F" })
        let award = try #require(transactions.first { $0.transactionCode == "A" })

        #expect(withholding.nature == .taxWithholding)
        #expect(!withholding.nature.isDiscretionary)
        #expect(award.nature == .grant)
        #expect(!award.nature.isDiscretionary)
    }

    @Test("A value given directly on the element is read, not only a wrapped one")
    func unwrappedValuesParse() throws {
        // Filing agents differ: most emit <transactionShares><value>9000</value>,
        // some put the text on the outer element.
        let award = try #require(try parsed().first { $0.transactionCode == "A" })
        #expect(award.shares == 9_000)
    }

    @Test("Shares owned afterwards survives when reported, and is absent when not")
    func postTransactionHoldings() throws {
        let transactions = try parsed()
        #expect(transactions.first { $0.transactionCode == "S" }?.sharesOwnedAfter == 180_000)
        // The withholding line reports no post-transaction total; inventing one
        // would be worse than leaving it out.
        #expect(transactions.first { $0.transactionCode == "F" }?.sharesOwnedAfter == nil)
    }

    @Test("Dates are the transaction's own, not the filing's")
    func transactionDatesParse() throws {
        let purchase = try #require(try parsed().first { $0.transactionCode == "P" })
        let expected = SECProvider.dayFormatter.date(from: "2026-08-22")
        #expect(purchase.transactionDate == expected)
        #expect(purchase.filedAt == filedAt)
    }

    @Test("Malformed XML refuses rather than returning an empty list")
    func malformedDocumentThrows() {
        // An empty array reads as "this insider made no trades". The provider
        // refused to be implemented at all rather than say that, and the parser
        // keeps the same discipline.
        #expect(throws: (any Error).self) {
            try Form4Parser.parse(Data("<not-xml".utf8),
                                  accessionNumber: "x", filedAt: .now)
        }
    }

    @Test("The styled document path resolves to the machine-readable XML")
    func ownershipURLDropsStylesheetDirectory() throws {
        let styled = FilingDTO(
            accessionNumber: "0001214156-26-000042", formType: "4", filedAt: .now,
            periodOfReport: nil,
            primaryDocumentURL: URL(string:
                "https://www.sec.gov/Archives/edgar/data/320193/000121415626000042/xslF345X05/form4.xml"),
            filingIndexURL: nil)

        // The xsl directory serves HTML; the parseable document is the same
        // filename one level up.
        let resolved = try #require(SECProvider.ownershipXMLURL(for: styled))
        #expect(resolved.absoluteString ==
                "https://www.sec.gov/Archives/edgar/data/320193/000121415626000042/form4.xml")
    }

    @Test("A document that is already raw XML is left alone")
    func ownershipURLLeavesRawPathsUnchanged() throws {
        let raw = FilingDTO(
            accessionNumber: "x", formType: "4", filedAt: .now, periodOfReport: nil,
            primaryDocumentURL: URL(string:
                "https://www.sec.gov/Archives/edgar/data/320193/000121415626000042/form4.xml"),
            filingIndexURL: nil)
        let resolved = try #require(SECProvider.ownershipXMLURL(for: raw))
        #expect(resolved.absoluteString.hasSuffix("/000121415626000042/form4.xml"))
    }
}
