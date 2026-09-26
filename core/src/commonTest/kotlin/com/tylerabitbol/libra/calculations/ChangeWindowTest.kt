package com.tylerabitbol.libra.calculations

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Ported from LibraTests/ChangeWindowTests.swift, suite "Change window".
 *
 * The second Swift suite, "Change window on the detail page", drives
 * `SecurityDetailViewModel` and a store; it is ported in Phase 7.
 */
class ChangeWindowTest {

    @Test
    fun no_prior_visit_forms_no_window_rather_than_a_window_over_all_time() {
        assertNull(ChangeWindow.LastVisit.startDate(lastVisit = null))
    }

    @Test
    fun last_visit_measures_from_the_visit() {
        val visit = Instant.fromEpochSeconds(1_700_000_000)
        assertEquals(visit, ChangeWindow.LastVisit.startDate(lastVisit = visit))
    }

    @Test
    fun a_rolling_window_is_measured_back_from_now_not_from_the_last_visit() {
        val now = Instant.fromEpochSeconds(1_800_000_000)
        val start = assertNotNull(ChangeWindow.Days(30).startDate(Instant.DISTANT_PAST, now))
        val days = (now - start).inWholeSeconds / 86_400.0
        assertTrue(abs(days - 30) < 1, "Within a day, to allow for the DST hour")
    }

    @Test
    fun a_custom_window_is_the_date_chosen() {
        val picked = Instant.fromEpochSeconds(1_700_000_000)
        assertEquals(picked, ChangeWindow.Custom(picked).startDate(Clock.System.now()))
    }

    @Test
    fun only_a_window_reaching_past_the_last_visit_counts_as_widening() {
        val now = Instant.fromEpochSeconds(1_800_000_000)
        val yesterday = now - 1.days
        val longAgo = now - 400.days

        assertFalse(ChangeWindow.LastVisit.widensPast(yesterday, now))
        assertTrue(
            ChangeWindow.Days(30).widensPast(yesterday, now),
            "Thirty days reaches past a visit made yesterday"
        )
        assertFalse(
            ChangeWindow.Days(30).widensPast(longAgo, now),
            "But not past one made over a year ago — that is narrowing"
        )
    }
}
