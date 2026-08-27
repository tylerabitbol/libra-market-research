import Testing
import Foundation
@testable import Vantage

@Suite("Staleness and freshness")
struct FreshnessTests {
    @Test("Never-fetched data reports missing, not stale")
    func missingWhenNeverFetched() {
        #expect(StalenessPolicy.quote.evaluate(lastUpdated: nil) == .missing)
    }

    @Test("Data inside the window is fresh")
    func freshInsideWindow() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let fetched = now.addingTimeInterval(-30)
        #expect(StalenessPolicy.quote.evaluate(lastUpdated: fetched, now: now) == .fresh(asOf: fetched))
    }

    @Test("Data past the window is stale but still carries its value")
    func staleOutsideWindow() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let fetched = now.addingTimeInterval(-120)
        let result = StalenessPolicy.quote.evaluate(lastUpdated: fetched, now: now)
        #expect(result == .stale(asOf: fetched))
        #expect(result.hasValue, "Stale data must still be displayable")
    }

    @Test("A failed refresh keeps the previous value available")
    func failedRefreshRetainsValue() {
        let previous = Date(timeIntervalSince1970: 900_000)
        let failed = Freshness.failed(previous: previous, reason: "network error")
        #expect(failed.hasValue)
        #expect(failed.asOf == previous)
    }

    @Test("A failure with no previous value has nothing to show")
    func failedWithoutPreviousHasNoValue() {
        #expect(!Freshness.failed(previous: nil, reason: "network error").hasValue)
    }
}

@Suite("Relative time text", .serialized)
@MainActor
struct RelativeTimeTextTests {
    @Test("Very recent timestamps read as 'just now'")
    func justNow() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        #expect(RelativeTimeText.string(for: now.addingTimeInterval(-1), now: now) == "just now")
    }

    @Test("Missing data says so plainly rather than showing a number")
    func missingReadsAsNotAvailable() {
        #expect(RelativeTimeText.status(for: .missing) == "Not available")
    }

    @Test("A failed refresh names the reason and the age of what's shown")
    func failedStatusMentionsBoth() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let status = RelativeTimeText.status(
            for: .failed(previous: now.addingTimeInterval(-3600), reason: "rate limit"),
            now: now
        )
        #expect(status.contains("rate limit"))
        #expect(status.contains("hour"))
    }
}
