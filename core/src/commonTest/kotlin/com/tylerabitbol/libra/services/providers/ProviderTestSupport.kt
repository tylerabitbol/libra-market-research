package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.networking.HTTPClient
import com.tylerabitbol.libra.networking.MockHttp
import com.tylerabitbol.libra.networking.RateLimiter
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.services.secrets.InMemorySecretsStore
import com.tylerabitbol.libra.services.secrets.SecretKey
import kotlin.time.Duration.Companion.seconds

/**
 * The seeded credentials every provider test uses.
 *
 * Deliberately obvious placeholders. No real key belongs in a test, a fixture
 * or anywhere else in this repository — Section 19, and the reason several of
 * these tests assert the value never reaches the URL.
 */
fun testSecrets(): InMemorySecretsStore = InMemorySecretsStore(
    mapOf(
        SecretKey.FinnhubAPIKey to "test-finnhub-key",
        SecretKey.TiingoAPIKey to "test-tiingo-key",
        SecretKey.FredAPIKey to "test-fred-key",
        SecretKey.AlpacaKeyID to "test-alpaca-id",
        SecretKey.AlpacaSecretKey to "test-alpaca-secret",
        SecretKey.SecContactEmail to "tests@example.com",
    ),
)

/**
 * A client wired to [router] with the rate limiters removed.
 *
 * The real presets would make a suite that drives eight endpoints wait out
 * Tiingo's one-hour refill. Back-pressure has its own tests against virtual
 * time in `RateLimiterTest`; here it would only be a delay.
 */
fun testClient(router: MockHttp.Router): HTTPClient = HTTPClient(
    engine = router.engine(),
    limiters = DataProviderID.entries.associateWith {
        RateLimiter(1_000_000, 1.seconds, burst = 1_000_000, provider = it)
    },
)
