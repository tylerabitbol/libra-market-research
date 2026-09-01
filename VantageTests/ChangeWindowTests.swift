import Testing
import Foundation
import SwiftData
@testable import Vantage

/// The "What changed" panel as something you can point at a period.
///
/// Two properties matter and neither is obvious from the types. The window
/// must actually widen what is shown — a menu that reorders nothing is the
/// defect the watchlist sorts already taught us to test for — and the kind
/// menu must offer only kinds something can produce. Ten of the eighteen
/// `EventKind`s have a producer today; a toggle for the other eight would be a
/// control that can never match.
private struct SilentProvider: MarketDataProvider {
    let id: DataProviderID = .finnhub
    func isConfigured() async -> Bool { true }
    func quote(symbol: String) async throws -> QuoteDTO {
        throw APIError.transport(.finnhub, underlying: "offline")
    }
    func bars(symbol: String, resolution: BarResolution,
              from: Date, to: Date) async throws -> [PriceBarDTO] {
        throw APIError.transport(.tiingo, underlying: "offline")
    }
    func profile(symbol: String) async throws -> CompanyProfileDTO {
        throw APIError.notFound(.finnhub, endpoint: "profile")
    }
    func search(query: String) async throws -> [CompanyProfileDTO] { [] }
}

@Suite("Change window")
struct ChangeWindowArithmeticTests {

    @Test("No prior visit forms no window, rather than a window over all time")
    func firstVisitHasNoWindow() {
        #expect(ChangeWindow.lastVisit.startDate(lastVisit: nil) == nil)
    }

    @Test("Last visit measures from the visit")
    func lastVisitUsesTheVisit() {
        let visit = Date(timeIntervalSince1970: 1_700_000_000)
        #expect(ChangeWindow.lastVisit.startDate(lastVisit: visit) == visit)
    }

    @Test("A rolling window is measured back from now, not from the last visit")
    func rollingWindowIgnoresTheVisit() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let start = try #require(
            ChangeWindow.days(30).startDate(lastVisit: .distantPast, now: now))
        let days = now.timeIntervalSince(start) / 86_400
        #expect(abs(days - 30) < 1, "Within a day, to allow for the DST hour")
    }

    @Test("A custom window is the date chosen")
    func customIsTheDate() {
        let picked = Date(timeIntervalSince1970: 1_700_000_000)
        #expect(ChangeWindow.custom(picked).startDate(lastVisit: .now) == picked)
    }

    @Test("Only a window reaching past the last visit counts as widening")
    func wideningIsRelativeToTheVisit() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let yesterday = now.addingTimeInterval(-86_400)
        let longAgo = now.addingTimeInterval(-400 * 86_400)

        #expect(ChangeWindow.lastVisit.widensPast(lastVisit: yesterday, now: now) == false)
        #expect(ChangeWindow.days(30).widensPast(lastVisit: yesterday, now: now),
                "Thirty days reaches past a visit made yesterday")
        #expect(ChangeWindow.days(30).widensPast(lastVisit: longAgo, now: now) == false,
                "But not past one made over a year ago — that is narrowing")
    }
}

@Suite("Change window on the detail page", .serialized)
@MainActor
struct ChangeWindowFilteringTests {

    /// Two hundred sessions of small alternating moves with one 9% jump sixty
    /// days ago: far enough back that a thirty-day window must exclude it, and
    /// with 139 priors before it so the detector has its sample.
    private func spikeBars(now: Date = .now) -> [PriceBarDTO] {
        let end = Calendar.current.startOfDay(for: now).addingTimeInterval(-86_400)
        var close = 100.0
        return (0..<200).map { index -> PriceBarDTO in
            let daysBack = 199 - index
            let previous = close
            if daysBack == 60 {
                close *= 1.09
            } else {
                close *= index.isMultiple(of: 2) ? 1.003 : 0.997
            }
            return PriceBarDTO(date: end.addingTimeInterval(-Double(daysBack) * 86_400),
                               open: previous, high: max(previous, close),
                               low: min(previous, close), close: close,
                               volume: 1_000_000, adjustedClose: close)
        }
    }

    private func loadedModel() async throws -> SecurityDetailViewModel {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()

        let store = SnapshotStore(modelContainer: container)
        try await store.record(bars: spikeBars(), symbol: "TEST", resolution: .daily)

        let model = SecurityDetailViewModel(symbol: "TEST")
        model.load(using: ProviderRegistry(
            marketData: SilentProvider(), fundamentals: nil, analyst: nil,
            metrics: nil, sec: nil, macro: nil, news: nil, isUsingSampleData: false
        ), snapshots: store)
        try await Task.sleep(for: .milliseconds(800))
        #expect(model.bars.count == 200, "The held copy is what the detectors read")
        return model
    }

    @Test("Widening the window reveals a move the default window hides")
    func widerWindowShowsMore() async throws {
        let model = try await loadedModel()

        model.changeWindow = .days(30)
        let narrow = model.events.filter { $0.kind == .unusualPriceMove }
        model.changeWindow = .days(365)
        let wide = model.events.filter { $0.kind == .unusualPriceMove }

        #expect(wide.count > narrow.count,
                "The 9% session sixty days ago falls outside thirty days and inside a year")
        #expect(wide.contains { $0.occurredAt < Date.now.addingTimeInterval(-31 * 86_400) })
    }

    @Test("A first visit reports no backfill as change")
    func firstVisitStaysQuiet() async throws {
        let model = try await loadedModel()
        // No prior visit is recorded, so there is no window. Showing two
        // hundred sessions of history as "what changed" would be false.
        #expect(model.lastVisit == nil)
        model.changeWindow = .lastVisit
        #expect(model.events.allSatisfy { $0.occurredAt > Date.now.addingTimeInterval(-7 * 86_400) },
                "Only the standing detectors, which describe the present")
    }

    @Test("Only kinds something can produce are offered")
    func unproducibleKindsAreNotOffered() async throws {
        let model = try await loadedModel()
        model.changeWindow = .days(365)

        #expect(!model.availableKinds.isEmpty)
        // Nothing in the app emits these; a toggle for one could never match.
        for absent: EventKind in [.ratingChange, .earningsSurprise, .majorNews,
                                  .analystEstimateRevision, .sectorRelativeMove] {
            #expect(!model.availableKinds.contains(absent),
                    "\(absent.rawValue) has no producer and must not be offered")
        }
        // With price bars only, nothing fundamental or filed can be offered either.
        #expect(!model.availableKinds.contains(.newFiling))
    }

    @Test("Selecting a kind removes the rest")
    func kindFilterNarrows() async throws {
        let model = try await loadedModel()
        model.changeWindow = .days(365)
        let kind = try #require(model.availableKinds.first)

        model.kindFilter = [kind]
        #expect(!model.events.isEmpty)
        #expect(model.events.allSatisfy { $0.kind == kind })

        model.kindFilter = []
        #expect(model.events.count >= model.availableKinds.count)
    }

    @Test("A window reaching past the held history says so")
    func coverageIsStated() async throws {
        let model = try await loadedModel()

        // The note carries two independent claims and they are checked apart:
        // how far the bars reach, and that fundamentals cannot be backfilled.
        model.changeWindow = .days(30)
        #expect(model.coverageNote?.contains("history begins") != true,
                "Thirty days is well inside two hundred sessions of bars")

        model.changeWindow = .days(365)
        let note = try #require(model.coverageNote)
        // A year reaches past the earliest bar, and the panel must not imply
        // the period it could not examine was simply quiet.
        #expect(note.contains("history begins"))
        #expect(note.contains("latest reported period"),
                "And fundamentals cannot be backfilled at all")
    }
}
