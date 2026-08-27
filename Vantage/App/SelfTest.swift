import Foundation
import OSLog

/// A startup self-check, run only when launched with `-VantageSelfTest`.
///
/// The Keychain cannot be exercised by the unit test bundle: that bundle has no
/// host app, so it carries no entitlements of its own — the very condition that
/// caused the -34018 failure this check exists to catch. Running inside the real,
/// signed app is the only place the answer is meaningful.
enum SelfTest {
    static let launchArgument = "-VantageSelfTest"

    static var isRequested: Bool {
        CommandLine.arguments.contains(launchArgument)
    }

    private static let logger = Logger(subsystem: "com.tylerabitbol.vantage", category: "selftest")

    /// Exercises every configured provider with a real request and logs the
    /// outcome.
    ///
    /// Credentials are read from the Keychain and never appear in a command
    /// line, a log line, or this source file — which is what makes it safe to
    /// verify live API access without handling the keys directly.
    static func runConnectionTests(secrets: any SecretsStoring) async {
        let client = HTTPClient()
        let finnhub = FinnhubProvider(client: client, secrets: secrets)
        let tiingo = TiingoProvider(client: client, secrets: secrets)

        let registry = ProviderRegistry(
            marketData: CompositeMarketDataProvider(quotes: finnhub, history: tiingo),
            fundamentals: nil,
            analyst: FinnhubAnalystProvider(provider: finnhub),
            sec: SECProvider(client: client, secrets: secrets),
            macro: FREDProvider(client: client, secrets: secrets),
            news: FinnhubNewsProvider(provider: finnhub),
            isUsingSampleData: false
        )

        for provider in [DataProviderID.finnhub, .tiingo, .fred, .sec] {
            let result = await ConnectionTest.run(for: provider, registry: registry)
            let status = result.isSuccess ? "PASS" : "FAIL"
            logger.notice(
                "SELFTEST \(provider.rawValue, privacy: .public)=\(status, privacy: .public) \(result.message, privacy: .public)"
            )
        }
    }

    /// Reports secure-storage health to the unified log and returns whether it passed.
    @discardableResult
    static func run(secrets: any SecretsStoring) -> Bool {
        switch secrets.diagnose() {
        case .available:
            logger.notice("SELFTEST keychain=PASS round-trip succeeded")
            return true
        case .unavailable(let reason):
            logger.error("SELFTEST keychain=FAIL \(reason, privacy: .public)")
            return false
        }
    }
}
