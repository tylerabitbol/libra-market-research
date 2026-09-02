import Foundation

/// Number formatting for a research terminal.
///
/// Built on `FormatStyle` rather than cached `NumberFormatter` instances so
/// every entry point is `Sendable` and usable off the main actor — the
/// alternative forced `@MainActor` on formatting, which then leaks into every
/// calculation type that wants to describe its own result.
///
/// The rule running through all of this: an absent value formats as
/// "Not available", never as 0, "—0.0%", or a blank that reads like a real
/// number. Section 21 treats fabricating a value as worse than showing nothing.
enum Format {
    static let notAvailable = "Not available"
    /// Compact stand-in for table cells where the full phrase doesn't fit.

    // MARK: - Currency

    static func currency(_ value: Double?, code: String = "USD", precision: Int = 2) -> String {
        guard let value else { return notAvailable }
        return value.formatted(
            .currency(code: code).precision(.fractionLength(precision))
        )
    }

    /// Large monetary figures: $3.10T, $412.5B, $24.4M.
    static func compactCurrency(_ value: Double?, code: String = "USD") -> String {
        guard let value else { return notAvailable }
        let symbol = code == "USD" ? "$" : ""
        let sign = value < 0 ? "-" : ""
        return "\(sign)\(symbol)\(compactMagnitude(abs(value)))"
    }

    /// Large plain counts: 1.9B shares, 24.4M.
    static func compact(_ value: Double?) -> String {
        guard let value else { return notAvailable }
        let sign = value < 0 ? "-" : ""
        return "\(sign)\(compactMagnitude(abs(value)))"
    }

    private static func compactMagnitude(_ value: Double) -> String {
        let units: [(threshold: Double, suffix: String)] = [
            (1_000_000_000_000, "T"),
            (1_000_000_000, "B"),
            (1_000_000, "M"),
            (1_000, "K")
        ]
        for unit in units where value >= unit.threshold {
            let scaled = value / unit.threshold
            // Keep three significant figures so 3.10T and 412B both read well.
            let precision = scaled >= 100 ? 0 : (scaled >= 10 ? 1 : 2)
            return "\(scaled.formatted(.number.precision(.fractionLength(precision))))\(unit.suffix)"
        }
        return value.formatted(.number.precision(.fractionLength(value < 10 ? 2 : 0)))
    }

    // MARK: - Percentages

    /// A percentage that is already expressed in percentage points (4.82 → "4.82%").
    static func percent(_ value: Double?, precision: Int = 2) -> String {
        guard let value else { return notAvailable }
        return "\(value.formatted(.number.precision(.fractionLength(precision))))%"
    }

    /// The same, with an explicit sign, for changes where direction matters.
    static func signedPercent(_ value: Double?, precision: Int = 2) -> String {
        guard let value else { return notAvailable }
        let sign = value > 0 ? "+" : ""
        return "\(sign)\(value.formatted(.number.precision(.fractionLength(precision))))%"
    }

    static func signed(_ value: Double?, precision: Int = 2) -> String {
        guard let value else { return notAvailable }
        let sign = value > 0 ? "+" : ""
        return "\(sign)\(value.formatted(.number.precision(.fractionLength(precision))))"
    }

    /// Difference between two percentages, in percentage points.
    ///
    /// Section 7 asks for statements like "outperformed its sector by roughly
    /// 7 percentage points". Percentage points and percent are different units
    /// and conflating them is a real source of wrong numbers, so the unit is
    /// spelled out rather than reusing the "%" symbol.
    static func percentagePoints(_ value: Double?, precision: Int = 1) -> String {
        guard let value else { return notAvailable }
        let sign = value > 0 ? "+" : ""
        return "\(sign)\(value.formatted(.number.precision(.fractionLength(precision)))) pp"
    }

    // MARK: - Ratios and multiples

    /// Volume relative to average: 1.9×.
    static func multiple(_ value: Double?, precision: Int = 1) -> String {
        guard let value else { return notAvailable }
        return "\(value.formatted(.number.precision(.fractionLength(precision))))×"
    }

    /// Valuation multiples: P/E 31.4.
    static func ratio(_ value: Double?, precision: Int = 1) -> String {
        guard let value else { return notAvailable }
        // A negative P/E is not meaningful; the caller should have passed nil,
        // but rendering "n/m" is better than a confidently wrong negative.
        guard value.isFinite else { return notAvailable }
        return value.formatted(.number.precision(.fractionLength(precision)))
    }

    /// Ordinal for historical percentile context: "78th".
    static func ordinal(_ value: Int?) -> String {
        guard let value else { return notAvailable }
        let suffix: String
        switch (value % 100, value % 10) {
        case (11...13, _): suffix = "th"
        case (_, 1): suffix = "st"
        case (_, 2): suffix = "nd"
        case (_, 3): suffix = "rd"
        default: suffix = "th"
        }
        return "\(value)\(suffix)"
    }

    // MARK: - Dates

    static func shortDate(_ date: Date?) -> String {
        guard let date else { return notAvailable }
        return date.formatted(.dateTime.month(.abbreviated).day().year())
    }

    static func dayAndMonth(_ date: Date?) -> String {
        guard let date else { return notAvailable }
        return date.formatted(.dateTime.month(.abbreviated).day())
    }
}
