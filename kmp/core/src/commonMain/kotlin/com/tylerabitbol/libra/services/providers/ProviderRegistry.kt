package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.services.mock.MockMacroDataProvider
import com.tylerabitbol.libra.services.mock.MockMarketDataProvider
import com.tylerabitbol.libra.services.mock.MockNewsProvider
import com.tylerabitbol.libra.services.mock.MockSECDataProvider

/**
 * The single place the app resolves "who do I ask for this kind of data".
 *
 * Section 18 asks that a provider be replaceable without rewriting the app.
 * Views and view models depend on this registry and on the interfaces, never
 * on [FinnhubProvider] or [SECProvider] directly, so a swap is one edit here.
 *
 * It also carries the sample-data flag. Running against mocks must be visible
 * in the UI — synthetic prices that look real would be worse than no data.
 */
data class ProviderRegistry(
    val marketData: MarketDataProvider,
    val fundamentals: FundamentalsProvider? = null,
    val analyst: AnalystDataProvider? = null,
    val metrics: CompanyMetricsProvider? = null,
    val sec: SECDataProvider? = null,
    val macro: MacroDataProvider? = null,
    val news: NewsProvider? = null,
    /**
     * True when any provider in this registry returns synthetic data. Views
     * must surface this; see `SampleDataBanner`.
     */
    val isUsingSampleData: Boolean = false,
) {
    companion object {
        /**
         * Everything mocked. Used by previews, tests, and first launch before
         * any key has been entered.
         */
        val sample: ProviderRegistry
            get() = ProviderRegistry(
                marketData = MockMarketDataProvider(),
                fundamentals = null,
                analyst = null,
                metrics = null,
                sec = MockSECDataProvider(),
                macro = MockMacroDataProvider(),
                news = MockNewsProvider(),
                isUsingSampleData = true,
            )
    }
}
