package com.tylerabitbol.libra.networking

import com.tylerabitbol.libra.models.provenance.DataProviderID
import kotlin.time.Duration

/**
 * Every failure mode the spec's Section 21 requires the app to survive.
 *
 * Typed rather than free-form because the UI needs to distinguish "you need
 * to enter a key" (actionable by the user) from "the provider is down"
 * (actionable by waiting) from "this company doesn't report that figure"
 * (not a failure at all, and must render as "Not available").
 */
sealed class APIError : Exception() {

    /**
     * No API key configured for this provider. The UI should route the user
     * to Settings rather than showing a generic error.
     */
    data class MissingCredentials(val providerID: DataProviderID) : APIError()

    /** Key present but rejected — expired, revoked, or wrong. */
    data class InvalidCredentials(val providerID: DataProviderID) : APIError()

    /** Provider rate limit hit. [retryAfter] when the provider tells us. */
    data class RateLimited(val providerID: DataProviderID, val retryAfter: Duration? = null) : APIError()

    /**
     * The endpoint exists but this account's tier doesn't include it.
     * Common on free tiers and must be surfaced as a capability gap, not a bug.
     */
    data class NotEntitled(val providerID: DataProviderID, val endpoint: String) : APIError()

    data class NotFound(val providerID: DataProviderID, val endpoint: String) : APIError()

    data class Server(val providerID: DataProviderID, val statusCode: Int) : APIError()

    data class Transport(val providerID: DataProviderID, val underlying: String) : APIError()

    data class Decoding(
        val providerID: DataProviderID,
        val endpoint: String,
        val underlying: String
    ) : APIError()

    /**
     * Request succeeded but the payload was empty or structurally valid yet
     * semantically useless. Distinct from [NotFound] — the concept exists,
     * this issuer just doesn't report it.
     */
    data class NoData(val providerID: DataProviderID, val endpoint: String) : APIError()

    data object Cancelled : APIError()

    val provider: DataProviderID?
        get() = when (this) {
            is MissingCredentials -> providerID
            is InvalidCredentials -> providerID
            is RateLimited -> providerID
            is NotEntitled -> providerID
            is NotFound -> providerID
            is Server -> providerID
            is Transport -> providerID
            is Decoding -> providerID
            is NoData -> providerID
            Cancelled -> null
        }

    /** Whether retrying the identical request could plausibly succeed. */
    val isRetryable: Boolean
        get() = when (this) {
            is RateLimited, is Transport -> true
            is Server -> statusCode >= 500
            is MissingCredentials, is InvalidCredentials, is NotEntitled,
            is NotFound, is Decoding, is NoData, Cancelled -> false
        }

    /** Short text for inline display next to a value. */
    val shortDescription: String
        get() = when (this) {
            is MissingCredentials -> "${providerID.displayName} key not set"
            is InvalidCredentials -> "${providerID.displayName} key rejected"
            is RateLimited -> "${providerID.displayName} rate limit"
            is NotEntitled -> "not in ${providerID.displayName} plan"
            is NotFound -> "not found"
            is Server -> "server error $statusCode"
            is Transport -> "network error"
            is Decoding -> "unexpected response"
            is NoData -> "not reported"
            Cancelled -> "cancelled"
        }

    /** Longer text with a suggested next step, for error surfaces that have room. */
    val recoverySuggestion: String?
        get() = when (this) {
            is MissingCredentials ->
                "Add your ${providerID.displayName} key in Settings → Data Sources."
            is InvalidCredentials ->
                "Check your ${providerID.displayName} key in Settings → Data Sources. It may have expired."
            is RateLimited ->
                retryAfter?.let { "Rate limited. Retrying is possible in about ${it.inWholeSeconds} seconds." }
                    ?: "Rate limited. Try again shortly."
            is NotEntitled -> "$endpoint isn't included in your ${providerID.displayName} plan."
            is Transport -> "Check your network connection."
            is NoData -> "This issuer doesn't report this figure."
            is NotFound, is Server, is Decoding, Cancelled -> null
        }

    override val message: String get() = shortDescription
}
