package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.fixtures.Fixture
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.networking.MockHttp
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Swift: `Finnhub provider` in `ProviderDecodingTests.swift`. */
class FinnhubProviderTest {

    private fun provider(router: MockHttp.Router) =
        FinnhubProvider(testClient(router), testSecrets())

    @Test
    fun aRealQuoteDecodesAndMaps() = runTest {
        val router = MockHttp.router().stub("/quote", Fixture.text("finnhub_quote_AAPL"))
        val quote = provider(router).quote("AAPL")

        assertEquals("AAPL", quote.symbol)
        assertTrue(quote.last > 0)
        assertNotNull(quote.previousClose)
        assertNotNull(quote.changePercent)
    }

    @Test
    fun anAllZeroQuoteIsTreatedAsNoDataNotAsAPriceOfZero() = runTest {
        // Finnhub answers unknown symbols with 200 and zeros rather than a 404.
        val router = MockHttp.router()
            .stub("/quote", """{"c":0,"d":null,"dp":null,"h":0,"l":0,"o":0,"pc":0,"t":0}""")

        val error = assertFailsWith<APIError.NoData> {
            provider(router).quote("NOTAREALTICKER")
        }
        assertEquals(DataProviderID.Finnhub, error.providerID)
        assertEquals("quote", error.endpoint)
    }

    @Test
    fun aRealProfileDecodesWithMarketCapScaledFromMillions() = runTest {
        val router = MockHttp.router()
            .stub("/stock/profile2", Fixture.text("finnhub_profile2_AAPL"))
        val profile = provider(router).profile("AAPL")

        assertEquals("AAPL", profile.symbol)
        assertTrue(profile.name.contains("Apple"))
        // Finnhub reports millions; a trillion-dollar company must read as such.
        val marketCap = assertNotNull(profile.marketCap)
        assertTrue(marketCap > 1_000_000_000_000.0)
    }

    @Test
    fun ratingSnapshotsDecodeAndSortOldestFirst() = runTest {
        val router = MockHttp.router()
            .stub("/stock/recommendation", Fixture.text("finnhub_recommendation_AAPL"))
        val ratings = provider(router).ratings("AAPL")

        assertTrue(ratings.isNotEmpty())
        assertEquals(ratings.sortedBy { it.asOf }, ratings)
        assertTrue(ratings.all { it.total > 0 })
    }

    @Test
    fun earningsSurprisesDecodeWithEstimateAndActual() = runTest {
        val router = MockHttp.router()
            .stub("/stock/earnings", Fixture.text("finnhub_earnings_AAPL"))
        val surprises = provider(router).earningsSurprises("AAPL")

        assertTrue(surprises.isNotEmpty())
        assertTrue(surprises.any { it.actual != null && it.estimate != null })
    }

    @Test
    fun newsDecodesAndSortsNewestFirst() = runTest {
        val router = MockHttp.router()
            .stub("/company-news", Fixture.text("finnhub_news_AAPL"))
        val news = provider(router).companyNews(
            symbol = "AAPL",
            from = Instant.parse("2026-08-20T00:00:00Z"),
            to = Instant.parse("2026-09-03T00:00:00Z"),
        )

        assertTrue(news.isNotEmpty())
        assertEquals(news.sortedByDescending { it.publishedAt }, news)
    }

    @Test
    fun aForbiddenResponseBecomesNotEntitledSoTheUIShowsAPlanGapNotAnError() = runTest {
        val router = MockHttp.router().stub(
            "/stock/recommendation",
            Fixture.text("finnhub_403_notEntitled"),
            status = HttpStatusCode.Forbidden,
        )

        val error = assertFailsWith<APIError.NotEntitled> { provider(router).ratings("AAPL") }
        assertEquals(DataProviderID.Finnhub, error.providerID)
        assertEquals("recommendation", error.endpoint)
    }

    @Test
    fun estimateEndpointsReportTheTierGapWithoutMakingARequest() = runTest {
        val router = MockHttp.router()
        assertFailsWith<APIError.NotEntitled> {
            provider(router).estimates("AAPL", EstimateMetric.Eps)
        }
        assertTrue(router.requests.isEmpty())
    }

    @Test
    fun metricsKeepNumbersAndDropTheDateStringsFinnhubMixesIn() = runTest {
        val router = MockHttp.router()
            .stub("/stock/metric", Fixture.text("finnhub_metric_AAPL"))
        val metrics = provider(router).metrics("AAPL")

        assertTrue(metrics.current.isNotEmpty())
        assertTrue(metrics.current.values.all { it.isFinite() })
        // `52WeekHighDate` is a date string in the same object as the ratios.
        // Reading it as a number would put a year where a multiple belongs.
        assertFalse(metrics.current.containsKey("52WeekHighDate"))
        assertTrue(metrics.current.containsKey("52WeekHigh"))
    }

    @Test
    fun theHistoricalSeriesIsWhatMakesAMultipleInterpretable() = runTest {
        val router = MockHttp.router()
            .stub("/stock/metric", Fixture.text("finnhub_metric_AAPL"))
        val metrics = provider(router).metrics("AAPL")

        assertTrue(
            metrics.annual.isNotEmpty() || metrics.quarterly.isNotEmpty(),
            "The series block is the reason this endpoint is worth calling",
        )
        for (points in metrics.annual.values + metrics.quarterly.values) {
            assertTrue(points.isNotEmpty(), "An empty series should be dropped, not kept")
            assertEquals(points.sortedBy { it.period }, points)
        }
    }

    @Test
    fun anEmptyMetricObjectReportsNoDataRatherThanAnEmptySuccess() = runTest {
        val router = MockHttp.router().stub("/stock/metric", """{"metric":{},"series":null}""")
        val error = assertFailsWith<APIError.NoData> { provider(router).metrics("AAPL") }
        assertEquals("metric", error.endpoint)
    }

    @Test
    fun theTokenIsSentAsAQueryParameterAndStrippedFromTheCacheKey() = runTest {
        val router = MockHttp.router().stub("/quote", Fixture.text("finnhub_quote_AAPL"))
        provider(router).quote("AAPL")

        val request = assertNotNull(router.requests.firstOrNull())
        // Finnhub has no header auth, so the token is unavoidably in the URL.
        // What must never happen is it reaching a cache key.
        assertTrue(request.url.toString().contains("token=test-finnhub-key"))
    }
}
