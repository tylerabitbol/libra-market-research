import Foundation
import SwiftData

/// A point-in-time quote observation.
///
/// Append-only. Each fetch inserts a new row rather than overwriting the last,
/// which is what makes "what did this look like when I last opened the app"
/// answerable without a separate snapshot mechanism.
@Model
final class QuoteObservation {
    var security: Security?
    /// When we recorded this. Distinct from `quoteTime`, which is the exchange's
    /// timestamp — they differ, and the gap matters for stale-data detection.
    var observedAt: Date
    var quoteTime: Date?

    var last: Double
    var open: Double?
    var high: Double?
    var low: Double?
    var previousClose: Double?
    var volume: Double?

    var providerRaw: String

    init(
        security: Security? = nil,
        observedAt: Date = .now,
        quoteTime: Date? = nil,
        last: Double,
        open: Double? = nil,
        high: Double? = nil,
        low: Double? = nil,
        previousClose: Double? = nil,
        volume: Double? = nil,
        provider: DataProviderID = .finnhub
    ) {
        self.security = security
        self.observedAt = observedAt
        self.quoteTime = quoteTime
        self.last = last
        self.open = open
        self.high = high
        self.low = low
        self.previousClose = previousClose
        self.volume = volume
        self.providerRaw = provider.rawValue
    }

    var provider: DataProviderID { DataProviderID(rawValue: providerRaw) ?? .finnhub }

    /// Absolute and percentage change against the prior session's close.
    /// Nil when the provider didn't supply a previous close — the spec forbids
    /// inventing a value to fill the gap.
    var change: Double? {
        guard let previousClose else { return nil }
        return last - previousClose
    }

    var changePercent: Double? {
        guard let previousClose, previousClose != 0 else { return nil }
        return (last - previousClose) / previousClose * 100
    }
}

/// One OHLCV bar. Bars are immutable once written for a given
/// (security, date, resolution); a re-fetch that disagrees indicates a
/// provider restatement and is recorded as a new row with a later `observedAt`.
@Model
final class PriceBar {
    var security: Security?
    /// The session or interval this bar describes.
    var date: Date
    var resolutionRaw: String
    var observedAt: Date

    var open: Double
    var high: Double
    var low: Double
    var close: Double
    var volume: Double?
    /// Split/dividend-adjusted close where the provider supplies one. Returns
    /// computed over long windows must use this, not raw close.
    var adjustedClose: Double?

    init(
        security: Security? = nil,
        date: Date,
        resolution: BarResolution,
        observedAt: Date = .now,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double? = nil,
        adjustedClose: Double? = nil
    ) {
        self.security = security
        self.date = date
        self.resolutionRaw = resolution.rawValue
        self.observedAt = observedAt
        self.open = open
        self.high = high
        self.low = low
        self.close = close
        self.volume = volume
        self.adjustedClose = adjustedClose
    }

    var resolution: BarResolution { BarResolution(rawValue: resolutionRaw) ?? .daily }

    /// Prefers the adjusted series when available, since that is what any
    /// return or moving-average calculation should be built on.
    var analysisClose: Double { adjustedClose ?? close }
}

enum BarResolution: String, Codable, Sendable, CaseIterable {
    case oneMinute = "1"
    case fiveMinute = "5"
    case fifteenMinute = "15"
    case hourly = "60"
    case daily = "D"
    case weekly = "W"
    case monthly = "M"
}

/// The chart ranges from Section 5.
enum ChartRange: String, CaseIterable, Identifiable, Sendable {
    case oneDay = "1D"
    case fiveDay = "5D"
    case oneMonth = "1M"
    case threeMonth = "3M"
    case sixMonth = "6M"
    case oneYear = "1Y"
    case fiveYear = "5Y"

    var id: String { rawValue }

    /// The bar resolution that gives a readable chart without over-fetching.
    var resolution: BarResolution {
        switch self {
        case .oneDay: .fiveMinute
        case .fiveDay: .fifteenMinute
        case .oneMonth, .threeMonth, .sixMonth, .oneYear: .daily
        case .fiveYear: .weekly
        }
    }

    /// Whether this range needs a series finer than daily — and therefore a
    /// different vendor, with a different share of the tape behind it.
    var usesIntraday: Bool { !resolution.isDailyOrCoarser }

    var dateInterval: DateComponents {
        switch self {
        case .oneDay: DateComponents(day: -1)
        case .fiveDay: DateComponents(day: -5)
        case .oneMonth: DateComponents(month: -1)
        case .threeMonth: DateComponents(month: -3)
        case .sixMonth: DateComponents(month: -6)
        case .oneYear: DateComponents(year: -1)
        case .fiveYear: DateComponents(year: -5)
        }
    }

    func startDate(from end: Date = .now, calendar: Calendar = .current) -> Date {
        calendar.date(byAdding: dateInterval, to: end) ?? end
    }

    /// How many trading sessions an intraday range shows, if it is one.
    ///
    /// Sessions, not calendar days: five days back from a Monday afternoon
    /// reaches the previous Wednesday, so "5D" drew three sessions and called
    /// them five.
    var intradaySessions: Int? {
        switch self {
        case .oneDay: 1
        case .fiveDay: 5
        default: nil
        }
    }

    /// How far back to ask an intraday vendor for bars.
    ///
    /// Deliberately wider than the range itself, because the count that
    /// matters is sessions and the calendar does not know which days traded.
    /// The surplus is trimmed once the bars are in hand.
    func intradayFetchStart(from end: Date = .now, calendar: Calendar = .current) -> Date {
        guard let sessions = intradaySessions else {
            return startDate(from: end, calendar: calendar)
        }
        let days = sessions == 1 ? -5 : -(sessions * 2 + 4)
        return calendar.date(byAdding: DateComponents(day: days), to: end) ?? end
    }
}


/// Whether a chart can be drawn, and what to say when it cannot.
///
/// Exists because "no bars" and "still loading" were previously the same
/// state on screen, which is how 1D and 5D came to show a spinner that never
/// resolved for a range that had nothing to load.
enum ChartAvailability: Equatable, Sendable {
    case ready
    case loading
    case unavailable(String)
}


/// One point on an intraday chart, placed by position rather than by clock.
///
/// A wall-clock axis gives 17 of every 24 hours to time that did not trade:
/// on 5D three sessions occupied about a fifth of the width and the rest was
/// blank. Placing bars end to end spends the whole axis on trading, and the
/// axis labels below say which session each stretch belongs to.
struct ChartPoint: Identifiable {
    let id: Int
    let date: Date
    let close: Double
    /// Start of the trading day this point belongs to, in New York.
    let session: Date

    var position: Double { Double(id) }
}


/// A labelled position on the intraday chart's x-axis.
struct ChartAxisTick: Identifiable {
    let id: Int
    let label: String

    var position: Double { Double(id) }
}
