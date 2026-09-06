import Foundation
import SwiftUI

/// Backs the screener (Section 14).
///
/// Reads the store and nothing else. No rule triggers a fetch, so running a
/// screen costs nothing against any provider's budget — which is what makes it
/// usable at all on a free tier.
@Observable
@MainActor
final class ScreenerViewModel {
    private(set) var subjects: [ScreenSubject] = []
    private(set) var isLoading = false
    private(set) var loadFailure: String?

    var screen = Screen()
    private(set) var savedScreens: [Screen] = SavedScreens.load()

    var results: [ScreenSubject] { screen.run(over: subjects) }

    /// How many held securities a rule could not be tested against.
    ///
    /// Surfaced rather than hidden: a screen quietly dropping the securities it
    /// lacked figures for reports a smaller universe than the user thinks they
    /// searched.
    var untestableCount: Int { screen.untestable(in: subjects) }

    func load(snapshots: SnapshotStore?) async {
        guard let snapshots else {
            subjects = []
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            subjects = try await snapshots.screenSubjects()
            loadFailure = nil
        } catch {
            loadFailure = error.localizedDescription
        }
    }

    func addRule() {
        screen.rules.append(ScreenRule())
    }

    func removeRules(at offsets: IndexSet) {
        screen.rules.remove(atOffsets: offsets)
    }

    func saveCurrentScreen() {
        guard !screen.rules.isEmpty else { return }
        var toSave = screen
        if toSave.name.trimmingCharacters(in: .whitespaces).isEmpty {
            toSave.name = "Screen \(savedScreens.count + 1)"
        }
        savedScreens.removeAll { $0.id == toSave.id }
        savedScreens.append(toSave)
        SavedScreens.save(savedScreens)
        screen = toSave
    }

    func apply(_ saved: Screen) {
        screen = saved
    }

    func delete(_ saved: Screen) {
        savedScreens.removeAll { $0.id == saved.id }
        SavedScreens.save(savedScreens)
    }

    func newScreen() {
        screen = Screen()
    }
}
