package com.tylerabitbol.libra.services.mock

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Swift: `Sample data determinism` in `SampleDataTests.swift`. */
class SampleDataTest {

    private val from = Instant.fromEpochSeconds(1_700_000_000)

    @Test
    fun theSameSymbolAlwaysProducesTheSameSeries() {
        val to = from + 30.days
        val first = SampleData.bars("NVDA", BarResolution.Daily, from, to)
        val second = SampleData.bars("NVDA", BarResolution.Daily, from, to)
        assertEquals(first, second, "Tests depend on sample data being reproducible")
    }

    @Test
    fun differentSymbolsProduceDifferentSeries() {
        val to = from + 30.days
        assertNotEquals(
            SampleData.bars("NVDA", BarResolution.Daily, from, to),
            SampleData.bars("AAPL", BarResolution.Daily, from, to),
        )
    }

    @Test
    fun generatedBarsAreInternallyConsistent() {
        val bars = SampleData.bars("MSFT", BarResolution.Daily, from, from + 60.days)
        assertTrue(bars.isNotEmpty())
        for (bar in bars) {
            assertTrue(bar.high >= bar.low)
            assertTrue(bar.high >= bar.close)
            assertTrue(bar.low <= bar.close)
            assertTrue(bar.close > 0)
        }
    }

    @Test
    fun theMockRegistryIsFlaggedAsSampleData() {
        assertTrue(
            ProviderRegistry.sample.isUsingSampleData,
            "Synthetic prices must never render without the banner",
        )
    }

    @Test
    fun noMockAnswersToARealVendorsIdentity() {
        // No Swift counterpart at this level. A mock that borrows a vendor's
        // identity produces rows the store cannot tell from measured data —
        // which is how five years of synthetic closes once ended up on disk
        // looking like Tiingo's.
        assertEquals(DataProviderID.Sample, MockMarketDataProvider().id)
        assertEquals(DataProviderID.Sample, MockSECDataProvider().id)
        assertEquals(DataProviderID.Sample, MockMacroDataProvider().id)
        assertEquals(DataProviderID.Sample, MockNewsProvider().id)
    }

    @Test
    fun sampleFilingsCarryTheImpossibleCIKPrefixSoTheyCanBeEvicted() = runTest {
        // `evictSyntheticRows` keys on this prefix. A sample filing without it
        // is indistinguishable from a real one once written.
        val filings = MockSECDataProvider().filings("0000320193", emptyList(), 6)
        assertTrue(filings.isNotEmpty())
        assertTrue(filings.all { it.accessionNumber.startsWith("0000000000-") })
    }
}

/** Swift: `Provider failure handling` in `SampleDataTests.swift`. */
class ProviderFailureTest {

    @Test
    fun anInjectedFailureSurfacesAsTheTypedErrorNotACrash() = runTest {
        val provider = MockMarketDataProvider(
            failure = APIError.RateLimited(DataProviderID.Finnhub, retryAfter = 30.days),
        )
        val error = assertFailsWith<APIError.RateLimited> { provider.quote("NVDA") }
        assertEquals(DataProviderID.Finnhub, error.providerID)
    }

    @Test
    fun anUnknownSymbolIsNotFoundRatherThanFabricated() = runTest {
        val provider = MockMarketDataProvider()
        // `Sample`, not `Finnhub`. The mock used to borrow a real vendor's
        // identity, which is how its output reached the store looking like
        // measured data. It now names itself, and the store refuses it.
        val error = assertFailsWith<APIError.NotFound> { provider.profile("ZZZZ") }
        assertEquals(DataProviderID.Sample, error.providerID)
        assertEquals("profile", error.endpoint)
    }
}

/** Swift: `Chart ranges` in `SampleDataTests.swift`. */
class ChartRangeTest {

    @Test
    fun intradayRangesUseIntradayResolutionLongRangesDoNot() {
        assertEquals(BarResolution.FiveMinute, ChartRange.OneDay.resolution)
        assertEquals(BarResolution.Daily, ChartRange.OneMonth.resolution)
        assertEquals(BarResolution.Weekly, ChartRange.FiveYear.resolution)
    }

    @Test
    fun startDatesPrecedeTheEndDateForEveryRange() {
        val end = Instant.fromEpochSeconds(1_700_000_000)
        for (range in ChartRange.entries) {
            assertTrue(
                range.startDate(end) < end,
                "${range.raw} produced a non-past start",
            )
        }
    }
}
