package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.networking.Endpoint
import com.tylerabitbol.libra.networking.HTTPClient
import com.tylerabitbol.libra.networking.QueryItem
import com.tylerabitbol.libra.networking.serializers.VendorDate
import com.tylerabitbol.libra.services.secrets.SecretKey
import com.tylerabitbol.libra.services.secrets.SecretsStore
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Economic series and real index levels from the St. Louis Fed.
 *
 * FRED does double duty here. It supplies the macro indicators of Section 11,
 * and it also supplies genuine index data — `SP500`, `DJIA`, `NASDAQCOM` and
 * `VIXCLS` — which Finnhub's free tier withholds behind a CFD subscription.
 * That lets the dashboard show the actual S&P 500 and the actual VIX rather
 * than ETF proxies.
 *
 * The tradeoff is that FRED is end-of-day and close-only: no intraday, no
 * OHLC, no volume. The UI states the as-of date rather than implying live data.
 */
class FREDProvider(
    private val client: HTTPClient,
    private val secrets: SecretsStore,
) : MacroDataProvider {

    override val id: DataProviderID = DataProviderID.FRED

    override suspend fun isConfigured(): Boolean = secrets.hasValue(SecretKey.FredAPIKey)

    override suspend fun observations(
        seriesID: String,
        from: Instant?,
        to: Instant?,
    ): List<MacroObservationDTO> {
        val key = secrets.require(SecretKey.FredAPIKey, DataProviderID.FRED)
        val query = buildList {
            add(QueryItem("series_id", seriesID))
            add(QueryItem("file_type", "json"))
            // FRED authenticates by query string, so `api_key` is one of the
            // names `Endpoint.cacheKey` strips — otherwise the key would end
            // up in a cache key.
            add(QueryItem("api_key", key))
            if (from != null) add(QueryItem("observation_start", from.day()))
            if (to != null) add(QueryItem("observation_end", to.day()))
        }

        val endpoint = Endpoint(
            provider = DataProviderID.FRED,
            baseURL = BASE_URL,
            path = "/fred/series/observations",
            queryItems = query,
            label = "fred observations",
        )

        val response = client.get<FREDObservationsResponse>(endpoint)
        val parsed = response.observations.mapNotNull { row ->
            val date = VendorDate.day(row.date) ?: return@mapNotNull null
            val value = parseValue(row.value) ?: return@mapNotNull null
            MacroObservationDTO(seriesID = seriesID, date = date, value = value)
        }

        if (parsed.isEmpty()) {
            throw APIError.NoData(DataProviderID.FRED, endpoint = "fred observations")
        }
        return parsed.sortedBy { it.date }
    }

    private fun Instant.day(): String = toLocalDateTime(TimeZone.UTC).date.toString()

    companion object {
        const val BASE_URL = "https://api.stlouisfed.org"

        /**
         * FRED writes a missing observation as the string `"."` — market
         * holidays in daily series, or periods before a series begins. Those
         * rows are dropped rather than coerced to 0, which would put a fake
         * crash to zero in the middle of an index chart.
         */
        fun parseValue(raw: String?): Double? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed == "." || trimmed.isEmpty()) return null
            return trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
    }
}

// MARK: - Wire format

@Serializable
data class FREDObservationsResponse(
    val observations: List<Observation> = emptyList(),
) {
    @Serializable
    data class Observation(
        val date: String,
        /**
         * Always a string in FRED's JSON, including the "." missing marker,
         * so it is decoded as text and parsed deliberately.
         */
        val value: String,
    )
}
