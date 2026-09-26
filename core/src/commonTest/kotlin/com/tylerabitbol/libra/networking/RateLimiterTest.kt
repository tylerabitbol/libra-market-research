package com.tylerabitbol.libra.networking

import com.tylerabitbol.libra.models.provenance.DataProviderID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.yield

/**
 * The limiter, on virtual time.
 *
 * Swift measured against `ContinuousClock` and really slept. Here `runTest`
 * drives both the delay and the limiter's own clock, so the same assertions
 * hold without a test suite that takes a minute to prove a one-hour refill.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {

    @Test
    fun burstPassesImmediately() = runTest {
        val limiter = RateLimiter(10, 1.seconds, burst = 5, timeSource = testTimeSource)
        val start = testTimeSource.markNow()
        repeat(5) { limiter.waitForSlot() }
        assertTrue(start.elapsedNow() < 100.milliseconds, "Burst capacity should not block")
    }

    @Test
    fun exceedingBurstBlocks() = runTest {
        // 10/sec means the 3rd request after a burst of 2 must wait ~100ms.
        val limiter = RateLimiter(10, 1.seconds, burst = 2, timeSource = testTimeSource)
        repeat(2) { limiter.waitForSlot() }

        val start = testTimeSource.markNow()
        limiter.waitForSlot()
        assertTrue(
            start.elapsedNow() >= 50.milliseconds,
            "Third request should have been throttled"
        )
    }

    @Test
    fun cancellationIsHonoured() = runTest {
        val limiter = RateLimiter(1, 60.seconds, burst = 1, timeSource = testTimeSource)
        limiter.waitForSlot() // drain

        var thrown: Throwable? = null
        val job = launch {
            try {
                limiter.waitForSlot(maxWait = 60.seconds)
            } catch (cancellation: CancellationException) {
                thrown = cancellation
                throw cancellation
            }
        }
        yield()
        job.cancel()
        job.join()
        assertTrue(thrown is CancellationException)
    }

    // MARK: - Back-pressure

    @Test
    fun longWaitIsRefused() = runTest {
        // Tiingo's real shape: ~1 token per 80 seconds once the burst is spent.
        val limiter = RateLimiter(
            45, 1.hours, burst = 2, provider = DataProviderID.Tiingo,
            timeSource = testTimeSource
        )
        repeat(2) { limiter.waitForSlot() }

        // The third request would wait ~80s. With a 5s budget it must give up.
        try {
            limiter.waitForSlot(maxWait = 5.seconds)
            fail("Expected a rate-limit refusal")
        } catch (error: APIError) {
            assertTrue(error is APIError.RateLimited)
        }
    }

    @Test
    fun refusalNamesProvider() = runTest {
        val limiter = RateLimiter(
            45, 1.hours, burst = 1, provider = DataProviderID.Tiingo,
            timeSource = testTimeSource
        )
        limiter.waitForSlot()
        try {
            limiter.waitForSlot(maxWait = 1.seconds)
            fail("Expected a rate-limit refusal")
        } catch (error: APIError) {
            assertEquals(DataProviderID.Tiingo, error.provider)
            assertTrue(error.isRetryable)
        }
    }

    @Test
    fun estimatedWaitIsZeroWithCapacity() = runTest {
        val limiter = RateLimiter(
            45, 1.hours, burst = 3, provider = DataProviderID.Tiingo,
            timeSource = testTimeSource
        )
        assertEquals(Duration.ZERO, limiter.estimatedWait())
    }

    @Test
    fun generousBudgetStillWaits() = runTest {
        // 10/sec: the third request after a burst of 2 waits ~100ms, well
        // inside a default budget, so background backfills keep working.
        val limiter = RateLimiter(
            10, 1.seconds, burst = 2, provider = DataProviderID.Tiingo,
            timeSource = testTimeSource
        )
        repeat(2) { limiter.waitForSlot() }
        limiter.waitForSlot(maxWait = 5.seconds)
    }

    @Test
    fun penaltyDrainsTheBucketSoTheNextCallBacksOff() = runTest {
        // No Swift counterpart. `penalize` is only reached from a 429 inside
        // HTTPClient, and getting it wrong means retrying straight back into
        // the same wall — which is what the retry path exists to avoid.
        val limiter = RateLimiter(
            10, 1.seconds, burst = 5, provider = DataProviderID.Finnhub,
            timeSource = testTimeSource
        )
        limiter.penalize(2.seconds)

        // The penalty empties the bucket *and* freezes the refill clock for
        // the duration, so the next caller waits out the penalty rather than
        // the one-token refill it would otherwise have earned.
        val start = testTimeSource.markNow()
        limiter.waitForSlot(maxWait = 30.seconds)
        assertTrue(start.elapsedNow() >= 2.seconds, "Got ${start.elapsedNow()}")
    }

    @Test
    fun theWaitDoesNotHoldTheLock() = runTest {
        // No Swift counterpart: the actor serialised callers for free, a Mutex
        // does not. Holding the lock across the delay would make the second
        // caller wait for the first one's sleep *and then* its own.
        val limiter = RateLimiter(
            10, 1.seconds, burst = 1, provider = DataProviderID.Finnhub,
            timeSource = testTimeSource
        )
        limiter.waitForSlot()

        val start = testTimeSource.markNow()
        val first = launch { limiter.waitForSlot() }
        val second = launch { limiter.waitForSlot() }
        first.join()
        second.join()

        // Two tokens at 10/sec is ~200ms of refill. Serialised behind a held
        // lock it would be noticeably more.
        assertTrue(
            start.elapsedNow() < 400.milliseconds,
            "Callers must queue on tokens, not on each other's sleep"
        )
    }
}
