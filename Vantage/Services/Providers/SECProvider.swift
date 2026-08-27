import Foundation

/// Filings and company identity from SEC EDGAR.
///
/// EDGAR requires no API key. What it does require is a `User-Agent` that
/// identifies the requester — their documented format is
/// "Company Name contact@domain.com" — and requests without one are refused
/// outright. Their fair-access policy also caps traffic at about 10 requests a
/// second, which the shared rate limiter enforces at 8.
///
/// Two hosts are involved and they are not interchangeable: the ticker-to-CIK
/// map is a static file on www.sec.gov, while the JSON APIs live on
/// data.sec.gov.
struct SECProvider: SECDataProvider {
    let id: DataProviderID = .sec
    private let client: HTTPClient
    private let secrets: any SecretsStoring

    private static let dataHost = URL(string: "https://data.sec.gov")!
    private static let wwwHost = URL(string: "https://www.sec.gov")!

    /// Used when the user hasn't supplied an organisation name.
    private static let defaultOrganization = "Vantage"

    init(client: HTTPClient, secrets: any SecretsStoring) {
        self.client = client
        self.secrets = secrets
    }

    func isConfigured() async -> Bool { secrets.hasValue(for: .secContactEmail) }

    /// "Vantage someone@example.com" — the identification EDGAR requires.
    ///
    /// Throws rather than substituting a placeholder address: sending an
    /// unreachable contact would defeat the entire point of the policy.
    private func userAgentHeaders() throws -> [String: String] {
        let email = try secrets.require(.secContactEmail, for: .sec)
        let organization = secrets.value(for: .secOrganizationName)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let name = (organization?.isEmpty == false ? organization! : Self.defaultOrganization)
        return [
            "User-Agent": "\(name) \(email)",
            "Accept-Encoding": "gzip, deflate",
            "Host": "data.sec.gov"
        ]
    }

    // MARK: - Identity

    /// Maps a ticker to a zero-padded 10-digit CIK.
    ///
    /// The whole map is one ~1MB file with no per-ticker endpoint, so it is
    /// fetched once and cached in memory for the process lifetime.
    func resolveCIK(symbol: String) async throws -> String {
        let map = try await Self.tickerMap(client: client, headers: try userAgentHeaders())
        guard let cik = map[symbol.uppercased()] else {
            throw APIError.notFound(.sec, endpoint: "company_tickers")
        }
        return cik
    }

    private static let cache = TickerMapCache()

    private static func tickerMap(
        client: HTTPClient,
        headers: [String: String]
    ) async throws -> [String: String] {
        if let cached = await cache.value { return cached }

        var fileHeaders = headers
        fileHeaders["Host"] = "www.sec.gov"
        let endpoint = Endpoint(
            provider: .sec,
            baseURL: wwwHost,
            path: "/files/company_tickers.json",
            headers: fileHeaders,
            label: "company_tickers"
        )
        // Keyed by row index ("0", "1", …) rather than by ticker, so it decodes
        // as a dictionary of records and is inverted here.
        let rows = try await client.get(endpoint, as: [String: TickerRow].self)
        let map = Dictionary(
            rows.values.map { ($0.ticker.uppercased(), Self.padCIK($0.cik_str)) },
            uniquingKeysWith: { first, _ in first }
        )
        await cache.store(map)
        return map
    }

    /// EDGAR paths need the CIK zero-padded to ten digits; the JSON carries it
    /// as a bare integer.
    static func padCIK(_ value: Int) -> String {
        String(format: "%010d", value)
    }

    /// Normalises a CIK to ten digits, rejecting anything unparseable.
    ///
    /// Throws rather than defaulting to zero. A `?? 0` fallback builds a
    /// perfectly well-formed request for CIK 0000000000 — a silent wrong
    /// question, whose answer is either a 404 or, worse, some other filer.
    static func normalizedCIK(_ raw: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        if trimmed.count == 10, trimmed.allSatisfy(\.isNumber) { return trimmed }
        guard let value = Int(trimmed), value > 0 else {
            throw APIError.notFound(.sec, endpoint: "CIK \(raw)")
        }
        return padCIK(value)
    }

    // MARK: - Filings

    func filings(cik: String, formTypes: [String], limit: Int) async throws -> [FilingDTO] {
        let padded = try Self.normalizedCIK(cik)
        let endpoint = Endpoint(
            provider: .sec,
            baseURL: Self.dataHost,
            path: "/submissions/CIK\(padded).json",
            headers: try userAgentHeaders(),
            label: "submissions"
        )
        let response = try await client.get(endpoint, as: SubmissionsResponse.self)
        let all = response.filings.recent.filings(cik: padded)

        let filtered = formTypes.isEmpty
            ? all
            : all.filter { formTypes.contains($0.formType) }
        return Array(filtered.prefix(limit))
    }

    /// Form 4 ownership documents are XML, not JSON, and each must be fetched
    /// and parsed individually. Listing them is in place; extracting the
    /// transaction lines is Phase 4 proper, and returning an empty array here
    /// would read as "this insider made no trades", so it refuses instead.
    func insiderTransactions(cik: String, since: Date?) async throws -> [InsiderTransactionDTO] {
        throw APIError.noData(.sec, endpoint: "Form 4 parsing not yet implemented")
    }
}

/// Caches the ticker map for the process lifetime.
private actor TickerMapCache {
    private(set) var value: [String: String]?
    func store(_ map: [String: String]) { value = map }
}

// MARK: - Wire format

private struct TickerRow: Decodable, Sendable {
    let cik_str: Int
    let ticker: String
    let title: String
}

struct SubmissionsResponse: Decodable, Sendable {
    let cik: String?
    let name: String?
    let filings: Filings

    struct Filings: Decodable, Sendable {
        let recent: Recent
    }

    /// EDGAR returns filings as **parallel arrays**, not as an array of
    /// objects: `form[i]` describes the same filing as `accessionNumber[i]`.
    /// Zipping them by index is required, and any length mismatch means a row
    /// cannot be trusted — so the shortest array bounds the result.
    struct Recent: Decodable, Sendable {
        let accessionNumber: [String]
        let filingDate: [String]
        let reportDate: [String]?
        let form: [String]
        let primaryDocument: [String]?

        func filings(cik: String) -> [FilingDTO] {
            let count = min(accessionNumber.count, filingDate.count, form.count)
            // A CIK that will not parse cannot produce a correct archive URL,
            // and a URL pointing at the wrong filer is worse than no link.
            guard let numericCIK = Int(cik.trimmingCharacters(in: .whitespaces)), numericCIK > 0
            else { return [] }

            return (0..<count).compactMap { index in
                guard let filed = SECProvider.dayFormatter.date(from: filingDate[index])
                else { return nil }

                let accession = accessionNumber[index]
                let bare = accession.replacingOccurrences(of: "-", with: "")
                let base = "https://www.sec.gov/Archives/edgar/data/\(numericCIK)/\(bare)"
                let document = primaryDocument?.indices.contains(index) == true
                    ? primaryDocument?[index] : nil

                return FilingDTO(
                    accessionNumber: accession,
                    formType: form[index],
                    filedAt: filed,
                    periodOfReport: reportDate?.indices.contains(index) == true
                        ? SECProvider.dayFormatter.date(from: reportDate![index]) : nil,
                    primaryDocumentURL: document.flatMap { URL(string: "\(base)/\($0)") },
                    filingIndexURL: URL(string: "\(base)/\(accession)-index.htm")
                )
            }
        }
    }
}

extension SECProvider {
    static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .iso8601)
        f.timeZone = TimeZone(secondsFromGMT: 0)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()
}
