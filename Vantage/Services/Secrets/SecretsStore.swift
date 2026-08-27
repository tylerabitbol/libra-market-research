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
    case fredAPIKey
    case anthropicAPIKey
    /// Not a credential, but it belongs with them: SEC EDGAR's fair-access
    /// policy requires a contact address in the User-Agent header, and that
    /// address is personal data that shouldn't be committed to the repo.
    case secContactEmail

    var displayName: String {
        switch self {
        case .finnhubAPIKey: "Finnhub API key"
        case .tiingoAPIKey: "Tiingo API key"
        case .fredAPIKey: "FRED API key"
        case .anthropicAPIKey: "Anthropic API key"
        case .secContactEmail: "SEC contact email"
        }
    }

    var helpText: String {
        switch self {
        case .finnhubAPIKey:
            "Free key from finnhub.io. Used for quotes, company profiles, fundamentals and news."
        case .tiingoAPIKey:
            "Free key from tiingo.com. Used for daily price history — charts, moving averages and volatility all depend on it."
        case .fredAPIKey:
            "Free key from fred.stlouisfed.org. Used for macroeconomic series."
        case .anthropicAPIKey:
            "Optional. Only used if you turn on AI summaries. Calls are billed to your account."
        case .secContactEmail:
            "Required by the SEC. They ask every automated client to identify itself with a contact address. Requests to EDGAR are disabled until this is set."
        }
    }

    /// Secrets are masked in the UI; the contact email is not a secret and is
    /// more useful shown in full so typos are visible.
    var isSensitive: Bool { self != .secContactEmail }
}

/// Read/write access to stored credentials.
protocol SecretsStoring: Sendable {
    func value(for key: SecretKey) -> String?
    func set(_ value: String?, for key: SecretKey) throws
    func hasValue(for key: SecretKey) -> Bool
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
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key.rawValue
        ]
    }
}

enum SecretsError: Error, LocalizedError {
    case keychain(OSStatus)

    var errorDescription: String? {
        switch self {
        case .keychain(let status):
            let message = SecCopyErrorMessageString(status, nil) as String? ?? "unknown"
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
