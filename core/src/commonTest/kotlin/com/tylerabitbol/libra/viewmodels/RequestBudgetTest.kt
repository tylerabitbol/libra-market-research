package com.tylerabitbol.libra.viewmodels

import com.tylerabitbol.libra.services.mock.MockMacroDataProvider
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Counts calls per endpoint so a screen's request budget can be asserted.
 *
 * Quota is a correctness concern here, not an optimisation: Tiingo's free tier
 * refills roughly one token every 80 seconds, so a screen that asks for twelve
 * history requests blocks for eight minutes. That bug was invisible to every
 * existing test because none of them counted.
 *
 * Swift's `RateLimiter back-pressure` suite shares this file; it was ported in
 * Phase 3 and lives in `networking/RateLimiterTest.kt`.
 */
class RequestBudgetTest {

    private fun registry(log: CallLog) = ProviderRegistry(
        marketData = StubMarketProvider(
            log, last = 100.0, previousClose = 99.0,
        ),
        fundamentals = null, analyst = null, metrics = null, sec = null,
        macro = MockMacroDataProvider(), news = null, isUsingSampleData = false,
    )

    private fun loaded(log: CallLog): DashboardViewModel {
        val model = DashboardViewModel(CoroutineScope(Dispatchers.Default))
        return model
    }

    @Test
    fun sectorsDoNotFetchHistory() = runTest {
        val log = CallLog()
        val model = loaded(log)
        model.refresh(registry(log))

        // Only Russell 2000 lacks a FRED series and shows 1W/1M, so exactly one
        // history request is justified. Eleven sector tiles show a daily change
        // only and must cost none.
        assertTrue(log.bars <= 1, "Expected at most 1 history request, got ${log.bars}")
    }

    @Test
    fun quotesStillFetched() = runTest {
        val log = CallLog()
        val model = loaded(log)
        model.refresh(registry(log))

        // 11 sectors + Russell 2000; the other three indexes and the VIX come
        // from FRED and cost no quote.
        assertEquals(12, log.quotes, "Expected 12 quotes, got ${log.quotes}")
    }

    @Test
    fun sectorsStillRenderDailyChange() = runTest {
        val log = CallLog()
        val model = loaded(log)
        model.refresh(registry(log))

        val sectors = model.state.value.sectors
        assertEquals(11, sectors.size)
        assertTrue(
            sectors.all { it.dailyPercent != null },
            "Daily change comes from the quote's previous close, not from bars",
        )
    }
}
