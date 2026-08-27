import Foundation
import SwiftUI

/// The app's composition root.
///
/// Views reach dependencies through this rather than constructing providers
/// themselves, which is what keeps Section 18's swap-a-provider promise real:
/// changing which implementation backs `MarketDataProvider` is one edit in
/// `rebuildRegistry()`, not a search across the view layer.
@Observable
@MainActor
final class AppEnvironment {
    private(set) var registry: ProviderRegistry
    private(set) var capabilities: [ProviderCapability] = []

    let secrets: any SecretsStoring
    let httpClient: HTTPClient

    /// Mirrors the stored secrets so SwiftUI redraws when a key is entered.
    /// The values themselves are never held here — only whether one exists.
    private(set) var configuredKeys: Set<SecretKey> = []

    init(
        secrets: any SecretsStoring = KeychainSecretsStore(),
        httpClient: HTTPClient = HTTPClient()
    ) {
        self.secrets = secrets
        self.httpClient = httpClient
        self.registry = .sample
        refreshConfiguredKeys()
        rebuildRegistry()
    }

    /// True while any provider is serving synthetic data. Drives the banner
    /// that must appear over any sample-data view.
    var isUsingSampleData: Bool { registry.isUsingSampleData }

    func hasKey(_ key: SecretKey) -> Bool { configuredKeys.contains(key) }

    func setSecret(_ value: String?, for key: SecretKey) throws {
        try secrets.set(value, for: key)
        refreshConfiguredKeys()
        rebuildRegistry()
    }

    private func refreshConfiguredKeys() {
        configuredKeys = Set(SecretKey.allCases.filter { secrets.hasValue(for: $0) })
    }

    /// Chooses live or sample implementations based on what credentials exist.
    ///
    /// Live providers arrive in Phase 2; until then this always resolves to
    /// mocks, but the decision point exists so wiring them in later doesn't
    /// touch any view.
    private func rebuildRegistry() {
        // Phase 2 will branch here on `hasKey(.finnhubAPIKey)` and
        // `hasKey(.secContactEmail)` to install FinnhubProvider / SECProvider.
        registry = .sample
    }

    /// Whether a given data source is usable right now, and why not if it isn't.
    func readiness(for provider: DataProviderID) -> SourceReadiness {
        switch provider {
        case .finnhub:
            hasKey(.finnhubAPIKey) ? .ready : .needsSetup("Add your Finnhub API key.")
        case .fred:
            hasKey(.fredAPIKey) ? .ready : .needsSetup("Add your FRED API key.")
        case .sec:
            hasKey(.secContactEmail)
                ? .ready
                : .needsSetup("The SEC requires a contact email before EDGAR requests are allowed.")
        case .computed, .userJournal:
            .ready
        }
    }
}

enum SourceReadiness: Sendable, Hashable {
    case ready
    case needsSetup(String)

    var isReady: Bool { self == .ready }

    var message: String? {
        if case .needsSetup(let text) = self { return text }
        return nil
    }
}

extension AppEnvironment {
    /// Mock-backed environment for previews and tests. Never touches the
    /// Keychain, so previews run without credentials and without prompting.
    static func preview() -> AppEnvironment {
        AppEnvironment(secrets: InMemorySecretsStore())
    }
}
