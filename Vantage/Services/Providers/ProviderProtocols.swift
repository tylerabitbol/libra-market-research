import Foundation

// MARK: - Transport-neutral value types
//
// Providers return these rather than SwiftData models. Keeping the boundary at
// plain `Sendable` structs means provider work can run off the main actor, and
// swapping Finnhub for another vendor touches only the mapping layer — the
// requirement in Section 18.

struct QuoteDTO: Sendable, Hashable {
    let symbol: String
    let last: Double
    let open: Double?
    let high: Double?
    let low: Double?
    let previousClose: Double?
    let volume: Double?
    let quoteTime: Date?

    /// Nil when the provider didn't supply a previous close. Returning nil
    /// rather than 0 keeps an unknown change from rendering as "flat".
    var change: Double? {
        guard let previousClose else { return nil }
        return last - previousClose
    }

    var changePercent: Double? {
        guard let previousClose, previousClose != 0 else { return nil }
        return (last - previousClose) / previousClose * 100
    }
}

struct PriceBarDTO: Sendable, Hashable {
    let date: Date
    let open: Double
    let high: Double
    let low: Double
    let close: Double
    let volume: Double?
    let adjustedClose: Double?
}

struct CompanyProfileDTO: Sendable, Hashable {
    let symbol: String
    let name: String
    let exchange: String?
    let sector: String?
    let industry: String?
    let currency: String?
    let marketCap: Double?
    let sharesOutstanding: Double?
    let cik: String?
}

/// How long a reported figure covers.
///
/// XBRL files cumulative year-to-date figures under the *same tag* as discrete
/// quarterly ones: a Q3 10-Q carries both the three-month revenue and the
/// nine-month running total. Reading a nine-month total as a quarter makes Q3
/// look roughly three times Q2 and corrupts every growth rate computed from it,
/// so duration is classified explicitly rather than inferred from `fp`.
enum FiscalPeriodKind: String, Sendable, Hashable, CaseIterable {
    /// A single quarter, roughly 90 days.
    case quarter
    /// Six months cumulative.
    case halfYear
    /// Nine months cumulative.
    case nineMonth
    /// A full year, roughly 365 days.
    case annual
    /// A balance-sheet item: a point in time, with no duration.
    case instant

    /// Classifies by day count, with windows wide enough for 52/53-week fiscal
    /// calendars and companies whose quarters are not exactly 13 weeks.
    static func classify(days: Double?) -> FiscalPeriodKind {
        guard let days else { return .instant }
        switch days {
        case ..<140: return .quarter
        case ..<230: return .halfYear
        case ..<310: return .nineMonth
        default: return .annual
        }
    }

    /// The kinds worth showing directly. Cumulative periods are kept in the
    /// store but excluded from quarter-over-quarter work.
    var isDiscrete: Bool { self == .quarter || self == .annual || self == .instant }
}

struct FinancialFactDTO: Sendable, Hashable {
    let concept: FinancialConcept
    let rawTag: String?
    let periodStart: Date?
    let periodEnd: Date
    let fiscalYear: Int
    let fiscalQuarter: Int?
    let isAnnual: Bool
    let periodKind: FiscalPeriodKind
    let value: Double
    let unit: String
    let filedAt: Date?
    let accessionNumber: String?
}

struct FilingDTO: Sendable, Hashable {
    let accessionNumber: String
    let formType: String
    let filedAt: Date
    let periodOfReport: Date?
    let primaryDocumentURL: URL?
    let filingIndexURL: URL?
}

struct InsiderTransactionDTO: Sendable, Hashable {
    let accessionNumber: String
    let insiderName: String
    let insiderTitle: String?
    let isDirector: Bool
    let isOfficer: Bool
    let isTenPercentOwner: Bool
    let transactionDate: Date
    let filedAt: Date
    let transactionCode: String
    let isUnderTradingPlan: Bool
    let shares: Double?
    let pricePerShare: Double?
    let sharesOwnedAfter: Double?
}

struct NewsItemDTO: Sendable, Hashable {
    let id: String
    let headline: String
    let summary: String?
    let source: String?
    let url: URL?
    let publishedAt: Date
    let relatedSymbols: [String]
}

struct AnalystEstimateDTO: Sendable, Hashable {
    let period: Date
    let fiscalYear: Int
    let fiscalQuarter: Int?
    let metric: EstimateMetric
    let consensus: Double?
    let high: Double?
    let low: Double?
    let analystCount: Int?
    /// When this consensus was observed. Revisions are detected by comparing
    /// consecutive observations, so this field is what makes Section 8 work.
    let asOf: Date
}

enum EstimateMetric: String, Sendable, CaseIterable {
    case eps, revenue, priceTarget

    var displayName: String {
        switch self {
        case .eps: "EPS"
        case .revenue: "Revenue"
        case .priceTarget: "Price target"
        }
    }
}

struct RatingSnapshotDTO: Sendable, Hashable {
    let asOf: Date
    let strongBuy: Int
    let buy: Int
    let hold: Int
    let sell: Int
    let strongSell: Int

    var total: Int { strongBuy + buy + hold + sell + strongSell }
}

struct MacroObservationDTO: Sendable, Hashable {
    let seriesID: String
    let date: Date
    let value: Double
}

// MARK: - Provider protocols

/// Marker for anything the app fetches from, so capability reporting and
/// error surfaces can treat providers uniformly.
protocol DataProvider: Sendable {
    var id: DataProviderID { get }
    /// Whether the provider has everything it needs (keys, contact email) to
    /// make requests. Checked before dispatch so the UI can show an actionable
    /// "add your key" state instead of a failed request.
    func isConfigured() async -> Bool
}

protocol MarketDataProvider: DataProvider {
    func quote(symbol: String) async throws -> QuoteDTO
    func bars(
        symbol: String,
        resolution: BarResolution,
        from: Date,
        to: Date
    ) async throws -> [PriceBarDTO]
    func profile(symbol: String) async throws -> CompanyProfileDTO
    func search(query: String) async throws -> [CompanyProfileDTO]
}

protocol FundamentalsProvider: DataProvider {
    /// Historical reported figures. `since` bounds how far back to pull.
    func facts(
        symbol: String,
        cik: String?,
        concepts: [FinancialConcept],
        since: Date?
    ) async throws -> [FinancialFactDTO]
}

protocol AnalystDataProvider: DataProvider {
    func estimates(symbol: String, metric: EstimateMetric) async throws -> [AnalystEstimateDTO]
    func ratings(symbol: String) async throws -> [RatingSnapshotDTO]
}

protocol SECDataProvider: DataProvider {
    func resolveCIK(symbol: String) async throws -> String
    func filings(cik: String, formTypes: [String], limit: Int) async throws -> [FilingDTO]
    func insiderTransactions(cik: String, since: Date?) async throws -> [InsiderTransactionDTO]
}

protocol MacroDataProvider: DataProvider {
    func observations(
        seriesID: String,
        from: Date?,
        to: Date?
    ) async throws -> [MacroObservationDTO]
}

protocol NewsProvider: DataProvider {
    func companyNews(symbol: String, from: Date, to: Date) async throws -> [NewsItemDTO]
}
