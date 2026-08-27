import Foundation

/// The indexes and sector proxies the dashboard tracks (Section 3) and that
/// relative analysis measures against (Section 7).
///
/// A benchmark can be backed by two different things, and which one is used
/// changes what the number means:
///
/// - `fredSeriesID` is the **real index** — the actual S&P 500 level, the
///   actual VIX. Free from FRED, daily history back decades, but close-only and
///   end-of-day.
/// - `etfSymbol` is a **tradable proxy**. Live and intraday with volume, but an
///   ETF is not the index: it carries fees and can trade at a premium or
///   discount.
///
/// Where both exist the real index is preferred and the ETF is the intraday
/// fallback. Sectors have no FRED equivalent, so they remain proxies and say so.
struct Benchmark: Sendable, Hashable, Identifiable {
    let id: String
    let displayName: String
    /// FRED series for the genuine index, when one exists.
    let fredSeriesID: String?
    /// Tradable ETF, for intraday moves or where no index series exists.
    let etfSymbol: String?
    /// Explains the substitution whenever the displayed figure is not the thing
    /// named. Nil when `fredSeriesID` supplies the real index.
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

    /// True when the displayed value is a stand-in rather than the named index.
    var isProxy: Bool { fredSeriesID == nil && proxyNote != nil }

    /// The symbol to quote for a live intraday price, if any.
    var intradaySymbol: String? { etfSymbol }

    /// Whether this benchmark's headline value comes from a real index series.
    var hasRealIndex: Bool { fredSeriesID != nil }

    // MARK: - Broad market

    static let broadMarket: [Benchmark] = [
        .init(id: "sp500", displayName: "S&P 500",
              fredSeriesID: "SP500", etfSymbol: "SPY",
              proxyNote: nil, category: .broadMarket),
        .init(id: "nasdaq", displayName: "Nasdaq Composite",
              fredSeriesID: "NASDAQCOM", etfSymbol: "QQQ",
              proxyNote: nil, category: .broadMarket),
        .init(id: "dow", displayName: "Dow Jones Industrial Average",
              fredSeriesID: "DJIA", etfSymbol: "DIA",
              proxyNote: nil, category: .broadMarket),
        // No FRED series for the Russell 2000, so this one stays a proxy.
        .init(id: "russell2000", displayName: "Russell 2000",
              fredSeriesID: nil, etfSymbol: "IWM",
              proxyNote: "iShares Russell 2000 ETF, used as a proxy for the index.",
              category: .broadMarket)
    ]

    /// The real CBOE VIX, daily back to 1990. This replaces the VIX-futures ETF
    /// that earlier stood in for it — those futures decay over time and track
    /// the index only loosely, which made the substitution actively misleading.
    static let volatility: [Benchmark] = [
        .init(id: "vix", displayName: "VIX",
              fredSeriesID: "VIXCLS", etfSymbol: nil,
              proxyNote: nil, category: .volatility)
    ]

    // MARK: - Sectors

    /// The eleven GICS sectors via SPDR sector ETFs. FRED publishes no sector
    /// index, so every row here is explicitly a proxy.
    static let sectors: [Benchmark] = [
        sector("tech", "Information Technology", "XLK"),
        sector("financials", "Financials", "XLF"),
        sector("healthcare", "Health Care", "XLV"),
        sector("energy", "Energy", "XLE"),
        sector("discretionary", "Consumer Discretionary", "XLY"),
        sector("staples", "Consumer Staples", "XLP"),
        sector("industrials", "Industrials", "XLI"),
        sector("materials", "Materials", "XLB"),
        sector("realestate", "Real Estate", "XLRE"),
        sector("utilities", "Utilities", "XLU"),
        sector("communications", "Communication Services", "XLC")
    ]

    private static func sector(_ id: String, _ name: String, _ symbol: String) -> Benchmark {
        .init(id: id, displayName: name,
              fredSeriesID: nil, etfSymbol: symbol,
              proxyNote: "\(symbol) sector ETF, used as a proxy for the \(name) sector.",
              category: .sector)
    }

    static let all: [Benchmark] = broadMarket + volatility + sectors

    /// The market benchmark other things are measured against by default.
    static var market: Benchmark { broadMarket[0] }

    /// The sector benchmark a company maps to, for Section 7 comparisons.
    /// Nil when the sector string doesn't match anything we track — better than
    /// silently comparing a bank against the technology sector.
    static func sector(matching sectorName: String?) -> Benchmark? {
        guard let sectorName else { return nil }
        let needle = sectorName.lowercased()
        return sectors.first { benchmark in
            let name = benchmark.displayName.lowercased()
            return name == needle || needle.contains(name) || name.contains(needle)
        }
    }
}
