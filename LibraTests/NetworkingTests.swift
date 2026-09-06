import Testing
import Foundation
@testable import Libra

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

@Suite("Keychain error reporting")
struct KeychainErrorTests {
    @Test("errSecMissingEntitlement is explained as a build problem, not a user error")
    func missingEntitlementIsExplained() {
        let message = SecretsError.explain(errSecMissingEntitlement)
        #expect(message.contains("entitlements"))
        #expect(message.contains("not"), "Must make clear this isn't the user's fault")
        #expect(!message.contains("-34018"),
                "A bare OSStatus is what made this hard to diagnose in the first place")
    }

    @Test("A locked keychain suggests unlocking rather than reporting a code")
    func lockedKeychainIsActionable() {
        #expect(SecretsError.explain(errSecInteractionNotAllowed).lowercased().contains("unlock"))
    }

    @Test("Unmapped statuses still report the raw code so nothing is swallowed")
    func unmappedStatusKeepsTheCode() {
        let message = SecretsError.explain(errSecDecode)
        #expect(message.contains("\(errSecDecode)"))
    }

    @Test("A healthy store reports available with no reason to show")
    func healthyStoreHasNoReason() {
        let health = InMemorySecretsStore().diagnose()
        #expect(health.isAvailable)
        #expect(health.reason == nil)
    }

    @Test("An unavailable store carries an explanation for the UI")
    func unavailableCarriesReason() {
        let health = SecretsHealth.unavailable(reason: "signed without entitlements")
        #expect(!health.isAvailable)
        #expect(health.reason == "signed without entitlements")
    }
}

@Suite("Key fingerprints")
struct KeyFingerprintTests {
    @Test("A stored key is fingerprinted by length and edges, never shown whole")
    func fingerprintMasksTheMiddle() throws {
        let store = InMemorySecretsStore()
        try store.set("0123456789abcdef0123456789abcdef01234567", for: .tiingoAPIKey)
        let fingerprint = try #require(store.fingerprint(for: .tiingoAPIKey))

        #expect(fingerprint.contains("40 chars"))
        #expect(fingerprint.contains("0123"))
        #expect(fingerprint.contains("4567"))
        #expect(!fingerprint.contains("456789abcdef0123456789abcdef0123"),
                "The middle of a credential must never be displayed")
    }

    @Test("Length is what catches a truncated paste")
    func truncationIsVisible() throws {
        let store = InMemorySecretsStore()
        try store.set("0123456789abcdef0123456789abcdef012345", for: .tiingoAPIKey)  // 38
        #expect(store.fingerprint(for: .tiingoAPIKey)?.contains("38 chars") == true)
    }

    @Test("A value too short to mask is flagged rather than partially revealed")
    func shortValueIsFlagged() throws {
        let store = InMemorySecretsStore()
        try store.set("abc123", for: .finnhubAPIKey)
        let fingerprint = try #require(store.fingerprint(for: .finnhubAPIKey))
        #expect(fingerprint.contains("too short"))
        #expect(!fingerprint.contains("abc1"))
    }

    @Test("The SEC contact email is shown in full, being an address rather than a secret")
    func emailIsNotMasked() throws {
        let store = InMemorySecretsStore()
        try store.set("someone@example.com", for: .secContactEmail)
        #expect(store.fingerprint(for: .secContactEmail) == "someone@example.com")
    }

    @Test("Nothing stored yields no fingerprint")
    func absentKeyHasNoFingerprint() {
        #expect(InMemorySecretsStore().fingerprint(for: .tiingoAPIKey) == nil)
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
        #expect(SecretKey.alpacaSecretKey.isSensitive)
    }
}
