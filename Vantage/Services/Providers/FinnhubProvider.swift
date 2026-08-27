import Foundation

/// Quotes, company data, analyst ratings and news from Finnhub.
///
/// This account's tier was measured rather than assumed. Available: `quote`,
/// `profile2`, `metric`, `recommendation`, `stock/earnings`,
/// `insider-transactions`, `company-news`. Not available (403): `candle`,
/// `eps-estimate`, `revenue-estimate`, `price-target`, and index symbols.
///
/// Price history therefore comes from Tiingo, and the estimate-revision work in
/// Section 8 is reported as a capability gap instead of rendering empty.
struct FinnhubProvider: Sendable {
    let id: DataProviderID = .finnhub
    private let client: HTTPClient
    private let secrets: any SecretsStoring

    private static let baseURL = URL(string: "https://finnhub.io/api/v1")!

    init(client: HTTPClient, secrets: any SecretsStoring) {
        self.client = client
        self.secrets = secrets
    }

    func isConfigured() async -> Bool { secrets.hasValue(for: .finnhubAPIKey) }

    /// Finnhub authenticates by query parameter. `Endpoint.cacheKey` strips the
    /// token explicitly (see `Endpoint.tokenQueryNames`) so a credential can
    /// never become part of a cache key.
    private func endpoint(
        path: String,
        query: [URLQueryItem] = [],
        label: String
    ) throws -> Endpoint {
        let token = try secrets.require(.finnhubAPIKey, for: .finnhub)
        return Endpoint(
            provider: .finnhub,
            baseURL: Self.baseURL,
            path: path,
            queryItems: query + [.init(name: "token", value: token)],
            label: label
        )
    }

    // MARK: - Market data

    func quote(symbol: String) async throws -> QuoteDTO {
        let raw = try await client.get(
            try endpoint(path: "/quote",
                         query: [.init(name: "symbol", value: symbol.uppercased())],
                         label: "quote"),
            as: FinnhubQuote.self
        )
        // Finnhub answers unknown symbols with a 200 and an all-zero body
        // rather than a 404. Treating that as a real price of $0.00 would be a
        // fabricated quote, so it is reported as no data.
        guard let last = raw.c, last > 0 else {
            throw APIError.noData(.finnhub, endpoint: "quote")
        }
        return QuoteDTO(
            symbol: symbol.uppercased(),
            last: last,
            open: raw.o.nonZero,
            high: raw.h.nonZero,
            low: raw.l.nonZero,
            previousClose: raw.pc.nonZero,
            volume: nil,                     // Not carried by /quote.
            quoteTime: raw.t.map { Date(timeIntervalSince1970: $0) }
        )
    }

    func profile(symbol: String) async throws -> CompanyProfileDTO {
        let raw = try await client.get(
            try endpoint(path: "/stock/profile2",
                         query: [.init(name: "symbol", value: symbol.uppercased())],
                         label: "profile2"),
            as: FinnhubProfile.self
        )
        guard let ticker = raw.ticker, !ticker.isEmpty else {
            throw APIError.notFound(.finnhub, endpoint: "profile2")
        }
        return CompanyProfileDTO(
            symbol: ticker.uppercased(),
            name: raw.name ?? ticker,
            exchange: raw.exchange,
            sector: raw.finnhubIndustry,   // Finnhub's sector-level label.
            industry: raw.finnhubIndustry,
            currency: raw.currency,
            // Reported in millions of the listing currency.
            marketCap: raw.marketCapitalization.map { $0 * 1_000_000 },
            sharesOutstanding: raw.shareOutstanding.map { $0 * 1_000_000 },
            cik: nil
        )
    }

    func search(query: String) async throws -> [CompanyProfileDTO] {
        let raw = try await client.get(
            try endpoint(path: "/search",
                         query: [.init(name: "q", value: query),
                                 .init(name: "exchange", value: "US")],
                         label: "search"),
            as: FinnhubSearchResponse.self
        )
        return raw.result?.compactMap { item in
            guard let symbol = item.symbol, let description = item.description else { return nil }
            return CompanyProfileDTO(
                symbol: symbol.uppercased(), name: description,
                exchange: nil, sector: nil, industry: nil, currency: nil,
                marketCap: nil, sharesOutstanding: nil, cik: nil
            )
        } ?? []
    }

    // MARK: - Analyst

    /// Rating distribution over time. Estimate revisions (`eps-estimate`,
    /// `revenue-estimate`, `price-target`) are not on this tier and throw
    /// `.notEntitled`, which the UI renders as a capability gap.
    func ratings(symbol: String) async throws -> [RatingSnapshotDTO] {
        let rows = try await client.get(
            try endpoint(path: "/stock/recommendation",
                         query: [.init(name: "symbol", value: symbol.uppercased())],
                         label: "recommendation"),
            as: [FinnhubRecommendation].self
        )
        return rows.compactMap { row in
            guard let period = row.period, let date = Self.dayFormatter.date(from: period)
            else { return nil }
            return RatingSnapshotDTO(
                asOf: date,
                strongBuy: row.strongBuy ?? 0, buy: row.buy ?? 0, hold: row.hold ?? 0,
                sell: row.sell ?? 0, strongSell: row.strongSell ?? 0
            )
        }.sorted { $0.asOf < $1.asOf }
    }

    func estimates(symbol: String, metric: EstimateMetric) async throws -> [AnalystEstimateDTO] {
        throw APIError.notEntitled(.finnhub, endpoint: "\(metric.displayName) estimates")
    }

    /// Reported EPS against consensus, per quarter — the basis for Section 4's
    /// earnings-surprise events.
    func earningsSurprises(symbol: String) async throws -> [EarningsSurpriseDTO] {
        let rows = try await client.get(
            try endpoint(path: "/stock/earnings",
                         query: [.init(name: "symbol", value: symbol.uppercased())],
                         label: "earnings"),
            as: [FinnhubEarnings].self
        )
        return rows.compactMap { row in
            guard let period = row.period, let date = Self.dayFormatter.date(from: period)
            else { return nil }
            return EarningsSurpriseDTO(
                period: date, fiscalYear: row.year, fiscalQuarter: row.quarter,
                estimate: row.estimate, actual: row.actual,
                surprise: row.surprise, surprisePercent: row.surprisePercent
            )
        }.sorted { $0.period < $1.period }
    }

    // MARK: - Metrics

    /// Current metrics plus the historical ratio series.
    ///
    /// The `series` block is the reason this endpoint matters: it carries years
    /// of P/E, P/S, margins and ROE, which is what turns a bare multiple into
    /// "78th percentile of its own history". Finnhub's free tier includes it
    /// even though it withholds candles and estimates.
    func metrics(symbol: String) async throws -> CompanyMetricsDTO {
        let raw = try await client.get(
            try endpoint(path: "/stock/metric",
                         query: [.init(name: "symbol", value: symbol.uppercased()),
                                 .init(name: "metric", value: "all")],
                         label: "metric"),
            as: FinnhubMetrics.self
        )
        guard let current = raw.metric, !current.isEmpty else {
            throw APIError.noData(.finnhub, endpoint: "metric")
        }

        return CompanyMetricsDTO(
            // Finnhub mixes numbers and strings (dates such as 52WeekHighDate)
            // in one object, so non-numeric entries are dropped rather than
            // coerced.
            current: current.compactMapValues(\.doubleValue),
            annual: Self.series(from: raw.series?.annual),
            quarterly: Self.series(from: raw.series?.quarterly),
            asOf: .now
        )
    }

    private static func series(
        from block: [String: [FinnhubSeriesPoint]]?
    ) -> [String: [MetricPoint]] {
        guard let block else { return [:] }
        return block.compactMapValues { points in
            let mapped = points.compactMap { point -> MetricPoint? in
                guard let value = point.v,
                      let date = dayFormatter.date(from: point.period)
                else { return nil }
                return MetricPoint(period: date, value: value)
            }
            .sorted { $0.period < $1.period }
            return mapped.isEmpty ? nil : mapped
        }
    }

    // MARK: - News

    func companyNews(symbol: String, from: Date, to: Date) async throws -> [NewsItemDTO] {
        let rows = try await client.get(
            try endpoint(path: "/company-news",
                         query: [.init(name: "symbol", value: symbol.uppercased()),
                                 .init(name: "from", value: Self.dayFormatter.string(from: from)),
                                 .init(name: "to", value: Self.dayFormatter.string(from: to))],
                         label: "company-news"),
            as: [FinnhubNews].self
        )
        return rows.compactMap { row in
            guard let headline = row.headline, !headline.isEmpty, let datetime = row.datetime
            else { return nil }
            return NewsItemDTO(
                id: row.id.map(String.init) ?? "\(symbol)-\(datetime)",
                headline: headline,
                summary: row.summary,
                source: row.source,
                url: row.url.flatMap(URL.init(string:)),
                publishedAt: Date(timeIntervalSince1970: datetime),
                relatedSymbols: [symbol.uppercased()]
            )
        }.sorted { $0.publishedAt > $1.publishedAt }
    }

    static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .iso8601)
        f.timeZone = TimeZone(secondsFromGMT: 0)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()
}

/// Reported versus expected EPS for one fiscal period.
struct EarningsSurpriseDTO: Sendable, Hashable {
    let period: Date
    let fiscalYear: Int?
    let fiscalQuarter: Int?
    let estimate: Double?
    let actual: Double?
    let surprise: Double?
    let surprisePercent: Double?
}

// MARK: - Wire format

private extension Optional where Wrapped == Double {
    /// Finnhub uses 0 for "not supplied" in quote payloads. Zero is never a
    /// real price, so it maps to nil rather than to a fabricated value.
    var nonZero: Double? {
        guard let value = self, value != 0 else { return nil }
        return value
    }
}

private struct FinnhubQuote: Decodable, Sendable {
    let c: Double?   // current
    let d: Double?   // change
    let dp: Double?  // change percent
    let h: Double?   // high
    let l: Double?   // low
    let o: Double?   // open
    let pc: Double?  // previous close
    let t: Double?   // unix timestamp
}

private struct FinnhubProfile: Decodable, Sendable {
    let ticker: String?
    let name: String?
    let exchange: String?
    let currency: String?
    let finnhubIndustry: String?
    let marketCapitalization: Double?
    let shareOutstanding: Double?
}

private struct FinnhubSearchResponse: Decodable, Sendable {
    struct Item: Decodable, Sendable {
        let symbol: String?
        let description: String?
    }
    let result: [Item]?
}

private struct FinnhubRecommendation: Decodable, Sendable {
    let period: String?
    let strongBuy: Int?
    let buy: Int?
    let hold: Int?
    let sell: Int?
    let strongSell: Int?
}

private struct FinnhubEarnings: Decodable, Sendable {
    let period: String?
    let year: Int?
    let quarter: Int?
    let estimate: Double?
    let actual: Double?
    let surprise: Double?
    let surprisePercent: Double?
}

private struct FinnhubMetrics: Decodable, Sendable {
    let metric: [String: JSONValue]?
    let series: Series?

    struct Series: Decodable, Sendable {
        let annual: [String: [FinnhubSeriesPoint]]?
        let quarterly: [String: [FinnhubSeriesPoint]]?
    }
}

private struct FinnhubSeriesPoint: Decodable, Sendable {
    let period: String
    let v: Double?
}

/// Finnhub's metric object mixes numbers, strings and nulls under one schema,
/// so values are decoded permissively and only the numeric ones are kept.
/// Decoding it as `[String: Double]` fails outright on the first date string.
private enum JSONValue: Decodable, Sendable {
    case number(Double)
    case string(String)
    case null
    case other

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else {
            self = .other
        }
    }

    var doubleValue: Double? {
        if case .number(let value) = self { return value }
        return nil
    }
}

private struct FinnhubNews: Decodable, Sendable {
    let id: Int?
    let datetime: Double?
    let headline: String?
    let summary: String?
    let source: String?
    let url: String?
}
