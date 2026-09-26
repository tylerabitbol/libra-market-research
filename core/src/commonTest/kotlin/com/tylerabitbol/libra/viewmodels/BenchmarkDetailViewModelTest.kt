package com.tylerabitbol.libra.viewmodels

import com.tylerabitbol.libra.models.core.Benchmark
import com.tylerabitbol.libra.models.core.ChartAvailability
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.core.closeOnly
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.services.providers.MacroDataProvider
import com.tylerabitbol.libra.services.providers.MacroObservationDTO
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Records what was asked for, so the tests can assert on the request rather
 * than only on the result. Bars are the scarcest thing the app spends.
 */
private class CountingMacroProvider(
    val failure: APIError? = null,
) : MacroDataProvider {
    override val id: DataProviderID = DataProviderID.FRED
    val starts = mutableListOf<Instant>()
    val count: Int get() = starts.size

    override suspend fun isConfigured(): Boolean = true

    override suspend fun observations(
        seriesID: String,
        from: Instant?,
        to: Instant?,
    ): List<MacroObservationDTO> {
        starts.add(from ?: Instant.DISTANT_PAST)
        failure?.let { throw it }
        // A daily close series, oldest first, ascending in value so the
        // direction of any computed return is unambiguous.
        val end = to ?: Clock.System.now()
        var cursor = from ?: (end - 400.days)
        var value = 100.0
        val result = mutableListOf<MacroObservationDTO>()
        while (cursor <= end) {
            result.add(MacroObservationDTO(seriesID, cursor, value))
            value += 0.5
            cursor += 1.days
        }
        return result
    }
}

private fun registry(macro: MacroDataProvider): ProviderRegistry =
    ProviderRegistry.sample.copy(macro = macro)

/** Swift: `Benchmark detail` in `BenchmarkDetailViewModelTests.swift`. */
@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkDetailViewModelTest {

    private fun model(benchmark: Benchmark, scope: TestScope) =
        BenchmarkDetailViewModel(benchmark, scope)

    @Test
    fun anIndexOffersOnlyTheRangesFredCanFillAProxyOffersAllSeven() = runTest {
        val index = model(Benchmark.market, this)
        assertEquals(
            listOf(
                ChartRange.OneMonth, ChartRange.ThreeMonth, ChartRange.SixMonth,
                ChartRange.OneYear, ChartRange.FiveYear,
            ),
            index.state.value.availableRanges,
            "FRED publishes end-of-day, so 1D and 5D can never draw",
        )
        assertTrue(index.state.value.availableRanges.none { it.usesIntraday })

        val etf = model(Benchmark.sectors[0], this)
        assertEquals(
            ChartRange.entries,
            etf.state.value.availableRanges,
            "A sector ETF follows the same provider path as any security",
        )
    }

    @Test
    fun aSelectionThePickerDoesNotOfferIsRefused() = runTest {
        val vm = model(Benchmark.market, this)
        vm.select(ChartRange.OneDay, registry(CountingMacroProvider()))
        runCurrent()
        assertTrue(vm.state.value.selectedRange != ChartRange.OneDay)
    }

    @Test
    fun fredClosesBecomeBarsThatCarryNoRange() {
        val now = Clock.System.now()
        val observations = listOf(
            MacroObservationDTO("SP500", now, 7691.76),
            MacroObservationDTO("SP500", now - 1.days, 7650.10),
        )
        val bars = PriceBar.closeOnly(observations)

        assertEquals(listOf(7650.10, 7691.76), bars.map { it.close }, "Oldest first")
        assertTrue(
            bars.all { it.open == it.close && it.high == it.close && it.low == it.close },
            "FRED publishes a close and nothing else",
        )
    }

    @Test
    fun aFiveYearWindowAsksForMoreThanTheDashboards400Days() = runTest {
        val macro = CountingMacroProvider()
        val vm = model(Benchmark.market, this)
        vm.select(ChartRange.FiveYear, registry(macro))
        runCurrent()

        assertTrue(macro.starts.isNotEmpty())
        val age = Clock.System.now() - assertNotNull(macro.starts.lastOrNull())
        assertTrue(
            age > 400.days,
            "The dashboard row's fixed 400-day window cannot fill a 5Y chart",
        )
    }

    @Test
    fun reSelectingARangeAlreadyHeldSpendsNoRequest() = runTest {
        val macro = CountingMacroProvider()
        val providers = registry(macro)
        val vm = model(Benchmark.market, this)

        vm.load(providers)
        runCurrent()
        vm.select(ChartRange.OneMonth, providers)
        runCurrent()

        assertEquals(
            1,
            macro.count,
            "A redundant refetch spends a token the free tier refills slowly",
        )
    }

    @Test
    fun aChartThatHasNotLookedYetSaysLoadingNotUnavailable() = runTest {
        val vm = model(Benchmark.market, this)
        assertEquals(
            ChartAvailability.Loading,
            vm.state.value.chartAvailability,
            "Nothing held and nothing attempted is not a verdict",
        )
    }

    @Test
    fun aFailedFetchSaysWhyRatherThanShowingAnEmptyChart() = runTest {
        val failing = CountingMacroProvider(
            failure = APIError.RateLimited(DataProviderID.FRED, retryAfter = null),
        )
        val vm = model(Benchmark.market, this)
        vm.load(registry(failing))
        runCurrent()

        val availability = vm.state.value.chartAvailability
        assertTrue(
            availability is ChartAvailability.Unavailable,
            "Expected Unavailable, got $availability",
        )
        assertTrue(availability.reason.isNotEmpty())
    }

    @Test
    fun anIndexLevelIsPointsAProxysPriceIsMoney() = runTest {
        assertEquals(
            ChartValueFormat.Points,
            model(Benchmark.market, this).state.value.valueFormat,
            "The S&P 500 at 7691.76 is not $7,691.76",
        )
        assertEquals(
            ChartValueFormat.Currency,
            model(Benchmark.sectors[0], this).state.value.valueFormat,
        )
    }

    @Test
    fun everyScreenThatShowsAProxySaysItIsOne() = runTest {
        val sector = model(Benchmark.sectors[0], this).state.value
        assertTrue(sector.benchmark.isProxy)
        assertTrue(sector.sourceExplanation.lowercase().contains("proxy"))

        val index = model(Benchmark.market, this).state.value
        assertEquals("FRED SP500", index.sourceLabel)
        assertTrue(
            index.sourceExplanation.contains("end-of-day"),
            "An index level that is a day old must not read as live",
        )
    }

    @Test
    fun anEmptyResponseDoesNotEraseASeriesAlreadyHeld() = runTest {
        // No Swift counterpart. A market closed all window returns nothing,
        // and blanking a chart that was already drawn is worse than leaving it.
        val macro = CountingMacroProvider()
        val vm = model(Benchmark.market, this)
        vm.load(registry(macro))
        runCurrent()
        val held = vm.state.value.chartBars.size
        assertTrue(held >= 2)

        vm.load(registry(CountingMacroProvider(failure = null)), force = true)
        runCurrent()
        assertTrue(vm.state.value.chartBars.isNotEmpty())
    }
}
