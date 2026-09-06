import Foundation

/// Renders "Updated 4 minutes ago" strings.
///
/// The spec calls this out specifically: the UI should say how old the data is
/// rather than leaving the user to guess.
///
/// Main-actor isolated because `RelativeDateTimeFormatter` is not `Sendable`
/// and formatter construction is expensive enough that we don't want one per
/// row in a list. Every caller is view code, so this costs nothing in practice.
@MainActor
enum RelativeTimeText {
    private static let formatter: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .full
        f.dateTimeStyle = .numeric
        return f
    }()

    static func string(for date: Date, now: Date = .now) -> String {
        // Anything under a few seconds reads oddly as "in 0 seconds".
        if abs(now.timeIntervalSince(date)) < 5 { return "just now" }
        return formatter.localizedString(for: date, relativeTo: now)
    }

    /// The full status line shown under a value, e.g. "Updated 4 minutes ago"
    /// or "Couldn't refresh — showing data from 2 hours ago".
    static func status(for freshness: Freshness, now: Date = .now) -> String {
        switch freshness {
        case .missing:
            "Not available"
        case .fresh(let date):
            "Updated \(string(for: date, now: now))"
        case .stale(let date):
            "Stale — last updated \(string(for: date, now: now))"
        case .refreshing(let previous):
            if let previous {
                "Refreshing — showing data from \(string(for: previous, now: now))"
            } else {
                "Loading…"
            }
        case .failed(let previous, let reason):
            if let previous {
                "Couldn't refresh (\(reason)) — showing data from \(string(for: previous, now: now))"
            } else {
                "Not available (\(reason))"
            }
        }
    }
}
