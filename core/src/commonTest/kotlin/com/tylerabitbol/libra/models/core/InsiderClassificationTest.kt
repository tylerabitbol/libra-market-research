package com.tylerabitbol.libra.models.core

import com.tylerabitbol.libra.services.providers.InsiderTransactionDTO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Ported from LibraTests/ProvenanceTests.swift, suite "Insider transaction
 * classification". Swift exercised the stored row; the row is Phase 4, and the
 * classification it delegates to is shared with the DTO, so the DTO carries
 * the test until then.
 */
class InsiderClassificationTest {

    private fun transaction(
        code: String,
        plan: Boolean = false,
        shares: Double? = null,
        pricePerShare: Double? = null
    ) = InsiderTransactionDTO(
        accessionNumber = "x",
        insiderName = "Test",
        transactionDate = Clock.System.now(),
        filedAt = Clock.System.now(),
        transactionCode = code,
        isUnderTradingPlan = plan,
        shares = shares,
        pricePerShare = pricePerShare
    )

    @Test
    fun open_market_purchases_and_sales_are_distinguished_from_grants() {
        assertEquals(InsiderTransactionNature.OpenMarketPurchase, transaction("P").nature)
        assertEquals(InsiderTransactionNature.OpenMarketSale, transaction("S").nature)
        assertEquals(InsiderTransactionNature.Grant, transaction("A").nature)
        assertEquals(InsiderTransactionNature.OptionExercise, transaction("M").nature)
        assertEquals(InsiderTransactionNature.TaxWithholding, transaction("F").nature)
    }

    @Test
    fun a_10b5_1_plan_sale_is_not_treated_as_a_discretionary_sale() {
        val planned = transaction("S", plan = true)
        assertEquals(InsiderTransactionNature.ScheduledPlan, planned.nature)
        assertFalse(
            planned.nature.isDiscretionary,
            "Scheduled sales carry no signal about current sentiment"
        )
    }

    @Test
    fun grants_and_tax_withholding_are_not_discretionary_decisions() {
        assertFalse(InsiderTransactionNature.Grant.isDiscretionary)
        assertFalse(InsiderTransactionNature.TaxWithholding.isDiscretionary)
        assertTrue(InsiderTransactionNature.OpenMarketPurchase.isDiscretionary)
    }

    @Test
    fun value_is_null_rather_than_zero_when_price_or_size_is_missing() {
        val partial = transaction("P", shares = 1000.0, pricePerShare = null)
        assertNull(partial.approximateValue)
    }
}
