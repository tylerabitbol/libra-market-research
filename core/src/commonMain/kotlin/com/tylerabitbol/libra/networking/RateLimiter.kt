package com.tylerabitbol.libra.networking

import com.tylerabitbol.libra.models.provenance.DataProviderID
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * A token-bucket rate limiter, one instance per upstream provider.
 *
 * SEC EDGAR's fair-access policy caps requests at 10/second and will block a
 * client that ignores it; Finnhub's free tier allows 60/minute. Getting
 * throttled or blocked is the most likely way this app breaks in normal use,
 * so limiting is enforced client-side before a request goes out rather than
 * reacting to 429s after the fact.
 *
 * Swift used an `actor`; the Kotlin equivalent is a [Mutex] around the bucket
 * state. The wait itself happens outside the lock — holding it across a
 * `delay` would serialise every caller behind the first one's sleep and turn a
 * rate limiter into a queue.
 *
 * [timeSource] is injected so tests can run on virtual time rather than
 * sleeping through a real refill.
 *
 * @param requests how many requests are permitted per [per].
 * @param per the window those requests are spread over.
 * @param burst how many may be spent at once. Defaults to [requests].
 */
class RateLimiter(
    requests: Int,
    per: Duration,
    burst: Int? = null,
    /** Named so a refusal reports which upstream limit was hit. */
    private val provider: DataProviderID = DataProviderID.Computed,
    private val timeSource: TimeSource = TimeSource.Monotonic
) {
    private val capacity: Double
    private val refillPerSecond: Double
    private var tokens: Double
    private var lastRefill: TimeMark
    private val mutex = Mutex()

    init {
        require(requests > 0 && per > Duration.ZERO) { "Rate limit must be positive" }
        capacity = (burst ?: requests).toDouble()
        refillPerSecond = requests / per.toDouble(kotlin.time.DurationUnit.SECONDS)
        tokens = (burst ?: requests).toDouble()
        lastRefill = timeSource.markNow()
    }

    /**
     * Suspends until a token is available, or gives up if that would take
     * longer than [maxWait].
     *
     * The bound matters more than it looks. Tiingo's free tier refills roughly
     * one token every 80 seconds, so an unbounded wait turns a screen needing a
     * dozen requests into eight minutes of a spinner with no explanation. A
     * caller that is told "rate limited" can say so; a caller left blocking
     * cannot. Pass a longer [maxWait] deliberately for background backfills.
     */
    suspend fun waitForSlot(maxWait: Duration = 15.seconds) {
        while (true) {
            coroutineContext.ensureActive()

            val wait = mutex.withLock {
                refill()
                if (tokens >= 1) {
                    tokens -= 1
                    return
                }
                ((1 - tokens) / refillPerSecond).seconds
            }

            if (wait > maxWait) {
                throw APIError.RateLimited(provider, retryAfter = wait)
            }
            delay(maxOf(wait, 10.milliseconds))
        }
    }

    /**
     * How long the next request would have to wait right now. Lets callers
     * budget a batch of work instead of discovering the wall mid-flight.
     */
    suspend fun estimatedWait(): Duration = mutex.withLock {
        refill()
        if (tokens >= 1) Duration.ZERO else ((1 - tokens) / refillPerSecond).seconds
    }

    /**
     * Called after a 429 to drain the bucket, so the next requests back off
     * rather than immediately retrying into the same wall.
     */
    suspend fun penalize(duration: Duration) = mutex.withLock {
        refill()
        tokens = min(tokens, 0.0)
        lastRefill = timeSource.markNow() + duration
    }

    /** Must be called with [mutex] held. */
    private fun refill() {
        val elapsed = lastRefill.elapsedNow()
        if (elapsed <= Duration.ZERO) return
        tokens = min(
            capacity,
            tokens + elapsed.toDouble(kotlin.time.DurationUnit.SECONDS) * refillPerSecond
        )
        lastRefill = timeSource.markNow()
    }

    // MARK: - Provider policies

    companion object {
        /** SEC asks for no more than 10 requests/second. We use 8 for headroom. */
        fun sec(timeSource: TimeSource = TimeSource.Monotonic) =
            RateLimiter(8, 1.seconds, burst = 8, provider = DataProviderID.SEC, timeSource = timeSource)

        /** Finnhub's free tier documents 60 calls/minute. We use 50. */
        fun finnhub(timeSource: TimeSource = TimeSource.Monotonic) =
            RateLimiter(
                50, 1.minutes, burst = 10, provider = DataProviderID.Finnhub,
                timeSource = timeSource
            )

        /**
         * Tiingo's free tier allows 50 requests/hour and 1000/day. The hourly
         * cap is the binding one and is easy to exhaust while backfilling
         * history, so we pace to 45/hour with a small burst for interactive use.
         */
        fun tiingo(timeSource: TimeSource = TimeSource.Monotonic) =
            RateLimiter(
                45, 1.hours, burst = 6, provider = DataProviderID.Tiingo,
                timeSource = timeSource
            )

        /**
         * Alpaca's free plan documents 200 requests/minute. Intraday charts are
         * interactive — a range tap should redraw at once — so the whole
         * minute's allowance is available as burst.
         */
        fun alpaca(timeSource: TimeSource = TimeSource.Monotonic) =
            RateLimiter(
                200, 1.minutes, burst = 30, provider = DataProviderID.Alpaca,
                timeSource = timeSource
            )

        /** FRED permits 120 requests/minute. */
        fun fred(timeSource: TimeSource = TimeSource.Monotonic) =
            RateLimiter(
                100, 1.minutes, burst = 20, provider = DataProviderID.FRED,
                timeSource = timeSource
            )
    }
}
