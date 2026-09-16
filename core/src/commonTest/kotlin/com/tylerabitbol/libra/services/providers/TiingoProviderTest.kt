package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.fixtures.Fixture
import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.networking.MockHttp
import com.tylerabitbol.libra.services.secrets.InMemorySecretsStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Swift: `Tiingo provider` in `ProviderDecodingTests.swift`. */
class TiingoProviderTest {

    private fun router(fixture: String) = MockHttp.router()
        .stub("/tiingo/daily/aapl/prices", Fixture.text(fixture))

    @Test
    fun realDailyBarsDecodeAndMap() = runTest {
        val router = router("tiingo_prices_AAPL")
        val provider = TiingoProvider(testClient(router), testSecrets())

        val bars = provider.bars(
            symbol = "AAPL",
            resolution = BarResolution.Daily,
            from = Instant.fromEpochSeconds(1_780_000_000),
            to = Instant.fromEpochSeconds(1_790_000_000),
        )
        assertTrue(bars.isNotEmpty())
        assertTrue(bars.all { it.high >= it.low })
        assertTrue(bars.all { it.close > 0 })
    }

    @Test
    fun adjustedCloseSurvivesASplitAndRawCloseDoesNot() = runTest {
        val router = router("tiingo_prices_AAPL_split")
        val provider = TiingoProvider(testClient(router), testSecrets())

        val dtos = provider.bars(
            symbol = "AAPL",
            resolution = BarResolution.Daily,
            from = Instant.fromEpochSeconds(1_598_000_000),
            to = Instant.fromEpochSeconds(1_599_300_000),
        )
        val first = assertNotNull(dtos.firstOrNull())
        // AAPL's 4-for-1 split fell inside this window: raw close ~499,
        // adjusted ~121.
        assertTrue(first.close > 400)
        val adjusted = assertNotNull(first.adjustedClose)
        assertTrue(adjusted < 200)

        // PriceBar must analyse on the adjusted series or every return
        // spanning the split is wrong by ~4x.
        val bar = PriceBar(
            date = first.date,
            resolution = BarResolution.Daily,
            open = first.open,
            high = first.high,
            low = first.low,
            close = first.close,
            adjustedClose = first.adjustedClose,
        )
        assertEquals(adjusted, bar.analysisClose)
    }

    @Test
    fun intradayResolutionsAreRefusedRatherThanSilentlyServedAsDaily() = runTest {
        val router = MockHttp.router()
        val provider = TiingoProvider(testClient(router), testSecrets())

        val error = assertFailsWith<APIError.NotEntitled> {
            provider.bars(
                symbol = "AAPL",
                resolution = BarResolution.FiveMinute,
                from = Instant.fromEpochSeconds(1_780_000_000),
                to = Instant.fromEpochSeconds(1_780_086_400),
            )
        }
        assertEquals(DataProviderID.Tiingo, error.providerID)
        assertEquals("intraday prices", error.endpoint)
        assertTrue(router.requests.isEmpty(), "A refusal must not reach the network")
    }

    @Test
    fun theTokenTravelsInAHeaderNeverInTheURL() = runTest {
        val router = router("tiingo_prices_AAPL")
        val provider = TiingoProvider(testClient(router), testSecrets())

        provider.bars(
            symbol = "AAPL",
            resolution = BarResolution.Daily,
            from = Instant.fromEpochSeconds(1_780_000_000),
            to = Instant.fromEpochSeconds(1_780_086_400),
        )
        val request = assertNotNull(router.requests.firstOrNull())
        assertFalse(request.url.toString().contains("test-tiingo-key"))
        assertEquals("Token test-tiingo-key", request.headers["Authorization"])
    }

    @Test
    fun aMissingKeyThrowsBeforeAnyRequestIsMade() = runTest {
        val router = MockHttp.router()
        val provider = TiingoProvider(testClient(router), InMemorySecretsStore())

        val error = assertFailsWith<APIError.MissingCredentials> {
            provider.bars(
                symbol = "AAPL",
                resolution = BarResolution.Daily,
                from = Instant.fromEpochSeconds(1_780_000_000),
                to = Instant.fromEpochSeconds(1_780_086_400),
            )
        }
        assertEquals(DataProviderID.Tiingo, error.providerID)
        assertTrue(router.requests.isEmpty(), "No credential means no outbound request")
    }

    @Test
    fun theDateWindowIsSentAsBareDaysNotTimestamps() = runTest {
        val router = router("tiingo_prices_AAPL")
        val provider = TiingoProvider(testClient(router), testSecrets())

        provider.bars(
            symbol = "AAPL",
            resolution = BarResolution.Weekly,
            from = Instant.parse("2026-01-02T18:30:00Z"),
            to = Instant.parse("2026-03-04T05:00:00Z"),
        )
        val url = assertNotNull(router.requests.firstOrNull()).url.toString()
        assertTrue(url.contains("startDate=2026-01-02"), url)
        assertTrue(url.contains("endDate=2026-03-04"), url)
        assertTrue(url.contains("resampleFreq=weekly"), url)
    }

    @Test
    fun metadataMapsToAProfileWithTheFieldsTiingoActuallyCarries() = runTest {
        val router = MockHttp.router()
            .stub("/tiingo/daily/aapl", Fixture.text("tiingo_meta_AAPL"))
        val provider = TiingoProvider(testClient(router), testSecrets())

        val profile = provider.profile("AAPL")
        assertEquals("AAPL", profile.symbol)
        assertTrue(profile.name.isNotBlank())
        // Tiingo metadata carries no sector, industry or market cap; claiming
        // otherwise would put an empty field on the detail screen.
        assertEquals(null, profile.sector)
        assertEquals(null, profile.marketCap)
    }
}
