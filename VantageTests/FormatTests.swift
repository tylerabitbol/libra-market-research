import Testing
import Foundation
@testable import Vantage

@Suite("Formatting")
struct FormatTests {
    @Test("Every formatter renders a missing value as 'Not available', never as zero")
    func nilNeverBecomesZero() {
        #expect(Format.currency(nil) == Format.notAvailable)
        #expect(Format.compactCurrency(nil) == Format.notAvailable)
        #expect(Format.compact(nil) == Format.notAvailable)
        #expect(Format.percent(nil) == Format.notAvailable)
        #expect(Format.signedPercent(nil) == Format.notAvailable)
        #expect(Format.percentagePoints(nil) == Format.notAvailable)
        #expect(Format.multiple(nil) == Format.notAvailable)
        #expect(Format.ratio(nil) == Format.notAvailable)
        #expect(Format.ordinal(nil) == Format.notAvailable)
        #expect(Format.shortDate(nil) == Format.notAvailable)
    }

    @Test("Large figures use compact magnitudes")
    func compactMagnitudes() {
        #expect(Format.compactCurrency(3_100_000_000_000) == "$3.10T")
        // Avoids an exact .5 tie, where half-to-even rounding makes the
        // expected value a coin flip rather than a property of the formatter.
        #expect(Format.compactCurrency(412_700_000_000) == "$413B")
        #expect(Format.compact(24_400_000) == "24.4M")
        #expect(Format.compact(1_900_000_000) == "1.90B")
    }

    @Test("Negative magnitudes keep their sign")
    func negativeCompact() {
        #expect(Format.compactCurrency(-2_500_000_000) == "-$2.50B")
    }

    @Test("Signed percentages always show direction")
    func signedPercentages() {
        #expect(Format.signedPercent(4.82) == "+4.82%")
        #expect(Format.signedPercent(-1.34) == "-1.34%")
        #expect(Format.signedPercent(0) == "0.00%")
    }

    @Test("Percentage points are labelled 'pp', not '%'")
    func percentagePointsAreDistinct() {
        let text = Format.percentagePoints(7)
        #expect(text.contains("pp"))
        #expect(!text.contains("%"), "Percent and percentage points are different units")
    }

    @Test("A non-finite ratio is not available rather than 'inf'")
    func nonFiniteRatio() {
        #expect(Format.ratio(.infinity) == Format.notAvailable)
        #expect(Format.ratio(.nan) == Format.notAvailable)
    }

    @Test("Ordinals handle the teens correctly")
    func ordinalTeens() {
        #expect(Format.ordinal(1) == "1st")
        #expect(Format.ordinal(2) == "2nd")
        #expect(Format.ordinal(3) == "3rd")
        #expect(Format.ordinal(4) == "4th")
        #expect(Format.ordinal(11) == "11th")
        #expect(Format.ordinal(12) == "12th")
        #expect(Format.ordinal(13) == "13th")
        #expect(Format.ordinal(21) == "21st")
        #expect(Format.ordinal(78) == "78th")
        #expect(Format.ordinal(101) == "101st")
    }

    @Test("Volume multiples read as '1.9×'")
    func multiples() {
        #expect(Format.multiple(1.9) == "1.9×")
    }
}

@Suite("Benchmarks")
struct BenchmarkTests {
    @Test("Index rows are labelled as ETF proxies, not as the index itself")
    func indexesAreMarkedAsProxies() throws {
        let sp500 = try #require(Benchmark.broadMarket.first { $0.id == "sp500" })
        #expect(sp500.symbol == "SPY")
        #expect(sp500.isProxy, "SPY is not the S&P 500 and must not be presented as it")
        #expect(sp500.proxyNote?.isEmpty == false)
    }

    @Test("The volatility proxy warns that futures are not the VIX index")
    func volatilityProxyIsCaveated() throws {
        let vix = try #require(Benchmark.volatility.first)
        let note = try #require(vix.proxyNote)
        #expect(note.contains("not the VIX index"))
    }

    @Test("All eleven GICS sectors are covered")
    func allSectorsPresent() {
        #expect(Benchmark.sectors.count == 11)
    }

    @Test("Benchmark identifiers and symbols are unique")
    func noDuplicates() {
        #expect(Set(Benchmark.all.map(\.id)).count == Benchmark.all.count)
        #expect(Set(Benchmark.all.map(\.symbol)).count == Benchmark.all.count)
    }

    @Test("A company's sector maps to the matching sector benchmark")
    func sectorMatching() {
        #expect(Benchmark.sector(matching: "Information Technology")?.symbol == "XLK")
        #expect(Benchmark.sector(matching: "Financials")?.symbol == "XLF")
    }

    @Test("An unrecognised sector matches nothing rather than a wrong benchmark")
    func unknownSectorIsNil() {
        #expect(Benchmark.sector(matching: "Blockchain Widgets") == nil,
                "A wrong sector comparison is worse than none")
        #expect(Benchmark.sector(matching: nil) == nil)
    }
}
