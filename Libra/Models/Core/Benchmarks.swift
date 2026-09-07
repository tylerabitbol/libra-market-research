import Foundation
import OSLog

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

    /// Whether this benchmark's headline value comes from a real index series.
    var hasRealIndex: Bool { fredSeriesID != nil }

    // MARK: - Broad market

    /// The reference market for attribution — separating "the market moved"
    /// from "this company moved". The real index, since a beta measured
    /// against an ETF inherits the ETF's tracking error.
    static let marketSeriesID = "SP500"

    /// Stands in for the index during an open session, because FRED publishes
    /// the index only at the close. Labelled a proxy wherever it is shown.
    static let marketProxySymbol = "SPY"

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
    ///
    /// Nil when the label doesn't match anything we track — better than silently
    /// comparing a bank against the technology sector. A company with no sector
    /// benchmark reports "could not be measured" rather than a guess, and that
    /// behaviour is deliberate: widening coverage must not come at the cost of
    /// inventing a parent for a label that has none.
    ///
    /// Two passes. The substring pass handles labels that already contain a
    /// sector name — Finnhub's "Technology" inside "Information Technology".
    /// The table below handles industry-level labels that share no substring
    /// with their sector at all: NVIDIA reports "Semiconductors", which is
    /// unmistakably Information Technology and matches none of it textually.
    static func sector(matching sectorName: String?) -> Benchmark? {
        guard let sectorName else { return nil }
        let needle = sectorName.lowercased().trimmingCharacters(in: .whitespaces)

        if let match = sectors.first(where: { benchmark in
            let name = benchmark.displayName.lowercased()
            return name == needle || needle.contains(name) || name.contains(needle)
        }) {
            return match
        }

        if let id = industryToSector[needle] {
            return sectors.first { $0.id == id }
        }

        // Logged rather than swallowed: the table above was written from the
        // labels seen so far, not from a published list, so the honest way to
        // extend it is from labels actually encountered.
        logger.info("No sector benchmark for industry \(sectorName, privacy: .public)")
        return nil
    }

    private static let logger = Logger(
        subsystem: "com.tylerabitbol.libra", category: "benchmarks"
    )

    /// Industry-level labels mapped up to their GICS sector.
    ///
    /// Deliberately omits anything genuinely diversified — a conglomerate or a
    /// "diversified financial services" issuer has no single sector, and naming
    /// one would be the guess this whole function exists to avoid.
    private static let industryToSector: [String: String] = [
        // Information Technology
        "semiconductors": "tech", "software": "tech", "technology": "tech",
        "hardware": "tech", "electronic equipment": "tech", "it services": "tech",

        // Health Care
        "pharmaceuticals": "healthcare", "biotechnology": "healthcare",
        "health care": "healthcare", "medical devices": "healthcare",
        "life sciences tools & services": "healthcare",

        // Financials
        "banking": "financials", "financial services": "financials",
        "insurance": "financials", "capital markets": "financials",
        "consumer finance": "financials",

        // Consumer Discretionary
        "retail": "discretionary", "automobiles": "discretionary",
        "hotels, restaurants & leisure": "discretionary",
        "textiles apparel & luxury goods": "discretionary",
        "leisure products": "discretionary",

        // Consumer Staples
        "food products": "staples", "beverages": "staples", "tobacco": "staples",
        "household products": "staples", "consumer products": "staples",

        // Communication Services
        "media": "communications", "communications": "communications",
        "telecommunication": "communications", "entertainment": "communications",

        // Industrials
        "aerospace & defense": "industrials", "machinery": "industrials",
        "transportation": "industrials", "logistics & transportation": "industrials",
        "airlines": "industrials", "construction": "industrials",
        "industrial conglomerates": "industrials",
        "commercial services & supplies": "industrials",

        // Energy
        "energy": "energy", "oil & gas": "energy",

        // Materials
        "chemicals": "materials", "metals & mining": "materials",
        "packaging": "materials", "paper & forest products": "materials",
        "building materials": "materials",

        // Utilities
        "utilities": "utilities", "electric utilities": "utilities",
        "gas utilities": "utilities", "water utilities": "utilities",

        // Real Estate
        "real estate": "realestate", "reit": "realestate"
    ]
}
