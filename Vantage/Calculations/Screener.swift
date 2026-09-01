import Foundation

/// Section 14's screener, scoped to what is true.
///
/// The original spec implies screening a universe. Free tiers make that
/// impossible: Tiingo refills roughly one token every 80 seconds, so covering a
/// market would take days and a screen that silently examined six stocks while
/// looking like it examined six thousand is worse than no screen at all.
///
/// So this screens **what the app already holds** — the watchlist plus anything
/// visited — reading only from the store and issuing no requests whatsoever.
/// The coverage is stated on screen rather than left to be assumed, which is
/// the difference between a small honest tool and a misleading one.
struct ScreenSubject: Sendable, Hashable, Identifiable {
    var id: String { symbol }
    let symbol: String
    let name: String
    let sector: String?

    var price: Double?
    var dailyChangePercent: Double?
    /// Year-over-year revenue growth from the most recent comparable quarter.
    var revenueGrowth: Double?
    var grossMargin: Double?
    var operatingMargin: Double?
    /// Cash and equivalents less total debt.
    var netCash: Double?
    /// Rank of the most recent recorded change, 0–1.
    var latestUnusualness: Double?
    var daysSinceLastEvent: Double?

    func value(for field: ScreenField) -> Double? {
        switch field {
        case .price: price
        case .dailyChangePercent: dailyChangePercent
        case .revenueGrowth: revenueGrowth
        case .grossMargin: grossMargin
        case .operatingMargin: operatingMargin
        case .netCash: netCash
        case .unusualness: latestUnusualness
        case .daysSinceLastEvent: daysSinceLastEvent
        }
    }
}

enum ScreenField: String, Sendable, CaseIterable, Identifiable, Codable {
    case price
    case dailyChangePercent
    case revenueGrowth
    case grossMargin
    case operatingMargin
    case netCash
    case unusualness
    case daysSinceLastEvent

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .price: "Price"
        case .dailyChangePercent: "Daily change %"
        case .revenueGrowth: "Revenue growth % YoY"
        case .grossMargin: "Gross margin %"
        case .operatingMargin: "Operating margin %"
        case .netCash: "Net cash"
        case .unusualness: "Unusualness of last change"
        case .daysSinceLastEvent: "Days since last change"
        }
    }

    func format(_ value: Double) -> String {
        switch self {
        case .price: Format.currency(value)
        case .netCash: Format.compactCurrency(value)
        case .unusualness: Format.ratio(value, precision: 2)
        case .daysSinceLastEvent: "\(Int(value))"
        default: Format.percent(value, precision: 1)
        }
    }
}

enum ScreenComparison: String, Sendable, CaseIterable, Identifiable, Codable {
    case greaterThan
    case lessThan

    var id: String { rawValue }
    var displayName: String { self == .greaterThan ? "is above" : "is below" }
    var symbol: String { self == .greaterThan ? ">" : "<" }

    func matches(_ value: Double, _ threshold: Double) -> Bool {
        self == .greaterThan ? value > threshold : value < threshold
    }
}

struct ScreenRule: Sendable, Hashable, Identifiable, Codable {
    var id = UUID()
    var field: ScreenField = .revenueGrowth
    var comparison: ScreenComparison = .greaterThan
    var threshold: Double = 0

    /// Whether one subject satisfies this rule.
    ///
    /// A rule over a figure the app does not hold **fails**. It is tempting to
    /// pass it through so a half-populated security is not excluded, but a
    /// screen that returns companies it could not actually test is asserting
    /// something it does not know — the same fabrication as rendering an absent
    /// figure as zero.
    func matches(_ subject: ScreenSubject) -> Bool {
        guard let value = subject.value(for: field) else { return false }
        return comparison.matches(value, threshold)
    }

    var summary: String {
        "\(field.displayName) \(comparison.symbol) \(field.format(threshold))"
    }
}

enum ScreenCombinator: String, Sendable, CaseIterable, Identifiable, Codable {
    case all
    case any

    var id: String { rawValue }
    var displayName: String { self == .all ? "Match all rules" : "Match any rule" }
}

/// A named set of rules. Codable so saved screens survive a relaunch without a
/// schema migration — they are user preferences, not observations, and do not
/// belong in the append-only store.
struct Screen: Sendable, Hashable, Identifiable, Codable {
    var id = UUID()
    var name: String = ""
    var combinator: ScreenCombinator = .all
    var rules: [ScreenRule] = []

    func matches(_ subject: ScreenSubject) -> Bool {
        guard !rules.isEmpty else { return true }
        return combinator == .all
            ? rules.allSatisfy { $0.matches(subject) }
            : rules.contains { $0.matches(subject) }
    }

    func run(over subjects: [ScreenSubject]) -> [ScreenSubject] {
        subjects.filter(matches).sorted { $0.symbol < $1.symbol }
    }

    /// How many subjects a rule could not be tested against, so the coverage of
    /// a result is visible rather than assumed.
    func untestable(in subjects: [ScreenSubject]) -> Int {
        subjects.filter { subject in
            rules.contains { subject.value(for: $0.field) == nil }
        }.count
    }
}

/// Saved screens, in preferences rather than in the store.
enum SavedScreens {
    private static let key = "com.tylerabitbol.vantage.savedScreens"

    static func load(from defaults: UserDefaults = .standard) -> [Screen] {
        guard let data = defaults.data(forKey: key),
              let screens = try? JSONDecoder().decode([Screen].self, from: data)
        else { return [] }
        return screens
    }

    static func save(_ screens: [Screen], to defaults: UserDefaults = .standard) {
        guard let data = try? JSONEncoder().encode(screens) else { return }
        defaults.set(data, forKey: key)
    }
}
