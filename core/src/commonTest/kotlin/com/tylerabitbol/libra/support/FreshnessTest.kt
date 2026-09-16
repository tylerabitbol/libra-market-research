package com.tylerabitbol.libra.support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Ported from LibraTests/FreshnessTests.swift. */
class FreshnessTest {

    @Test
    fun never_fetched_data_reports_missing_not_stale() {
        assertEquals(Freshness.Missing, StalenessPolicy.quote.evaluate(lastUpdated = null))
    }

    @Test
    fun data_inside_the_window_is_fresh() {
        val now = Instant.fromEpochSeconds(1_000_000)
        val fetched = now - 30.seconds
        assertEquals(Freshness.Fresh(fetched), StalenessPolicy.quote.evaluate(fetched, now))
    }

    @Test
    fun data_past_the_window_is_stale_but_still_carries_its_value() {
        val now = Instant.fromEpochSeconds(1_000_000)
        val fetched = now - 120.seconds
        val result = StalenessPolicy.quote.evaluate(fetched, now)
        assertEquals(Freshness.Stale(fetched), result)
        assertTrue(result.hasValue, "Stale data must still be displayable")
    }

    @Test
    fun a_failed_refresh_keeps_the_previous_value_available() {
        val previous = Instant.fromEpochSeconds(900_000)
        val failed = Freshness.Failed(previous = previous, reason = "network error")
        assertTrue(failed.hasValue)
        assertEquals(previous, failed.asOf)
    }

    @Test
    fun a_failure_with_no_previous_value_has_nothing_to_show() {
        assertFalse(Freshness.Failed(previous = null, reason = "network error").hasValue)
    }
}

/** Ported from LibraTests/FreshnessTests.swift, suite "Relative time text". */
class RelativeTimeTextTest {

    @Test
    fun very_recent_timestamps_read_as_just_now() {
        val now = Instant.fromEpochSeconds(1_000_000)
        assertEquals("just now", RelativeTimeText.string(now - 1.seconds, now))
    }

    @Test
    fun missing_data_says_so_plainly_rather_than_showing_a_number() {
        assertEquals("Not available", RelativeTimeText.status(Freshness.Missing))
    }

    @Test
    fun a_failed_refresh_names_the_reason_and_the_age_of_what_is_shown() {
        val now = Instant.fromEpochSeconds(1_000_000)
        val status = RelativeTimeText.status(
            Freshness.Failed(previous = now - 3600.seconds, reason = "rate limit"),
            now
        )
        assertTrue(status.contains("rate limit"))
        assertTrue(status.contains("hour"))
    }
}
