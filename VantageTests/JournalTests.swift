import Testing
import Foundation
import SwiftData
@testable import Vantage

/// Section 16: the journal, and what happened since a note was written.
///
/// The comparison must be arithmetic and stop there. A tool that graded a
/// thesis would be marking its own homework with the user's money, and the
/// tests below check that the falsification condition is restated rather than
/// judged.
@Suite("Research journal", .serialized)
struct JournalTests {

    private func makeStore() throws -> (SnapshotStore, ModelContainer) {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()
        return (SnapshotStore(modelContainer: container), container)
    }

    private func entry(_ title: String = "First look", at created: Date = .now,
                       price: Double? = 100) -> JournalEntryDTO {
        JournalEntryDTO(
            createdAt: created, title: title,
            thesis: "Margins should widen as the new plant ramps.",
            expectation: "Gross margin above 40% within four quarters.",
            falsification: "Two consecutive quarters of margin below 35%.",
            priceAtEntry: price, sectorIndexAtEntry: 200, marketIndexAtEntry: 4_000)
    }

    // MARK: - Storage

    @Test("A note round-trips through the store")
    func notesRoundTrip() async throws {
        let (store, _) = try makeStore()
        try await store.record(journal: entry(), symbol: "TEST")

        let stored = try await store.journal(symbol: "TEST")
        #expect(stored.count == 1)
        #expect(stored.first?.thesis.contains("new plant") == true)
        #expect(stored.first?.falsification.contains("below 35%") == true)
        #expect(stored.first?.priceAtEntry == 100)
    }

    @Test("Editing a note revises it rather than adding a second")
    func editingUpdatesInPlace() async throws {
        let (store, _) = try makeStore()
        let created = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.record(journal: entry("Draft", at: created), symbol: "TEST")

        var revised = entry("Revised", at: created)
        revised.notes = "Second thoughts."
        try await store.record(journal: revised, symbol: "TEST")

        let stored = try await store.journal(symbol: "TEST")
        // The one place updating is right: a note is the user's own text, and
        // there is no restatement to preserve the way there is for a filing.
        #expect(stored.count == 1)
        #expect(stored.first?.title == "Revised")
        #expect(stored.first?.notes == "Second thoughts.")
    }

    @Test("Notes come back newest first")
    func notesAreOrdered() async throws {
        let (store, _) = try makeStore()
        let base = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.record(journal: entry("Older", at: base), symbol: "TEST")
        try await store.record(journal: entry("Newer", at: base.addingTimeInterval(86_400)),
                               symbol: "TEST")
        #expect(try await store.journal(symbol: "TEST").map(\.title) == ["Newer", "Older"])
    }

    @Test("A note is only recorded for a security the user follows")
    func unknownSymbolIsNotRecorded() async throws {
        let (store, _) = try makeStore()
        try await store.record(journal: entry(), symbol: "NOTFOLLOWED")
        #expect(try await store.journal(symbol: "NOTFOLLOWED").isEmpty)
    }

    @Test("Deleting removes the note")
    func deletion() async throws {
        let (store, _) = try makeStore()
        let created = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.record(journal: entry(at: created), symbol: "TEST")
        try await store.deleteJournal(symbol: "TEST", createdAt: created)
        #expect(try await store.journal(symbol: "TEST").isEmpty)
    }

    // MARK: - Comparison

    @Test("The comparison reports price, market and sector against the captured baseline")
    func comparisonArithmetic() throws {
        let comparison = ThesisComparison(
            entry: entry(price: 100), priceNow: 120, marketNow: 4_200, sectorNow: 210,
            sectorName: "Information Technology", eventsSince: [])

        #expect(comparison.priceChange == 20)
        #expect(comparison.marketChange == 5)
        #expect(comparison.sectorChange == 5)
        #expect(comparison.versusMarket == 15)

        let claim = try #require(comparison.claim)
        #expect(claim.kind == .calculation)
        #expect(claim.text.contains("+20.0%"))
        #expect(claim.text.contains("Information Technology"))
    }

    @Test("The falsification condition is restated, never judged")
    func falsificationIsNotGraded() throws {
        let comparison = ThesisComparison(
            entry: entry(), priceNow: 50, marketNow: 4_400, sectorNow: 190,
            sectorName: nil, eventsSince: [])

        // The price has halved and the app still declines to say the thesis is
        // broken. Deciding that is the reason the field exists.
        let prompt = try #require(comparison.falsificationPrompt)
        #expect(prompt.contains("below 35%"))
        #expect(!prompt.lowercased().contains("wrong now"))
        #expect(!prompt.lowercased().contains("thesis is"))
        #expect(comparison.claim?.kind == .calculation,
                "What happened is arithmetic; what it means is not the app's to say")
    }

    @Test("A note written with no price has nothing to compare against")
    func missingBaselineIsHonest() {
        let comparison = ThesisComparison(
            entry: entry(price: nil), priceNow: 120, marketNow: nil, sectorNow: nil,
            sectorName: nil, eventsSince: [])
        // Reconstructing the baseline later would move it in whichever
        // direction happened to flatter the note.
        #expect(!comparison.hasComparison)
        #expect(comparison.claim == nil)
    }

    @Test("An empty note is not saved")
    func emptyNotesAreRejected() {
        #expect(JournalEntryDTO().isEmpty)
        #expect(!JournalEntryDTO(title: "Something").isEmpty)
    }
}
