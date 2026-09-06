import Foundation

/// Economic series and real index levels from the St. Louis Fed.
///
/// FRED does double duty here. It supplies the macro indicators of Section 11,
/// and it also supplies genuine index data — `SP500`, `DJIA`, `NASDAQCOM` and
/// `VIXCLS` — which Finnhub's free tier withholds behind a CFD subscription.
/// That lets the dashboard show the actual S&P 500 and the actual VIX rather
/// than ETF proxies.
///
/// The tradeoff is that FRED is end-of-day and close-only: no intraday, no
/// OHLC, no volume. The UI states the as-of date rather than implying live data.
struct FREDProvider: MacroDataProvider {
    let id: DataProviderID = .fred
    private let client: HTTPClient
    private let secrets: any SecretsStoring

    private static let baseURL = URL(string: "https://api.stlouisfed.org")!

    init(client: HTTPClient, secrets: any SecretsStoring) {
        self.client = client
        self.secrets = secrets
    }

    func isConfigured() async -> Bool { secrets.hasValue(for: .fredAPIKey) }

    func observations(
        seriesID: String,
        from: Date?,
        to: Date?
    ) async throws -> [MacroObservationDTO] {
        let key = try secrets.require(.fredAPIKey, for: .fred)
        var query: [URLQueryItem] = [
            .init(name: "series_id", value: seriesID),
            .init(name: "file_type", value: "json"),
            .init(name: "api_key", value: key)
        ]
        if let from {
            query.append(.init(name: "observation_start", value: Self.dayFormatter.string(from: from)))
        }
        if let to {
            query.append(.init(name: "observation_end", value: Self.dayFormatter.string(from: to)))
        }

        let endpoint = Endpoint(
            provider: .fred,
            baseURL: Self.baseURL,
            path: "/fred/series/observations",
            queryItems: query,
            label: "fred observations"
        )

        let response = try await client.get(endpoint, as: FREDObservationsResponse.self)
        let parsed = response.observations.compactMap { row -> MacroObservationDTO? in
            guard let date = Self.dayFormatter.date(from: row.date),
                  let value = Self.parseValue(row.value)
            else { return nil }
            return MacroObservationDTO(seriesID: seriesID, date: date, value: value)
        }

        guard !parsed.isEmpty else {
            throw APIError.noData(.fred, endpoint: "fred observations")
        }
        return parsed.sorted { $0.date < $1.date }
    }

    /// FRED writes a missing observation as the string `"."` — market holidays
    /// in daily series, or periods before a series begins. Those rows are
    /// dropped rather than coerced to 0, which would put a fake crash to zero
    /// in the middle of an index chart.
    static func parseValue(_ raw: String) -> Double? {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        guard trimmed != ".", !trimmed.isEmpty else { return nil }
        return Double(trimmed)
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

// MARK: - Wire format

struct FREDObservationsResponse: Decodable, Sendable {
    struct Observation: Decodable, Sendable {
        let date: String
        /// Always a string in FRED's JSON, including the "." missing marker,
        /// so it is decoded as text and parsed deliberately.
        let value: String
    }
    let observations: [Observation]
}
