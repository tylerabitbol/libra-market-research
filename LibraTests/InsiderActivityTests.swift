import Testing
import Foundation
@testable import Libra

/// Section 10's summaries.
///
/// The counts are only meaningful if the exclusions are right. A scheduled sale
/// was decided months before it executed, a grant is compensation and withheld
/// shares are an administrative consequence of vesting — counting any of them
/// as insider sentiment produces a figure that is mostly payroll.
private func transaction(
    code: String,
    plan: Bool = false,
    shares: Double? = 1_000,
    price: Double? = 100,
    day: Int = 0,
    filedDay: Int = 0,
    name: String = "DOE JANE"
) -> InsiderTransactionDTO {
    let base = Date(timeIntervalSince1970: 1_700_000_000)
    return InsiderTransactionDTO(
        accessionNumber: "acc-\(code)-\(day)", insiderName: name, insiderTitle: "CFO",
        isDirector: false, isOfficer: true, isTenPercentOwner: false,
        transactionDate: base.addingTimeInterval(Double(day) * 86_400),
        filedAt: base.addingTimeInterval(Double(filedDay) * 86_400),
        transactionCode: code, isUnderTradingPlan: plan,
        shares: shares, pricePerShare: price, sharesOwnedAfter: nil)
}

@Suite("Insider activity")
struct InsiderActivityTests {

    @Test("Only open-market decisions are counted")
    func exclusionsAreApplied() throws {
        let summary = try #require(InsiderActivity.summarize([
            transaction(code: "P"),
            transaction(code: "S", day: 1),
            transaction(code: "S", plan: true, day: 2),
            transaction(code: "A", day: 3),
            transaction(code: "F", day: 4),
            transaction(code: "M", day: 5)
        ]))

        #expect(summary.purchaseCount == 1)
        #expect(summary.saleCount == 1)
        #expect(summary.scheduledCount == 1, "The plan sale is excluded, not counted as a sale")
        #expect(summary.routineCount == 3, "Grant, withholding and exercise")
    }

    @Test("Approximate value multiplies shares by the price on each line")
    func valueIsSummedPerLine() throws {
        let summary = try #require(InsiderActivity.summarize([
            transaction(code: "P", shares: 1_000, price: 50),
            transaction(code: "P", shares: 2_000, price: 25, day: 1)
        ]))
        #expect(summary.purchaseValue == 100_000)
        // Approximate, because one decision is often filed as several lines at
        // several prices — the claim says so rather than implying precision.
        #expect(summary.claim.text.contains("does not predict returns"))
    }

    @Test("A line with no price contributes no value rather than zero")
    func missingPriceIsAbsent() throws {
        let summary = try #require(InsiderActivity.summarize([
            transaction(code: "P", shares: 1_000, price: nil)
        ]))
        #expect(summary.purchaseCount == 1)
        #expect(summary.purchaseValue == nil, "A missing price must not read as a free purchase")
    }

    @Test("The excluded counts are stated, so a reader can reconcile")
    func exclusionsAreVisible() throws {
        let summary = try #require(InsiderActivity.summarize([
            transaction(code: "S", plan: true),
            transaction(code: "A", day: 1)
        ]))
        #expect(!summary.hasDiscretionaryActivity)
        #expect(summary.claim.text.contains("1 scheduled-plan"))
        #expect(summary.claim.text.contains("1 routine"))
    }

    @Test("Nothing filed produces no summary rather than a row of zeros")
    func emptyProducesNothing() {
        #expect(InsiderActivity.summarize([]) == nil)
    }

    // MARK: - Events

    @Test("Only discretionary transactions become events")
    func scheduledSalesAreNotEvents() {
        let since = Date(timeIntervalSince1970: 1_700_000_000 - 86_400)
        let events = InsiderActivity.events([
            transaction(code: "P", filedDay: 1),
            transaction(code: "S", plan: true, day: 1, filedDay: 1),
            transaction(code: "A", day: 2, filedDay: 2)
        ], since: since)

        // A plan executing on schedule is not news, and a feed reporting every
        // one would bury the purchases that are.
        #expect(events.count == 1)
        #expect(events.first?.kind == .insiderTransaction)
        #expect(events.first?.headline.contains("bought") == true)
    }

    @Test("An event carries no unusualness, because there is no sample to rank")
    func eventsCarryNoRank() throws {
        let since = Date(timeIntervalSince1970: 1_700_000_000 - 86_400)
        let event = try #require(
            InsiderActivity.events([transaction(code: "P", filedDay: 1)], since: since).first)
        #expect(event.unusualness == 0)
        #expect(event.context?.contains("do not predict returns") == true)
        #expect(event.context?.contains("many innocent explanations") == true)
    }

    @Test("With no prior visit nothing is reported as new")
    func firstVisitReportsNothing() {
        // The same rule the filing detector follows: with no "since" there is
        // no "new", and presenting a year of Form 4s as fresh would be false.
        #expect(InsiderActivity.events([transaction(code: "P")], since: nil).isEmpty)
    }

    @Test("Transactions filed before the last visit are not new")
    func onlyNewerTransactionsBecomeEvents() {
        let since = Date(timeIntervalSince1970: 1_700_000_000 + 10 * 86_400)
        #expect(InsiderActivity.events([transaction(code: "P", filedDay: 1)],
                                       since: since).isEmpty)
    }
}
