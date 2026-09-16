package com.tylerabitbol.libra.app

import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.HTTPClient
import com.tylerabitbol.libra.persistence.LibraDatabase
import com.tylerabitbol.libra.persistence.SnapshotStore
import com.tylerabitbol.libra.services.mock.MockMacroDataProvider
import com.tylerabitbol.libra.services.mock.MockMarketDataProvider
import com.tylerabitbol.libra.services.mock.MockSECDataProvider
import com.tylerabitbol.libra.services.providers.AlpacaProvider
import com.tylerabitbol.libra.services.providers.CompositeMarketDataProvider
import com.tylerabitbol.libra.services.providers.FREDProvider
import com.tylerabitbol.libra.services.providers.FinnhubAnalystProvider
import com.tylerabitbol.libra.services.providers.FinnhubMetricsProvider
import com.tylerabitbol.libra.services.providers.FinnhubNewsProvider
import com.tylerabitbol.libra.services.providers.FinnhubProvider
import com.tylerabitbol.libra.services.providers.MarketDataProvider
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import com.tylerabitbol.libra.services.providers.SECFundamentalsProvider
import com.tylerabitbol.libra.services.providers.SECProvider
import com.tylerabitbol.libra.services.providers.TiingoProvider
import com.tylerabitbol.libra.services.secrets.InMemorySecretsStore
import com.tylerabitbol.libra.services.secrets.SecretKey
import com.tylerabitbol.libra.services.secrets.SecretsHealth
import com.tylerabitbol.libra.services.secrets.SecretsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's composition root.
 *
 * Views reach dependencies through this rather than constructing providers
 * themselves, which is what keeps Section 18's swap-a-provider promise real:
 * changing which implementation backs [MarketDataProvider] is one edit in
 * [rebuildRegistry], not a search across the view layer.
 *
 * Swift marked this `@Observable @MainActor`. Here the observable surface is a
 * [StateFlow] per changing value, which Compose collects; there is no main-actor
 * annotation because nothing in this class touches UI state directly.
 */
class AppEnvironment(
    val secrets: SecretsStore = InMemorySecretsStore(),
    val httpClient: HTTPClient = HTTPClient(),
) {
    private val _registry = MutableStateFlow(ProviderRegistry.sample)
    val registry: StateFlow<ProviderRegistry> = _registry.asStateFlow()

    /**
     * Records fetched data into the append-only store. Assigned once the
     * database exists; null in previews that opt out of persistence.
     */
    private val _snapshots = MutableStateFlow<SnapshotStore?>(null)
    val snapshots: StateFlow<SnapshotStore?> = _snapshots.asStateFlow()

    /**
     * Mirrors the stored secrets so the UI redraws when a key is entered.
     * The values themselves are never held here — only whether one exists.
     */
    private val _configuredKeys = MutableStateFlow<Set<SecretKey>>(emptySet())
    val configuredKeys: StateFlow<Set<SecretKey>> = _configuredKeys.asStateFlow()

    /**
     * Whether secure storage works at all. Checked once at startup, because a
     * broken keystore makes [configuredKeys] silently empty — the app would
     * otherwise report every key as unset with no explanation.
     */
    private val _secretsHealth = MutableStateFlow<SecretsHealth>(SecretsHealth.Available)
    val secretsHealth: StateFlow<SecretsHealth> = _secretsHealth.asStateFlow()

    init {
        _secretsHealth.value = secrets.diagnose()
        refreshConfiguredKeys()
        rebuildRegistry()
    }

    /**
     * True while any provider is serving synthetic data. Drives the banner
     * that must appear over any sample-data view.
     */
    val isUsingSampleData: Boolean get() = _registry.value.isUsingSampleData

    fun hasKey(key: SecretKey): Boolean = key in _configuredKeys.value

    /** Attaches the store once the database is available. */
    fun attach(database: LibraDatabase) {
        if (_snapshots.value != null) return
        _snapshots.value = SnapshotStore(database)
    }

    fun setSecret(value: String?, key: SecretKey) {
        secrets.set(value, key)
        refreshConfiguredKeys()
        rebuildRegistry()
    }

    private fun refreshConfiguredKeys() {
        _configuredKeys.value = SecretKey.entries.filter { secrets.hasValue(it) }.toSet()
    }

    /**
     * Chooses live or sample implementations based on what credentials exist.
     *
     * Resolution is per-source, not all-or-nothing: entering only a Finnhub
     * key gives live quotes while macro data stays mocked. [isUsingSampleData]
     * stays true while *any* source is still synthetic, so the banner remains
     * accurate through partial setup.
     */
    private fun rebuildRegistry() {
        val finnhubReady = hasKey(SecretKey.FinnhubAPIKey)
        val tiingoReady = hasKey(SecretKey.TiingoAPIKey)
        val fredReady = hasKey(SecretKey.FredAPIKey)
        // EDGAR needs no key — only the contact email its fair-access policy
        // requires. Without one, requests are refused by the SEC, not by us.
        val secReady = hasKey(SecretKey.SecContactEmail)

        val finnhub = FinnhubProvider(httpClient, secrets)
        val tiingo = TiingoProvider(httpClient, secrets)
        // Both halves of the pair, or nothing: one alone authenticates no
        // request. Absent, 1D and 5D say they need a key rather than spin.
        val alpacaReady = hasKey(SecretKey.AlpacaKeyID) && hasKey(SecretKey.AlpacaSecretKey)
        val alpaca = if (alpacaReady) AlpacaProvider(httpClient, secrets) else null

        // Quotes and history come from different vendors; the composite needs
        // both keys. With only one, mocks stay in place rather than serving a
        // half-populated screen that looks real.
        val marketData: MarketDataProvider = if (finnhubReady && tiingoReady) {
            CompositeMarketDataProvider(quotes = finnhub, history = tiingo, intraday = alpaca)
        } else {
            MockMarketDataProvider()
        }

        _registry.value = ProviderRegistry(
            marketData = marketData,
            fundamentals = if (secReady) {
                SECFundamentalsProvider(httpClient, secrets)
            } else {
                null
            },
            analyst = if (finnhubReady) FinnhubAnalystProvider(finnhub) else null,
            metrics = if (finnhubReady) FinnhubMetricsProvider(finnhub) else null,
            sec = if (secReady) SECProvider(httpClient, secrets) else MockSECDataProvider(),
            macro = if (fredReady) FREDProvider(httpClient, secrets) else MockMacroDataProvider(),
            news = if (finnhubReady) FinnhubNewsProvider(finnhub) else null,
            isUsingSampleData = !(finnhubReady && tiingoReady && fredReady && secReady),
        )
    }

    /** Whether a given data source is usable right now, and why not if it isn't. */
    fun readiness(provider: DataProviderID): SourceReadiness = when (provider) {
        DataProviderID.Finnhub ->
            if (hasKey(SecretKey.FinnhubAPIKey)) {
                SourceReadiness.Ready
            } else {
                SourceReadiness.NeedsSetup("Add your Finnhub API key.")
            }

        DataProviderID.Tiingo ->
            if (hasKey(SecretKey.TiingoAPIKey)) {
                SourceReadiness.Ready
            } else {
                SourceReadiness.NeedsSetup("Add your Tiingo API key.")
            }

        DataProviderID.Alpaca ->
            if (hasKey(SecretKey.AlpacaKeyID) && hasKey(SecretKey.AlpacaSecretKey)) {
                SourceReadiness.Ready
            } else {
                SourceReadiness.NeedsSetup(
                    "Add both halves of your Alpaca key pair for intraday charts.",
                )
            }

        DataProviderID.FRED ->
            if (hasKey(SecretKey.FredAPIKey)) {
                SourceReadiness.Ready
            } else {
                SourceReadiness.NeedsSetup(
                    "Add your FRED API key. The dashboard's index data needs it.",
                )
            }

        DataProviderID.SEC ->
            if (hasKey(SecretKey.SecContactEmail)) {
                SourceReadiness.Ready
            } else {
                SourceReadiness.NeedsSetup(
                    "The SEC requires a contact email before EDGAR requests are allowed.",
                )
            }

        DataProviderID.Computed -> SourceReadiness.Ready

        DataProviderID.Sample ->
            // Not a source anyone can configure. It appears in no settings row;
            // the case exists so the `when` stays exhaustive rather than
            // defaulting a real provider into "ready" by accident.
            SourceReadiness.NeedsSetup("Sample data is synthetic. Add real keys above.")
    }

    companion object {
        /**
         * Mock-backed environment for previews and tests. Never touches the
         * keystore, so previews run without credentials and without prompting.
         */
        fun preview(): AppEnvironment = AppEnvironment(secrets = InMemorySecretsStore())
    }
}

sealed class SourceReadiness {
    data object Ready : SourceReadiness()
    data class NeedsSetup(val text: String) : SourceReadiness()

    val isReady: Boolean get() = this is Ready

    val message: String? get() = (this as? NeedsSetup)?.text
}
