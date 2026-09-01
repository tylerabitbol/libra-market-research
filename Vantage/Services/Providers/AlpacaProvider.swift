import Foundation

/// Intraday price bars from Alpaca's IEX feed.
///
/// Tiingo's free tier is end-of-day only, so the 1D and 5D chart ranges had
/// nothing behind them. Alpaca's free plan serves minute-level bars at 200
/// requests a minute, which covers them.
///
/// **The IEX feed is roughly 2.5% of US equity volume.** That is why the
/// ROADMAP declined Alpaca as a history source and why these bars are for
/// drawing only. A volume anomaly computed against 2.5% of the real tape would
/// be meaningless, and a return computed from IEX prints can differ from the
/// consolidated tape. Nothing here reaches `EventDetector`,
/// `FundamentalDetector`, `RelativeAnalysis` or `ReturnCalculator.priceContext`:
/// the view model keeps this series in `intradayBars`, separate from `bars`, so
/// a calculation cannot be handed one of these by forgetting.
///
/// Daily and coarser bars stay on Tiingo, which is consolidated and
/// split-adjusted. Swapping those for an IEX-only series would quietly degrade
/// every calculation in the app.
struct AlpacaProvider: Sendable {
    let id: DataProviderID = .alpaca
    private let client: HTTPClient
    private let secrets: any SecretsStoring

    private static let baseURL = URL(string: "https://data.alpaca.markets")!

    /// Alpaca caps a page at 10,000 bars. A full session of one-minute bars is
    /// 390, so a single page covers every range the app offers — but the token
    /// is followed anyway rather than trusted not to appear. Silently dropping
    /// the tail of a series is the kind of defect that reads as a quiet market.
    private static let pageLimit = 10_000
    private static let maximumPages = 10

    init(client: HTTPClient, secrets: any SecretsStoring) {
        self.client = client
        self.secrets = secrets
    }

    /// Both halves are required. One alone authenticates nothing, so treating
    /// a half-entered pair as configured would produce 403s that look like
    /// outages.
    func isConfigured() async -> Bool {
        secrets.hasValue(for: .alpacaKeyID) && secrets.hasValue(for: .alpacaSecretKey)
    }

    /// Credentials go in headers, so they never enter the URL and therefore
    /// never reach `Endpoint.cacheKey`.
    private func authHeaders() throws -> [String: String] {
        [
            "APCA-API-KEY-ID": try secrets.require(.alpacaKeyID, for: .alpaca),
            "APCA-API-SECRET-KEY": try secrets.require(.alpacaSecretKey, for: .alpaca),
            "Accept": "application/json"
        ]
    }

    func bars(
        symbol: String,
        resolution: BarResolution,
        from: Date,
        to: Date
    ) async throws -> [PriceBarDTO] {
        // The mirror image of Tiingo's refusal. Asking Alpaca for daily bars
        // would return an IEX-only daily series that looks exactly like the
        // consolidated one and is not.
        guard let timeframe = resolution.alpacaTimeframe else {
            throw APIError.notEntitled(.alpaca, endpoint: "daily bars")
        }

        let headers = try authHeaders()
        var collected: [PriceBarDTO] = []
        var pageToken: String?

        for _ in 0..<Self.maximumPages {
            var query: [URLQueryItem] = [
                .init(name: "symbols", value: symbol.uppercased()),
                .init(name: "timeframe", value: timeframe),
                .init(name: "start", value: Self.instant(from)),
                .init(name: "end", value: Self.instant(to)),
                .init(name: "feed", value: "iex"),
                // Raw prints jump across a split. The chart is the one place a
                // split would be visible as a cliff, so ask for the adjusted
                // series; `adjustedClose` stays nil because these closes *are*
                // the adjusted ones.
                .init(name: "adjustment", value: "split"),
                .init(name: "limit", value: String(Self.pageLimit))
            ]
            if let pageToken {
                query.append(.init(name: "page_token", value: pageToken))
            }

            let endpoint = Endpoint(
                provider: .alpaca,
                baseURL: Self.baseURL,
                path: "/v2/stocks/bars",
                queryItems: query,
                headers: headers,
                label: "alpaca bars"
            )

            let page = try await client.get(endpoint, as: AlpacaBarsResponse.self)
            collected += (page.bars?[symbol.uppercased()] ?? []).compactMap(\.asDTO)

            guard let next = page.nextPageToken, !next.isEmpty else { break }
            pageToken = next
        }

        return collected.sorted { $0.date < $1.date }
    }

    /// RFC 3339 in UTC, which is what Alpaca's `start` and `end` expect.
    ///
    /// `ISO8601FormatStyle` rather than `ISO8601DateFormatter`: the latter is
    /// not `Sendable` and this runs off the main actor.
    private static func instant(_ date: Date) -> String {
        date.formatted(Date.ISO8601FormatStyle(timeZone: TimeZone(secondsFromGMT: 0)!))
    }
}

// MARK: - Wire format

/// `{"bars": {"AAPL": [...]}, "next_page_token": "..."}`
///
/// `bars` is keyed by symbol and is **absent, not empty**, when the window
/// contains no trades — a weekend, or a session that has not opened. Decoding
/// it as optional is what lets the view model tell "no trades" apart from a
/// failed request.
private struct AlpacaBarsResponse: Decodable, Sendable {
    let bars: [String: [AlpacaBar]]?
    let nextPageToken: String?

    enum CodingKeys: String, CodingKey {
        case bars
        case nextPageToken = "next_page_token"
    }
}

/// One bar. Alpaca uses single-letter keys: `t` timestamp, `o` open, `h` high,
/// `l` low, `c` close, `v` volume, `n` trade count, `vw` volume-weighted price.
private struct AlpacaBar: Decodable, Sendable {
    let t: String
    let o: Double?
    let h: Double?
    let l: Double?
    let c: Double?
    let v: Double?
    let n: Double?
    let vw: Double?

    var asDTO: PriceBarDTO? {
        // A bar without a timestamp or a close cannot be drawn. Dropping it is
        // correct; interpolating would invent price history.
        guard let date = AlpacaBar.parse(t), let o, let h, let l, let c
        else { return nil }
        return PriceBarDTO(
            date: date,
            open: o, high: h, low: l, close: c,
            // IEX volume, not consolidated volume. Carried so the series is
            // complete, and kept away from every volume calculation by the
            // view model rather than by being dropped here.
            volume: v,
            // These closes are already split-adjusted by the `adjustment`
            // parameter, so there is no separate adjusted series to report.
            adjustedClose: nil
        )
    }

    /// Alpaca returns RFC 3339, with fractional seconds on some rows and not
    /// on others. `ISO8601FormatStyle` is used rather than
    /// `ISO8601DateFormatter` because parsing happens off the main actor and
    /// the latter is not `Sendable`.
    private static func parse(_ string: String) -> Date? {
        if let date = try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(string) {
            return date
        }
        return try? Date.ISO8601FormatStyle().parse(string)
    }
}

extension BarResolution {
    /// Alpaca's `timeframe` vocabulary, and `nil` for anything daily or
    /// coarser — which Alpaca can serve but this app must not take from it.
    var alpacaTimeframe: String? {
        switch self {
        case .oneMinute: "1Min"
        case .fiveMinute: "5Min"
        case .fifteenMinute: "15Min"
        case .hourly: "1Hour"
        case .daily, .weekly, .monthly: nil
        }
    }
}
