import Foundation

/// The single place the app resolves "who do I ask for this kind of data".
///
/// Section 18 asks that a provider be replaceable without rewriting the app.
/// Views and view models depend on this registry and on the protocols, never
/// on `FinnhubProvider` or `SECProvider` directly, so a swap is one edit here.
///
/// It also carries the sample-data flag. Running against mocks must be visible
/// in the UI — synthetic prices that look real would be worse than no data.
struct ProviderRegistry: Sendable {
    var marketData: any MarketDataProvider
    var fundamentals: (any FundamentalsProvider)?
    var analyst: (any AnalystDataProvider)?
    var metrics: (any CompanyMetricsProvider)?
    var sec: (any SECDataProvider)?
    var macro: (any MacroDataProvider)?
    var news: (any NewsProvider)?

    /// True when any provider in this registry returns synthetic data. Views
    /// must surface this; see `SampleDataBanner`.
    var isUsingSampleData: Bool

    /// Everything mocked. Used by previews, tests, and first launch before any
    /// key has been entered.
    static var sample: ProviderRegistry {
        ProviderRegistry(
            marketData: MockMarketDataProvider(),
            fundamentals: nil,
            analyst: nil,
            metrics: nil,
            sec: MockSECDataProvider(),
            macro: MockMacroDataProvider(),
            news: MockNewsProvider(),
            isUsingSampleData: true
        )
    }
}
