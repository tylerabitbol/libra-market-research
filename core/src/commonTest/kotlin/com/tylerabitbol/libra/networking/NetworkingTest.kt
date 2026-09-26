package com.tylerabitbol.libra.networking

import com.tylerabitbol.libra.models.provenance.DataProviderID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Errors and endpoint construction.
 *
 * The Swift file this comes from also holds the keychain, fingerprint and
 * secrets-store suites; those belong to `SecretsStore`, which Phase 5 brings.
 * See KNOWN_ISSUES.md.
 */
class APIErrorTest {

    @Test
    fun retryClassification() {
        assertTrue(APIError.RateLimited(DataProviderID.SEC).isRetryable)
        assertTrue(APIError.Transport(DataProviderID.SEC, "offline").isRetryable)
        assertTrue(APIError.Server(DataProviderID.SEC, 503).isRetryable)

        assertFalse(APIError.Server(DataProviderID.SEC, 400).isRetryable)
        assertFalse(APIError.MissingCredentials(DataProviderID.Finnhub).isRetryable)
        assertFalse(
            APIError.NotEntitled(DataProviderID.Finnhub, "estimates").isRetryable
        )
        assertFalse(
            APIError.Decoding(DataProviderID.FRED, "series", "bad").isRetryable
        )
    }

    @Test
    fun missingCredentialsIsActionable() {
        val suggestion = APIError.MissingCredentials(DataProviderID.Finnhub).recoverySuggestion
        assertTrue(suggestion?.contains("Settings") == true)
    }

    @Test
    fun noDataReadsAsNotReported() {
        assertEquals(
            "not reported",
            APIError.NoData(DataProviderID.SEC, "companyfacts").shortDescription
        )
    }
}

class EndpointTest {

    private val base = "https://data.sec.gov"

    @Test
    fun queryItemsAreIncluded() {
        val endpoint = Endpoint(
            provider = DataProviderID.FRED,
            baseURL = "https://api.stlouisfed.org",
            path = "/fred/series/observations",
            queryItems = listOf(QueryItem("series_id", "UNRATE")),
            label = "observations"
        )
        assertTrue(endpoint.requestURL().contains("series_id=UNRATE"))
    }

    @Test
    fun cacheKeyExcludesHeaders() {
        val withKey = Endpoint(
            provider = DataProviderID.SEC, baseURL = base, path = "/submissions/CIK.json",
            headers = mapOf("User-Agent" to "someone@example.com"), label = "submissions"
        )
        val without = Endpoint(
            provider = DataProviderID.SEC, baseURL = base, path = "/submissions/CIK.json",
            label = "submissions"
        )
        assertEquals(without.cacheKey, withKey.cacheKey)
        assertFalse(withKey.cacheKey.contains("example.com"))
    }

    @Test
    fun cacheKeyIsOrderStable() {
        val a = Endpoint(
            provider = DataProviderID.FRED, baseURL = base, path = "/x",
            queryItems = listOf(QueryItem("b", "2"), QueryItem("a", "1")), label = "x"
        )
        val b = Endpoint(
            provider = DataProviderID.FRED, baseURL = base, path = "/x",
            queryItems = listOf(QueryItem("a", "1"), QueryItem("b", "2")), label = "x"
        )
        assertEquals(a.cacheKey, b.cacheKey)
    }

    @Test
    fun cacheKeyDropsCredentialParameters() {
        // No Swift counterpart at this level — Swift covered header exclusion
        // only. Finnhub and FRED authenticate by query string, so a cache key
        // built from the URL would otherwise carry the key itself (Section 19).
        val endpoint = Endpoint(
            provider = DataProviderID.Finnhub, baseURL = base, path = "/quote",
            queryItems = listOf(
                QueryItem("symbol", "AAPL"),
                QueryItem("token", "secret-value"),
                QueryItem("api_key", "another-secret")
            ),
            label = "quote"
        )
        assertFalse(endpoint.cacheKey.contains("secret"))
        assertTrue(endpoint.cacheKey.contains("symbol=AAPL"))
        // The request itself must still carry them.
        assertTrue(endpoint.requestURL().contains("token=secret-value"))
    }

    @Test
    fun queryValuesArePercentEncoded() {
        // No Swift counterpart: Foundation's URLComponents did this. A SEC
        // User-Agent or a search term with a space would otherwise produce a
        // malformed URL rather than a failed request.
        val endpoint = Endpoint(
            provider = DataProviderID.Finnhub, baseURL = base, path = "/search",
            queryItems = listOf(QueryItem("q", "apple inc & co")), label = "search"
        )
        assertTrue(endpoint.requestURL().contains("q=apple%20inc%20%26%20co"))
    }
}
