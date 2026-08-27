import Foundation
import OSLog
import SwiftData

/// Debug-build conveniences for testing against live APIs.
///
/// Keys are read from the environment, never compiled in. Hardcoding them would
/// put live credentials in source control, in every build artefact, and in any
/// crash log — and the repository is exactly where secrets are hardest to
/// recall once they have been committed.
///
/// Set them on the Run scheme (Product → Scheme → Edit Scheme → Run →
/// Arguments → Environment Variables), or pass them to a simulator launch with
/// simctl's `SIMCTL_CHILD_` prefix:
///
///     SIMCTL_CHILD_VANTAGE_FINNHUB_KEY=… \
///     xcrun simctl launch booted com.tylerabitbol.vantage -VantageSeedKeys
///
/// Seeding requires the `-VantageSeedKeys` launch argument as well as the
/// environment values, so a stray variable in a shell profile cannot silently
/// overwrite the keys entered in Settings.
enum DeveloperOptions {
    static let seedArgument = "-VantageSeedKeys"

    private static let logger = Logger(subsystem: "com.tylerabitbol.vantage", category: "devoptions")

    /// Environment variable backing each secret.
    private static let mapping: [(SecretKey, String)] = [
        (.finnhubAPIKey, "VANTAGE_FINNHUB_KEY"),
        (.tiingoAPIKey, "VANTAGE_TIINGO_KEY"),
        (.fredAPIKey, "VANTAGE_FRED_KEY"),
        (.secContactEmail, "VANTAGE_SEC_EMAIL")
    ]

    static let seedWatchlistArgument = "-VantageSeedWatchlist"

    static var isWatchlistSeedRequested: Bool {
        #if DEBUG
        CommandLine.arguments.contains(seedWatchlistArgument)
        #else
        false
        #endif
    }

    /// Populates the watchlist with a few well-known symbols.
    ///
    /// Debug builds only, and only on an explicit launch argument. Existing
    /// entries are left alone so this never clobbers a real watchlist.
    @MainActor
    static func seedWatchlist(context: ModelContext) {
        #if DEBUG
        guard isWatchlistSeedRequested else { return }

        let seeds = [
            ("AAPL", "Apple Inc.", "Information Technology", "0000320193"),
            ("NVDA", "NVIDIA Corporation", "Information Technology", "0001045810"),
            ("COST", "Costco Wholesale Corporation", "Consumer Staples", "0000909832")
        ]

        for (symbol, name, sector, cik) in seeds {
            let descriptor = FetchDescriptor<Security>(
                predicate: #Predicate { $0.symbol == symbol }
            )
            let existing = try? context.fetch(descriptor).first
            let security: Security
            if let existing {
                security = existing
            } else {
                security = Security(symbol: symbol, name: name, sector: sector, cik: cik)
                context.insert(security)
            }
            guard security.watchlistEntry == nil else { continue }
            context.insert(WatchlistEntry(security: security))
        }

        do {
            try context.save()
            logger.notice("Seeded watchlist with \(seeds.count, privacy: .public) securities.")
        } catch {
            logger.error("Watchlist seed failed: \(error.localizedDescription, privacy: .public)")
        }
        #endif
    }

    /// `-VantageOpenSymbol AAPL` opens straight to a security's detail page.
    ///
    /// Exists because capturing that screen previously meant editing
    /// `RootView` by hand, and one of those edits was committed — leaving the
    /// Watchlist tab wired to a hardcoded symbol. A supported route costs a few
    /// lines and removes the need to touch navigation at all.
    static var debugSymbol: String? {
        #if DEBUG
        let arguments = CommandLine.arguments
        guard let index = arguments.firstIndex(of: "-VantageOpenSymbol"),
              index + 1 < arguments.count
        else { return nil }
        let symbol = arguments[index + 1].trimmingCharacters(in: .whitespaces).uppercased()
        return symbol.isEmpty ? nil : symbol
        #else
        return nil
        #endif
    }

    static var isSeedRequested: Bool {
        #if DEBUG
        CommandLine.arguments.contains(seedArgument)
        #else
        false
        #endif
    }

    /// Copies any provided environment values into secure storage.
    ///
    /// Compiled out of release builds entirely. Logs which keys were seeded and
    /// never their values — a log line is not a place for a credential.
    @discardableResult
    static func seedSecretsFromEnvironment(into secrets: any SecretsStoring) -> [SecretKey] {
        #if DEBUG
        guard isSeedRequested else { return [] }

        var seeded: [SecretKey] = []
        let environment = ProcessInfo.processInfo.environment
        for (key, variable) in mapping {
            guard let raw = environment[variable]?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !raw.isEmpty
            else { continue }
            do {
                try secrets.set(raw, for: key)
                seeded.append(key)
            } catch {
                logger.error("Could not seed \(key.rawValue, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }
        }

        if seeded.isEmpty {
            logger.notice("Seed requested but no VANTAGE_* environment variables were set.")
        } else {
            let names = seeded.map(\.rawValue).joined(separator: ", ")
            logger.notice("Seeded from environment: \(names, privacy: .public)")
        }
        return seeded
        #else
        return []
        #endif
    }
}
