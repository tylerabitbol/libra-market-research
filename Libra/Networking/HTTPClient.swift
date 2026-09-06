import Foundation
import OSLog

/// The single path every outbound request takes.
///
/// Centralising this is what makes Section 21 achievable: rate limiting,
/// retry/backoff, status-code interpretation and decoding all happen once, so
/// a new provider inherits correct failure behaviour instead of reimplementing
/// it. Providers describe *what* to fetch; this decides *how*.
actor HTTPClient {
    private let session: URLSession
    private let limiters: [DataProviderID: RateLimiter]
    private let logger = Logger(subsystem: "com.tylerabitbol.libra", category: "http")

    private let maxAttempts = 3

    init(session: URLSession? = nil, limiters: [DataProviderID: RateLimiter]? = nil) {
        if let session {
            self.session = session
        } else {
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 20
            config.timeoutIntervalForResource = 60
            config.waitsForConnectivity = false
            // We manage freshness ourselves via StalenessPolicy; URLCache would
            // hide staleness from the UI that has to report it.
            config.requestCachePolicy = .reloadIgnoringLocalCacheData
            self.session = URLSession(configuration: config)
        }
        self.limiters = limiters ?? [
            .sec: .sec(),
            .finnhub: .finnhub(),
            .tiingo: .tiingo(),
            .fred: .fred()
        ]
    }

    /// Fetches and decodes, retrying transient failures with exponential backoff.
    func get<T: Decodable & Sendable>(
        _ endpoint: Endpoint,
        as type: T.Type,
        decoder: JSONDecoder = .libra
    ) async throws -> T {
        let data = try await data(for: endpoint)
        guard !data.isEmpty else {
            throw APIError.noData(endpoint.provider, endpoint: endpoint.label)
        }
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            logger.error("Decode failed for \(endpoint.label): \(error.localizedDescription)")
            throw APIError.decoding(
                endpoint.provider,
                endpoint: endpoint.label,
                underlying: error.localizedDescription
            )
        }
    }

    /// Fetches raw bytes. Used for filing documents, which are HTML/XML rather
    /// than JSON.
    func data(for endpoint: Endpoint) async throws -> Data {
        var lastError: APIError = .transport(endpoint.provider, underlying: "no attempt made")

        for attempt in 1...maxAttempts {
            try Task.checkCancellation()
            if let limiter = limiters[endpoint.provider] {
                try await limiter.waitForSlot()
            }

            do {
                return try await performOnce(endpoint)
            } catch let error as APIError {
                lastError = error
                guard error.isRetryable, attempt < maxAttempts else { throw error }

                var delay = pow(2.0, Double(attempt - 1)) * 0.5
                if case .rateLimited(let provider, let retryAfter) = error {
                    delay = retryAfter ?? delay
                    await limiters[provider]?.penalize(for: delay)
                }
                logger.notice("Retrying \(endpoint.label) in \(delay, format: .fixed(precision: 1))s (attempt \(attempt))")
                try await Task.sleep(for: .seconds(delay))
            } catch is CancellationError {
                throw APIError.cancelled
            }
        }
        throw lastError
    }

    private func performOnce(_ endpoint: Endpoint) async throws -> Data {
        let request = try endpoint.makeRequest()
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError where error.code == .cancelled {
            throw APIError.cancelled
        } catch {
            throw APIError.transport(endpoint.provider, underlying: error.localizedDescription)
        }

        guard let http = response as? HTTPURLResponse else {
            throw APIError.transport(endpoint.provider, underlying: "Non-HTTP response")
        }

        switch http.statusCode {
        case 200...299:
            return data
        case 401:
            throw APIError.invalidCredentials(endpoint.provider)
        case 403:
            // 403 is overloaded across these providers and the distinction is
            // the difference between two very different user actions.
            //
            // Tiingo answers a bad credential with 403 {"detail":"Invalid
            // token."} rather than 401, so a mistyped key would otherwise be
            // reported as "not in your plan" and send the user off to consider
            // upgrading a subscription that was never the problem. Finnhub uses
            // 403 for genuine tier gaps; SEC uses it when the User-Agent is
            // missing. Reading the body is the only way to tell them apart.
            let body = String(decoding: data, as: UTF8.self).lowercased()
            let looksLikeBadCredential = ["invalid token", "invalid api key",
                                          "not authorized", "invalid credentials"]
                .contains { body.contains($0) }
            throw looksLikeBadCredential
                ? APIError.invalidCredentials(endpoint.provider)
                : APIError.notEntitled(endpoint.provider, endpoint: endpoint.label)
        case 404:
            throw APIError.notFound(endpoint.provider, endpoint: endpoint.label)
        case 429:
            let retryAfter = http.value(forHTTPHeaderField: "Retry-After").flatMap(TimeInterval.init)
            throw APIError.rateLimited(endpoint.provider, retryAfter: retryAfter)
        default:
            throw APIError.server(endpoint.provider, statusCode: http.statusCode)
        }
    }
}

extension JSONDecoder {
    /// Shared decoder. Date strategy is deliberately *not* set globally —
    /// the three providers use three different date encodings, so each
    /// provider's DTOs decode dates explicitly.
    static var libra: JSONDecoder {
        let decoder = JSONDecoder()
        return decoder
    }
}
