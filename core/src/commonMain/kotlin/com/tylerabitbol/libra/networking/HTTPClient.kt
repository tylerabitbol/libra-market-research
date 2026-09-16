package com.tylerabitbol.libra.networking

import co.touchlab.kermit.Logger
import com.tylerabitbol.libra.models.provenance.DataProviderID
import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.CancellationException
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.coroutines.coroutineContext

/**
 * The single path every outbound request takes.
 *
 * Centralising this is what makes Section 21 achievable: rate limiting,
 * retry/backoff, status-code interpretation and decoding all happen once, so
 * a new provider inherits correct failure behaviour instead of reimplementing
 * it. Providers describe *what* to fetch; this decides *how*.
 */
class HTTPClient(
    engine: HttpClientEngine? = null,
    limiters: Map<DataProviderID, RateLimiter>? = null,
    /**
     * Shared decoder. No global date handling: the providers use three
     * different date encodings, so each provider's DTOs decode dates
     * explicitly.
     */
    val json: Json = libraJson
) {
    private val logger = Logger.withTag("http")
    private val maxAttempts = 3

    private val limiters: Map<DataProviderID, RateLimiter> = limiters ?: mapOf(
        DataProviderID.SEC to RateLimiter.sec(),
        DataProviderID.Finnhub to RateLimiter.finnhub(),
        DataProviderID.Tiingo to RateLimiter.tiingo(),
        DataProviderID.FRED to RateLimiter.fred()
    )

    private val client: KtorClient = run {
        val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            expectSuccess = false // Status codes are mapped to APIError below.
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
                connectTimeoutMillis = 20_000
            }
            // SEC serves gzip and will not negotiate without this.
            install(ContentEncoding) {
                gzip()
                deflate()
            }
        }
        if (engine != null) KtorClient(engine, configure) else KtorClient(configure)
    }

    /** Fetches and decodes, retrying transient failures with exponential backoff. */
    suspend fun <T> get(endpoint: Endpoint, deserializer: DeserializationStrategy<T>): T {
        val bytes = data(endpoint)
        if (bytes.isEmpty()) {
            throw APIError.NoData(endpoint.provider, endpoint = endpoint.label)
        }
        return try {
            json.decodeFromString(deserializer, bytes.decodeToString())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.e { "Decode failed for ${endpoint.label}: ${error.message}" }
            throw APIError.Decoding(
                endpoint.provider,
                endpoint = endpoint.label,
                underlying = error.message ?: "unknown"
            )
        }
    }

    suspend inline fun <reified T> get(endpoint: Endpoint): T =
        get(endpoint, json.serializersModule.serializer())

    /**
     * Fetches raw bytes. Used for filing documents, which are HTML/XML rather
     * than JSON.
     */
    suspend fun data(endpoint: Endpoint): ByteArray {
        var lastError: APIError =
            APIError.Transport(endpoint.provider, underlying = "no attempt made")

        for (attempt in 1..maxAttempts) {
            coroutineContext.ensureActive()
            limiters[endpoint.provider]?.waitForSlot()

            try {
                return performOnce(endpoint)
            } catch (error: APIError) {
                lastError = error
                if (!error.isRetryable || attempt == maxAttempts) throw error

                var wait = (2.0.pow(attempt - 1) * 0.5).seconds
                if (error is APIError.RateLimited) {
                    wait = error.retryAfter ?: wait
                    limiters[error.providerID]?.penalize(wait)
                }
                logger.i {
                    "Retrying ${endpoint.label} in $wait (attempt $attempt)"
                }
                delay(wait)
            }
        }
        throw lastError
    }

    private suspend fun performOnce(endpoint: Endpoint): ByteArray {
        val response: HttpResponse = try {
            client.get(endpoint.requestURL()) {
                endpoint.headers.forEach { (key, value) -> header(key, value) }
            }
        } catch (cancellation: CancellationException) {
            throw APIError.Cancelled
        } catch (error: APIError) {
            throw error
        } catch (error: Exception) {
            throw APIError.Transport(
                endpoint.provider, underlying = error.message ?: "transport failure"
            )
        }

        val bytes = response.bodyAsBytes()
        val status = response.status.value

        return when {
            status in 200..299 -> bytes

            status == 401 -> throw APIError.InvalidCredentials(endpoint.provider)

            status == 403 -> {
                // 403 is overloaded across these providers and the distinction
                // is the difference between two very different user actions.
                //
                // Tiingo answers a bad credential with 403 {"detail":"Invalid
                // token."} rather than 401, so a mistyped key would otherwise
                // be reported as "not in your plan" and send the user off to
                // consider upgrading a subscription that was never the problem.
                // Finnhub uses 403 for genuine tier gaps; SEC uses it when the
                // User-Agent is missing. Reading the body is the only way to
                // tell them apart.
                val body = bytes.decodeToString().lowercase()
                val looksLikeBadCredential = badCredentialMarkers.any { body.contains(it) }
                throw if (looksLikeBadCredential) {
                    APIError.InvalidCredentials(endpoint.provider)
                } else {
                    APIError.NotEntitled(endpoint.provider, endpoint = endpoint.label)
                }
            }

            status == 404 -> throw APIError.NotFound(endpoint.provider, endpoint = endpoint.label)

            status == 429 -> throw APIError.RateLimited(
                endpoint.provider,
                retryAfter = response.headers[HttpHeaders.RetryAfter]
                    ?.toDoubleOrNull()?.seconds
            )

            else -> throw APIError.Server(endpoint.provider, statusCode = status)
        }
    }

    fun close() = client.close()

    companion object {
        private val badCredentialMarkers = listOf(
            "invalid token", "invalid api key", "not authorized", "invalid credentials"
        )

        val libraJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    }
}
