import Foundation
import SwiftData

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

    static let shared: ModelContainer = {
        do {
            return try ModelContainer(
                for: schema,
                configurations: ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)
            )
        } catch {
            // A container that cannot open is not recoverable at runtime — the
            // app has no data layer at all. Fail loudly here rather than
            // shipping a silently empty store.
            fatalError("Could not create ModelContainer: \(error)")
        }
    }()

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
