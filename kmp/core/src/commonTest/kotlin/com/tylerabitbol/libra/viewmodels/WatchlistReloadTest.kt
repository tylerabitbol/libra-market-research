package com.tylerabitbol.libra.viewmodels

import com.tylerabitbol.libra.persistence.WatchlistMember
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What coming back to the tab does to a list already loaded.
 *
 * The model now outlives the tab, and the host loads it again on every
 * return. That load rebuilt each row from membership alone, which blanked
 * every price until the next fetch.
 */
class WatchlistReloadTest {

    private fun members(vararg symbols: String) =
        symbols.map { WatchlistMember(symbol = it, name = it) }

    @Test
    fun aSecondLoadKeepsThePrices() = runTest {
        val log = CallLog()
        val providers = stubRegistry(log)
        val model = WatchlistViewModel(this)

        model.load(members("AAPL", "MSFT"), providers)
        advanceUntilIdle()
        val quotes = log.quotes

        // Equal, not the same list: the host reads membership afresh.
        model.load(members("AAPL", "MSFT"), providers)

        assertTrue(
            model.state.value.rows.all { it.quote != null },
            "The prices on screen survive the reload",
        )
        advanceUntilIdle()
        assertEquals(quotes, log.quotes, "Fresh prices are not fetched again")
    }

    @Test
    fun anAddedSymbolIsFetchedEvenWhenTheRestAreFresh() = runTest {
        val log = CallLog()
        val providers = stubRegistry(log)
        val model = WatchlistViewModel(this)

        model.load(members("AAPL"), providers)
        advanceUntilIdle()
        model.load(members("AAPL", "NVDA"), providers)
        advanceUntilIdle()

        assertTrue("NVDA" in log.symbols)
        assertTrue(model.state.value.rows.all { it.quote != null })
    }

    @Test
    fun newProvidersDropTheOldPrices() = runTest {
        val model = WatchlistViewModel(this)
        model.load(members("AAPL"), stubRegistry(CallLog()))
        advanceUntilIdle()

        val live = CallLog()
        model.load(members("AAPL"), stubRegistry(live))
        advanceUntilIdle()

        assertTrue("AAPL" in live.symbols, "The new providers are asked for the price")
    }
}
