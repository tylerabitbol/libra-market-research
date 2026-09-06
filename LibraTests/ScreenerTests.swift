import Testing
import Foundation
import SwiftData
@testable import Libra

/// Section 14, scoped to what the app holds.
///
/// The rule that matters most is what happens to a figure the app does not
/// have. A screen that passes untestable subjects through returns companies it
/// could not actually test — asserting something it does not know, which is the
/// same fabrication as rendering an absent figure as zero.
private func subject(
    _ symbol: String,
    revenueGrowth: Double? = nil,
    operatingMargin: Double? = nil,
    netCash: Double? = nil,
    dailyChange: Double? = nil
) -> ScreenSubject {
    var subject = ScreenSubject(symbol: symbol, name: "\(symbol) Inc.", sector: nil)
    subject.revenueGrowth = revenueGrowth
    subject.operatingMargin = operatingMargin
    subject.netCash = netCash
    subject.dailyChangePercent = dailyChange
    return subject
}

private func rule(_ field: ScreenField, _ comparison: ScreenComparison,
                  _ threshold: Double) -> ScreenRule {
    ScreenRule(field: field, comparison: comparison, threshold: threshold)
}

@Suite("Screener")
struct ScreenerTests {

    @Test("A rule over a figure the app does not hold fails rather than passes")
    func untestableSubjectsDoNotMatch() {
        var screen = Screen()
        screen.rules = [rule(.revenueGrowth, .greaterThan, 10)]

        // Tempting to let it through so a half-populated security is not
        // excluded — but then the screen returns a company it never tested.
        #expect(!screen.matches(subject("UNKNOWN")))
        #expect(screen.matches(subject("KNOWN", revenueGrowth: 15)))
    }

    @Test("The count of untestable subjects is reported, not hidden")
    func untestableCountIsVisible() {
        var screen = Screen()
        screen.rules = [rule(.operatingMargin, .greaterThan, 10)]
        let subjects = [subject("A", operatingMargin: 20),
                        subject("B"),
                        subject("C")]
        #expect(screen.run(over: subjects).map(\.symbol) == ["A"])
        // A screen that silently drops what it could not test reports a smaller
        // universe than the user thinks they searched.
        #expect(screen.untestable(in: subjects) == 2)
    }

    @Test("All-rules and any-rule compose differently")
    func combinatorsCompose() {
        let subjects = [
            subject("BOTH", revenueGrowth: 20, operatingMargin: 30),
            subject("ONE", revenueGrowth: 20, operatingMargin: 2),
            subject("NEITHER", revenueGrowth: 1, operatingMargin: 2)
        ]
        var screen = Screen()
        screen.rules = [rule(.revenueGrowth, .greaterThan, 10),
                        rule(.operatingMargin, .greaterThan, 10)]

        screen.combinator = .all
        #expect(screen.run(over: subjects).map(\.symbol) == ["BOTH"])

        screen.combinator = .any
        #expect(screen.run(over: subjects).map(\.symbol) == ["BOTH", "ONE"])
    }

    @Test("Both comparison directions work, including on negative figures")
    func comparisonsHandleNegatives() {
        let subjects = [subject("NETCASH", netCash: 5_000),
                        subject("NETDEBT", netCash: -5_000)]
        var screen = Screen()

        screen.rules = [rule(.netCash, .lessThan, 0)]
        #expect(screen.run(over: subjects).map(\.symbol) == ["NETDEBT"])

        screen.rules = [rule(.netCash, .greaterThan, 0)]
        #expect(screen.run(over: subjects).map(\.symbol) == ["NETCASH"])
    }

    @Test("A screen with no rules matches everything rather than nothing")
    func emptyScreenMatchesAll() {
        let screen = Screen()
        let subjects = [subject("A"), subject("B")]
        // An empty screen is not a filter that excludes; it is the absence of
        // one, and returning nothing would read as "no matches".
        #expect(screen.run(over: subjects).count == 2)
    }

    @Test("Saved screens survive a round trip through preferences")
    func savedScreensRoundTrip() throws {
        let defaults = try #require(UserDefaults(suiteName: "screener.tests"))
        defaults.removePersistentDomain(forName: "screener.tests")

        var screen = Screen()
        screen.name = "Improving margins"
        screen.combinator = .any
        screen.rules = [rule(.operatingMargin, .greaterThan, 15),
                        rule(.revenueGrowth, .greaterThan, 5)]
        SavedScreens.save([screen], to: defaults)

        let loaded = SavedScreens.load(from: defaults)
        #expect(loaded.count == 1)
        #expect(loaded.first?.name == "Improving margins")
        #expect(loaded.first?.combinator == .any)
        #expect(loaded.first?.rules.count == 2)
        defaults.removePersistentDomain(forName: "screener.tests")
    }

    @Test("Subjects come from the store, and benchmarks are excluded")
    func benchmarksAreNotScreened() async throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "AAPL", name: "Apple Inc."))
        let etf = Security(symbol: "XLK", name: "Technology Select Sector SPDR")
        etf.isBenchmark = true
        context.insert(etf)
        try context.save()

        let store = SnapshotStore(modelContainer: container)
        let subjects = try await store.screenSubjects()

        // A sector ETF is not a company; screening one on revenue growth would
        // return nothing while looking like a result.
        #expect(subjects.map(\.symbol) == ["AAPL"])
    }

    @Test("Stored figures reach the screener without a request")
    func storedFiguresPopulateSubjects() async throws {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()
        let store = SnapshotStore(modelContainer: container)

        try await store.record(
            quote: QuoteDTO(symbol: "TEST", last: 110, open: nil, high: nil, low: nil,
                            previousClose: 100, volume: nil, quoteTime: nil),
            symbol: "TEST", provider: .finnhub)

        let calendar = Calendar(identifier: .iso8601)
        func fact(_ concept: FinancialConcept, index: Int, value: Double,
                  kind: FiscalPeriodKind = .quarter) -> FinancialFactDTO {
            let end = calendar.date(from: DateComponents(
                year: 2024 + index / 4, month: [3, 6, 9, 12][index % 4], day: 28))!
            return FinancialFactDTO(
                concept: concept, rawTag: nil,
                periodStart: kind == .instant ? nil : end.addingTimeInterval(-90 * 86_400),
                periodEnd: end, fiscalYear: 2024 + index / 4, fiscalQuarter: index % 4 + 1,
                isAnnual: false, periodKind: kind, value: value, unit: "USD",
                filedAt: end, accessionNumber: "acc-\(concept.rawValue)-\(index)")
        }
        var facts: [FinancialFactDTO] = []
        for index in 0..<8 {
            facts.append(fact(.revenue, index: index, value: index >= 4 ? 1_200 : 1_000))
        }
        facts.append(fact(.totalDebt, index: 7, value: 500, kind: .instant))
        facts.append(fact(.cashAndEquivalents, index: 7, value: 900, kind: .instant))
        try await store.record(facts: facts, symbol: "TEST", provider: .sec)

        let subject = try #require(try await store.screenSubjects().first)
        #expect(subject.dailyChangePercent == 10)
        #expect(subject.revenueGrowth.map { abs($0 - 20) < 0.001 } == true)
        #expect(subject.netCash == 400)
    }
}
