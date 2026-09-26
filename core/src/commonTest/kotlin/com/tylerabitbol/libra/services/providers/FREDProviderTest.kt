package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.fixtures.Fixture
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.networking.Endpoint
import com.tylerabitbol.libra.networking.MockHttp
import com.tylerabitbol.libra.networking.QueryItem
import com.tylerabitbol.libra.services.secrets.InMemorySecretsStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Swift: `FRED provider` in `ProviderDecodingTests.swift`. */
class FREDProviderTest {

    @Test
    fun observationsDecodeWithHolidayGapsRemoved() = runTest {
        val router = MockHttp.router()
            .stub("/fred/series/observations", Fixture.text("fred_observations_SP500"))
        val provider = FREDProvider(testClient(router), testSecrets())

        val observations = provider.observations("SP500", from = null, to = null)
        // 12 rows in the fixture, two of which are "." holidays.
        assertEquals(10, observations.size)
        assertTrue(observations.all { it.value > 0 })
        assertEquals(observations.sortedBy { it.date }, observations)
        assertTrue(observations.all { it.seriesID == "SP500" })
    }

    @Test
    fun aSeriesWithNoUsableValuesReportsNoDataRatherThanAnEmptySuccess() = runTest {
        val router = MockHttp.router().stub(
            "/fred/series/observations",
            """{"observations":[{"date":"2026-01-01","value":"."}]}""",
        )
        val provider = FREDProvider(testClient(router), testSecrets())

        val error = assertFailsWith<APIError.NoData> {
            provider.observations("SP500", from = null, to = null)
        }
        assertEquals(DataProviderID.FRED, error.providerID)
        assertEquals("fred observations", error.endpoint)
    }

    @Test
    fun theDateWindowIsOmittedEntirelyWhenNotAskedFor() = runTest {
        val router = MockHttp.router()
            .stub("/fred/series/observations", Fixture.text("fred_observations_SP500"))
        val provider = FREDProvider(testClient(router), testSecrets())

        provider.observations("SP500", from = null, to = null)
        val url = assertNotNull(router.requests.firstOrNull()).url.toString()
        assertFalse(url.contains("observation_start"))
        assertFalse(url.contains("observation_end"))

        provider.observations(
            "SP500",
            from = Instant.parse("2025-12-24T17:00:00Z"),
            to = Instant.parse("2026-01-05T09:30:00Z"),
        )
        val bounded = router.requests.last().url.toString()
        assertTrue(bounded.contains("observation_start=2025-12-24"), bounded)
        assertTrue(bounded.contains("observation_end=2026-01-05"), bounded)
    }

    @Test
    fun aMissingKeyThrowsBeforeAnyRequestIsMade() = runTest {
        val router = MockHttp.router()
        val provider = FREDProvider(testClient(router), InMemorySecretsStore())

        assertFailsWith<APIError.MissingCredentials> {
            provider.observations("SP500", from = null, to = null)
        }
        assertTrue(router.requests.isEmpty(), "No credential means no outbound request")
    }
}

/** Swift: `Credential hygiene` in `ProviderDecodingTests.swift`. */
class CredentialHygieneTest {

    @Test
    fun finnhubsTokenQueryParameterNeverReachesACacheKey() {
        val endpoint = Endpoint(
            provider = DataProviderID.Finnhub,
            baseURL = FinnhubProvider.BASE_URL,
            path = "/quote",
            queryItems = listOf(
                QueryItem("symbol", "AAPL"),
                QueryItem("token", "super-secret-key"),
            ),
            label = "quote",
        )
        assertFalse(endpoint.cacheKey.contains("super-secret-key"))
        assertTrue(endpoint.cacheKey.contains("AAPL"))
    }

    @Test
    fun fredsApiKeyParameterNeverReachesACacheKey() {
        val endpoint = Endpoint(
            provider = DataProviderID.FRED,
            baseURL = FREDProvider.BASE_URL,
            path = "/fred/series/observations",
            queryItems = listOf(
                QueryItem("series_id", "SP500"),
                QueryItem("api_key", "super-secret-key"),
            ),
            label = "observations",
        )
        assertFalse(endpoint.cacheKey.contains("super-secret-key"))
        assertTrue(endpoint.cacheKey.contains("SP500"))
    }

    @Test
    fun twoRequestsDifferingOnlyByCredentialShareOneCacheKey() {
        fun endpoint(token: String) = Endpoint(
            provider = DataProviderID.Finnhub,
            baseURL = FinnhubProvider.BASE_URL,
            path = "/quote",
            queryItems = listOf(QueryItem("symbol", "AAPL"), QueryItem("token", token)),
            label = "quote",
        )
        assertEquals(endpoint("old").cacheKey, endpoint("rotated").cacheKey)
    }
}
