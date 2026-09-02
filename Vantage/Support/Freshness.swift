import Foundation

/// How current a piece of stored data is.
///
/// Section 20 requires that every data object have a visible "last updated"
/// state, so the user never has to wonder whether they are looking at live
/// data. Staleness thresholds differ wildly by data type — a quote goes stale
/// in a minute, an annual filing does not go stale for a quarter — so the
/// policy is supplied per data kind rather than hardcoded here.
enum Freshness: Sendable, Hashable {
    /// Never fetched.
    case missing
    /// Fetched recently enough to trust for its kind.
    case fresh(asOf: Date)
    /// Fetched, but past its useful window. Still shown, clearly marked.
    case stale(asOf: Date)
    /// A refresh is in flight. Carries the previous value's timestamp if any.
    case refreshing(previous: Date?)
    /// The last refresh attempt failed. Carries the last good timestamp if any.
    case failed(previous: Date?, reason: String)

    var asOf: Date? {
        switch self {
        case .missing: nil
        case .fresh(let date), .stale(let date): date
        case .refreshing(let previous): previous
        case .failed(let previous, _): previous
        }
    }

    /// True when there is *some* value to show, even if it is old or the last
    /// refresh failed. Section 21: a failed request must not blank the screen.
    var hasValue: Bool { asOf != nil }
}

/// How long data of a given kind stays useful before it should be re-fetched.
///
/// These are deliberately generous. This is a personal-use app hitting free API
/// tiers, and Section 20 asks for aggressive caching over repeated requests.
struct StalenessPolicy: Sendable, Hashable {
    let maxAge: TimeInterval

    static let quote = StalenessPolicy(maxAge: 60)                    // 1 minute
    static let intradayCandles = StalenessPolicy(maxAge: 5 * 60)      // 5 minutes
    static let dailyCandles = StalenessPolicy(maxAge: 6 * 60 * 60)    // 6 hours
    static let fundamentals = StalenessPolicy(maxAge: 24 * 60 * 60)   // 1 day
    static let filings = StalenessPolicy(maxAge: 60 * 60)             // 1 hour
    static let news = StalenessPolicy(maxAge: 30 * 60)                // 30 minutes
    static let macro = StalenessPolicy(maxAge: 12 * 60 * 60)          // 12 hours

    func evaluate(lastUpdated: Date?, now: Date = .now) -> Freshness {
        guard let lastUpdated else { return .missing }
        return now.timeIntervalSince(lastUpdated) <= maxAge
            ? .fresh(asOf: lastUpdated)
            : .stale(asOf: lastUpdated)
    }
}
