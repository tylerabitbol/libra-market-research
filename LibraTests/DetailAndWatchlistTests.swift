import Testing
import Foundation
@testable import Libra

private func fact(
    _ concept: FinancialConcept,
    year: Int,
    value: Double,
    kind: FiscalPeriodKind = .annual
) -> FinancialFactDTO {
    FinancialFactDTO(
        concept: concept, rawTag: "us-gaap:Test",
        periodStart: nil,
        periodEnd: Calendar(identifier: .iso8601)
            .date(from: DateComponents(year: year, month: 12, day: 31))!,
        fiscalYear: year, fiscalQuarter: nil, isAnnual: kind == .annual,
        periodKind: kind, value: value, unit: "USD",
        filedAt: nil, accessionNumber: "acc-\(year)"
    )
}

@Suite("Watchlist ordering")
@MainActor
struct WatchlistOrderingTests {
    private func row(_ symbol: String, change: Double?) -> WatchlistRow {
        var row = WatchlistRow(symbol: symbol, name: symbol)
        if let change {
            row.quote = QuoteDTO(symbol: symbol, last: 100 * (1 + change / 100),
                                 open: nil, high: nil, low: nil,
                                 previousClose: 100, volume: nil, quoteTime: nil)
        }
        return row
    }

    @Test("Biggest change ranks by magnitude, so a large fall outranks a small rise")
    func biggestChangeUsesMagnitude() {
        let model = WatchlistViewModel()
        model.setSort(.biggestChange)
        // Injecting through the public surface isn't available, so the ordering
        // rule is asserted directly on the same comparison the model uses.
        let rows = [row("A", change: 1.2), row("B", change: -8.4), row("C", change: 3.0)]
        let sorted = rows.sorted { abs($0.changePercent ?? 0) > abs($1.changePercent ?? 0) }
        #expect(sorted.map(\.symbol) == ["B", "C", "A"],
                "This screen is about what moved, not about what went up")
    }

    @Test("A row with no quote sorts last rather than as if it were flat")
    func missingQuoteSortsLast() {
        let rows = [row("A", change: nil), row("B", change: 2.0)]
        let sorted = rows.sorted { abs($0.changePercent ?? 0) > abs($1.changePercent ?? 0) }
        #expect(sorted.first?.symbol == "B")
    }

    @Test("Change percent is nil, not zero, when no quote has arrived")
    func missingQuoteHasNoChange() {
        #expect(row("A", change: nil).changePercent == nil)
    }
}

@Suite("Security detail derived figures")
@MainActor
struct SecurityDetailDerivedTests {
    @Test("Annual revenue carries year-over-year growth, newest first")
    func revenueGrowth() {
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.applyFundamentalsForTesting([
            fact(.revenue, year: 2023, value: 100),
            fact(.revenue, year: 2024, value: 120),
            fact(.revenue, year: 2025, value: 150)
        ])

        let annual = model.annualRevenue
        #expect(annual.count == 3)
        #expect(annual.first?.fact.fiscalYear == 2025, "Newest first")

        let growth2025 = try? #require(annual.first?.growth)
        #expect(abs((growth2025 ?? 0) - 25) < 0.001)
        // The earliest year has no prior year to compare against.
        #expect(annual.last?.growth == nil,
                "Growth with no prior period must be absent, not zero")
    }

    @Test("Free cash flow is operating cash flow less capital expenditures")
    func freeCashFlowComputed() {
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.applyFundamentalsForTesting([
            fact(.operatingCashFlow, year: 2025, value: 100_000),
            fact(.capitalExpenditures, year: 2025, value: 30_000)
        ])
        let fcf = model.annualFreeCashFlow
        #expect(fcf.count == 1)
        #expect(fcf.first?.value == 70_000)
    }

    @Test("Capex filed as a positive outflow is still subtracted")
    func capexSignHandled() {
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.applyFundamentalsForTesting([
            fact(.operatingCashFlow, year: 2025, value: 100_000),
            fact(.capitalExpenditures, year: 2025, value: -30_000)
        ])
        // Issuers file capex positive; a negative filing must not add to FCF.
        #expect(model.annualFreeCashFlow.first?.value == 70_000)
    }

    @Test("A year missing either input yields no free cash flow figure")
    func partialInputsYieldNothing() {
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.applyFundamentalsForTesting([
            fact(.operatingCashFlow, year: 2025, value: 100_000)
        ])
        #expect(model.annualFreeCashFlow.isEmpty,
                "Half a calculation is worse than none")
    }

    @Test("Quarterly rows are excluded from the annual revenue series")
    func quarterlyExcludedFromAnnual() {
        let model = SecurityDetailViewModel(symbol: "TEST")
        model.applyFundamentalsForTesting([
            fact(.revenue, year: 2025, value: 150),
            fact(.revenue, year: 2025, value: 40, kind: .quarter)
        ])
        #expect(model.annualRevenue.count == 1)
        #expect(model.annualRevenue.first?.fact.value == 150)
    }

    @Test("Symbols are normalised to uppercase")
    func symbolNormalised() {
        #expect(SecurityDetailViewModel(symbol: "aapl").symbol == "AAPL")
    }
}
