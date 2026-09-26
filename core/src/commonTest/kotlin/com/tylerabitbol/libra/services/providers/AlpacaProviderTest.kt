package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.fixtures.Fixture
import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.networking.HTTPClient
import com.tylerabitbol.libra.networking.MockHttp
import com.tylerabitbol.libra.services.secrets.InMemorySecretsStore
import com.tylerabitbol.libra.services.secrets.SecretKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Intraday bars, and the boundary that keeps them out of every calculation.
 *
 * Alpaca's free plan serves the IEX feed — roughly 2.5% of US equity volume.
 * That is fine for the shape of a one-day chart and disqualifying for anything
 * measured: a volume anomaly computed against 2.5% of the tape would be
 * meaningless, which is why the ROADMAP declined Alpaca as a history source.
 * The tests below pin the mapping; Phase 7 pins the boundary in the view model
 * that enforces it.
 *
 * Swift: `Alpaca provider` in `AlpacaProviderTests.swift`.
 */
class AlpacaProviderTest {

    private val window = Instant.fromEpochSeconds(1_787_000_000) to
        Instant.fromEpochSeconds(1_787_090_000)

    private fun alpacaSecrets() = InMemorySecretsStore(
        mapOf(
            SecretKey.AlpacaKeyID to "test-alpaca-key-id",
            SecretKey.AlpacaSecretKey to "test-alpaca-secret",
        ),
    )

    private fun router(fixture: String) =
        MockHttp.router().stub("/v2/stocks/bars", Fixture.text(fixture))

    private suspend fun bars(router: MockHttp.Router): List<PriceBarDTO> =
        AlpacaProvider(testClient(router), alpacaSecrets())
            .bars("AAPL", BarResolution.FiveMinute, window.first, window.second)

    @Test
    fun barsDecodeAndMapWithTheUnusableRowDropped() = runTest {
        val bars = bars(router("alpaca_bars_AAPL_synthetic"))

        // Five rows in, one without a close. A bar with no close cannot be
        // drawn, and interpolating one would invent price history.
        assertEquals(4, bars.size)
        assertTrue(bars.all { it.high >= it.low })
        assertTrue(bars.all { it.close > 0 })
    }

    @Test
    fun bothTimestampFormsParseAndTheSeriesComesBackInOrder() = runTest {
        val bars = bars(router("alpaca_bars_AAPL_synthetic"))

        // The fixture lists 13:45 before 13:40. A chart drawn from an unsorted
        // series doubles back on itself.
        assertEquals(bars.map { it.date }.sorted(), bars.map { it.date })
        // The fractional-seconds row is the 13:35 one; losing it would leave 3.
        assertEquals(4, bars.size)
    }

    @Test
    fun closesAreAlreadySplitAdjustedSoNoSeparateAdjustedSeriesIsClaimed() = runTest {
        val bars = bars(router("alpaca_bars_AAPL_synthetic"))

        // The request asks for adjustment=split, so `close` is the adjusted
        // value. Reporting it twice would imply a raw series we did not fetch.
        assertTrue(bars.all { it.adjustedClose == null })
    }

    @Test
    fun theSplitAdjustmentIsActuallyRequested() = runTest {
        val router = router("alpaca_bars_AAPL_synthetic")
        bars(router)

        val url = assertNotNull(router.requests.firstOrNull()).url.toString()
        // Without this parameter the previous test's claim would be false and
        // the chart would show a cliff at every split.
        assertTrue(url.contains("adjustment=split"), url)
        assertTrue(url.contains("feed=iex"), url)
    }

    @Test
    fun aWindowWithNoTradesDecodesAsEmptyRatherThanFailing() = runTest {
        val bars = bars(router("alpaca_bars_empty_synthetic"))

        // A weekend is not an error. The distinction matters on screen: one
        // says "no trades", the other says "something went wrong".
        assertTrue(bars.isEmpty())
    }

    @Test
    fun dailyAndCoarserBarsAreRefusedRatherThanServedFromIEX() = runTest {
        val router = MockHttp.router()
        val provider = AlpacaProvider(testClient(router), alpacaSecrets())

        for (resolution in listOf(
            BarResolution.Daily,
            BarResolution.Weekly,
            BarResolution.Monthly,
        )) {
            val error = assertFailsWith<APIError.NotEntitled> {
                provider.bars("AAPL", resolution, window.first, window.second)
            }
            assertEquals(DataProviderID.Alpaca, error.providerID)
        }
        assertTrue(router.requests.isEmpty(), "A refusal must not reach the network")
    }

    @Test
    fun halfAKeyPairIsNotConfigured() = runTest {
        val idOnly = AlpacaProvider(
            testClient(MockHttp.router()),
            InMemorySecretsStore(mapOf(SecretKey.AlpacaKeyID to "id")),
        )
        // One half authenticates nothing. Treating it as ready would turn a
        // setup mistake into what looks like an outage.
        assertFalse(idOnly.isConfigured())

        val both = AlpacaProvider(testClient(MockHttp.router()), alpacaSecrets())
        assertTrue(both.isConfigured())
    }

    @Test
    fun bothHalvesTravelInHeadersNeverInTheURL() = runTest {
        val router = router("alpaca_bars_AAPL_synthetic")
        bars(router)

        val request = assertNotNull(router.requests.firstOrNull())
        val url = request.url.toString()
        assertFalse(url.contains("test-alpaca-key-id"))
        assertFalse(url.contains("test-alpaca-secret"))
        assertEquals("test-alpaca-key-id", request.headers["APCA-API-KEY-ID"])
        assertEquals("test-alpaca-secret", request.headers["APCA-API-SECRET-KEY"])
    }

    @Test
    fun theNextPageTokenIsFollowedRatherThanTrustedNotToAppear() = runTest {
        // Alpaca caps a page at 10,000 bars and a session is 390, so a second
        // page should never appear in practice. Silently dropping the tail of a
        // series is the kind of defect that reads as a quiet market, so the
        // token is followed anyway. The router matches by path, so the two
        // pages are served by a sequential engine instead.
        val pages = listOf(
            """{"bars":{"AAPL":[{"t":"2026-08-28T13:30:00Z","o":1,"h":2,"l":1,"c":2,"v":10}]},""" +
                """"next_page_token":"page-two"}""",
            """{"bars":{"AAPL":[{"t":"2026-08-28T13:35:00Z","o":2,"h":3,"l":2,"c":3,"v":11}]},""" +
                """"next_page_token":null}""",
        )
        val recorder = MockHttp.Recorder()
        val client = HTTPClient(
            engine = MockHttp.engine(pages.map { MockHttp.Reply(body = it) }, recorder),
        )

        val bars = AlpacaProvider(client, alpacaSecrets())
            .bars("AAPL", BarResolution.FiveMinute, window.first, window.second)

        assertEquals(2, bars.size, "Both pages must be collected, not just the first")
        assertEquals(bars.map { it.date }.sorted(), bars.map { it.date })
        assertEquals(2, recorder.count, "The second page needs its own request")
        assertTrue(
            recorder.requests.last().url.toString().contains("page_token=page-two"),
            "The token from page one must be sent with page two",
        )
    }
}
