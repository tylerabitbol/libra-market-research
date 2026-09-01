import Foundation

/// A research note as a value, safe to cross the actor boundary.
struct JournalEntryDTO: Sendable, Hashable, Identifiable {
    /// The moment it was written. Natural key for a personal journal — two
    /// notes on one security at the same instant is not a case worth modelling.
    var id: Date { createdAt }

    let createdAt: Date
    var updatedAt: Date
    var title: String
    var notes: String
    var thesis: String
    var concerns: String
    var catalysts: String
    var risks: String
    /// What you expect to happen.
    var expectation: String
    /// What would prove you wrong. Written in advance so it cannot be quietly
    /// revised once the outcome is known.
    var falsification: String

    var priceAtEntry: Double?
    var sectorIndexAtEntry: Double?
    var marketIndexAtEntry: Double?

    init(
        createdAt: Date = .now,
        updatedAt: Date = .now,
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
        self.createdAt = createdAt
        self.updatedAt = updatedAt
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

    var isEmpty: Bool {
        [title, notes, thesis, concerns, catalysts, risks, expectation, falsification]
            .allSatisfy { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }
}

/// What has happened since a note was written — Section 16's whole point.
///
/// The comparison is arithmetic. It reports what the price, the sector and the
/// market did, and what the detectors recorded in the interval; it does not
/// grade the thesis. Whether you were right is a judgement about reasoning, and
/// a tool that scored it would be marking its own homework with your money.
///
/// The reference values are the ones captured *when the note was written*, not
/// reconstructed later. Reconstruction is unreliable — the store may not hold a
/// bar for that exact session — and quietly picking a nearby day would move the
/// baseline in whichever direction happened to flatter the note.
struct ThesisComparison: Sendable, Hashable {
    let entry: JournalEntryDTO
    let priceNow: Double?
    let marketNow: Double?
    let sectorNow: Double?
    let sectorName: String?
    /// Changes the detectors recorded after the note was written.
    let eventsSince: [DetectedEventDTO]

    var priceChange: Double? { change(from: entry.priceAtEntry, to: priceNow) }
    var marketChange: Double? { change(from: entry.marketIndexAtEntry, to: marketNow) }
    var sectorChange: Double? { change(from: entry.sectorIndexAtEntry, to: sectorNow) }

    /// Percentage points against the market, when both legs exist.
    var versusMarket: Double? {
        guard let priceChange, let marketChange else { return nil }
        return priceChange - marketChange
    }

    var hasComparison: Bool { priceChange != nil }

    private func change(from start: Double?, to end: Double?) -> Double? {
        guard let start, let end, start != 0 else { return nil }
        return (end - start) / start * 100
    }

    /// A CALCULATION: it states what moved, and stops there.
    var claim: Claim? {
        guard let priceChange else { return nil }
        var text = "Since this note on \(Format.shortDate(entry.createdAt)), the price is "
            + "\(Format.signedPercent(priceChange, precision: 1))"
        if let marketChange {
            text += ", the S&P 500 \(Format.signedPercent(marketChange, precision: 1))"
        }
        if let sectorChange, let sectorName {
            text += ", and \(sectorName) \(Format.signedPercent(sectorChange, precision: 1))"
        }
        text += "."

        var inputs: [Derivation.Input] = [
            .init(name: "price when written",
                  value: Format.currency(entry.priceAtEntry), source: nil),
            .init(name: "price now", value: Format.currency(priceNow), source: nil)
        ]
        if let versusMarket {
            inputs.append(.init(name: "against the market",
                                value: Format.percentagePoints(versusMarket), source: nil))
        }

        return Claim(
            kind: .calculation,
            text: text,
            derivation: Derivation(
                formula: "(now - whenWritten) ÷ whenWritten",
                inputs: inputs,
                result: Format.signedPercent(priceChange, precision: 1)))
    }

    /// Shown beside the note's own falsification condition.
    ///
    /// The app restates what you said would prove you wrong; it does not decide
    /// whether it has. That judgement is the reason the field exists.
    var falsificationPrompt: String? {
        let condition = entry.falsification.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !condition.isEmpty else { return nil }
        return "You wrote that this would prove you wrong: \(condition)"
    }
}
