import Foundation

/// How far back the "What changed" panel looks.
///
/// The default is the user's previous visit, which is the question the panel
/// was built to answer and the one the `NEW` badge rests on. Every other case
/// is exploration layered on top: a wider window does not change what counts
/// as new, only what is shown.
enum ChangeWindow: Hashable, Identifiable, Sendable {
    /// Everything the user has not already seen.
    case lastVisit
    /// A rolling window ending now.
    case days(Int)
    /// A fixed date the user picked.
    case custom(Date)

    /// The windows offered in the menu. `custom` is reached through the date
    /// picker rather than listed, since it has no single value to list.
    static let offered: [ChangeWindow] = [
        .lastVisit, .days(7), .days(30), .days(90), .days(365)
    ]

    var id: String {
        switch self {
        case .lastVisit: "lastVisit"
        case .days(let count): "days-\(count)"
        case .custom(let date): "custom-\(date.timeIntervalSince1970)"
        }
    }

    var displayName: String {
        switch self {
        case .lastVisit: "Last visit"
        case .days(365): "1 year"
        case .days(let count): "\(count) days"
        case .custom(let date): Format.shortDate(date)
        }
    }

    /// The moment the window opens, or `nil` when no window can be formed.
    ///
    /// Only `.lastVisit` on a first visit returns `nil`, and it means "there is
    /// no prior visit to measure from" rather than "measure from the beginning
    /// of time". Callers must treat the two differently: reporting a company's
    /// entire history as new on first open would be false.
    func startDate(lastVisit: Date?, now: Date = .now) -> Date? {
        switch self {
        case .lastVisit:
            lastVisit
        case .days(let count):
            Calendar.current.date(byAdding: .day, value: -count, to: now)
        case .custom(let date):
            date
        }
    }

    /// Whether this window reaches further back than the user's last visit,
    /// and so is asking for history the last-visit default would have hidden.
    func widensPast(lastVisit: Date?, now: Date = .now) -> Bool {
        guard case .lastVisit = self else {
            guard let start = startDate(lastVisit: lastVisit, now: now) else { return false }
            guard let lastVisit else { return true }
            return start < lastVisit
        }
        return false
    }
}
