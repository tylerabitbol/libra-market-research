package com.tylerabitbol.libra.support

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How current a piece of stored data is.
 *
 * Section 20 requires that every data object have a visible "last updated"
 * state, so the user never has to wonder whether they are looking at live
 * data. Staleness thresholds differ wildly by data type — a quote goes stale
 * in a minute, an annual filing does not go stale for a quarter — so the
 * policy is supplied per data kind rather than hardcoded here.
 */
sealed interface Freshness {
    val asOf: Instant?

    /**
     * True when there is *some* value to show, even if it is old or the last
     * refresh failed. Section 21: a failed request must not blank the screen.
     */
    val hasValue: Boolean get() = asOf != null

    /** Never fetched. */
    data object Missing : Freshness {
        override val asOf: Instant? get() = null
    }

    /** Fetched recently enough to trust for its kind. */
    data class Fresh(override val asOf: Instant) : Freshness

    /** Fetched, but past its useful window. Still shown, clearly marked. */
    data class Stale(override val asOf: Instant) : Freshness

    /** A refresh is in flight. Carries the previous value's timestamp if any. */
    data class Refreshing(val previous: Instant?) : Freshness {
        override val asOf: Instant? get() = previous
    }

    /** The last refresh attempt failed. Carries the last good timestamp if any. */
    data class Failed(val previous: Instant?, val reason: String) : Freshness {
        override val asOf: Instant? get() = previous
    }
}

/**
 * How long data of a given kind stays useful before it should be re-fetched.
 *
 * These are deliberately generous. This is a personal-use app hitting free API
 * tiers, and Section 20 asks for aggressive caching over repeated requests.
 */
data class StalenessPolicy(val maxAge: Duration) {

    fun evaluate(lastUpdated: Instant?, now: Instant = Clock.System.now()): Freshness {
        if (lastUpdated == null) return Freshness.Missing
        return if (now - lastUpdated <= maxAge) {
            Freshness.Fresh(lastUpdated)
        } else {
            Freshness.Stale(lastUpdated)
        }
    }

    companion object {
        val quote = StalenessPolicy(1.minutes)
        val intradayCandles = StalenessPolicy(5.minutes)
        val dailyCandles = StalenessPolicy(6.hours)
        val fundamentals = StalenessPolicy(24.hours)
        val filings = StalenessPolicy(1.hours)
        val news = StalenessPolicy(30.minutes)
        val macro = StalenessPolicy(12.hours)
    }
}
