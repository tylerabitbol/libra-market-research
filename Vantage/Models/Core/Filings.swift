import Foundation
import SwiftData

/// An SEC filing. The spec treats EDGAR as a primary source, so every filing
/// keeps its original document URL — the app summarises, it never replaces the
/// document.
@Model
final class FilingRecord {
    var security: Security?

    @Attribute(.unique) var accessionNumber: String
    var formType: String
    var filedAt: Date
    var periodOfReport: Date?
    var primaryDocumentURL: URL?
    var filingIndexURL: URL?
    var observedAt: Date

    /// Set once the app has extracted figures from this filing, so the
    /// "what changed in this filing" work isn't repeated on every refresh.
    var analyzedAt: Date?

    init(
        security: Security? = nil,
        accessionNumber: String,
        formType: String,
        filedAt: Date,
        periodOfReport: Date? = nil,
        primaryDocumentURL: URL? = nil,
        filingIndexURL: URL? = nil,
        observedAt: Date = .now,
        analyzedAt: Date? = nil
    ) {
        self.security = security
        self.accessionNumber = accessionNumber
        self.formType = formType
        self.filedAt = filedAt
        self.periodOfReport = periodOfReport
        self.primaryDocumentURL = primaryDocumentURL
        self.filingIndexURL = filingIndexURL
        self.observedAt = observedAt
        self.analyzedAt = analyzedAt
    }

    var isPeriodicReport: Bool { ["10-K", "10-Q", "20-F", "40-F"].contains(formType) }
    var isCurrentReport: Bool { formType.hasPrefix("8-K") }
    var isInsiderForm: Bool { ["3", "4", "5"].contains(formType.trimmingCharacters(in: .whitespaces)) }
}

/// A Form 4 insider transaction line.
///
/// Section 10 requires distinguishing routine automatic sales from genuine
/// open-market decisions — conflating them is the single most common way
/// insider data gets misread.
@Model
final class InsiderTransaction {
    var security: Security?

    var accessionNumber: String
    var insiderName: String
    var insiderTitle: String?
    var isDirector: Bool
    var isOfficer: Bool
    var isTenPercentOwner: Bool

    var transactionDate: Date
    var filedAt: Date
    /// Form 4 transaction code: P purchase, S sale, A grant/award, M option
    /// exercise, F tax withholding, G gift, and others.
    var transactionCode: String
    /// True when the filing footnotes a Rule 10b5-1 plan, meaning the trade was
    /// scheduled in advance and carries no signal about current sentiment.
    var isUnderTradingPlan: Bool

    var shares: Double?
    var pricePerShare: Double?
    var sharesOwnedAfter: Double?
    var observedAt: Date

    init(
        security: Security? = nil,
        accessionNumber: String,
        insiderName: String,
        insiderTitle: String? = nil,
        isDirector: Bool = false,
        isOfficer: Bool = false,
        isTenPercentOwner: Bool = false,
        transactionDate: Date,
        filedAt: Date,
        transactionCode: String,
        isUnderTradingPlan: Bool = false,
        shares: Double? = nil,
        pricePerShare: Double? = nil,
        sharesOwnedAfter: Double? = nil,
        observedAt: Date = .now
    ) {
        self.security = security
        self.accessionNumber = accessionNumber
        self.insiderName = insiderName
        self.insiderTitle = insiderTitle
        self.isDirector = isDirector
        self.isOfficer = isOfficer
        self.isTenPercentOwner = isTenPercentOwner
        self.transactionDate = transactionDate
        self.filedAt = filedAt
        self.transactionCode = transactionCode
        self.isUnderTradingPlan = isUnderTradingPlan
        self.shares = shares
        self.pricePerShare = pricePerShare
        self.sharesOwnedAfter = sharesOwnedAfter
        self.observedAt = observedAt
    }

    var approximateValue: Double? {
        guard let shares, let pricePerShare else { return nil }
        return shares * pricePerShare
    }

    var nature: InsiderTransactionNature {
        if isUnderTradingPlan { return .scheduledPlan }
        switch transactionCode.uppercased() {
        case "P": return .openMarketPurchase
        case "S": return .openMarketSale
        case "A": return .grant
        case "M": return .optionExercise
        case "F": return .taxWithholding
        case "G": return .gift
        default: return .other
        }
    }
}

enum InsiderTransactionNature: String, Sendable, CaseIterable {
    case openMarketPurchase
    case openMarketSale
    case scheduledPlan
    case grant
    case optionExercise
    case taxWithholding
    case gift
    case other

    var displayName: String {
        switch self {
        case .openMarketPurchase: "Open-market purchase"
        case .openMarketSale: "Open-market sale"
        case .scheduledPlan: "Scheduled (10b5-1 plan)"
        case .grant: "Grant or award"
        case .optionExercise: "Option exercise"
        case .taxWithholding: "Shares withheld for tax"
        case .gift: "Gift"
        case .other: "Other"
        }
    }

    /// Whether this reflects a discretionary decision by the insider at that
    /// moment. Grants, tax withholding and scheduled plan sales do not.
    var isDiscretionary: Bool {
        switch self {
        case .openMarketPurchase, .openMarketSale, .gift: true
        case .scheduledPlan, .grant, .optionExercise, .taxWithholding, .other: false
        }
    }
}
