import Foundation
import SwiftUI
import OSLog

/// Loads everything the Security Detail page shows.
///
/// Each section loads independently and records its own failure. Section 21
/// requires that one provider failing degrades one section rather than the
/// screen — and with four providers on three different rate limits, partial
/// success is the normal case rather than an edge case.
@Observable
@MainActor
final class SecurityDetailViewModel {
    nonisolated static let logger = Logger(
        subsystem: "com.tylerabitbol.vantage", category: "detail"
    )

    let symbol: String

    private(set) var profile: CompanyProfileDTO?
    private(set) var quote: QuoteDTO?
    private(set) var bars: [PriceBar] = []
    private(set) var metrics: CompanyMetricsDTO?
    private(set) var fundamentals: [FinancialFactDTO] = []
    private(set) var filings: [FilingDTO] = []

    /// The market's daily closes, for separating "the market moved" from
    /// "this company moved". FRED's real S&P 500 index, not an ETF.
    private(set) var marketCloses: [(date: Date, close: Double)] = []
    /// The current session's market move, which FRED cannot supply because it
    /// publishes at the close. An ETF stands in, and is labelled as one.
    private(set) var marketIntradayMove: Double?
    private(set) var beta: Beta?

    /// The sector this company maps to, and its history. Nil when the profile
    /// carries no sector, or names one we do not track — better than silently
    /// comparing a bank against the technology sector.
    private(set) var sectorBenchmark: Benchmark?
    private(set) var sectorBars: [PriceBar] = []
    private(set) var sectorIntradayMove: Double?
    private(set) var sectorFactor: SectorFactor?

    /// Published analyst ratings. Estimate revisions need a paid Finnhub tier
    /// and are absent by design; what the free tier serves is the rating mix.
    private(set) var ratings: RatingSnapshotDTO?

    private(set) var quoteError: APIError?
    private(set) var historyError: APIError?
    private(set) var metricsError: APIError?
    private(set) var fundamentalsError: APIError?
    private(set) var filingsError: APIError?

    /// Changes detected from the data just loaded, most recent first.
    private(set) var events: [DetectedEventDTO] = []
    /// The subset the user has not seen — everything that occurred after their
    /// previous visit. Empty on a first visit by design.
    private(set) var newSinceLastVisit: [DetectedEventDTO] = []
    private(set) var lastVisit: Date?

    private(set) var isLoading = false
    private(set) var lastRefreshedAt: Date?
    private(set) var selectedRange: ChartRange = .oneYear

    private var loadTask: Task<Void, Never>?
    /// Bars are the scarcest request in the app — Tiingo's free tier refills
    /// about one token every 80 seconds — so a range change reuses what we
    /// already fetched rather than re-requesting.
    private var loadedBarWindow: (from: Date, to: Date)?

    /// Observation times of what the store already holds, consulted before a
    /// request is spent.
    private var storedFreshness: [StoredDataKind: Date] = [:]
    /// Set for the duration of a forced refresh, so pull-to-refresh reaches the
    /// network even where the held copy would otherwise be considered fresh.
    private var isForcingRefresh = false

    /// Whether any section on this page was filled from the store.
    private(set) var hydratedFromStore = false

    /// What each periodic filing reported, keyed by accession number.
    private(set) var filingAnalyses: [String: FilingAnalysis.Result] = [:]

    /// The concepts this page works with, named once.
    ///
    /// The store read and the network fetch must ask for the same set, or a
    /// hydrated page renders a series that the fetched page then drops.
    static let trackedConcepts: [FinancialConcept] = [
        .revenue, .netIncome, .grossProfit, .operatingIncome,
        .operatingCashFlow, .capitalExpenditures,
        .cashAndEquivalents, .totalDebt, .stockholdersEquity
    ]

    /// How far back fundamentals are asked for, on both paths.
    static var fundamentalsSince: Date? {
        Calendar.current.date(byAdding: .year, value: -6, to: .now)
    }

    init(symbol: String) {
        self.symbol = symbol.uppercased()
    }

    var freshness: Freshness {
        if isLoading { return .refreshing(previous: lastRefreshedAt) }
        return StalenessPolicy.quote.evaluate(lastUpdated: lastRefreshedAt)
    }

    /// The market series as bars, so it can go through the same return
    /// machinery as everything else.
    var marketBars: [PriceBar] {
        marketCloses.map {
            PriceBar(date: $0.date, resolution: .daily, open: $0.close, high: $0.close,
                     low: $0.close, close: $0.close, adjustedClose: $0.close)
        }
    }

    /// Return over the selected range for the sector and the market, on the
    /// same window as `rangeReturn`.
    var sectorRangeReturn: PeriodReturn? {
        ReturnCalculator.trailingReturn(bars: sectorBars, window: selectedRange.dateInterval)
    }

    var marketRangeReturn: PeriodReturn? {
        ReturnCalculator.trailingReturn(bars: marketBars, window: selectedRange.dateInterval)
    }

    /// Section 7, finally on screen: the arithmetic of out- or
    /// under-performance, stated in percentage points rather than adjectives.
    var relativeToSector: RelativePerformance? {
        ReturnCalculator.relativePerformance(security: rangeReturn, benchmark: sectorRangeReturn)
    }

    var relativeToMarket: RelativePerformance? {
        ReturnCalculator.relativePerformance(security: rangeReturn, benchmark: marketRangeReturn)
    }

    /// How the sector itself did against the market — Section 13's "sector
    /// strength", which is about the industry rather than this company.
    var sectorVersusMarket: RelativePerformance? {
        ReturnCalculator.relativePerformance(security: sectorRangeReturn,
                                             benchmark: marketRangeReturn)
    }

    /// The eleven dimensions of Section 13, and with them Section 12's
    /// disconfirming evidence. Recomputed from what is loaded rather than
    /// stored, so it can never disagree with the figures above it.
    var researchProfile: ResearchProfile {
        ResearchProfileBuilder.build(ResearchProfileBuilder.Inputs(
            bars: bars,
            rangeReturn: rangeReturn,
            relativeToMarket: relativeToMarket,
            sectorRelativeToMarket: sectorVersusMarket,
            sectorName: sectorBenchmark?.displayName,
            fundamentals: fundamentals,
            metrics: metrics,
            ratings: ratings,
            insiderPurchases: nil,
            insiderSales: nil))
    }

    /// Bars trimmed to the selected range, from the single wide fetch.
    var visibleBars: [PriceBar] {
        let start = selectedRange.startDate()
        return bars.filter { $0.date >= start }.sorted { $0.date < $1.date }
    }

    /// Price context over the visible range. "Down 18% from its peak" means a
    /// different thing over a month than over five years, so it follows the
    /// range the user chose rather than everything held.
    var priceContext: PriceContext? {
        ReturnCalculator.priceContext(bars: visibleBars)
    }

    /// Return over the selected range, computed from the visible bars.
    var rangeReturn: PeriodReturn? {
        ReturnCalculator.trailingReturn(bars: bars, window: selectedRange.dateInterval)
    }

    func select(_ range: ChartRange, registry: ProviderRegistry) {
        selectedRange = range
        // Only re-fetch when the new range reaches back further than what we hold.
        let needed = range.startDate()
        if let window = loadedBarWindow, window.from <= needed { return }
        Task { await loadHistory(using: registry) }
    }

    func load(
        using registry: ProviderRegistry,
        snapshots: SnapshotStore? = nil,
        force: Bool = false
    ) {
        if !force, case .fresh = freshness { return }
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            self?.isForcingRefresh = force
            await self?.hydrate(from: snapshots)
            await self?.performLoad(using: registry, snapshots: snapshots)
            await self?.detectChanges(using: snapshots)
            await self?.persist(using: snapshots)
            self?.isForcingRefresh = false
        }
    }

    /// True when a fetch failed and the page fell back to what was on disk.
    ///
    /// Deliberately not "some of this came from the store": a section skipped
    /// because the held copy is still fresh is not a degraded state and does
    /// not need announcing. This is the case the user needs told about.
    var isShowingSavedCopy: Bool {
        hydratedFromStore && (historyError != nil || quoteError != nil)
    }

    /// When the saved copy on screen was observed.
    var savedCopyAsOf: Date? { storedFreshness[.bars] }

    /// The price to show, falling back to the last stored close when no live
    /// quote arrived. Standing alone that would misrepresent a close as a
    /// current price, so it is only ever rendered beneath the saved-copy
    /// notice that dates it.
    var displayPrice: Double? { quote?.last ?? bars.last?.analysisClose }

    var displayChangePercent: Double? { quote?.changePercent ?? lastStoredSessionChange }

    /// The most recent closed session's move, from bars alone.
    private var lastStoredSessionChange: Double? {
        let closes = bars.suffix(2).map(\.analysisClose)
        guard closes.count == 2, closes[0] != 0 else { return nil }
        return (closes[1] - closes[0]) / closes[0] * 100
    }

    /// Fills the page from what the store already holds, before any request.
    ///
    /// Three things follow that did not before. A previously visited security
    /// renders instantly and works offline. A failed fetch degrades to the last
    /// good copy rather than to an empty section. And a section whose held copy
    /// is still fresh costs no request at all — which is what makes Tiingo's
    /// 50-requests-per-hour budget survivable on a page that reads five years
    /// of history.
    private func hydrate(from snapshots: SnapshotStore?) async {
        guard let snapshots else { return }
        do {
            for kind in StoredDataKind.allCases {
                storedFreshness[kind] = try await snapshots.latestObservedAt(
                    symbol: symbol, kind: kind)
            }

            let storedBars = try await snapshots.bars(
                symbol: symbol, from: ChartRange.fiveYear.startDate())
            if !storedBars.isEmpty, bars.isEmpty {
                bars = storedBars.map {
                    PriceBar(date: $0.date, resolution: .daily, open: $0.open, high: $0.high,
                             low: $0.low, close: $0.close, volume: $0.volume,
                             adjustedClose: $0.adjustedClose)
                }
                // What we actually hold, which is what `select(_:registry:)`
                // needs to decide whether a longer range requires a request.
                if let first = storedBars.first?.date, let last = storedBars.last?.date {
                    loadedBarWindow = (first, last)
                }
                hydratedFromStore = true
            }

            let storedFacts = try await snapshots.facts(
                symbol: symbol, concepts: Self.trackedConcepts, since: Self.fundamentalsSince)
            if !storedFacts.isEmpty, fundamentals.isEmpty {
                fundamentals = storedFacts
                hydratedFromStore = true
            }

            let storedFilings = try await snapshots.filings(symbol: symbol, limit: 15)
            if !storedFilings.isEmpty, filings.isEmpty {
                filings = storedFilings
                hydratedFromStore = true
            }
        } catch {
            // Hydration is an optimisation, not a requirement. A store that
            // cannot be read leaves the page exactly as it was before: empty,
            // and about to fetch.
            Self.logger.error("Hydration failed for \(self.symbol, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Whether the held copy is recent enough that fetching would buy nothing.
    private func isHeldCopyFresh(_ kind: StoredDataKind, policy: StalenessPolicy) -> Bool {
        guard !isForcingRefresh, let observed = storedFreshness[kind] else { return false }
        if case .fresh = policy.evaluate(lastUpdated: observed) { return true }
        return false
    }

    /// Whether this event postdates the user's previous visit.
    func isNew(_ event: DetectedEventDTO) -> Bool {
        guard let lastVisit else { return false }
        return event.occurredAt > lastVisit
    }

    /// Runs the Section 4 detectors over what was just loaded.
    ///
    /// The prior visit is read *before* anything is stamped: the question is
    /// "what happened since last time", and marking this visit first would make
    /// the answer permanently "nothing". Detection itself runs whether or not a
    /// store is attached, so the panel works in previews and on a first launch;
    /// only the persistence and the visit stamp need one.
    private func detectChanges(using snapshots: SnapshotStore?) async {
        if let snapshots {
            lastVisit = try? await snapshots.lastViewed(symbol: symbol)
        }
        analyseFilings()
        // The quote carries today; the bars stop at the previous close.
        // The backfill covers the sessions in between — a large move on a day
        // the app was not opened is still a change the user has not seen.
        let detected = EventDetector.detect(bars: bars, quote: quote)
            + EventDetector.priceMoves(bars: bars, after: lastVisit)
            + EventDetector.volumeAnomalies(bars: bars, after: lastVisit)
            + EventDetector.newFilings(filings: filings, since: lastVisit)
            // What the business did, as distinct from what the price did.
            // Judged against the company's own reported history, on the same
            // rank-not-probability terms as everything above.
            + FundamentalDetector.detect(facts: fundamentals)
            + (await restatementEvents(using: snapshots))
        // The live detector and the backfill can both reach the most recent
        // closed session. They describe it identically, so the list would show
        // the same card twice — the store deduplicates on the same key, but
        // the screen has no such protection.
        var seen: Set<String> = []
        events = detected
            .filter { seen.insert($0.naturalKey).inserted }
            .sorted { $0.occurredAt > $1.occurredAt }

        #if DEBUG
        if let reading = EventDetector.latestReading(bars: bars, quote: quote) {
            let measure = AnomalyMeasure.measure(
                reading.reading.percent, against: reading.priors.map(\.percent)
            )
            Self.logger.notice("DETECT \(self.symbol, privacy: .public) bars=\(self.bars.count) intraday=\(reading.reading.isIntraday) move=\(reading.reading.percent) priors=\(reading.priors.count) scale=\(measure?.scale ?? -1) dev=\(measure?.deviations ?? -1) rank=\(measure?.unusualness ?? -1) events=\(detected.count)")
        } else {
            Self.logger.notice("DETECT \(self.symbol, privacy: .public) no reading available")
        }
        #endif
        if let lastVisit {
            newSinceLastVisit = events.filter { $0.occurredAt > lastVisit }
        } else {
            newSinceLastVisit = []
        }
    }

    /// Joins each periodic filing to the figures it reported.
    ///
    /// Costs nothing: every XBRL fact already carries the accession number of
    /// the filing that reported it, and the submissions feed supplies the same
    /// accession in the same format, so this is a filter over data in hand.
    ///
    /// `analyzedAt` on `FilingRecord` is deliberately left unset. It exists so
    /// expensive extraction is not repeated, and this is a pass over an array
    /// already in memory — writing the stamp would create state nothing reads.
    private func analyseFilings() {
        var analyses: [String: FilingAnalysis.Result] = [:]
        for filing in filings {
            guard let result = FilingAnalysis.analyse(filing: filing, facts: fundamentals)
            else { continue }
            analyses[filing.accessionNumber] = result
        }
        filingAnalyses = analyses
    }

    /// The analysis belonging to a filing event.
    ///
    /// Matched on `occurredAt`, which for a filing event is the filing's own
    /// `filedAt`. EDGAR dates filings to the day, so an 8-K filed alongside a
    /// 10-Q shares the timestamp — requiring an analysis to exist picks the
    /// periodic report out of the pair.
    func analysis(for event: DetectedEventDTO) -> FilingAnalysis.Result? {
        guard event.kind == .newFiling else { return nil }
        return filings
            .first { $0.filedAt == event.occurredAt && filingAnalyses[$0.accessionNumber] != nil }
            .flatMap { filingAnalyses[$0.accessionNumber] }
    }

    /// Restatements across the tracked concepts.
    ///
    /// Needs the superseded rows, which only the store holds: the provider
    /// returns the current view of a company's history, and every other read
    /// path in the app collapses revisions away on purpose.
    ///
    /// The freshly fetched facts are merged in rather than read back after
    /// persisting, because `persist` runs after this and would otherwise delay
    /// every restatement by one visit — the amendment would land, be stored,
    /// and only be noticed the next time the page was opened.
    ///
    /// `naturalKey` is one event per kind per day, so several concepts revised
    /// in the same amendment collapse to a single stored row regardless. The
    /// most recent is chosen deliberately rather than letting an arbitrary one
    /// win the race; the others remain visible in the fundamentals section.
    private func restatementEvents(using snapshots: SnapshotStore?) async -> [DetectedEventDTO] {
        guard let snapshots else { return [] }
        var found: [DetectedEventDTO] = []
        for concept in Self.trackedConcepts {
            let stored = (try? await snapshots.factRevisions(
                symbol: symbol, concept: concept, since: Self.fundamentalsSince)) ?? []
            let merged = stored + fundamentals.filter { $0.concept == concept }
            if let event = FundamentalDetector.restatements(revisions: merged, concept: concept) {
                found.append(event)
            }
        }
        return found.max { $0.occurredAt < $1.occurredAt }.map { [$0] } ?? []
    }

    /// Records everything the load produced.
    ///
    /// Deliberately after the UI has its data: persistence must never delay
    /// what is on screen, and a write failure must not blank a loaded page.
    private func persist(using snapshots: SnapshotStore?) async {
        guard let snapshots else { return }
        let symbol = self.symbol
        do {
            if let quote { try await snapshots.record(quote: quote, symbol: symbol) }
            if !bars.isEmpty {
                let dtos = bars.map {
                    PriceBarDTO(date: $0.date, open: $0.open, high: $0.high, low: $0.low,
                                close: $0.close, volume: $0.volume,
                                adjustedClose: $0.adjustedClose)
                }
                try await snapshots.record(bars: dtos, symbol: symbol, resolution: .daily)
            }
            if !fundamentals.isEmpty {
                try await snapshots.record(facts: fundamentals, symbol: symbol)
            }
            if !filings.isEmpty {
                try await snapshots.record(filings: filings, symbol: symbol)
            }
            if !events.isEmpty {
                try await snapshots.record(events: events, symbol: symbol)
            }
            // Stamped last, and only when the load actually completed.
            // Cancelling mid-load leaves `lastRefreshedAt` nil; stamping there
            // would advance the reference point past changes the user never
            // saw, and they would never be reported again.
            if lastRefreshedAt != nil {
                try await snapshots.markViewed(symbol: symbol)
            }
        } catch {
            Self.logger.error("Persist failed for \(symbol, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }

    private func performLoad(using registry: ProviderRegistry,
                             snapshots: SnapshotStore?) async {
        isLoading = true
        defer { isLoading = false }

        // Independent sections, run concurrently, each swallowing only its own
        // failure into its own error slot.
        async let profileWork: Void = loadProfile(using: registry)
        async let quoteWork: Void = loadQuote(using: registry)
        async let historyWork: Void = loadHistory(using: registry)
        async let metricsWork: Void = loadMetrics(using: registry)
        async let ratingsWork: Void = loadRatings(using: registry)
        async let marketWork: Void = loadMarketContext(using: registry)
        _ = await (profileWork, quoteWork, historyWork, metricsWork, marketWork, ratingsWork)

        // Needs the profile's sector, so it follows the concurrent block
        // rather than running inside it.
        await loadSectorHistory(using: registry, snapshots: snapshots)
        computeBeta()

        // These need the CIK, which comes from the profile or a lookup, so they
        // follow rather than run alongside.
        await loadSECSections(using: registry)

        guard !Task.isCancelled else { return }
        lastRefreshedAt = .now
    }

    private func loadProfile(using registry: ProviderRegistry) async {
        profile = try? await registry.marketData.profile(symbol: symbol)
    }

    private func loadQuote(using registry: ProviderRegistry) async {
        do {
            quote = try await registry.marketData.quote(symbol: symbol)
            quoteError = nil
        } catch let error as APIError {
            quoteError = error
        } catch {
            quoteError = .transport(.finnhub, underlying: error.localizedDescription)
        }
    }

    private func loadHistory(using registry: ProviderRegistry) async {
        // Bars are the scarcest request in the app. When hydration produced a
        // copy that is still fresh, the page is already correct and the request
        // is pure cost against a budget that refills one token every 80 seconds.
        if !bars.isEmpty, isHeldCopyFresh(.bars, policy: .dailyCandles) {
            historyError = nil
            return
        }
        // Fetch the widest range once. Five years of daily bars is a single
        // request and covers every shorter range without another.
        let from = ChartRange.fiveYear.startDate()
        let to = Date.now
        do {
            let fetched = try await registry.marketData.bars(
                symbol: symbol, resolution: .daily, from: from, to: to
            )
            bars = fetched.map {
                PriceBar(date: $0.date, resolution: .daily, open: $0.open, high: $0.high,
                         low: $0.low, close: $0.close, volume: $0.volume,
                         adjustedClose: $0.adjustedClose)
            }
            loadedBarWindow = (from, to)
            historyError = nil
        } catch let error as APIError {
            historyError = error
            Self.logger.error("History failed for \(self.symbol, privacy: .public): \(error.shortDescription, privacy: .public)")
        } catch {
            historyError = .transport(.tiingo, underlying: error.localizedDescription)
        }
    }

    /// Loads the market series the attribution rests on.
    ///
    /// Failures are swallowed on purpose: attribution is additional context,
    /// and losing it must not mark the page as failed or hide the price move
    /// it annotates. The UI shows attribution only when it exists.
    private func loadMarketContext(using registry: ProviderRegistry) async {
        async let history: Void = loadMarketHistory(using: registry)
        async let live: Void = loadMarketIntraday(using: registry)
        _ = await (history, live)
    }

    private func loadMarketHistory(using registry: ProviderRegistry) async {
        guard let macro = registry.macro else { return }
        let from = ChartRange.fiveYear.startDate()
        do {
            let observations = try await macro.observations(
                seriesID: Benchmark.marketSeriesID, from: from, to: .now
            )
            marketCloses = observations.map { (date: $0.date, close: $0.value) }
        } catch {
            Self.logger.error("Market context unavailable for \(self.symbol, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }

    /// The index itself is end-of-day, so the current session comes from a
    /// broad-market ETF. Declared a proxy everywhere it is shown.
    private func loadMarketIntraday(using registry: ProviderRegistry) async {
        guard symbol != Benchmark.marketProxySymbol else { return }
        let proxy = try? await registry.marketData.quote(symbol: Benchmark.marketProxySymbol)
        marketIntradayMove = proxy?.changePercent
    }

    private func computeBeta() {
        guard !bars.isEmpty, !marketCloses.isEmpty else {
            beta = nil
            sectorFactor = nil
            return
        }
        beta = RelativeAnalysis.beta(
            RelativeAnalysis.align(security: bars, market: marketCloses)
        )

        guard let sectorBenchmark, !sectorBars.isEmpty else {
            sectorFactor = nil
            return
        }
        sectorFactor = RelativeAnalysis.sectorFactor(
            RelativeAnalysis.align(security: bars, market: marketCloses, sector: sectorBars),
            name: sectorBenchmark.displayName,
            isProxy: sectorBenchmark.isProxy || sectorBenchmark.fredSeriesID == nil)
    }

    /// The sector's history, when the company maps to one we track.
    ///
    /// One bars request per distinct sector, cached in the store afterwards —
    /// eleven companies in the same sector share one series, which is what
    /// makes this affordable against Tiingo's hourly budget.
    private func loadSectorHistory(using registry: ProviderRegistry,
                                   snapshots: SnapshotStore?) async {
        guard let benchmark = Benchmark.sector(matching: profile?.sector),
              let symbol = benchmark.etfSymbol,
              symbol != self.symbol
        else {
            sectorBenchmark = nil
            return
        }
        sectorBenchmark = benchmark

        if let snapshots {
            try? await snapshots.ensureBenchmark(symbol: symbol, name: benchmark.displayName)
            let stored = (try? await snapshots.bars(
                symbol: symbol, from: ChartRange.fiveYear.startDate())) ?? []
            let observed = try? await snapshots.latestObservedAt(symbol: symbol, kind: .bars)
            if !stored.isEmpty, let observed,
               case .fresh = StalenessPolicy.dailyCandles.evaluate(lastUpdated: observed),
               !isForcingRefresh {
                sectorBars = stored.map(Self.priceBar)
                return
            }
        }

        do {
            let fetched = try await registry.marketData.bars(
                symbol: symbol, resolution: .daily,
                from: ChartRange.fiveYear.startDate(), to: .now)
            sectorBars = fetched.map(Self.priceBar)
            if let snapshots {
                try? await snapshots.record(bars: fetched, symbol: symbol, resolution: .daily)
            }
        } catch {
            // Sector context is additional, exactly like market attribution.
            // Losing it must not mark the page as failed.
            Self.logger.error("Sector history unavailable for \(self.symbol, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
        sectorIntradayMove = try? await registry.marketData.quote(symbol: symbol).changePercent
    }

    private static func priceBar(_ dto: PriceBarDTO) -> PriceBar {
        PriceBar(date: dto.date, resolution: .daily, open: dto.open, high: dto.high,
                 low: dto.low, close: dto.close, volume: dto.volume,
                 adjustedClose: dto.adjustedClose)
    }

    /// How much of the most recent move the market accounts for.
    ///
    /// Nil rather than a guess when the market leg for that session is
    /// missing — an attribution computed against the wrong day's market move
    /// would be worse than none.
    var latestAttribution: MoveAttribution? {
        guard let reading = EventDetector.latestReading(bars: bars, quote: quote)?.reading
        else { return nil }

        let marketMove: Double
        let isProxy: Bool
        if reading.isIntraday {
            guard let intraday = marketIntradayMove else { return nil }
            marketMove = intraday
            isProxy = true
        } else {
            let aligned = RelativeAnalysis.align(security: bars, market: marketCloses)
            let day = Calendar.current.startOfDay(for: reading.date)
            guard let match = aligned.last(where: {
                Calendar.current.startOfDay(for: $0.date) == day
            }) else { return nil }
            marketMove = match.market
            isProxy = false
        }

        // The sector leg must come from the same session as the market leg.
        // An intraday reading needs the sector's live quote; a closed session
        // needs its bar for that day.
        let sectorMove: Double?
        if reading.isIntraday {
            sectorMove = sectorIntradayMove
        } else {
            let day = Calendar.current.startOfDay(for: reading.date)
            let sorted = sectorBars.sorted { $0.date < $1.date }
            if let index = sorted.lastIndex(where: {
                Calendar.current.startOfDay(for: $0.date) == day
            }), index > 0 {
                sectorMove = ReturnCalculator.simpleReturn(
                    from: sorted[index - 1].analysisClose, to: sorted[index].analysisClose)
            } else {
                sectorMove = nil
            }
        }

        return RelativeAnalysis.attribute(
            securityMove: reading.percent,
            marketMove: marketMove,
            marketName: isProxy ? "S&P 500 (SPY)" : "S&P 500",
            beta: beta,
            sector: sectorFactor,
            sectorMove: sectorMove,
            isMarketProxy: isProxy
        )
    }

    private func loadMetrics(using registry: ProviderRegistry) async {
        guard let provider = registry.metrics else { return }
        do {
            metrics = try await provider.metrics(symbol: symbol)
            metricsError = nil
        } catch let error as APIError {
            metricsError = error
        } catch {
            metricsError = .transport(.finnhub, underlying: error.localizedDescription)
        }
    }

    /// Ratings, where the tier serves them.
    ///
    /// Swallows its failure like the other context sections: the rating mix is
    /// one dimension of eleven, and losing it must not mark the page as failed.
    private func loadRatings(using registry: ProviderRegistry) async {
        guard let analyst = registry.analyst else { return }
        ratings = try? await analyst.ratings(symbol: symbol).last
    }

    private func loadSECSections(using registry: ProviderRegistry) async {
        guard let sec = registry.sec else { return }

        // Resolving the CIK is itself a request. With nothing left to fetch it
        // buys nothing, so the check happens before it rather than inside each
        // section below.
        let filingsHeld = !filings.isEmpty && isHeldCopyFresh(.filings, policy: .filings)
        let factsHeld = !fundamentals.isEmpty && isHeldCopyFresh(.facts, policy: .fundamentals)
        if filingsHeld && factsHeld {
            filingsError = nil
            fundamentalsError = nil
            return
        }

        let cik: String
        do {
            // `??` takes an autoclosure, which cannot contain an await.
            if let known = profile?.cik {
                cik = known
            } else {
                cik = try await sec.resolveCIK(symbol: symbol)
            }
        } catch let error as APIError {
            fundamentalsError = error
            filingsError = error
            return
        } catch {
            return
        }

        async let filingWork: Void = loadFilings(cik: cik, sec: sec)
        async let factWork: Void = loadFundamentals(cik: cik, registry: registry)
        _ = await (filingWork, factWork)
    }

    private func loadFilings(cik: String, sec: any SECDataProvider) async {
        if !filings.isEmpty, isHeldCopyFresh(.filings, policy: .filings) {
            filingsError = nil
            return
        }
        do {
            filings = try await sec.filings(
                cik: cik,
                formTypes: ["10-K", "10-Q", "8-K", "4"],
                limit: 15
            )
            filingsError = nil
        } catch let error as APIError {
            filingsError = error
        } catch {
            filingsError = .transport(.sec, underlying: error.localizedDescription)
        }
    }

    private func loadFundamentals(cik: String, registry: ProviderRegistry) async {
        guard let provider = registry.fundamentals else { return }
        if !fundamentals.isEmpty, isHeldCopyFresh(.facts, policy: .fundamentals) {
            fundamentalsError = nil
            return
        }
        do {
            fundamentals = try await provider.facts(
                symbol: symbol, cik: cik,
                concepts: Self.trackedConcepts,
                since: Self.fundamentalsSince
            )
            fundamentalsError = nil
        } catch let error as APIError {
            fundamentalsError = error
        } catch {
            fundamentalsError = .transport(.sec, underlying: error.localizedDescription)
        }
    }

    #if DEBUG
    /// Injects fundamentals so the derived figures can be tested without a
    /// provider. The stored property is private(set), and the derivations —
    /// growth linking and the free-cash-flow subtraction — are exactly the
    /// arithmetic worth pinning down.
    func applyFundamentalsForTesting(_ facts: [FinancialFactDTO]) {
        fundamentals = facts
    }
    #endif

    // MARK: - Derived views of the data

    /// Valuation metrics that have enough history to be ranked. Metrics without
    /// it are omitted rather than shown as a bare number implying context.
    var valuationContexts: [RankedMetric] {
        guard let metrics else { return [] }
        return ValuationMetric.all.compactMap { metric in
            // Both halves normalised to one scale before comparison; see
            // ValuationMetric for why that is load-bearing.
            guard let pair = metrics.normalized(for: metric),
                  let context = ValuationCalculator.historicalContext(
                    current: pair.current,
                    history: pair.history,
                    lowerIsCheaper: metric.lowerIsCheaper,
                    currentIsFromHistory: pair.currentIsFromHistory)
            else { return nil }
            return RankedMetric(metric: metric, context: context, asOf: pair.asOf)
        }
    }

    /// Annual revenue with year-over-year growth, most recent first.
    var annualRevenue: [AnnualFigure] {
        let annual = fundamentals
            .filter { $0.concept == .revenue && $0.periodKind == .annual }
            .sorted { $0.periodEnd < $1.periodEnd }
        return annual.enumerated().reversed().map { index, fact in
            let previous = index > 0 ? annual[index - 1].value : nil
            let growth = previous.flatMap { ReturnCalculator.simpleReturn(from: $0, to: fact.value) }
            return AnnualFigure(fact: fact, growth: growth)
        }
    }

    /// Free cash flow per annual period: operating cash flow less capex.
    ///
    /// Computed rather than read, because issuers do not file an "FCF" concept.
    /// A period missing either input yields no figure, never a partial one.
    ///
    /// Keyed on the period the figures describe, **not** on `fiscalYear`. That
    /// field is the *filing's* fiscal context, so a restatement carries the
    /// filing's year rather than the period's — which pairs cash-flow figures
    /// with the wrong dates and puts a real number under a wrong year. The same
    /// mistake was already fixed once in XBRL extraction; it survived here.
    var annualFreeCashFlow: [CashFlowPoint] {
        let ocf = annualValuesByPeriod(.operatingCashFlow)
        let capex = annualValuesByPeriod(.capitalExpenditures)

        return ocf.compactMap { period, operating -> CashFlowPoint? in
            guard let spend = capex[period] else { return nil }
            // Capex is filed as a positive outflow.
            return CashFlowPoint(period: period, value: operating - abs(spend))
        }
        .sorted { $0.period < $1.period }
    }

    /// Annual values for one concept, keyed by the period they describe.
    /// Where a period has been restated, the most recently filed figure wins —
    /// the original is still in the store, which is the point of keeping it.
    private func annualValuesByPeriod(_ concept: FinancialConcept) -> [Date: Double] {
        let facts = fundamentals
            .filter { $0.concept == concept && $0.periodKind == .annual }
            .sorted { ($0.filedAt ?? .distantPast) < ($1.filedAt ?? .distantPast) }
        return Dictionary(facts.map { ($0.periodEnd, $0.value) },
                          uniquingKeysWith: { _, latest in latest })
    }
}

/// A valuation metric together with where it sits in its own history.
struct RankedMetric: Identifiable, Sendable {
    var id: String { metric.id }
    let metric: ValuationMetric
    let context: HistoricalContext
    /// The date the current value refers to. Equal to now for live figures;
    /// older for metrics sourced from the quarterly series.
    let asOf: Date
}

/// One annual figure with its year-over-year growth, when a prior year exists.
struct AnnualFigure: Identifiable, Sendable {
    var id: Date { fact.periodEnd }
    let fact: FinancialFactDTO
    let growth: Double?

    /// The year the period actually ended in.
    ///
    /// Not `fact.fiscalYear`: that is the filing's fiscal context, and a
    /// restated period carries the restating filing's year. Two different years
    /// then render under the same label — observed on screen as two "2022"
    /// rows holding FY2022 and FY2021 revenue.
    var periodLabel: String {
        Calendar.current.component(.year, from: fact.periodEnd).formatted(.number.grouping(.never))
    }
}

struct CashFlowPoint: Identifiable, Sendable {
    var id: Date { period }
    let period: Date
    let value: Double
}
