import Foundation
import SwiftData

/// A tradable security the user follows or has looked at.
///
/// This is the one model in the app that is genuinely mutable — a company's
/// name, sector, or CIK can be corrected over time and there is no research
/// value in retaining the old spelling. Everything that *describes* the
/// company at a point in time (price, fundamentals, filings) is stored as
/// append-only observations elsewhere.
@Model
final class Security {
    /// Ticker, uppercased. Unique within the store.
    @Attribute(.unique) var symbol: String
    var name: String
    var exchange: String?
    var sector: String?
    var industry: String?
    /// SEC Central Index Key, zero-padded to 10 digits. Required for EDGAR
    /// lookups and absent for non-US listings and most ETFs.
    var cik: String?
    var currency: String?
    /// Set for index/ETF rows used as benchmarks so they can be excluded from
    /// company-style analysis that would be meaningless for them.
    var isBenchmark: Bool

    var profileUpdatedAt: Date?
    var createdAt: Date

    @Relationship(deleteRule: .cascade, inverse: \WatchlistEntry.security)
    var watchlistEntry: WatchlistEntry?

    @Relationship(deleteRule: .cascade, inverse: \QuoteObservation.security)
    var quotes: [QuoteObservation] = []

    @Relationship(deleteRule: .cascade, inverse: \PriceBar.security)
    var priceBars: [PriceBar] = []

    @Relationship(deleteRule: .cascade, inverse: \FinancialFactRecord.security)
    var financialFacts: [FinancialFactRecord] = []

    @Relationship(deleteRule: .cascade, inverse: \FilingRecord.security)
    var filings: [FilingRecord] = []

    @Relationship(deleteRule: .cascade, inverse: \JournalEntry.security)
    var journalEntries: [JournalEntry] = []

    @Relationship(deleteRule: .cascade, inverse: \DetectedEvent.security)
    var events: [DetectedEvent] = []

    init(
        symbol: String,
        name: String,
        exchange: String? = nil,
        sector: String? = nil,
        industry: String? = nil,
        cik: String? = nil,
        currency: String? = nil,
        isBenchmark: Bool = false,
        createdAt: Date = .now
    ) {
        self.symbol = symbol.uppercased()
        self.name = name
        self.exchange = exchange
        self.sector = sector
        self.industry = industry
        self.cik = cik
        self.currency = currency
        self.isBenchmark = isBenchmark
        self.createdAt = createdAt
    }
}

/// A security's place on the user's watchlist, plus their own priority ordering.
@Model
final class WatchlistEntry {
    var security: Security?
    var addedAt: Date
    /// User-defined priority for the "user-defined priority" sort in Section 15.
    /// Lower sorts first.
    var priority: Int
    var notes: String?

    init(security: Security? = nil, addedAt: Date = .now, priority: Int = 0, notes: String? = nil) {
        self.security = security
        self.addedAt = addedAt
        self.priority = priority
        self.notes = notes
    }
}
