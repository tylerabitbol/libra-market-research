import Foundation

/// Daily price history from Tiingo.
///
/// Finnhub's `stock/candle` endpoint is not available on the free tier (it
/// returns 403), so Tiingo supplies every historical bar the app uses. Its free
/// tier carries adjusted daily OHLCV back to the 1980s for US equities and
/// ETFs, which covers the 5Y chart requirement with room to spare.
///
/// Tiingo is end-of-day only. Live prices come from Finnhub; see
/// `CompositeMarketDataProvider`, which routes between the two.
struct TiingoProvider: Sendable {
    let id: DataProviderID = .tiingo
    private let client: HTTPClient
    private let secrets: any SecretsStoring

    private static let baseURL = URL(string: "https://api.tiingo.com")!

    init(client: HTTPClient, secrets: any SecretsStoring) {
        self.client = client
        self.secrets = secrets
    }

    func isConfigured() async -> Bool { secrets.hasValue(for: .tiingoAPIKey) }

    /// Tiingo authenticates with a header, so the token never enters the URL —
    /// and therefore never reaches `Endpoint.cacheKey`, which is built from
    /// path and query only.
    private func authHeaders() throws -> [String: String] {
        let token = try secrets.require(.tiingoAPIKey, for: .tiingo)
        return ["Authorization": "Token \(token)", "Content-Type": "application/json"]
    }

    func bars(
        symbol: String,
        resolution: BarResolution,
        from: Date,
        to: Date
    ) async throws -> [PriceBarDTO] {
        // The free tier serves daily and coarser only. Asking for intraday
        // would silently return daily bars mislabelled as 5-minute data, so we
        // refuse instead — a wrong resolution corrupts every calculation built
        // on top of it.
        guard resolution.isDailyOrCoarser else {
            throw APIError.notEntitled(.tiingo, endpoint: "intraday prices")
        }

        let endpoint = Endpoint(
            provider: .tiingo,
            baseURL: Self.baseURL,
            path: "/tiingo/daily/\(symbol.lowercased())/prices",
            queryItems: [
                .init(name: "startDate", value: Self.dayFormatter.string(from: from)),
                .init(name: "endDate", value: Self.dayFormatter.string(from: to)),
                .init(name: "resampleFreq", value: resolution.tiingoResampleFrequency)
            ],
            headers: try authHeaders(),
            label: "tiingo prices"
        )

        let rows = try await client.get(endpoint, as: [TiingoBar].self)
        return rows.compactMap(\.asDTO)
    }

    func profile(symbol: String) async throws -> CompanyProfileDTO {
        let endpoint = Endpoint(
            provider: .tiingo,
            baseURL: Self.baseURL,
            path: "/tiingo/daily/\(symbol.lowercased())",
            headers: try authHeaders(),
            label: "tiingo metadata"
        )
        let meta = try await client.get(endpoint, as: TiingoMetadata.self)
        return CompanyProfileDTO(
            symbol: meta.ticker.uppercased(),
            name: meta.name,
            exchange: meta.exchangeCode,
            sector: nil,        // Tiingo metadata carries no sector.
            industry: nil,
            currency: nil,
            marketCap: nil,
            sharesOutstanding: nil,
            cik: nil
        )
    }

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .iso8601)
        f.timeZone = TimeZone(secondsFromGMT: 0)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()
}

// MARK: - Wire format

/// One row of `/tiingo/daily/{ticker}/prices`.
///
/// Both raw and adjusted OHLCV are decoded. The distinction is not cosmetic:
/// for AAPL on 2020-08-25 the raw close is 499.30 while the adjusted close is
/// 120.98, because of the 4-for-1 split days later. Any return, moving average
/// or volatility figure computed on raw closes would be badly wrong across a
/// split, so `PriceBar.analysisClose` prefers the adjusted series.
private struct TiingoBar: Decodable, Sendable {
    let date: String
    let open: Double?
    let high: Double?
    let low: Double?
    let close: Double?
    let volume: Double?
    let adjOpen: Double?
    let adjHigh: Double?
    let adjLow: Double?
    let adjClose: Double?
    let adjVolume: Double?
    let divCash: Double?
    let splitFactor: Double?

    var asDTO: PriceBarDTO? {
        // A bar without a date or a close is not usable. Dropping it is correct;
        // substituting a neighbouring value would invent price history.
        guard let parsed = TiingoBar.parse(date),
              let open, let high, let low, let close
        else { return nil }
        return PriceBarDTO(
            date: parsed,
            open: open, high: high, low: low, close: close,
            volume: volume,
            adjustedClose: adjClose
        )
    }

    /// Tiingo returns ISO-8601 with milliseconds, e.g. "2026-08-20T00:00:00.000Z".
    ///
    /// Uses `ISO8601FormatStyle` rather than `ISO8601DateFormatter`: the latter
    /// is not `Sendable`, and parsing happens off the main actor. Falls back to
    /// the non-fractional form, since Tiingo omits milliseconds on some rows.
    private static func parse(_ string: String) -> Date? {
        if let date = try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(string) {
            return date
        }
        return try? Date.ISO8601FormatStyle().parse(string)
    }
}

private struct TiingoMetadata: Decodable, Sendable {
    let ticker: String
    let name: String
    let exchangeCode: String?
    let startDate: String?
    let endDate: String?
}

extension BarResolution {
    var isDailyOrCoarser: Bool {
        switch self {
        case .daily, .weekly, .monthly: true
        case .oneMinute, .fiveMinute, .fifteenMinute, .hourly: false
        }
    }

    /// Tiingo's `resampleFreq` vocabulary.
    var tiingoResampleFrequency: String {
        switch self {
        case .weekly: "weekly"
        case .monthly: "monthly"
        default: "daily"
        }
    }
}
