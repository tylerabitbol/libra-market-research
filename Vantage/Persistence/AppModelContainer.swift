import Foundation
import SwiftData
import OSLog

/// The app's SwiftData stack.
///
/// A single schema declaration kept in one place so that adding a model is one
/// edit and so the in-memory preview/test container can never drift from the
/// real one.
enum AppModelContainer {
    static let schema = Schema([
        Security.self,
        WatchlistEntry.self,
        QuoteObservation.self,
        PriceBar.self,
        FinancialFactRecord.self,
        FilingRecord.self,
        InsiderTransaction.self,
        DetectedEvent.self,
        MacroObservation.self
    ])

    private static let logger = Logger(
        subsystem: "com.tylerabitbol.vantage", category: "persistence"
    )

    static let shared: ModelContainer = {
        do {
            let container = try ModelContainer(
                for: schema,
                configurations: ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)
            )
            // Before anything can read it. `shared` is resolved by the scene's
            // `.modelContainer` modifier, so this runs ahead of the first
            // hydration rather than racing it.
            evictSyntheticRows(in: container)
            return container
        } catch {
            // A container that cannot open is not recoverable at runtime — the
            // app has no data layer at all. Fail loudly here rather than
            // shipping a silently empty store.
            fatalError("Could not create ModelContainer: \(error)")
        }
    }()

    /// Removes rows that a keyless run wrote and nothing later could tell
    /// apart from real data.
    ///
    /// Until `PriceBar` carried a provider, a run with no API keys stored five
    /// years of `SampleData` closes — one per calendar day, weekends included,
    /// a smooth ramp to a four-figure price — and the store then served them in
    /// preference to fetching real ones, because they looked like a fresh held
    /// copy. Two signatures identify what to remove, and neither is a guess:
    ///
    /// - A bar with no provider. Every write now names one, so an unattributed
    ///   row is by definition one written before the column existed and of
    ///   unknown origin. Bars are the one thing here that a single request
    ///   restores, which is what makes deleting rather than flagging them
    ///   affordable.
    /// - An accession number beginning `0000000000-`. EDGAR builds accessions
    ///   from the filer's own ten-digit CIK, and no filer has CIK zero. Only
    ///   `MockSECDataProvider` emits that prefix.
    ///
    /// Events detected *over* those series go too, but only the kinds that are
    /// re-derived from bars and filings on the next visit — a synthetic "fell
    /// 4.2%" headline is otherwise permanent in the Research feed, while
    /// fundamental events, which the sample registry cannot produce and nothing
    /// backfills, are left alone. An acknowledged event that returns
    /// unacknowledged is the accepted cost.
    @discardableResult
    static func evictSyntheticRows(in container: ModelContainer) -> Int {
        let context = ModelContext(container)
        var removed = 0
        do {
            let bars = try context.fetch(FetchDescriptor<PriceBar>(
                predicate: #Predicate { $0.providerRaw == nil }))
            let filings = try context.fetch(FetchDescriptor<FilingRecord>(
                predicate: #Predicate { $0.accessionNumber.starts(with: mockAccessionPrefix) }))
            let insiders = try context.fetch(FetchDescriptor<InsiderTransaction>(
                predicate: #Predicate { $0.accessionNumber.starts(with: mockAccessionPrefix) }))
            // Facts carry the accession of the filing they were extracted from,
            // so the same impossible-CIK test applies. No mock fundamentals
            // provider exists today, which is why these rows are not expected
            // to be found — but the eviction is what makes that a fact about
            // the store rather than a fact about the registry, and the registry
            // is the easier of the two to change by accident.
            // Filtered in memory rather than by predicate: `accessionNumber` is
            // optional here, and every way of writing that in `#Predicate`
            // compiles to a ternary SwiftData cannot turn into SQL. The set is
            // bounded by the securities the user has actually opened, and this
            // runs once per launch.
            let facts = try context.fetch(FetchDescriptor<FinancialFactRecord>())
                .filter { $0.accessionNumber?.starts(with: mockAccessionPrefix) == true }

            // Whose derived history is now suspect: the securities that held
            // any of the above.
            var affected = Set(bars.compactMap { $0.security?.symbol })
            affected.formUnion(filings.compactMap { $0.security?.symbol })
            affected.formUnion(insiders.compactMap { $0.security?.symbol })
            affected.formUnion(facts.compactMap { $0.security?.symbol })

            for bar in bars { context.delete(bar) }
            for filing in filings { context.delete(filing) }
            for insider in insiders { context.delete(insider) }
            for fact in facts { context.delete(fact) }
            removed = bars.count + filings.count + insiders.count + facts.count

            if !affected.isEmpty {
                let rederivable = Set(EventKind.rederivedFromSeries.map(\.rawValue))
                let events = try context.fetch(FetchDescriptor<DetectedEvent>(
                    predicate: #Predicate<DetectedEvent> { event in
                        rederivable.contains(event.kindRaw)
                    }))
                for event in events
                where event.security.map({ affected.contains($0.symbol) }) == true {
                    context.delete(event)
                    removed += 1
                }
            }

            guard removed > 0 else { return 0 }
            try context.save()
            logger.notice("Evicted \(removed, privacy: .public) unattributed or synthetic rows")
        } catch {
            // A failed eviction leaves the store exactly as it was. It is not
            // worth refusing to launch over, but it is worth saying: the rows
            // it would have removed are the ones that misreport themselves.
            logger.error("Eviction failed: \(error.localizedDescription, privacy: .public)")
        }
        return removed
    }

    /// Ten zeroes and a dash: the CIK position of an accession number, for a
    /// filer that cannot exist.
    private static let mockAccessionPrefix = "0000000000-"

    /// In-memory container for previews and tests. Same schema, no disk.
    static var preview: ModelContainer {
        do {
            return try ModelContainer(
                for: schema,
                configurations: ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
            )
        } catch {
            fatalError("Could not create preview ModelContainer: \(error)")
        }
    }
}
