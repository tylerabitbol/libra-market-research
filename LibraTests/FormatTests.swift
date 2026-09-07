import Testing
import Foundation
@testable import Libra

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
    @Test("The major indexes use real FRED series, not ETF proxies")
    func majorIndexesAreReal() throws {
        for id in ["sp500", "nasdaq", "dow"] {
            let benchmark = try #require(Benchmark.broadMarket.first { $0.id == id })
            #expect(benchmark.hasRealIndex, "\(id) should resolve to a genuine index series")
            #expect(!benchmark.isProxy)
            #expect(benchmark.proxyNote == nil, "A real index needs no substitution caveat")
        }
        #expect(Benchmark.broadMarket.first { $0.id == "sp500" }?.fredSeriesID == "SP500")
    }

    @Test("Volatility is the real VIX, not a futures ETF")
    func volatilityIsTheRealVIX() throws {
        let vix = try #require(Benchmark.volatility.first)
        #expect(vix.fredSeriesID == "VIXCLS")
        #expect(vix.etfSymbol == nil, "The decaying VIX-futures proxy was removed deliberately")
        #expect(!vix.isProxy)
    }

    @Test("Benchmarks without a FRED series keep an ETF and say they are proxies")
    func proxiesAreDeclared() throws {
        let russell = try #require(Benchmark.broadMarket.first { $0.id == "russell2000" })
        #expect(russell.fredSeriesID == nil)
        #expect(russell.isProxy)
        #expect(russell.proxyNote?.isEmpty == false)

        // FRED publishes no sector index, so every sector row is a proxy.
        #expect(Benchmark.sectors.allSatisfy { $0.isProxy })
        #expect(Benchmark.sectors.allSatisfy { $0.proxyNote?.isEmpty == false })
    }

    @Test("All eleven GICS sectors are covered")
    func allSectorsPresent() {
        #expect(Benchmark.sectors.count == 11)
    }

    @Test("Benchmark identifiers are unique and every row has a data source")
    func wellFormed() {
        #expect(Set(Benchmark.all.map(\.id)).count == Benchmark.all.count)
        #expect(Benchmark.all.allSatisfy { $0.fredSeriesID != nil || $0.etfSymbol != nil },
                "A benchmark with no source could never render a value")
    }

    @Test("A company's sector maps to the matching sector benchmark")
    func sectorMatching() {
        #expect(Benchmark.sector(matching: "Information Technology")?.etfSymbol == "XLK")
        #expect(Benchmark.sector(matching: "Financials")?.etfSymbol == "XLF")
    }

    @Test("An unrecognised sector matches nothing rather than a wrong benchmark")
    func unknownSectorIsNil() {
        #expect(Benchmark.sector(matching: "Blockchain Widgets") == nil,
                "A wrong sector comparison is worse than none")
        #expect(Benchmark.sector(matching: nil) == nil)
    }

    @Test("An industry-level label resolves to its GICS sector")
    func industryLabelsMapUpToTheirSector() {
        // NVIDIA reports "Semiconductors", which shares no substring with any
        // sector name and previously fell through to no benchmark at all.
        #expect(Benchmark.sector(matching: "Semiconductors")?.etfSymbol == "XLK")
        #expect(Benchmark.sector(matching: "Pharmaceuticals")?.etfSymbol == "XLV")
        #expect(Benchmark.sector(matching: "Banking")?.etfSymbol == "XLF")
        #expect(Benchmark.sector(matching: "Beverages")?.etfSymbol == "XLP")
        #expect(Benchmark.sector(matching: "Aerospace & Defense")?.etfSymbol == "XLI")
        #expect(Benchmark.sector(matching: "  semiconductors  ")?.etfSymbol == "XLK",
                "Case and surrounding whitespace are the vendor's, not the reader's")
    }

    @Test("A label with no sensible parent still falls through")
    func diversifiedLabelsAreNotGuessedAt() {
        #expect(Benchmark.sector(matching: "Diversified Financial Services") == nil,
                "A genuinely diversified issuer has no single sector to name")
    }

    @Test("A count agrees in number with its noun")
    func countsAgreeWithTheirNoun() {
        #expect(Format.count(1, "dimension") == "1 dimension")
        #expect(Format.count(2, "dimension") == "2 dimensions")
        #expect(Format.count(0, "session") == "0 sessions")
        #expect(Format.count(1, "company", plural: "companies") == "1 company")
        #expect(Format.count(3, "company", plural: "companies") == "3 companies")
    }

    @Test("Percentage points can be rendered without a sign for verb-paired text")
    func unsignedPercentagePoints() {
        #expect(Format.percentagePoints(9.4) == "+9.4 pp")
        #expect(Format.percentagePoints(9.4, signed: false) == "9.4 pp")
        #expect(Format.percentagePoints(-9.4, signed: false) == "-9.4 pp",
                "Suppressing the plus must not suppress a minus")
        #expect(Format.percentagePoints(nil, signed: false) == Format.notAvailable)
    }
}
