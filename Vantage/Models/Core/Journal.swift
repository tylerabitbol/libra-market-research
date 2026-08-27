import Foundation
import SwiftData

/// A dated research note (Section 16).
///
/// The point of this model is the last two fields. Recording what you expected
/// and what would prove you wrong — *before* the outcome is known — is what
/// makes the later comparison honest rather than a memory exercise.
@Model
final class JournalEntry {
    var security: Security?
    var createdAt: Date
    var updatedAt: Date

    var title: String
    var notes: String
    var thesis: String
    var concerns: String
    var catalysts: String
    var risks: String
    /// "What I expect to happen."
    var expectation: String
    /// "What would prove me wrong." The disconfirming condition, written down
    /// in advance so it can't be quietly revised later.
    var falsification: String

    /// The state of the world when the note was written, so the app can show
    /// "since your note: price +8.2%, revenue estimate +3.1%, sector +2.4%".
    /// Captured at write time because reconstructing it later is unreliable.
    var priceAtEntry: Double?
    var sectorIndexAtEntry: Double?
    var marketIndexAtEntry: Double?

    init(
        security: Security? = nil,
        createdAt: Date = .now,
        title: String = "",
        notes: String = "",
        thesis: String = "",
        concerns: String = "",
        catalysts: String = "",
        risks: String = "",
        expectation: String = "",
        falsification: String = "",
        priceAtEntry: Double? = nil,
        sectorIndexAtEntry: Double? = nil,
        marketIndexAtEntry: Double? = nil
    ) {
        self.security = security
        self.createdAt = createdAt
        self.updatedAt = createdAt
        self.title = title
        self.notes = notes
        self.thesis = thesis
        self.concerns = concerns
        self.catalysts = catalysts
        self.risks = risks
        self.expectation = expectation
        self.falsification = falsification
        self.priceAtEntry = priceAtEntry
        self.sectorIndexAtEntry = sectorIndexAtEntry
        self.marketIndexAtEntry = marketIndexAtEntry
    }
}
