package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.fixtures.Fixture
import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.networking.MockHttp
import com.tylerabitbol.libra.services.secrets.InMemorySecretsStore
import com.tylerabitbol.libra.services.secrets.SecretKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Swift: `Intraday routing` in `AlpacaProviderTests.swift`. */
class IntradayRoutingTest {

    private val from = Instant.fromEpochSeconds(1_787_000_000)
    private val to = Instant.fromEpochSeconds(1_787_090_000)

    @Test
    fun dailyGoesToTiingoAndIntradayToAlpacaWithNoFallbackAcrossTheLine() = runTest {
        val router = MockHttp.router()
            .stub("/tiingo/daily/aapl/prices", Fixture.text("tiingo_prices_AAPL"))
            .stub("/v2/stocks/bars", Fixture.text("alpaca_bars_AAPL_synthetic"))
        val secrets = InMemorySecretsStore(
            mapOf(
                SecretKey.FinnhubAPIKey to "f",
                SecretKey.TiingoAPIKey to "t",
                SecretKey.AlpacaKeyID to "id",
                SecretKey.AlpacaSecretKey to "secret",
            ),
        )
        val client = testClient(router)
        val composite = CompositeMarketDataProvider(
            quotes = FinnhubProvider(client, secrets),
            history = TiingoProvider(client, secrets),
            intraday = AlpacaProvider(client, secrets),
        )

        val daily = composite.bars("AAPL", BarResolution.Daily, from, to)
        val intraday = composite.bars("AAPL", BarResolution.FiveMinute, from, to)

        // The Tiingo fixture is a long daily series; the Alpaca one is four
        // usable five-minute bars. Different counts prove different vendors
        // answered, which a shared stub path could not show.
        assertTrue(daily.size > 4)
        assertEquals(4, intraday.size)
    }

    @Test
    fun withoutAnAlpacaKeyIntradaySaysSoInsteadOfReturningDailyBars() = runTest {
        val router = MockHttp.router()
            .stub("/tiingo/daily/aapl/prices", Fixture.text("tiingo_prices_AAPL"))
        val secrets = InMemorySecretsStore(
            mapOf(SecretKey.FinnhubAPIKey to "f", SecretKey.TiingoAPIKey to "t"),
        )
        val client = testClient(router)
        val composite = CompositeMarketDataProvider(
            quotes = FinnhubProvider(client, secrets),
            history = TiingoProvider(client, secrets),
            intraday = null,
        )

        // Quietly serving daily bars would mislabel the resolution, and every
        // figure computed on top of it would be wrong while looking fine.
        val error = assertFailsWith<APIError.MissingCredentials> {
            composite.bars("AAPL", BarResolution.FiveMinute, from, to)
        }
        assertEquals(DataProviderID.Alpaca, error.providerID)
        assertTrue(router.requests.isEmpty(), "A refusal must not reach Tiingo either")
    }

    @Test
    fun aProfileFinnhubDoesNotCoverFallsBackToTiingosThinnerMetadata() = runTest {
        val router = MockHttp.router()
            // Finnhub returns 200 with an empty object for a symbol it has no
            // profile for, which the provider maps to NotFound.
            .stub("/stock/profile2", "{}")
            .stub("/tiingo/daily/aapl", Fixture.text("tiingo_meta_AAPL"))
        val secrets = InMemorySecretsStore(
            mapOf(SecretKey.FinnhubAPIKey to "f", SecretKey.TiingoAPIKey to "t"),
        )
        val client = testClient(router)
        val composite = CompositeMarketDataProvider(
            quotes = FinnhubProvider(client, secrets),
            history = TiingoProvider(client, secrets),
        )

        val profile = composite.profile("AAPL")
        assertEquals("AAPL", profile.symbol)
        assertTrue(profile.name.isNotBlank(), "A symbol must still resolve to a name")
    }

    @Test
    fun aFinnhubFailureThatIsNotAMissThrowsRatherThanQuietlySwitchingVendors() = runTest {
        val router = MockHttp.router()
            .stub("/stock/profile2", "{}", status = io.ktor.http.HttpStatusCode.Unauthorized)
            .stub("/tiingo/daily/aapl", Fixture.text("tiingo_meta_AAPL"))
        val secrets = InMemorySecretsStore(
            mapOf(SecretKey.FinnhubAPIKey to "f", SecretKey.TiingoAPIKey to "t"),
        )
        val client = testClient(router)
        val composite = CompositeMarketDataProvider(
            quotes = FinnhubProvider(client, secrets),
            history = TiingoProvider(client, secrets),
        )

        // A bad key is a setup problem the user must fix. Falling back would
        // hide it behind a thinner profile that looks like it worked.
        assertFailsWith<APIError.InvalidCredentials> { composite.profile("AAPL") }
    }
}
