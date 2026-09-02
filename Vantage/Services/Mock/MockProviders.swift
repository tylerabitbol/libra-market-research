import Foundation

/// Deterministic sample data for previews, tests and offline development.
///
/// Everything here is synthetic. It is seeded per symbol so a given ticker
/// always produces the same series — which is what makes it usable in tests —
/// but the numbers are invented and must never be presented as real. Any view
/// running against these providers is expected to show the sample-data banner;
/// see `ProviderRegistry.isUsingSampleData`.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: String) {
        // FNV-1a over the seed string, so the same symbol always starts the
        // same way across launches and across machines.
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in seed.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x100000001b3
        }
        self.state = hash == 0 ? 0x9E3779B97F4A7C15 : hash
    }

    mutating func next() -> UInt64 {
        // xorshift64*
        state ^= state >> 12
        state ^= state << 25
        state ^= state >> 27
        return state &* 2685821657736338717
    }
}

enum SampleData {
    static let symbols = ["NVDA", "AAPL", "MSFT", "COST", "JPM"]

    static let profiles: [String: CompanyProfileDTO] = [
        "NVDA": .init(symbol: "NVDA", name: "NVIDIA Corporation", exchange: "NASDAQ",
                      sector: "Information Technology", industry: "Semiconductors",
                      currency: "USD", marketCap: 3_100_000_000_000, sharesOutstanding: 24_400_000_000,
                      cik: "0001045810"),
        "AAPL": .init(symbol: "AAPL", name: "Apple Inc.", exchange: "NASDAQ",
                      sector: "Information Technology", industry: "Technology Hardware",
                      currency: "USD", marketCap: 3_400_000_000_000, sharesOutstanding: 15_000_000_000,
                      cik: "0000320193"),
        "MSFT": .init(symbol: "MSFT", name: "Microsoft Corporation", exchange: "NASDAQ",
                      sector: "Information Technology", industry: "Software",
                      currency: "USD", marketCap: 3_200_000_000_000, sharesOutstanding: 7_430_000_000,
                      cik: "0000789019"),
        "COST": .init(symbol: "COST", name: "Costco Wholesale Corporation", exchange: "NASDAQ",
                      sector: "Consumer Staples", industry: "Consumer Staples Merchandise Retail",
                      currency: "USD", marketCap: 400_000_000_000, sharesOutstanding: 443_000_000,
                      cik: "0000909832"),
        "JPM": .init(symbol: "JPM", name: "JPMorgan Chase & Co.", exchange: "NYSE",
                     sector: "Financials", industry: "Diversified Banks",
                     currency: "USD", marketCap: 700_000_000_000, sharesOutstanding: 2_800_000_000,
                     cik: "0000019617")
    ]

    /// A random walk with a mild drift, seeded by symbol.
    static func bars(
        symbol: String,
        resolution: BarResolution,
        from: Date,
        to: Date
    ) -> [PriceBarDTO] {
        var generator = SeededGenerator(seed: "\(symbol)-\(resolution.rawValue)")
        let step: TimeInterval = switch resolution {
        case .oneMinute: 60
        case .fiveMinute: 300
        case .fifteenMinute: 900
        case .hourly: 3600
        case .daily: 86_400
        case .weekly: 604_800
        case .monthly: 2_592_000
        }

        var price = Double.random(in: 80...420, using: &generator)
        var result: [PriceBarDTO] = []
        var cursor = from

        while cursor <= to {
            let drift = Double.random(in: -0.025...0.027, using: &generator)
            let open = price
            price = max(1, price * (1 + drift))
            let high = max(open, price) * Double.random(in: 1.000...1.012, using: &generator)
            let low = min(open, price) * Double.random(in: 0.988...1.000, using: &generator)
            let volume = Double.random(in: 8_000_000...60_000_000, using: &generator)
            result.append(.init(date: cursor, open: open, high: high, low: low,
                                close: price, volume: volume, adjustedClose: price))
            cursor = cursor.addingTimeInterval(step)
        }
        return result
    }
}

struct MockMarketDataProvider: MarketDataProvider {
    /// Not `.finnhub`. A mock that answers to a vendor's identity is a mock
    /// whose output cannot be told from that vendor's afterwards — which is
    /// exactly how synthetic bars ended up on disk looking like Tiingo's.
    let id: DataProviderID = .sample
    /// Set to simulate failures and verify the UI degrades rather than crashes.
    var failure: APIError?

    func isConfigured() async -> Bool { true }

    func quote(symbol: String) async throws -> QuoteDTO {
        if let failure { throw failure }
        let recent = SampleData.bars(
            symbol: symbol, resolution: .daily,
            from: Date.now.addingTimeInterval(-5 * 86_400), to: .now
        )
        guard let today = recent.last else {
            throw APIError.noData(.sample, endpoint: "quote")
        }
        return QuoteDTO(
            symbol: symbol.uppercased(),
            last: today.close,
            open: today.open,
            high: today.high,
            low: today.low,
            previousClose: recent.dropLast().last?.close,
            volume: today.volume,
            quoteTime: today.date
        )
    }

    func bars(symbol: String, resolution: BarResolution, from: Date, to: Date) async throws -> [PriceBarDTO] {
        if let failure { throw failure }
        return SampleData.bars(symbol: symbol, resolution: resolution, from: from, to: to)
    }

    func profile(symbol: String) async throws -> CompanyProfileDTO {
        if let failure { throw failure }
        guard let profile = SampleData.profiles[symbol.uppercased()] else {
            throw APIError.notFound(.sample, endpoint: "profile")
        }
        return profile
    }

    func search(query: String) async throws -> [CompanyProfileDTO] {
        if let failure { throw failure }
        let needle = query.uppercased()
        return SampleData.profiles.values
            .filter { $0.symbol.contains(needle) || $0.name.uppercased().contains(needle) }
            .sorted { $0.symbol < $1.symbol }
    }
}

struct MockSECDataProvider: SECDataProvider {
    let id: DataProviderID = .sample
    var failure: APIError?

    func isConfigured() async -> Bool { true }

    func resolveCIK(symbol: String) async throws -> String {
        if let failure { throw failure }
        guard let cik = SampleData.profiles[symbol.uppercased()]?.cik else {
            throw APIError.notFound(.sample, endpoint: "company_tickers")
        }
        return cik
    }

    func filings(cik: String, formTypes: [String], limit: Int) async throws -> [FilingDTO] {
        if let failure { throw failure }
        let forms = formTypes.isEmpty ? ["10-Q", "8-K", "10-K"] : formTypes
        return (0..<min(limit, 6)).map { index in
            FilingDTO(
                accessionNumber: "0000000000-00-\(String(format: "%06d", index))",
                formType: forms[index % forms.count],
                filedAt: Date.now.addingTimeInterval(-Double(index) * 21 * 86_400),
                periodOfReport: Date.now.addingTimeInterval(-Double(index) * 21 * 86_400 - 30 * 86_400),
                primaryDocumentURL: URL(string: "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=\(cik)"),
                filingIndexURL: URL(string: "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=\(cik)")
            )
        }
    }

    func insiderTransactions(cik: String, since: Date?) async throws -> [InsiderTransactionDTO] {
        if let failure { throw failure }
        var generator = SeededGenerator(seed: "insider-\(cik)")
        let codes = ["P", "S", "A", "M", "F"]
        return (0..<8).map { index in
            let code = codes[index % codes.count]
            return InsiderTransactionDTO(
                accessionNumber: "0000000000-00-9\(index)0000",
                insiderName: "Sample Insider \(index + 1)",
                insiderTitle: index % 3 == 0 ? "Chief Financial Officer" : "Director",
                isDirector: index % 3 != 0,
                isOfficer: index % 3 == 0,
                isTenPercentOwner: false,
                transactionDate: Date.now.addingTimeInterval(-Double(index) * 9 * 86_400),
                filedAt: Date.now.addingTimeInterval(-Double(index) * 9 * 86_400 + 86_400),
                transactionCode: code,
                isUnderTradingPlan: code == "S" && index % 2 == 0,
                shares: Double.random(in: 500...25_000, using: &generator).rounded(),
                pricePerShare: Double.random(in: 80...420, using: &generator),
                sharesOwnedAfter: Double.random(in: 20_000...400_000, using: &generator).rounded()
            )
        }
    }
}

struct MockMacroDataProvider: MacroDataProvider {
    let id: DataProviderID = .sample
    var failure: APIError?

    func isConfigured() async -> Bool { true }

    func observations(seriesID: String, from: Date?, to: Date?) async throws -> [MacroObservationDTO] {
        if let failure { throw failure }
        var generator = SeededGenerator(seed: "fred-\(seriesID)")
        let end = to ?? .now
        let start = from ?? end.addingTimeInterval(-730 * 86_400)
        var value = Double.random(in: 1.5...6.0, using: &generator)
        var cursor = start
        var result: [MacroObservationDTO] = []
        while cursor <= end {
            value = max(0, value + Double.random(in: -0.08...0.08, using: &generator))
            result.append(.init(seriesID: seriesID, date: cursor, value: value))
            cursor = cursor.addingTimeInterval(30 * 86_400)
        }
        return result
    }
}

struct MockNewsProvider: NewsProvider {
    let id: DataProviderID = .sample
    var failure: APIError?

    func isConfigured() async -> Bool { true }

    func companyNews(symbol: String, from: Date, to: Date) async throws -> [NewsItemDTO] {
        if let failure { throw failure }
        return (0..<5).map { index in
            NewsItemDTO(
                id: "\(symbol)-sample-\(index)",
                headline: "Sample headline \(index + 1) for \(symbol.uppercased())",
                summary: "Placeholder summary. This is synthetic sample data, not real news.",
                source: "Sample Source",
                url: nil,
                publishedAt: Date.now.addingTimeInterval(-Double(index) * 6 * 3600),
                relatedSymbols: [symbol.uppercased()]
            )
        }
    }
}
