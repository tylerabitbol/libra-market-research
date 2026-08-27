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
