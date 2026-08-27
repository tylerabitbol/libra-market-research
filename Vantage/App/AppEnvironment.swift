import Foundation
import SwiftUI
import SwiftData

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

    /// Records fetched data into the append-only store. Assigned once the
    /// container exists; nil in previews that opt out of persistence.
    private(set) var snapshots: SnapshotStore?

    /// Mirrors the stored secrets so SwiftUI redraws when a key is entered.
    /// The values themselves are never held here — only whether one exists.
    private(set) var configuredKeys: Set<SecretKey> = []

    /// Whether secure storage works at all. Checked once at startup, because a
    /// broken Keychain makes `configuredKeys` silently empty — the app would
    /// otherwise report every key as unset with no explanation.
    private(set) var secretsHealth: SecretsHealth = .available

    init(
        secrets: any SecretsStoring = KeychainSecretsStore(),
        httpClient: HTTPClient = HTTPClient()
    ) {
        self.secrets = secrets
        self.httpClient = httpClient
        self.registry = .sample
        self.secretsHealth = secrets.diagnose()
        refreshConfiguredKeys()
        rebuildRegistry()
    }

    /// True while any provider is serving synthetic data. Drives the banner
    /// that must appear over any sample-data view.
    var isUsingSampleData: Bool { registry.isUsingSampleData }

    func hasKey(_ key: SecretKey) -> Bool { configuredKeys.contains(key) }

    /// Attaches the store once the SwiftData container is available.
    func attach(container: ModelContainer) {
        guard snapshots == nil else { return }
        snapshots = SnapshotStore(modelContainer: container)
    }

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
    /// Resolution is per-source, not all-or-nothing: entering only a Finnhub
    /// key gives live quotes while macro data stays mocked. `isUsingSampleData`
    /// stays true while *any* source is still synthetic, so the banner remains
    /// accurate through partial setup.
    private func rebuildRegistry() {
        let finnhubReady = hasKey(.finnhubAPIKey)
        let tiingoReady = hasKey(.tiingoAPIKey)
        let fredReady = hasKey(.fredAPIKey)
        // EDGAR needs no key — only the contact email its fair-access policy
        // requires. Without one, requests are refused by the SEC, not by us.
        let secReady = hasKey(.secContactEmail)

        let finnhub = FinnhubProvider(client: httpClient, secrets: secrets)
        let tiingo = TiingoProvider(client: httpClient, secrets: secrets)

        // Quotes and history come from different vendors; the composite needs
        // both keys. With only one, mocks stay in place rather than serving a
        // half-populated screen that looks real.
        let marketData: any MarketDataProvider = (finnhubReady && tiingoReady)
            ? CompositeMarketDataProvider(quotes: finnhub, history: tiingo)
            : MockMarketDataProvider()

        registry = ProviderRegistry(
            marketData: marketData,
            fundamentals: secReady ? SECFundamentalsProvider(client: httpClient, secrets: secrets)
                                   : nil,
            analyst: finnhubReady ? FinnhubAnalystProvider(provider: finnhub) : nil,
            metrics: finnhubReady ? FinnhubMetricsProvider(provider: finnhub) : nil,
            sec: secReady ? SECProvider(client: httpClient, secrets: secrets)
                          : MockSECDataProvider(),
            macro: fredReady ? FREDProvider(client: httpClient, secrets: secrets)
                             : MockMacroDataProvider(),
            news: finnhubReady ? FinnhubNewsProvider(provider: finnhub) : nil,
            isUsingSampleData: !(finnhubReady && tiingoReady && fredReady && secReady)
        )
    }

    /// Whether a given data source is usable right now, and why not if it isn't.
    func readiness(for provider: DataProviderID) -> SourceReadiness {
        switch provider {
        case .finnhub:
            hasKey(.finnhubAPIKey) ? .ready : .needsSetup("Add your Finnhub API key.")
        case .tiingo:
            hasKey(.tiingoAPIKey) ? .ready : .needsSetup("Add your Tiingo API key.")
        case .fred:
            hasKey(.fredAPIKey)
                ? .ready
                : .needsSetup("Add your FRED API key. The dashboard's index data needs it.")
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
