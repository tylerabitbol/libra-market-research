import Foundation

/// A describable HTTP request. Kept provider-agnostic so the client can log,
/// rate-limit and cache uniformly regardless of which API it is talking to.
struct Endpoint: Sendable, Hashable {
    let provider: DataProviderID
    let baseURL: URL
    let path: String
    var queryItems: [URLQueryItem]
    var headers: [String: String]
    /// Short label used in errors and logs, e.g. "quote" or "companyfacts".
    let label: String

    init(
        provider: DataProviderID,
        baseURL: URL,
        path: String,
        queryItems: [URLQueryItem] = [],
        headers: [String: String] = [:],
        label: String
    ) {
        self.provider = provider
        self.baseURL = baseURL
        self.path = path
        self.queryItems = queryItems
        self.headers = headers
        self.label = label
    }

    func makeRequest() throws -> URLRequest {
        guard var components = URLComponents(
            url: baseURL.appending(path: path),
            resolvingAgainstBaseURL: false
        ) else {
            throw APIError.transport(provider, underlying: "Could not build URL for \(label)")
        }
        if !queryItems.isEmpty { components.queryItems = queryItems }
        guard let url = components.url else {
            throw APIError.transport(provider, underlying: "Could not build URL for \(label)")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        return request
    }

    /// Query parameter names that carry credentials.
    ///
    /// Finnhub and FRED both authenticate by query string rather than header,
    /// so excluding headers alone is not enough to keep secrets out of cache
    /// keys — these names are stripped explicitly.
    static let credentialQueryNames: Set<String> = ["token", "api_key", "apikey"]

    /// Stable key for the response cache. Excludes headers and any credential
    /// query parameter, so a stored key can never contain a secret.
    var cacheKey: String {
        var components = URLComponents()
        components.path = path
        components.queryItems = queryItems
            .filter { !Self.credentialQueryNames.contains($0.name.lowercased()) }
            .sorted { $0.name < $1.name }
        return "\(provider.rawValue)|\(components.string ?? path)"
    }
}
