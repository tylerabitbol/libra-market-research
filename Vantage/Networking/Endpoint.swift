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

    /// Stable key for the response cache. Excludes headers, which carry
    /// credentials and contact info that must never end up in a cache key.
    var cacheKey: String {
        var components = URLComponents()
        components.path = path
        components.queryItems = queryItems.sorted { $0.name < $1.name }
        return "\(provider.rawValue)|\(components.string ?? path)"
    }
}
