import Foundation
import Observation

/// The cross-security feed of everything the detectors have found.
///
/// Reads only from the store — it issues no network requests. Events are a
/// by-product of visiting securities, which keeps this screen free in a
/// rate-limited app and means the feed reflects what the user actually follows
/// rather than a universe they never asked about.
@Observable
@MainActor
final class ResearchViewModel {
    private(set) var events: [SecurityEvent] = []
    private(set) var isLoading = false
    private(set) var loadFailure: String?

    var sort: Sort = .mostRecent
    var kindFilter: EvidenceCategory?

    enum Sort: String, CaseIterable, Identifiable {
        case mostRecent = "Most recent"
        case mostUnusual = "Most unusual"

        var id: String { rawValue }
    }

    var visibleEvents: [SecurityEvent] {
        let filtered = kindFilter.map { category in
            events.filter { $0.event.kind.evidenceCategory == category }
        } ?? events

        switch sort {
        case .mostRecent:
            return filtered.sorted { $0.event.occurredAt > $1.event.occurredAt }
        case .mostUnusual:
            // Ties broken by recency so the order is stable rather than
            // arbitrary — several events can share an unusualness of 1.0.
            return filtered.sorted {
                if $0.event.unusualness == $1.event.unusualness {
                    return $0.event.occurredAt > $1.event.occurredAt
                }
                return $0.event.unusualness > $1.event.unusualness
            }
        }
    }

    /// Categories actually present, so the filter never offers an empty bucket.
    var availableCategories: [EvidenceCategory] {
        let present = Set(events.map(\.event.kind.evidenceCategory))
        return EvidenceCategory.allCases.filter(present.contains)
    }

    func load(snapshots: SnapshotStore?) async {
        guard let snapshots else {
            events = []
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            events = try await snapshots.recentEvents()
            loadFailure = nil
        } catch {
            loadFailure = error.localizedDescription
        }
    }
}
