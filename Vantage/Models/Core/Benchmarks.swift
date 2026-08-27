import Foundation

/// The indexes and sector proxies the dashboard tracks (Section 3) and that
/// relative analysis measures against (Section 7).
///
/// These are deliberately ETF tickers rather than index symbols. Index quotes
/// (^GSPC, ^IXIC, ^VIX) are commonly restricted or absent on free API tiers,
/// while the corresponding ETFs are ordinary equities that any quote endpoint
/// returns. The ETF tracks the index closely but is *not* the index — it
/// carries fees and can trade at a small premium or discount — so the UI names
/// the proxy explicitly rather than labelling SPY as "the S&P 500".
struct Benchmark: Sendable, Hashable, Identifiable {
    let id: String
    let symbol: String
    /// What the user is actually interested in, e.g. "S&P 500".
    let displayName: String
    /// Set when `symbol` is a proxy rather than the thing itself.
    let proxyNote: String?
    let category: Category

    enum Category: String, Sendable, CaseIterable, Identifiable {
        case broadMarket, volatility, sector
        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .broadMarket: "Market"
            case .volatility: "Volatility"
            case .sector: "Sectors"
            }
        }
    }

    var isProxy: Bool { proxyNote != nil }

    // MARK: - Broad market

    static let broadMarket: [Benchmark] = [
        .init(id: "sp500", symbol: "SPY", displayName: "S&P 500",
              proxyNote: "SPDR S&P 500 ETF, used as a proxy for the index.",
              category: .broadMarket),
        .init(id: "nasdaq100", symbol: "QQQ", displayName: "Nasdaq 100",
              proxyNote: "Invesco QQQ Trust, used as a proxy for the index.",
              category: .broadMarket),
        .init(id: "dow", symbol: "DIA", displayName: "Dow Jones Industrial Average",
              proxyNote: "SPDR Dow Jones Industrial Average ETF, used as a proxy for the index.",
              category: .broadMarket),
        .init(id: "russell2000", symbol: "IWM", displayName: "Russell 2000",
              proxyNote: "iShares Russell 2000 ETF, used as a proxy for the index.",
              category: .broadMarket)
    ]

    static let volatility: [Benchmark] = [
        .init(id: "vix", symbol: "VIXY", displayName: "Short-term volatility futures",
              proxyNote: "ProShares VIX Short-Term Futures ETF. This tracks VIX "
                       + "futures, not the VIX index itself, and decays over time — "
                       + "it indicates direction, not the level of the VIX.",
              category: .volatility)
    ]

    // MARK: - Sectors

    /// The eleven GICS sectors via SPDR sector ETFs.
    static let sectors: [Benchmark] = [
        .init(id: "tech", symbol: "XLK", displayName: "Information Technology",
              proxyNote: "Technology Select Sector SPDR.", category: .sector),
        .init(id: "financials", symbol: "XLF", displayName: "Financials",
              proxyNote: "Financial Select Sector SPDR.", category: .sector),
        .init(id: "healthcare", symbol: "XLV", displayName: "Health Care",
              proxyNote: "Health Care Select Sector SPDR.", category: .sector),
        .init(id: "energy", symbol: "XLE", displayName: "Energy",
              proxyNote: "Energy Select Sector SPDR.", category: .sector),
        .init(id: "discretionary", symbol: "XLY", displayName: "Consumer Discretionary",
              proxyNote: "Consumer Discretionary Select Sector SPDR.", category: .sector),
        .init(id: "staples", symbol: "XLP", displayName: "Consumer Staples",
              proxyNote: "Consumer Staples Select Sector SPDR.", category: .sector),
        .init(id: "industrials", symbol: "XLI", displayName: "Industrials",
              proxyNote: "Industrial Select Sector SPDR.", category: .sector),
        .init(id: "materials", symbol: "XLB", displayName: "Materials",
              proxyNote: "Materials Select Sector SPDR.", category: .sector),
        .init(id: "realestate", symbol: "XLRE", displayName: "Real Estate",
              proxyNote: "Real Estate Select Sector SPDR.", category: .sector),
        .init(id: "utilities", symbol: "XLU", displayName: "Utilities",
              proxyNote: "Utilities Select Sector SPDR.", category: .sector),
        .init(id: "communications", symbol: "XLC", displayName: "Communication Services",
              proxyNote: "Communication Services Select Sector SPDR.", category: .sector)
    ]

    static let all: [Benchmark] = broadMarket + volatility + sectors

    /// The sector benchmark a company maps to, for Section 7 comparisons.
    /// Nil when the sector string doesn't match anything we track — better than
    /// silently comparing a bank against the technology sector.
    static func sector(matching sectorName: String?) -> Benchmark? {
        guard let sectorName else { return nil }
        let needle = sectorName.lowercased()
        return sectors.first { benchmark in
            let name = benchmark.displayName.lowercased()
            return name == needle
                || needle.contains(name)
                || name.contains(needle)
        }
    }
}
