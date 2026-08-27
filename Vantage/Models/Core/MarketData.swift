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
}
