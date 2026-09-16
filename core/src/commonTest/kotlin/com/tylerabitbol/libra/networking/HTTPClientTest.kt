package com.tylerabitbol.libra.networking

import com.tylerabitbol.libra.models.provenance.DataProviderID
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/**
 * The status-code mapping, the retry path and the 403 disambiguation.
 *
 * No Swift counterpart file: `HTTPClient` was exercised only indirectly,
 * through the provider decoding tests. It is tested directly here because the
 * port changed its engine, and because Ktor's own defaults differ from the
 * Swift behaviour in exactly the places that matter — it raises on non-2xx
 * only when asked, so every mapping below is ours rather than inherited.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HTTPClientTest {

    @Serializable
    private data class Payload(val symbol: String, val last: Double)

    private fun endpoint(provider: DataProviderID = DataProviderID.Finnhub) = Endpoint(
        provider = provider,
        baseURL = "https://example.test",
        path = "/quote",
        label = "quote"
    )

    /** No limiters: the limiter has its own suite, and its waits are not under test. */
    private fun client(engine: io.ktor.client.engine.mock.MockEngine) =
        HTTPClient(engine = engine, limiters = emptyMap())

    @Test
    fun aSuccessfulResponseDecodes() = runTest {
        val engine = MockHttp.engine(
            MockHttp.Reply(body = """{"symbol":"AAPL","last":190.5}""")
        )
        val payload: Payload = client(engine).get(endpoint())
        assertEquals("AAPL", payload.symbol)
        assertEquals(190.5, payload.last)
    }

    @Test
    fun anEmptyBodyIsNotReportedAsADecodingFailure() = runTest {
        // "The provider returned nothing" and "the provider returned something
        // we could not read" send the user to two different places.
        val engine = MockHttp.engine(MockHttp.Reply(body = ""))
        try {
            client(engine).get<Payload>(endpoint())
            fail("Expected noData")
        } catch (error: APIError) {
            assertTrue(error is APIError.NoData)
        }
    }

    @Test
    fun malformedJsonIsADecodingError() = runTest {
        val engine = MockHttp.engine(MockHttp.Reply(body = "{not json"))
        try {
            client(engine).get<Payload>(endpoint())
            fail("Expected a decoding error")
        } catch (error: APIError) {
            assertTrue(error is APIError.Decoding)
            assertEquals("quote", error.endpointLabelForTest)
        }
    }

    @Test
    fun unauthorizedIsAnInvalidCredential() = runTest {
        val engine = MockHttp.engine(MockHttp.Reply(status = HttpStatusCode.Unauthorized))
        try {
            client(engine).data(endpoint())
            fail("Expected invalidCredentials")
        } catch (error: APIError) {
            assertTrue(error is APIError.InvalidCredentials)
        }
    }

    @Test
    fun aForbiddenTierGapIsNotEntitled() = runTest {
        val engine = MockHttp.engine(
            MockHttp.Reply(
                status = HttpStatusCode.Forbidden,
                body = """{"error":"You don't have access to this resource."}"""
            )
        )
        try {
            client(engine).data(endpoint())
            fail("Expected notEntitled")
        } catch (error: APIError) {
            assertTrue(error is APIError.NotEntitled)
        }
    }

    @Test
    fun aForbiddenBadTokenIsAnInvalidCredential() = runTest {
        // Tiingo answers a mistyped key with 403 {"detail":"Invalid token."}.
        // Reported as "not in your plan" it would send the user to consider
        // upgrading a subscription that was never the problem.
        val engine = MockHttp.engine(
            MockHttp.Reply(
                status = HttpStatusCode.Forbidden,
                body = """{"detail":"Invalid token."}"""
            )
        )
        try {
            client(engine).data(endpoint(DataProviderID.Tiingo))
            fail("Expected invalidCredentials")
        } catch (error: APIError) {
            assertTrue(
                error is APIError.InvalidCredentials,
                "A bad token must not read as a missing entitlement"
            )
        }
    }

    @Test
    fun notFoundIsNotFound() = runTest {
        val engine = MockHttp.engine(MockHttp.Reply(status = HttpStatusCode.NotFound))
        try {
            client(engine).data(endpoint())
            fail("Expected notFound")
        } catch (error: APIError) {
            assertTrue(error is APIError.NotFound)
        }
    }

    @Test
    fun anUnmappedStatusKeepsItsCode() = runTest {
        val engine = MockHttp.engine(
            MockHttp.Reply(status = HttpStatusCode(418, "I'm a teapot"))
        )
        try {
            client(engine).data(endpoint())
            fail("Expected a server error")
        } catch (error: APIError) {
            assertTrue(error is APIError.Server)
            assertEquals(418, error.statusCode)
        }
    }

    @Test
    fun aTransientServerErrorIsRetriedAndThenSucceeds() = runTest {
        val recorder = MockHttp.Recorder()
        val engine = MockHttp.engine(
            listOf(
                MockHttp.Reply(status = HttpStatusCode.ServiceUnavailable),
                MockHttp.Reply(body = """{"symbol":"AAPL","last":1.0}""")
            ),
            recorder
        )
        val payload: Payload = client(engine).get(endpoint())
        assertEquals("AAPL", payload.symbol)
        assertEquals(2, recorder.count, "The first attempt must have been retried")
    }

    @Test
    fun aPermanentFailureIsNotRetried() = runTest {
        val recorder = MockHttp.Recorder()
        val engine = MockHttp.engine(
            MockHttp.Reply(status = HttpStatusCode.NotFound), recorder
        )
        try {
            client(engine).data(endpoint())
            fail("Expected notFound")
        } catch (error: APIError) {
            assertTrue(error is APIError.NotFound)
        }
        assertEquals(1, recorder.count, "Retrying a 404 only wastes the quota")
    }

    @Test
    fun retriesAreBoundedAndTheLastErrorSurvives() = runTest {
        val recorder = MockHttp.Recorder()
        val engine = MockHttp.engine(
            MockHttp.Reply(status = HttpStatusCode.ServiceUnavailable), recorder
        )
        try {
            client(engine).data(endpoint())
            fail("Expected a server error")
        } catch (error: APIError) {
            assertTrue(error is APIError.Server)
        }
        assertEquals(3, recorder.count, "Three attempts, then give up")
    }

    @Test
    fun rateLimitingReadsRetryAfter() = runTest {
        val engine = MockHttp.engine(
            MockHttp.Reply(
                status = HttpStatusCode.TooManyRequests,
                headers = mapOf("Retry-After" to "7")
            )
        )
        // Only one attempt is allowed to reach the assertion, so the limiter is
        // present and absorbs the penalty the retry applies.
        try {
            HTTPClient(engine = engine, limiters = emptyMap()).data(endpoint())
            fail("Expected rateLimited")
        } catch (error: APIError) {
            assertTrue(error is APIError.RateLimited)
            assertEquals(7.seconds, error.retryAfter)
        }
    }

    @Test
    fun anOfflineDeviceIsATransportFailure() = runTest {
        try {
            client(MockHttp.failing()).data(endpoint())
            fail("Expected a transport error")
        } catch (error: APIError) {
            assertTrue(error is APIError.Transport)
            assertTrue(error.isRetryable)
        }
    }

    @Test
    fun headersReachTheRequest() = runTest {
        // SEC refuses a request without a User-Agent, so this is the difference
        // between a working provider and a 403.
        val recorder = MockHttp.Recorder()
        val engine = MockHttp.engine(MockHttp.Reply(body = "ok"), recorder)
        val endpoint = Endpoint(
            provider = DataProviderID.SEC, baseURL = "https://data.sec.gov",
            path = "/submissions/CIK0000320193.json",
            headers = mapOf("User-Agent" to "Libra someone@example.com"),
            label = "submissions"
        )
        client(engine).data(endpoint)
        assertEquals(
            "Libra someone@example.com",
            recorder.last?.headers?.get("User-Agent")
        )
    }
}

/** Reaches the label a decoding error carries, without widening the API. */
private val APIError.endpointLabelForTest: String?
    get() = when (this) {
        is APIError.Decoding -> endpoint
        is APIError.NoData -> endpoint
        is APIError.NotFound -> endpoint
        is APIError.NotEntitled -> endpoint
        else -> null
    }
