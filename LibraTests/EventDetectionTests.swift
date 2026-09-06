import Testing
import Foundation
import SwiftData
@testable import Libra

/// Section 4 detection.
///
/// The point of these tests is not that the detectors fire — it is that they
/// stay silent when they should. A research tool that flags an ordinary
/// Tuesday teaches the user to ignore it, at which point it is worse than
/// nothing. Most of what follows pins down the *absence* of an event.
@Suite("Event detection")
struct EventDetectionTests {

    // MARK: - Fixtures

    private static let epoch = Date(timeIntervalSince1970: 1_700_000_000)

    /// Bars from a series of daily percentage moves, compounded.
    private func bars(
        moves: [Double],
        start: Double = 100,
        volume: Double = 1_000_000
    ) -> [PriceBar] {
        var close = start
        var result: [PriceBar] = [
            PriceBar(date: Self.epoch, resolution: .daily, open: close, high: close,
                     low: close, close: close, volume: volume, adjustedClose: close)
        ]
        for (index, move) in moves.enumerated() {
            close *= (1 + move / 100)
            result.append(PriceBar(
                date: Self.epoch.addingTimeInterval(Double(index + 1) * 86_400),
                resolution: .daily, open: close, high: close, low: close,
                close: close, volume: volume, adjustedClose: close
            ))
        }
        return result
    }

    /// A calm series: alternating ±0.4% so dispersion exists but nothing is
    /// remarkable. Alternating rather than constant, because a constant series
    /// has zero dispersion and every detector correctly refuses to score it.
    private func calmMoves(_ count: Int) -> [Double] {
        (0..<count).map { $0.isMultiple(of: 2) ? 0.4 : -0.4 }
    }

    // MARK: - Statistics

    @Test("Median handles odd and even counts")
    func median() {
        #expect(Statistics.median([3, 1, 2]) == 2)
        #expect(Statistics.median([4, 1, 3, 2]) == 2.5)
        #expect(Statistics.median([]) == 0)
    }

    @Test("A flat series has no scale to measure deviations against")
    func flatSeriesHasNoScale() {
        // Without this guard every deviation divides by zero and renders as an
        // infinitely extraordinary event, when the truth is that nothing moved.
        #expect(Statistics.robustScale([5, 5, 5, 5]) == nil)
        #expect(Statistics.robustScale([]) == nil)
    }

    @Test("Annualised volatility scales daily dispersion by root 252")
    func annualisedVolatility() throws {
        let returns = [1.0, -1.0, 1.0, -1.0, 1.0, -1.0]
        let daily = try #require(Statistics.standardDeviation(returns))
        let annual = try #require(Statistics.annualisedVolatility(percentReturns: returns))
        #expect(abs(annual - daily * 252.0.squareRoot()) < 1e-9)
    }

    // MARK: - Anomaly measure

    @Test("An observation is excluded from the sample it is measured against")
    func observationExcludedFromOwnSample() throws {
        let sample = Array(repeating: 0.4, count: 30) + Array(repeating: -0.4, count: 30)
        let measure = try #require(AnomalyMeasure.measure(9.0, against: sample))

        // The sample is the priors only; the 9% day must not appear in it, or
        // it would widen the very yardstick used to judge it.
        #expect(measure.sampleSize == sample.count)
        #expect(measure.exceededCount == sample.count)
        #expect(measure.unusualness == 1.0)
    }

    @Test("A sample smaller than the minimum yields no measure")
    func smallSampleYieldsNothing() {
        #expect(AnomalyMeasure.measure(5, against: Array(repeating: 0.5, count: 10)) == nil)
    }

    // MARK: - Price moves

    @Test("A large move against a calm history is detected")
    func largeMoveDetected() throws {
        let series = bars(moves: calmMoves(80) + [9.0])
        let event = try #require(EventDetector.priceMove(bars: series))

        #expect(event.kind == .unusualPriceMove)
        #expect(event.unusualness > 0.97)
        #expect(event.derivation != nil, "A detected number must be checkable")
        let mentionsSample = event.detailLines.contains { $0.contains("prior") }
        #expect(mentionsSample, "The reader must be told what the move was compared against")
    }

    @Test("An ordinary day produces no event")
    func ordinaryDayIsSilent() {
        #expect(EventDetector.priceMove(bars: bars(moves: calmMoves(90))) == nil)
    }

    @Test("Too little history produces no event, however large the move")
    func shortHistoryIsSilent() {
        // Ten sessions cannot establish what is usual, so a 12% day says
        // nothing about this security yet.
        #expect(EventDetector.priceMove(bars: bars(moves: calmMoves(10) + [12.0])) == nil)
    }

    @Test("A statistically rare but tiny move is suppressed")
    func tinyMoveSuppressed() {
        // 0.05% moves make a 0.5% day rank at the very top of the sample and
        // clear the deviation threshold. It is still not worth telling anyone
        // about, so an absolute floor sits on top of the statistics — a
        // judgement about attention, not about significance.
        let quiet = (0..<80).map { $0.isMultiple(of: 2) ? 0.05 : -0.05 }
        #expect(EventDetector.priceMove(bars: bars(moves: quiet + [0.5])) == nil)
    }

    @Test("A move down is detected and described as a fall")
    func downMoveDetected() throws {
        let event = try #require(EventDetector.priceMove(bars: bars(moves: calmMoves(80) + [-9.0])))
        #expect(event.headline.contains("fell"))
        let signed = event.detailLines.contains { $0.contains("-") }
        #expect(signed, "Direction must survive into the detail lines")
    }

    // MARK: - The current session

    private func quote(
        changePercent: Double,
        at date: Date,
        volume: Double? = nil
    ) -> QuoteDTO {
        let previous = 100.0
        return QuoteDTO(symbol: "TEST", last: previous * (1 + changePercent / 100),
                        open: nil, high: nil, low: nil, previousClose: previous,
                        volume: volume, quoteTime: date)
    }

    @Test("Today's move is detected even though the bars end at yesterday's close")
    func quoteSuppliesCurrentSession() throws {
        // The defect this pins down was visible on screen: a security up 8.7%
        // on the day showed "nothing unusual", because daily bars stop at the
        // previous close and the detectors only ever read bars.
        let series = bars(moves: calmMoves(80))
        let lastBarDate = try #require(series.last?.date)
        let today = lastBarDate.addingTimeInterval(86_400)

        #expect(EventDetector.priceMove(bars: series) == nil)

        let event = try #require(
            EventDetector.priceMove(bars: series, quote: quote(changePercent: 8.7, at: today))
        )
        #expect(event.headline.contains("so far today"))
        #expect(event.occurredAt == today)
    }

    @Test("An open session is provisional and never stored")
    func openSessionIsProvisional() throws {
        let series = bars(moves: calmMoves(80))
        let today = try #require(series.last?.date).addingTimeInterval(86_400)
        let event = try #require(
            EventDetector.priceMove(bars: series, quote: quote(changePercent: 8.7, at: today))
        )

        #expect(event.isProvisional)
        let warns = event.detailLines.contains { $0.contains("still open") }
        #expect(warns, "A reading that can still change must say so")
    }

    @Test("A closed session is not provisional")
    func closedSessionIsFinal() throws {
        let event = try #require(EventDetector.priceMove(bars: bars(moves: calmMoves(80) + [9.0])))
        #expect(!event.isProvisional)
    }

    @Test("A quote for a session the bars already contain is not counted twice")
    func sameSessionIsNotDoubleCounted() throws {
        // Some providers publish a partial bar for the open session. Taking
        // both it and the quote would enter one day's move twice — once as a
        // prior and once as the observation.
        let series = bars(moves: calmMoves(80) + [9.0])
        let lastBarDate = try #require(series.last?.date)

        let reading = try #require(
            EventDetector.latestReading(bars: series,
                                        quote: quote(changePercent: 2.0, at: lastBarDate))
        )
        #expect(!reading.reading.isIntraday)
        #expect(abs(reading.reading.percent - 9.0) < 0.001,
                "The bar's own move, not the quote's")
    }

    @Test("A quote with no previous close cannot supply a session")
    func quoteWithoutPreviousCloseIsIgnored() throws {
        // Change percent is nil without a previous close, and inventing one
        // would fabricate the very number being judged.
        let series = bars(moves: calmMoves(80))
        let today = try #require(series.last?.date).addingTimeInterval(86_400)
        let bare = QuoteDTO(symbol: "TEST", last: 150, open: nil, high: nil, low: nil,
                            previousClose: nil, volume: nil, quoteTime: today)
        let reading = try #require(EventDetector.latestReading(bars: series, quote: bare))
        #expect(!reading.reading.isIntraday)
    }

    @Test("Today's volume is compared against every closed session, itself excluded")
    func liveVolumeUsesAllClosedSessionsAsPriors() throws {
        let series = bars(moves: calmMoves(80), volume: 1_000_000)
        let today = try #require(series.last?.date).addingTimeInterval(86_400)
        let event = try #require(EventDetector.volumeAnomaly(
            bars: series, quote: quote(changePercent: 1, at: today, volume: 3_000_000)
        ))

        #expect(event.isProvisional)
        #expect(event.headline.contains("so far today"))
        let warnsPartial = event.detailLines.contains { $0.contains("still open") }
        #expect(warnsPartial, "A partial day's volume understates the day")
    }

    // MARK: - Volume

    @Test("A volume spike against a steady base is detected")
    func volumeSpikeDetected() throws {
        var series = bars(moves: calmMoves(80), volume: 1_000_000)
        let last = series.removeLast()
        series.append(PriceBar(date: last.date, resolution: .daily, open: last.open,
                               high: last.high, low: last.low, close: last.close,
                               volume: 3_000_000, adjustedClose: last.adjustedClose))

        let event = try #require(EventDetector.volumeAnomaly(bars: series))
        #expect(event.kind == .unusualVolume)
        #expect(event.headline.contains("3.0×"))
    }

    @Test("Steady volume produces no event")
    func steadyVolumeIsSilent() {
        #expect(EventDetector.volumeAnomaly(bars: bars(moves: calmMoves(80))) == nil)
    }

    @Test("Bars without volume produce no volume event")
    func missingVolumeIsSilent() {
        // Absent volume must read as absent, never as zero — a provider that
        // omits the field would otherwise look like a day with no trading.
        let series = bars(moves: calmMoves(80)).map {
            PriceBar(date: $0.date, resolution: .daily, open: $0.open, high: $0.high,
                     low: $0.low, close: $0.close, volume: nil, adjustedClose: $0.adjustedClose)
        }
        #expect(EventDetector.volumeAnomaly(bars: series) == nil)
    }

    // MARK: - Volatility

    @Test("A regime change in volatility is detected, with both levels stated")
    func volatilityShiftDetected() throws {
        let quiet = (0..<70).map { $0.isMultiple(of: 2) ? 0.3 : -0.3 }
        let loud = (0..<20).map { $0.isMultiple(of: 2) ? 2.0 : -2.0 }
        let event = try #require(EventDetector.volatilityShift(bars: bars(moves: quiet + loud)))

        #expect(event.kind == .volatilityShift)
        #expect(event.headline.contains("risen"))
        // "Doubled" is uninformative without the levels: 8% to 16% and 60% to
        // 120% are very different situations.
        let statesBothLevels = event.detailLines.filter { $0.contains("annualised") }.count == 2
        #expect(statesBothLevels)
    }

    @Test("A steady volatility regime produces no event")
    func steadyVolatilityIsSilent() {
        #expect(EventDetector.volatilityShift(bars: bars(moves: calmMoves(90))) == nil)
    }

    // MARK: - Backfilling sessions the user missed

    @Test("Sessions missed between visits are detected from the stored bars")
    func backfillCoversMissedSessions() throws {
        // A large move on a day the app was not opened is still a change the
        // user has not seen. Judging only the newest session loses it entirely.
        let series = bars(moves: calmMoves(60) + [9.0] + calmMoves(3) + [-8.0])
        let lastVisit = try #require(series.dropLast(6).last?.date)

        let events = EventDetector.priceMoves(bars: series, after: lastVisit)
        #expect(events.count == 2)
        #expect(events.allSatisfy { !$0.isProvisional })
        #expect(events.contains { $0.headline.contains("rose") })
        #expect(events.contains { $0.headline.contains("fell") })
    }

    @Test("With no prior visit the backfill reports nothing")
    func backfillNeedsAReferencePoint() {
        let series = bars(moves: calmMoves(60) + [9.0])
        #expect(EventDetector.priceMoves(bars: series, after: nil).isEmpty)
    }

    @Test("Sessions before the last visit are left alone")
    func backfillIgnoresSeenSessions() throws {
        let series = bars(moves: calmMoves(60) + [9.0] + calmMoves(5))
        let after = try #require(series.last?.date)
        #expect(EventDetector.priceMoves(bars: series, after: after).isEmpty)
    }

    @Test("A session is judged only against the sessions before it")
    func backfillDoesNotUseHindsight() throws {
        // Including later sessions in the reference sample would let a move be
        // judged by information that did not exist yet.
        let series = bars(moves: calmMoves(60) + [5.0] + Array(repeating: 6.0, count: 30))
        let lastVisit = try #require(series.dropLast(32).last?.date)
        let events = EventDetector.priceMoves(bars: series, after: lastVisit)

        // The 5% day was extraordinary when it happened, even though a calmer
        // reading of the whole series would call it unremarkable.
        #expect(events.contains { $0.headline.contains("5.0%") })
    }

    @Test("A move that beat the whole window is described in words, not as a ratio")
    func comparisonLineReadsWell() throws {
        let event = try #require(EventDetector.priceMove(bars: bars(moves: calmMoves(80) + [9.0])))
        let line = try #require(event.detailLines.first { $0.contains("Larger than") })
        // "Larger than 250 of the prior 250" is correct and reads badly.
        #expect(line.contains("every one of"))
    }

    // MARK: - Filings

    private func filing(_ form: String, daysAgo: Double) -> FilingDTO {
        FilingDTO(accessionNumber: "\(form)-\(daysAgo)", formType: form,
                  filedAt: Self.epoch.addingTimeInterval(-daysAgo * 86_400),
                  periodOfReport: nil, primaryDocumentURL: nil, filingIndexURL: nil)
    }

    @Test("A first visit reports no filings as new")
    func firstVisitReportsNothing() {
        // With no prior visit there is no "since", and presenting a company's
        // whole filing history as new would be false.
        let filings = [filing("10-K", daysAgo: 5), filing("8-K", daysAgo: 1)]
        #expect(EventDetector.newFilings(filings: filings, since: nil).isEmpty)
    }

    @Test("Only filings after the last visit are reported, newest first")
    func filingsSinceLastVisit() throws {
        let filings = [filing("10-K", daysAgo: 30), filing("8-K", daysAgo: 3),
                       filing("4", daysAgo: 1)]
        let since = Self.epoch.addingTimeInterval(-10 * 86_400)
        let events = EventDetector.newFilings(filings: filings, since: since)

        #expect(events.count == 2)
        #expect(events.first?.headline.contains("4") == true)
        #expect(events.allSatisfy { $0.unusualness == 0 },
                "A filing either exists or does not; there is no sample to rank it against")
    }

    @Test("Filing significance describes the document, not its implications")
    func filingSignificance() {
        #expect(FilingSignificance.explanation(for: "10-K").contains("annual report"))
        #expect(FilingSignificance.explanation(for: "8-K").contains("material"))
        #expect(!FilingSignificance.explanation(for: "ZZZ").isEmpty,
                "An unknown form still gets an honest description rather than silence")
    }

    // MARK: - Event identity

    @Test("Identity survives a change of wording")
    func naturalKeyIgnoresHeadlineText() {
        let date = Self.epoch
        let first = DetectedEventDTO(kind: .unusualVolume, occurredAt: date,
                                     headline: "Volume 2.0× the median")
        let second = DetectedEventDTO(kind: .unusualVolume, occurredAt: date,
                                      headline: "Volume was twice its usual level")

        // Rewording a headline in a future release must not resurrect an event
        // the user already acknowledged.
        #expect(first.naturalKey == second.naturalKey)
    }

    @Test("Unusualness is clamped to a fraction")
    func unusualnessClamped() {
        #expect(DetectedEventDTO(kind: .other, occurredAt: .now,
                                 headline: "x", unusualness: 4).unusualness == 1)
        #expect(DetectedEventDTO(kind: .other, occurredAt: .now,
                                 headline: "x", unusualness: -2).unusualness == 0)
    }

    @Test("A measured headline is a calculation and its context an interpretation")
    func claimKinds() throws {
        let event = DetectedEventDTO(
            kind: .unusualPriceMove, occurredAt: .now,
            headline: "Price rose 9.0%",
            context: "Moves this large usually have a cause.",
            derivation: Derivation(formula: "a - b", inputs: [], result: "9.0%")
        )
        #expect(event.headlineClaim.kind == .calculation)
        #expect(try #require(event.contextClaim).kind == .interpretation)
    }

    @Test("An observed filing is a fact, not a calculation")
    func filingHeadlineIsFact() throws {
        // The app did not compute that an 8-K exists; the SEC reported it.
        // Badging it CALCULATION overstates the app's involvement and
        // understates the claim's authority.
        let since = Self.epoch.addingTimeInterval(-10 * 86_400)
        let event = try #require(
            EventDetector.newFilings(filings: [filing("8-K", daysAgo: 1)], since: since).first
        )
        #expect(event.headlineClaim.kind == .fact)
        #expect(event.headline.hasPrefix("Form 8-K"))
    }

    @Test("A filing offers one link, not the same filing twice")
    func filingHasSingleSourceLink() throws {
        let dated = FilingDTO(
            accessionNumber: "0000320193-26-000001", formType: "10-Q",
            filedAt: Self.epoch, periodOfReport: nil,
            primaryDocumentURL: URL(string: "https://www.sec.gov/doc.htm"),
            filingIndexURL: URL(string: "https://www.sec.gov/index.htm")
        )
        let since = Self.epoch.addingTimeInterval(-86_400)
        let event = try #require(EventDetector.newFilings(filings: [dated], since: since).first)

        #expect(event.sourceURLs.count == 1)
        #expect(event.sourceURLs.first?.absoluteString.contains("doc.htm") == true)
        // The reference must reach the user as a traceable source.
        #expect(event.headlineClaim.isTraceable)
        #expect(event.headlineClaim.sources.first?.provider == .sec)
    }
}

/// Storing and reading detected events.
@Suite("Event persistence", .serialized)
struct EventPersistenceTests {

    private func makeStore() throws -> (SnapshotStore, ModelContainer) {
        let container = AppModelContainer.preview
        let context = ModelContext(container)
        context.insert(Security(symbol: "TEST", name: "Test Corp"))
        try context.save()
        return (SnapshotStore(modelContainer: container), container)
    }

    private func event(_ kind: EventKind, dayOffset: Double) -> DetectedEventDTO {
        DetectedEventDTO(
            kind: kind,
            occurredAt: Date(timeIntervalSince1970: 1_700_000_000 + dayOffset * 86_400),
            headline: "\(kind.displayName) on day \(dayOffset)",
            unusualness: 0.99
        )
    }

    @Test("A provisional event is never written to the permanent record")
    func provisionalEventsAreNotStored() async throws {
        let (store, _) = try makeStore()
        let provisional = DetectedEventDTO(
            kind: .unusualPriceMove,
            occurredAt: Date(timeIntervalSince1970: 1_700_000_000),
            headline: "Price rose 8.7% so far today",
            isProvisional: true
        )

        // Storing it would freeze a midday figure as what happened that day.
        #expect(try await store.record(events: [provisional], symbol: "TEST", provider: .computed) == 0)
        #expect(try await store.events(symbol: "TEST").isEmpty)
    }

    @Test("Re-running detection does not duplicate the same day's event")
    func eventsAreIdempotent() async throws {
        let (store, _) = try makeStore()
        let events = [event(.unusualVolume, dayOffset: 0)]

        let first = try await store.record(events: events, symbol: "TEST", provider: .computed)
        let second = try await store.record(events: events, symbol: "TEST", provider: .computed)

        #expect(first == 1)
        #expect(second == 0, "Detection re-runs on every visit over the same bars")
    }

    @Test("The same kind on a different day is a different event")
    func differentDaysAreDistinct() async throws {
        let (store, _) = try makeStore()
        try await store.record(events: [event(.unusualVolume, dayOffset: 0)], symbol: "TEST",
                               provider: .computed)
        try await store.record(events: [event(.unusualVolume, dayOffset: 1)], symbol: "TEST",
                               provider: .computed)

        #expect(try await store.events(symbol: "TEST").count == 2)
    }

    @Test("Events read back newest first, with their scores intact")
    func eventsReadBackOrdered() async throws {
        let (store, _) = try makeStore()
        try await store.record(events: [
            event(.unusualVolume, dayOffset: 0),
            event(.unusualPriceMove, dayOffset: 5)
        ], symbol: "TEST", provider: .computed)

        let stored = try await store.events(symbol: "TEST")
        #expect(stored.first?.kind == .unusualPriceMove)
        #expect(stored.first?.unusualness == 0.99)
    }

    @Test("A visit stamp is nil until the first visit")
    func lastViewedStartsEmpty() async throws {
        let (store, _) = try makeStore()
        // Nil is what makes a first visit report nothing as new, rather than
        // reporting the entire available history.
        #expect(try await store.lastViewed(symbol: "TEST") == nil)
    }

    @Test("A visit stamp records when the user last looked")
    func markViewedRoundTrips() async throws {
        let (store, _) = try makeStore()
        let when = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.markViewed(symbol: "TEST", at: when)
        #expect(try await store.lastViewed(symbol: "TEST") == when)
    }

    @Test("Events for an unknown symbol are not recorded")
    func unknownSymbolIsIgnored() async throws {
        let (store, _) = try makeStore()
        // History is kept only for companies the user actually follows, so a
        // stray symbol cannot quietly populate the store.
        #expect(try await store.record(events: [event(.unusualVolume, dayOffset: 0)],
                                       symbol: "NOPE", provider: .computed) == 0)
    }

    @Test("Acknowledging an event marks only that event")
    func acknowledgeIsTargeted() async throws {
        let (store, container) = try makeStore()
        let target = event(.unusualVolume, dayOffset: 0)
        try await store.record(events: [target, event(.unusualPriceMove, dayOffset: 0)],
                               symbol: "TEST", provider: .computed)

        try await store.acknowledge(symbol: "TEST", naturalKey: target.naturalKey)

        let context = ModelContext(container)
        let stored = try context.fetch(FetchDescriptor<DetectedEvent>())
        let acknowledged = stored.filter(\.isAcknowledged)
        #expect(acknowledged.count == 1)
        #expect(acknowledged.first?.kind == .unusualVolume)
    }

    @Test("The cross-security feed carries the symbol each event belongs to")
    func feedCarriesSymbol() async throws {
        let (store, container) = try makeStore()
        let context = ModelContext(container)
        context.insert(Security(symbol: "OTHER", name: "Other Corp"))
        try context.save()

        try await store.record(events: [event(.unusualVolume, dayOffset: 0)], symbol: "TEST",
                               provider: .computed)
        try await store.record(events: [event(.unusualPriceMove, dayOffset: 1)], symbol: "OTHER",
                               provider: .computed)

        let feed = try await store.recentEvents()
        #expect(feed.count == 2)
        // A SwiftData relationship cannot cross the actor boundary, so the
        // symbol has to be carried explicitly or the feed cannot label rows.
        #expect(feed.first?.symbol == "OTHER")
        #expect(Set(feed.map(\.symbol)) == ["TEST", "OTHER"])
    }
}
