import Foundation

/// Every failure mode the spec's Section 21 requires the app to survive.
///
/// Typed rather than free-form because the UI needs to distinguish "you need
/// to enter a key" (actionable by the user) from "the provider is down"
/// (actionable by waiting) from "this company doesn't report that figure"
/// (not a failure at all, and must render as "Not available").
enum APIError: Error, Sendable, Equatable {
    /// No API key configured for this provider. The UI should route the user
    /// to Settings rather than showing a generic error.
    case missingCredentials(DataProviderID)
    /// Key present but rejected — expired, revoked, or wrong.
    case invalidCredentials(DataProviderID)
    /// Provider rate limit hit. `retryAfter` when the provider tells us.
    case rateLimited(DataProviderID, retryAfter: TimeInterval?)
    /// The endpoint exists but this account's tier doesn't include it.
    /// Common on free tiers and must be surfaced as a capability gap, not a bug.
    case notEntitled(DataProviderID, endpoint: String)
    case notFound(DataProviderID, endpoint: String)
    case server(DataProviderID, statusCode: Int)
    case transport(DataProviderID, underlying: String)
    case decoding(DataProviderID, endpoint: String, underlying: String)
    /// Request succeeded but the payload was empty or structurally valid yet
    /// semantically useless. Distinct from `.notFound` — the concept exists,
    /// this issuer just doesn't report it.
    case noData(DataProviderID, endpoint: String)
    case cancelled

    var provider: DataProviderID? {
        switch self {
        case .missingCredentials(let p), .invalidCredentials(let p),
             .rateLimited(let p, _), .notEntitled(let p, _), .notFound(let p, _),
             .server(let p, _), .transport(let p, _), .decoding(let p, _, _),
             .noData(let p, _):
            p
        case .cancelled:
            nil
        }
    }

    /// Whether retrying the identical request could plausibly succeed.
    var isRetryable: Bool {
        switch self {
        case .rateLimited, .transport: true
        case .server(_, let code): code >= 500
        case .missingCredentials, .invalidCredentials, .notEntitled,
             .notFound, .decoding, .noData, .cancelled: false
        }
    }

    /// Short text for inline display next to a value.
    var shortDescription: String {
        switch self {
        case .missingCredentials(let p): "\(p.displayName) key not set"
        case .invalidCredentials(let p): "\(p.displayName) key rejected"
        case .rateLimited(let p, _): "\(p.displayName) rate limit"
        case .notEntitled(let p, _): "not in \(p.displayName) plan"
        case .notFound: "not found"
        case .server(_, let code): "server error \(code)"
        case .transport: "network error"
        case .decoding: "unexpected response"
        case .noData: "not reported"
        case .cancelled: "cancelled"
        }
    }

    /// Longer text with a suggested next step, for error surfaces that have room.
    var recoverySuggestion: String? {
        switch self {
        case .missingCredentials(let p):
            "Add your \(p.displayName) key in Settings → Data Sources."
        case .invalidCredentials(let p):
            "Check your \(p.displayName) key in Settings → Data Sources. It may have expired."
        case .rateLimited(_, let retryAfter):
            if let retryAfter {
                "Rate limited. Retrying is possible in about \(Int(retryAfter)) seconds."
            } else {
                "Rate limited. Try again shortly."
            }
        case .notEntitled(let p, let endpoint):
            "\(endpoint) isn't included in your \(p.displayName) plan."
        case .transport:
            "Check your network connection."
        case .noData:
            "This issuer doesn't report this figure."
        case .notFound, .server, .decoding, .cancelled:
            nil
        }
    }
}
