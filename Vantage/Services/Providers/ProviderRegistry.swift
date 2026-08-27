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
            sec: MockSECDataProvider(),
            macro: MockMacroDataProvider(),
            news: MockNewsProvider(),
            isUsingSampleData: true
        )
    }
}

/// What a provider can actually do with the credentials currently stored.
///
/// Free API tiers exclude endpoints that paid tiers include, and the exclusion
/// is only discoverable by asking. Rather than let the UI fail one card at a
/// time, the app probes once and records the result, so it can say "estimate
/// revisions aren't in your Finnhub plan" instead of showing a broken section.
struct ProviderCapability: Sendable, Hashable, Identifiable {
    var id: String { "\(provider.rawValue).\(endpointLabel)" }

    let provider: DataProviderID
    let endpointLabel: String
    let displayName: String
    let status: Status
    let checkedAt: Date

    enum Status: Sendable, Hashable {
        case available
        case notEntitled
        case missingCredentials
        case failed(String)
        case unchecked

        var isUsable: Bool { self == .available }

        var displayName: String {
            switch self {
            case .available: "Available"
            case .notEntitled: "Not in your plan"
            case .missingCredentials: "Key not set"
            case .failed(let reason): "Failed: \(reason)"
            case .unchecked: "Not checked"
            }
        }
    }
}
