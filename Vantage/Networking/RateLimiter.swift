import Foundation

/// A token-bucket rate limiter, one instance per upstream provider.
///
/// SEC EDGAR's fair-access policy caps requests at 10/second and will block a
/// client that ignores it; Finnhub's free tier allows 60/minute. Getting
/// throttled or blocked is the most likely way this app breaks in normal use,
/// so limiting is enforced client-side before a request goes out rather than
/// reacting to 429s after the fact.
actor RateLimiter {
    private let capacity: Double
    private let refillPerSecond: Double
    private var tokens: Double
    private var lastRefill: Date
    /// Named so a refusal reports which upstream limit was hit.
    private let provider: DataProviderID

    /// - Parameters:
    ///   - requests: how many requests are permitted per `interval`.
    ///   - interval: the window those requests are spread over.
    ///   - burst: how many may be spent at once. Defaults to `requests`.
    init(requests: Int, per interval: TimeInterval, burst: Int? = nil,
         provider: DataProviderID = .computed) {
        precondition(requests > 0 && interval > 0, "Rate limit must be positive")
        self.capacity = Double(burst ?? requests)
        self.refillPerSecond = Double(requests) / interval
        self.tokens = Double(burst ?? requests)
        self.lastRefill = .now
        self.provider = provider
    }

    /// Blocks until a token is available, or gives up if that would take longer
    /// than `maxWait`.
    ///
    /// The bound matters more than it looks. Tiingo's free tier refills roughly
    /// one token every 80 seconds, so an unbounded wait turns a screen needing a
    /// dozen requests into eight minutes of a spinner with no explanation. A
    /// caller that is told "rate limited" can say so; a caller left blocking
    /// cannot. Pass a longer `maxWait` deliberately for background backfills.
    func waitForSlot(maxWait: TimeInterval = 15) async throws {
        while true {
            try Task.checkCancellation()
            refill()
            if tokens >= 1 {
                tokens -= 1
                return
            }

            let deficit = 1 - tokens
            let seconds = deficit / refillPerSecond
            guard seconds <= maxWait else {
                throw APIError.rateLimited(provider, retryAfter: seconds)
            }
            try await Task.sleep(for: .seconds(max(seconds, 0.01)))
        }
    }

    /// How long the next request would have to wait right now. Lets callers
    /// budget a batch of work instead of discovering the wall mid-flight.
    func estimatedWait() -> TimeInterval {
        refill()
        if tokens >= 1 { return 0 }
        return (1 - tokens) / refillPerSecond
    }

    /// Called after a 429 to drain the bucket, so the next requests back off
    /// rather than immediately retrying into the same wall.
    func penalize(for duration: TimeInterval) {
        refill()
        tokens = min(tokens, 0)
        lastRefill = Date.now.addingTimeInterval(duration)
    }

    private func refill() {
        let now = Date.now
        let elapsed = now.timeIntervalSince(lastRefill)
        guard elapsed > 0 else { return }
        tokens = min(capacity, tokens + elapsed * refillPerSecond)
        lastRefill = now
    }

    // MARK: - Provider policies

    /// SEC asks for no more than 10 requests/second. We use 8 to leave headroom.
    static func sec() -> RateLimiter { RateLimiter(requests: 8, per: 1, burst: 8, provider: .sec) }

    /// Finnhub's free tier documents 60 calls/minute. We use 50.
    static func finnhub() -> RateLimiter { RateLimiter(requests: 50, per: 60, burst: 10, provider: .finnhub) }

    /// Tiingo's free tier allows 50 requests/hour and 1000/day. The hourly cap
    /// is the binding one and is easy to exhaust while backfilling history, so
    /// we pace to 45/hour with a small burst for interactive use.
    static func tiingo() -> RateLimiter { RateLimiter(requests: 45, per: 3600, burst: 6, provider: .tiingo) }

    /// FRED permits 120 requests/minute.
    static func fred() -> RateLimiter { RateLimiter(requests: 100, per: 60, burst: 20, provider: .fred) }
}
