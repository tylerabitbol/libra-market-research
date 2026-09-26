package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.services.providers.InsiderTransactionDTO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Ported from LibraTests/InsiderActivityTests.swift.
 *
 * The counts are only meaningful if the exclusions are right. A scheduled sale
 * was decided months before it executed, a grant is compensation and withheld
 * shares are an administrative consequence of vesting — counting any of them
 * as insider sentiment produces a figure that is mostly payroll.
 */
private fun transaction(
    code: String,
    plan: Boolean = false,
    shares: Double? = 1_000.0,
    price: Double? = 100.0,
    day: Int = 0,
    filedDay: Int = 0,
    name: String = "DOE JANE"
): InsiderTransactionDTO {
    val base = Instant.fromEpochSeconds(1_700_000_000)
    return InsiderTransactionDTO(
        accessionNumber = "acc-$code-$day", insiderName = name, insiderTitle = "CFO",
        isDirector = false, isOfficer = true, isTenPercentOwner = false,
        transactionDate = base + day.days,
        filedAt = base + filedDay.days,
        transactionCode = code, isUnderTradingPlan = plan,
        shares = shares, pricePerShare = price, sharesOwnedAfter = null
    )
}

class InsiderActivityTest {

    @Test
    fun only_open_market_decisions_are_counted() {
        val summary = assertNotNull(
            InsiderActivity.summarize(
                listOf(
                    transaction("P"),
                    transaction("S", day = 1),
                    transaction("S", plan = true, day = 2),
                    transaction("A", day = 3),
                    transaction("F", day = 4),
                    transaction("M", day = 5)
                )
            )
        )

        assertEquals(1, summary.purchaseCount)
        assertEquals(1, summary.saleCount)
        assertEquals(1, summary.scheduledCount, "The plan sale is excluded, not counted as a sale")
        assertEquals(3, summary.routineCount, "Grant, withholding and exercise")
    }

    @Test
    fun approximate_value_multiplies_shares_by_the_price_on_each_line() {
        val summary = assertNotNull(
            InsiderActivity.summarize(
                listOf(
                    transaction("P", shares = 1_000.0, price = 50.0),
                    transaction("P", shares = 2_000.0, price = 25.0, day = 1)
                )
            )
        )
        assertEquals(100_000.0, summary.purchaseValue)
        // Approximate, because one decision is often filed as several lines at
        // several prices — the claim says so rather than implying precision.
        assertTrue(summary.claim.text.contains("does not predict returns"))
    }

    @Test
    fun a_line_with_no_price_contributes_no_value_rather_than_zero() {
        val summary = assertNotNull(
            InsiderActivity.summarize(listOf(transaction("P", shares = 1_000.0, price = null)))
        )
        assertEquals(1, summary.purchaseCount)
        assertNull(summary.purchaseValue, "A missing price must not read as a free purchase")
    }

    @Test
    fun the_excluded_counts_are_stated_so_a_reader_can_reconcile() {
        val summary = assertNotNull(
            InsiderActivity.summarize(
                listOf(transaction("S", plan = true), transaction("A", day = 1))
            )
        )
        assertFalse(summary.hasDiscretionaryActivity)
        assertTrue(summary.claim.text.contains("1 scheduled-plan"))
        assertTrue(summary.claim.text.contains("1 routine"))
    }

    @Test
    fun nothing_filed_produces_no_summary_rather_than_a_row_of_zeros() {
        assertNull(InsiderActivity.summarize(emptyList()))
    }

    // MARK: - Events

    @Test
    fun only_discretionary_transactions_become_events() {
        val since = Instant.fromEpochSeconds(1_700_000_000) - 1.days
        val events = InsiderActivity.events(
            listOf(
                transaction("P", filedDay = 1),
                transaction("S", plan = true, day = 1, filedDay = 1),
                transaction("A", day = 2, filedDay = 2)
            ),
            since = since
        )

        // A plan executing on schedule is not news, and a feed reporting every
        // one would bury the purchases that are.
        assertEquals(1, events.size)
        assertEquals(EventKind.InsiderTransaction, events.first().kind)
        assertTrue(events.first().headline.contains("bought"))
    }

    @Test
    fun an_event_carries_no_unusualness_because_there_is_no_sample_to_rank() {
        val since = Instant.fromEpochSeconds(1_700_000_000) - 1.days
        val event = assertNotNull(
            InsiderActivity.events(listOf(transaction("P", filedDay = 1)), since).firstOrNull()
        )
        assertEquals(0.0, event.unusualness)
        val context = assertNotNull(event.context)
        assertTrue(context.contains("do not predict returns"))
        assertTrue(context.contains("many innocent explanations"))
    }

    @Test
    fun with_no_prior_visit_nothing_is_reported_as_new() {
        // The same rule the filing detector follows: with no "since" there is
        // no "new", and presenting a year of Form 4s as fresh would be false.
        assertTrue(InsiderActivity.events(listOf(transaction("P")), since = null).isEmpty())
    }

    @Test
    fun transactions_filed_before_the_last_visit_are_not_new() {
        val since = Instant.fromEpochSeconds(1_700_000_000) + 10.days
        assertTrue(InsiderActivity.events(listOf(transaction("P", filedDay = 1)), since).isEmpty())
    }
}
