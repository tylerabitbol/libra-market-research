package com.tylerabitbol.libra.viewmodels

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.services.mock.MockMacroDataProvider
import com.tylerabitbol.libra.services.providers.MacroDataProvider
import com.tylerabitbol.libra.services.providers.MacroObservationDTO
import com.tylerabitbol.libra.services.providers.MarketDataProvider
import com.tylerabitbol.libra.services.providers.PriceBarDTO
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** FRED, except that one series answers only once [gate] is completed. */
private class HeldMacroProvider(val heldSeries: String) : MacroDataProvider {
    val gate = CompletableDeferred<Unit>()
    private val inner = MockMacroDataProvider()
    override val id: DataProviderID = DataProviderID.FRED
    override suspend fun isConfigured(): Boolean = true

    override suspend fun observations(
        seriesID: String,
        from: Instant?,
        to: Instant?,
    ): List<MacroObservationDTO> {
        if (seriesID == heldSeries) gate.await()
        return inner.observations(seriesID, from, to)
    }
}

/**
 * Quotes at once, history only once [gate] is completed: the Tiingo request
 * still waiting for a token after every quote has landed.
 */
private class HeldHistoryProvider(
    private val stub: StubMarketProvider,
) : MarketDataProvider by stub {
    val gate = CompletableDeferred<Unit>()

    override suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        gate.await()
        return stub.bars(symbol, resolution, from, to)
    }
}

/**
 * When rows reach the screen, and what a second load spends.
 *
 * The dashboard awaited every request before publishing anything, so one slow
 * row held back all of them; and its host rebuilt the model on every tab
 * switch, so coming back refetched the lot.
 */
class DashboardViewModelTest {

    private fun registry(
        log: CallLog,
        macro: MacroDataProvider = MockMacroDataProvider(),
        market: MarketDataProvider = StubMarketProvider(log, last = 100.0, previousClose = 99.0),
    ) = ProviderRegistry(
        marketData = market,
        fundamentals = null, analyst = null, metrics = null, sec = null,
        macro = macro, news = null, isUsingSampleData = false,
    )

    private fun russell(model: DashboardViewModel) =
        model.state.value.market.first { it.benchmark.etfSymbol == "IWM" }

    @Test
    fun aSlowRowDoesNotHoldBackTheOthers() = runTest {
        val macro = HeldMacroProvider(heldSeries = "VIXCLS")
        val model = DashboardViewModel(this)

        model.load(registry(CallLog(), macro = macro))
        runCurrent()

        val held = model.state.value
        assertEquals(4, held.market.size, "The market rows must not wait for the VIX")
        assertEquals(11, held.sectors.size)
        assertEquals(4, held.macro.size)
        assertTrue(held.volatility.isEmpty(), "The held row has not landed")
        assertTrue(held.isLoading, "Still loading until every group is in")

        macro.gate.complete(Unit)
        advanceUntilIdle()

        val done = model.state.value
        assertEquals(1, done.volatility.size)
        assertFalse(done.isLoading)
        assertNotNull(done.lastRefreshedAt)
    }

    @Test
    fun theRussellPriceLandsBeforeItsHistory() = runTest {
        val log = CallLog()
        val market = HeldHistoryProvider(StubMarketProvider(log, last = 100.0, previousClose = 99.0))
        val model = DashboardViewModel(this)

        model.load(registry(log, market = market))
        runCurrent()

        val priced = russell(model)
        assertEquals(100.0, priced.level, "The price is on screen before the history")
        assertNotNull(priced.daily, "The daily change comes from the quote")
        assertNull(priced.weekly, "The week waits for the history")

        market.gate.complete(Unit)
        advanceUntilIdle()

        val filled = russell(model)
        assertNotNull(filled.weekly)
        assertNotNull(filled.monthly)
        assertNull(filled.historyError)
    }

    @Test
    fun comingBackMidLoadDoesNotStartAgain() = runTest {
        val log = CallLog()
        val market = HeldHistoryProvider(StubMarketProvider(log, last = 100.0, previousClose = 99.0))
        val providers = registry(log, market = market)
        val model = DashboardViewModel(this)

        model.load(providers)
        runCurrent()
        val quotes = log.quotes

        // What returning to the tab does while the history is still held.
        model.load(providers)
        runCurrent()

        assertEquals(quotes, log.quotes, "Restarting the load spent every quote twice")
        market.gate.complete(Unit)
    }

    @Test
    fun freshRowsAreNotRefetched() = runTest {
        val log = CallLog()
        val providers = registry(log)
        val model = DashboardViewModel(this)

        model.load(providers)
        advanceUntilIdle()
        val quotes = log.quotes
        model.load(providers)
        advanceUntilIdle()

        assertEquals(quotes, log.quotes)
    }

    @Test
    fun newProvidersReplaceTheRows() = runTest {
        // Entering a key rebuilds the registry. The model outlives the tab, so
        // rows from the sample providers must not pass for fresh.
        val live = CallLog()
        val model = DashboardViewModel(this)

        model.load(registry(CallLog()))
        advanceUntilIdle()
        model.load(registry(live))
        advanceUntilIdle()

        assertEquals(12, live.quotes, "The new providers are asked for every quote")
        assertEquals(4, model.state.value.market.size)
    }
}
