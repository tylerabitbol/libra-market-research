import Testing
import Foundation
@testable import Vantage

@Suite("Rate limiter")
struct RateLimiterTests {
    @Test("Requests within the burst allowance pass without delay")
    func burstPassesImmediately() async throws {
        let limiter = RateLimiter(requests: 10, per: 1, burst: 5)
        let start = ContinuousClock.now
        for _ in 0..<5 { try await limiter.waitForSlot() }
        let elapsed = ContinuousClock.now - start
        #expect(elapsed < .milliseconds(100), "Burst capacity should not block")
    }

    @Test("Exceeding the burst forces a wait")
    func exceedingBurstBlocks() async throws {
        // 10/sec means the 3rd request after a burst of 2 must wait ~100ms.
        let limiter = RateLimiter(requests: 10, per: 1, burst: 2)
        for _ in 0..<2 { try await limiter.waitForSlot() }

        let start = ContinuousClock.now
        try await limiter.waitForSlot()
        let elapsed = ContinuousClock.now - start
        #expect(elapsed >= .milliseconds(50), "Third request should have been throttled")
    }

    @Test("Cancellation is honoured while waiting for a slot")
    func cancellationIsHonoured() async throws {
        let limiter = RateLimiter(requests: 1, per: 60, burst: 1)
        try await limiter.waitForSlot() // drain

        let task = Task { try await limiter.waitForSlot() }
        task.cancel()
        await #expect(throws: CancellationError.self) { try await task.value }
    }
}
