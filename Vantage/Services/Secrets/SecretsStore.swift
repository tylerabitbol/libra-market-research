import Foundation
import Security

/// Credentials the app needs, stored in the Keychain.
///
/// Section 19 requires that keys never appear in the client UI or in source.
/// The user enters each value once in Settings → Data Sources; it goes
/// straight to the Keychain and is never written to the SwiftData store, never
/// logged, and never included in a cache key. Reads return the value only to
/// the provider that needs it for an Authorization header.
enum SecretKey: String, CaseIterable, Sendable {
    case finnhubAPIKey
    case tiingoAPIKey
    /// Alpaca authenticates with a key *pair*, both halves sent as headers.
    case alpacaKeyID
    case alpacaSecretKey
    case fredAPIKey
    /// Not a credential, but it belongs with them: SEC EDGAR's fair-access
    /// policy requires a contact address in the User-Agent header, and that
    /// address is personal data that shouldn't be committed to the repo.
    case secContactEmail
    /// The organisation half of the SEC User-Agent. Optional: defaults to the
    /// app name, which satisfies the policy on its own.
    case secOrganizationName

    var displayName: String {
        switch self {
        case .finnhubAPIKey: "Finnhub API key"
        case .tiingoAPIKey: "Tiingo API key"
        case .alpacaKeyID: "Alpaca key ID"
        case .alpacaSecretKey: "Alpaca secret key"
        case .fredAPIKey: "FRED API key"
        case .secContactEmail: "SEC contact email"
        case .secOrganizationName: "SEC organisation name"
        }
    }

    var helpText: String {
        switch self {
        case .finnhubAPIKey:
            "Free key from finnhub.io. Used for quotes, company profiles, fundamentals and news."
        case .tiingoAPIKey:
            "Free key from tiingo.com. Used for daily price history — charts, moving averages and volatility all depend on it."
        case .alpacaKeyID:
            "Free from alpaca.markets — no funding required. Used only for the 1D and 5D intraday charts."
        case .alpacaSecretKey:
            "The secret half of the Alpaca key pair. Both halves are needed; neither works alone."
        case .fredAPIKey:
            "Free key from fred.stlouisfed.org. Used for macroeconomic series."
        case .secContactEmail:
            "Required by the SEC. They ask every automated client to identify itself with a contact address. Requests to EDGAR are disabled until this is set."
        case .secOrganizationName:
            "Optional. The SEC's documented User-Agent format is \"Company Name contact@domain.com\". Leave blank to identify as \"Vantage\"."
        }
    }

    /// Secrets are masked in the UI; the contact email is not a secret and is
    /// more useful shown in full so typos are visible.
    /// Neither the contact email nor the organisation name is a credential;
    /// both are identification, and both are more useful shown in full so a
    /// typo is visible.
    var isSensitive: Bool {
        self != .secContactEmail && self != .secOrganizationName
    }
}

/// Whether secure storage actually works right now.
///
/// This exists because a misconfigured build breaks the Keychain in a way that
/// is invisible from the outside: writes throw, but *reads* just return nil, so
/// the app cheerfully reports "key not set" for a key the user already entered.
/// Asking the store to prove it works is the only reliable way to tell.
enum SecretsHealth: Sendable, Equatable {
    case available
    /// Carries an explanation the user can act on, not an error code.
    case unavailable(reason: String)

    var isAvailable: Bool { self == .available }

    var reason: String? {
        if case .unavailable(let reason) = self { return reason }
        return nil
    }
}

/// Read/write access to stored credentials.
protocol SecretsStoring: Sendable {
    func value(for key: SecretKey) -> String?
    func set(_ value: String?, for key: SecretKey) throws
    func hasValue(for key: SecretKey) -> Bool
    /// Proves the store can round-trip a value, rather than assuming it can.
    func diagnose() -> SecretsHealth
}

extension SecretsStoring {
    func diagnose() -> SecretsHealth { .available }

    /// A masked fingerprint of a stored value: its length and first/last four
    /// characters, e.g. "40 chars · d54d…4940".
    ///
    /// Enough to compare against the key shown in a provider's dashboard —
    /// which is how a truncated paste or a key entered in the wrong field gets
    /// caught — without putting the credential on screen. Returns nil when
    /// nothing is stored, and refuses to fingerprint a value too short to mask.
    func fingerprint(for key: SecretKey) -> String? {
        guard let value = value(for: key)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty
        else { return nil }

        // Email addresses aren't secrets and are more useful shown in full.
        guard key.isSensitive else { return value }

        guard value.count >= 12 else {
            return "\(value.count) chars · too short to be valid"
        }
        return "\(value.count) chars · \(value.prefix(4))…\(value.suffix(4))"
    }
}

extension SecretsStoring {

    func hasValue(for key: SecretKey) -> Bool {
        guard let value = value(for: key) else { return false }
        return !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Returns the value or throws the error the UI knows how to act on.
    func require(_ key: SecretKey, for provider: DataProviderID) throws -> String {
        guard let value = value(for: key)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty
        else { throw APIError.missingCredentials(provider) }
        return value
    }
}

/// Keychain-backed implementation using a generic password item per key.
struct KeychainSecretsStore: SecretsStoring {
    private let service: String

    init(service: String = "com.tylerabitbol.vantage.secrets") {
        self.service = service
    }

    func value(for key: SecretKey) -> String? {
        var query = baseQuery(for: key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func set(_ value: String?, for key: SecretKey) throws {
        let query = baseQuery(for: key)

        guard let value, !value.isEmpty else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw SecretsError.keychain(status)
            }
            return
        }

        let data = Data(value.utf8)
        let attributes: [String: Any] = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)

        switch updateStatus {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            var insert = query
            insert[kSecValueData as String] = data
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            let addStatus = SecItemAdd(insert as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw SecretsError.keychain(addStatus) }
        default:
            throw SecretsError.keychain(updateStatus)
        }
    }

    private func baseQuery(for key: SecretKey) -> [String: Any] {
        baseQuery(account: key.rawValue)
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }

    /// Writes, reads back, and deletes a throwaway value.
    ///
    /// A capability check rather than an inference: entitlement problems,
    /// a locked device, and a corrupt keychain all present differently, and
    /// only an actual round-trip distinguishes "works" from "silently empty".
    func diagnose() -> SecretsHealth {
        let account = "__healthcheck__"
        let probe = Data("ok".utf8)
        var query = baseQuery(account: account)

        // Clear any residue from an interrupted earlier check.
        SecItemDelete(query as CFDictionary)

        query[kSecValueData as String] = probe
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let addStatus = SecItemAdd(query as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            return .unavailable(reason: SecretsError.explain(addStatus))
        }

        defer { SecItemDelete(baseQuery(account: account) as CFDictionary) }

        var read = baseQuery(account: account)
        read[kSecReturnData as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let readStatus = SecItemCopyMatching(read as CFDictionary, &item)
        guard readStatus == errSecSuccess else {
            return .unavailable(reason: SecretsError.explain(readStatus))
        }
        guard (item as? Data) == probe else {
            return .unavailable(reason: "The Keychain returned a different value than was written.")
        }
        return .available
    }
}

enum SecretsError: Error, LocalizedError {
    case keychain(OSStatus)

    var errorDescription: String? {
        switch self {
        case .keychain(let status): SecretsError.explain(status)
        }
    }

    /// Turns an OSStatus into something the user can act on.
    ///
    /// `-34018` in particular is not a user error and not a transient glitch:
    /// it means the build was signed without Keychain entitlements. Printing
    /// the bare code sent an earlier debugging session down the wrong path.
    static func explain(_ status: OSStatus) -> String {
        switch status {
        case errSecMissingEntitlement:
            return "This build can't use the Keychain because it was signed without the "
                 + "required entitlements. That's a build configuration problem, not "
                 + "something you did — code signing must be enabled with a development team."
        case errSecInteractionNotAllowed:
            return "The Keychain is locked. Unlock the device and try again."
        case errSecAuthFailed:
            return "The Keychain refused access. You may need to unlock the device."
        case errSecNotAvailable:
            return "The Keychain is unavailable on this device right now."
        default:
            let message = SecCopyErrorMessageString(status, nil) as String? ?? "unknown error"
            return "Keychain error \(status): \(message)"
        }
    }
}

/// In-memory store for previews and tests, so neither ever touches the real
/// Keychain or requires real credentials.
final class InMemorySecretsStore: SecretsStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [SecretKey: String]

    init(seed: [SecretKey: String] = [:]) {
        self.storage = seed
    }

    func value(for key: SecretKey) -> String? {
        lock.withLock { storage[key] }
    }

    func set(_ value: String?, for key: SecretKey) throws {
        lock.withLock {
            if let value, !value.isEmpty { storage[key] = value } else { storage[key] = nil }
        }
    }
}
