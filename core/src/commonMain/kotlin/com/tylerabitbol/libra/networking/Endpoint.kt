package com.tylerabitbol.libra.networking

import com.tylerabitbol.libra.models.provenance.DataProviderID

/** One query-string parameter. A null value renders as a bare name. */
data class QueryItem(val name: String, val value: String? = null)

/**
 * A describable HTTP request. Kept provider-agnostic so the client can log,
 * rate-limit and cache uniformly regardless of which API it is talking to.
 */
data class Endpoint(
    val provider: DataProviderID,
    val baseURL: String,
    val path: String,
    val queryItems: List<QueryItem> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    /** Short label used in errors and logs, e.g. "quote" or "companyfacts". */
    val label: String
) {

    /**
     * The absolute URL this endpoint addresses.
     *
     * Swift built a `URLRequest`; here the client owns the request object, so
     * the endpoint's job stops at the URL and the headers beside it.
     */
    fun requestURL(): String {
        val base = baseURL.trimEnd('/')
        val suffix = if (path.isEmpty() || path.startsWith("/")) path else "/$path"
        if (base.isEmpty()) {
            throw APIError.Transport(provider, "Could not build URL for $label")
        }
        if (queryItems.isEmpty()) return base + suffix
        val query = queryItems.joinToString("&") { item ->
            val name = encode(item.name)
            if (item.value == null) name else "$name=${encode(item.value)}"
        }
        return "$base$suffix?$query"
    }

    /**
     * Stable key for the response cache. Excludes headers and any credential
     * query parameter, so a stored key can never contain a secret.
     */
    val cacheKey: String
        get() {
            val safe = queryItems
                .filter { !credentialQueryNames.contains(it.name.lowercase()) }
                .sortedBy { it.name }
            val query = if (safe.isEmpty()) {
                ""
            } else {
                "?" + safe.joinToString("&") { item ->
                    if (item.value == null) item.name else "${item.name}=${item.value}"
                }
            }
            return "${provider.raw}|$path$query"
        }

    companion object {
        /**
         * Query parameter names that carry credentials.
         *
         * Finnhub and FRED both authenticate by query string rather than
         * header, so excluding headers alone is not enough to keep secrets out
         * of cache keys — these names are stripped explicitly.
         */
        val credentialQueryNames: Set<String> = setOf("token", "api_key", "apikey")

        private const val unreserved =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /**
         * Percent-encoding for one query component.
         *
         * Hand-rolled rather than taken from Ktor's URL builder because the
         * builder re-encodes values it believes are already escaped, which
         * turned a `%` inside a FRED series id into `%25`.
         */
        private fun encode(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val character = byte.toInt().toChar()
                if (unreserved.contains(character)) {
                    append(character)
                } else {
                    append('%')
                    append(
                        (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                    )
                }
            }
        }
    }
}
