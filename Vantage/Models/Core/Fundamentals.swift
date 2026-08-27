import Foundation
import SwiftData

/// A single reported financial figure for one period, as filed.
///
/// Modelled as narrow rows rather than a wide "income statement" object on
/// purpose. SEC XBRL data arrives fact-by-fact, companies report different
/// subsets of concepts, and issuers restate prior periods. A row per fact lets
/// us store exactly what was reported, keep restatements alongside the
/// originals, and show "not available" honestly when a concept is absent —
/// rather than defaulting a missing field to zero, which Section 21 forbids.
@Model
final class FinancialFactRecord {
    var security: Security?

    /// Normalised concept name, e.g. "Revenues", "NetIncomeLoss".
    var concept: String
    /// The raw taxonomy tag as filed, e.g. "us-gaap:RevenueFromContractWith…".
    /// Kept so a surprising number can be traced back to the exact XBRL element.
    var rawTag: String?

    var periodStart: Date?
    var periodEnd: Date
    var fiscalYear: Int
    /// 1–4, or nil for annual figures.
    var fiscalQuarter: Int?
    var isAnnual: Bool

    var value: Double
    var unit: String

    /// When the figure reached the public record.
    var filedAt: Date?
    /// Accession number of the filing it came from, linking to `FilingRecord`.
    var accessionNumber: String?
    /// When we retrieved it. A restatement produces a second row for the same
    /// period with a later `observedAt` — we never overwrite the original.
    var observedAt: Date
    var providerRaw: String

    init(
        security: Security? = nil,
        concept: String,
        rawTag: String? = nil,
        periodStart: Date? = nil,
        periodEnd: Date,
        fiscalYear: Int,
        fiscalQuarter: Int? = nil,
        isAnnual: Bool,
        value: Double,
        unit: String,
        filedAt: Date? = nil,
        accessionNumber: String? = nil,
        observedAt: Date = .now,
        provider: DataProviderID = .sec
    ) {
        self.security = security
        self.concept = concept
        self.rawTag = rawTag
        self.periodStart = periodStart
        self.periodEnd = periodEnd
        self.fiscalYear = fiscalYear
        self.fiscalQuarter = fiscalQuarter
        self.isAnnual = isAnnual
        self.value = value
        self.unit = unit
        self.filedAt = filedAt
        self.accessionNumber = accessionNumber
        self.observedAt = observedAt
        self.providerRaw = provider.rawValue
    }

    var provider: DataProviderID { DataProviderID(rawValue: providerRaw) ?? .sec }
}

/// The financial concepts the app understands, mapped to the US-GAAP tags
/// issuers actually use. Several concepts have multiple accepted tags because
/// companies genuinely differ; resolution order matters and is handled in the
/// SEC provider, not here.
enum FinancialConcept: String, CaseIterable, Sendable {
    case revenue
    case costOfRevenue
    case grossProfit
    case operatingIncome
    case netIncome
    case earningsPerShareDiluted
    case sharesOutstandingDiluted
    case operatingCashFlow
    case capitalExpenditures
    case cashAndEquivalents
    case shortTermInvestments
    case totalDebt
    case totalAssets
    case totalLiabilities
    case stockholdersEquity

    var displayName: String {
        switch self {
        case .revenue: "Revenue"
        case .costOfRevenue: "Cost of revenue"
        case .grossProfit: "Gross profit"
        case .operatingIncome: "Operating income"
        case .netIncome: "Net income"
        case .earningsPerShareDiluted: "EPS (diluted)"
        case .sharesOutstandingDiluted: "Diluted shares"
        case .operatingCashFlow: "Operating cash flow"
        case .capitalExpenditures: "Capital expenditures"
        case .cashAndEquivalents: "Cash and equivalents"
        case .shortTermInvestments: "Short-term investments"
        case .totalDebt: "Total debt"
        case .totalAssets: "Total assets"
        case .totalLiabilities: "Total liabilities"
        case .stockholdersEquity: "Stockholders' equity"
        }
    }
}
