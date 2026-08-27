import Foundation
import OSLog

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
