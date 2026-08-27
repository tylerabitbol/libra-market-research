import Testing
import Foundation
@testable import Vantage

@Suite("API errors")
struct APIErrorTests {
    @Test("Only transient failures are retried")
    func retryClassification() {
        #expect(APIError.rateLimited(.sec, retryAfter: nil).isRetryable)
        #expect(APIError.transport(.sec, underlying: "offline").isRetryable)
        #expect(APIError.server(.sec, statusCode: 503).isRetryable)

        #expect(!APIError.server(.sec, statusCode: 400).isRetryable)
        #expect(!APIError.missingCredentials(.finnhub).isRetryable)
        #expect(!APIError.notEntitled(.finnhub, endpoint: "estimates").isRetryable)
        #expect(!APIError.decoding(.fred, endpoint: "series", underlying: "bad").isRetryable)
    }

    @Test("Missing credentials point the user at Settings")
    func missingCredentialsIsActionable() {
        let suggestion = APIError.missingCredentials(.finnhub).recoverySuggestion
        #expect(suggestion?.contains("Settings") == true)
    }

    @Test("Unreported figures read as 'not reported', not as an error")
    func noDataReadsAsNotReported() {
        #expect(APIError.noData(.sec, endpoint: "companyfacts").shortDescription == "not reported")
    }
}

@Suite("Endpoint construction")
struct EndpointTests {
    private let base = URL(string: "https://data.sec.gov")!

    @Test("Query items are attached to the built request")
    func queryItemsAreIncluded() throws {
        let endpoint = Endpoint(
            provider: .fred, baseURL: URL(string: "https://api.stlouisfed.org")!,
            path: "/fred/series/observations",
            queryItems: [.init(name: "series_id", value: "UNRATE")],
            label: "observations"
        )
        let url = try #require(endpoint.makeRequest().url)
        #expect(url.absoluteString.contains("series_id=UNRATE"))
    }

    @Test("Cache keys exclude headers so credentials never enter the cache")
    func cacheKeyExcludesHeaders() {
        let withKey = Endpoint(provider: .sec, baseURL: base, path: "/submissions/CIK.json",
                               headers: ["User-Agent": "someone@example.com"], label: "submissions")
        let without = Endpoint(provider: .sec, baseURL: base, path: "/submissions/CIK.json",
                               label: "submissions")
        #expect(withKey.cacheKey == without.cacheKey)
        #expect(!withKey.cacheKey.contains("example.com"))
    }

    @Test("Cache keys are stable regardless of query item order")
    func cacheKeyIsOrderStable() {
        let a = Endpoint(provider: .fred, baseURL: base, path: "/x",
                         queryItems: [.init(name: "b", value: "2"), .init(name: "a", value: "1")],
                         label: "x")
        let b = Endpoint(provider: .fred, baseURL: base, path: "/x",
                         queryItems: [.init(name: "a", value: "1"), .init(name: "b", value: "2")],
                         label: "x")
        #expect(a.cacheKey == b.cacheKey)
    }
}

@Suite("Secrets store")
struct SecretsStoreTests {
    @Test("An unset key throws the error the UI can act on")
    func missingKeyThrowsActionableError() {
        let store = InMemorySecretsStore()
        #expect(throws: APIError.missingCredentials(.finnhub)) {
            try store.require(.finnhubAPIKey, for: .finnhub)
        }
    }

    @Test("A whitespace-only key counts as unset")
    func whitespaceIsNotAValue() throws {
        let store = InMemorySecretsStore()
        try store.set("   ", for: .fredAPIKey)
        #expect(!store.hasValue(for: .fredAPIKey))
    }

    @Test("The SEC contact email is not masked, unlike the API keys")
    func onlyKeysAreMasked() {
        #expect(!SecretKey.secContactEmail.isSensitive)
        #expect(SecretKey.finnhubAPIKey.isSensitive)
        #expect(SecretKey.anthropicAPIKey.isSensitive)
    }
}
