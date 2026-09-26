package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.networking.Endpoint
import com.tylerabitbol.libra.networking.HTTPClient
import com.tylerabitbol.libra.networking.QueryItem
import com.tylerabitbol.libra.networking.serializers.LenientDoubleSerializer
import com.tylerabitbol.libra.networking.serializers.VendorDate
import com.tylerabitbol.libra.services.secrets.SecretKey
import com.tylerabitbol.libra.services.secrets.SecretsStore
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Daily price history from Tiingo.
 *
 * Finnhub's `stock/candle` endpoint is not available on the free tier (it
 * returns 403), so Tiingo supplies every historical bar the app uses. Its free
 * tier carries adjusted daily OHLCV back to the 1980s for US equities and
 * ETFs, which covers the 5Y chart requirement with room to spare.
 *
 * Tiingo is end-of-day only. Live prices come from Finnhub; see
 * `CompositeMarketDataProvider`, which routes between the two.
 */
class TiingoProvider(
    private val client: HTTPClient,
    private val secrets: SecretsStore,
) {
    val id: DataProviderID = DataProviderID.Tiingo

    suspend fun isConfigured(): Boolean = secrets.hasValue(SecretKey.TiingoAPIKey)

    /**
     * Tiingo authenticates with a header, so the token never enters the URL —
     * and therefore never reaches [Endpoint.cacheKey], which is built from
     * path and query only.
     */
    private fun authHeaders(): Map<String, String> {
        val token = secrets.require(SecretKey.TiingoAPIKey, DataProviderID.Tiingo)
        return mapOf(
            "Authorization" to "Token $token",
            "Content-Type" to "application/json",
        )
    }

    suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        // The free tier serves daily and coarser only. Asking for intraday
        // would silently return daily bars mislabelled as 5-minute data, so we
        // refuse instead — a wrong resolution corrupts every calculation built
        // on top of it.
        if (!resolution.isDailyOrCoarser) {
            throw APIError.NotEntitled(DataProviderID.Tiingo, endpoint = "intraday prices")
        }

        val endpoint = Endpoint(
            provider = DataProviderID.Tiingo,
            baseURL = BASE_URL,
            path = "/tiingo/daily/${symbol.lowercase()}/prices",
            queryItems = listOf(
                QueryItem("startDate", from.day()),
                QueryItem("endDate", to.day()),
                QueryItem("resampleFreq", resolution.tiingoResampleFrequency),
            ),
            headers = authHeaders(),
            label = "tiingo prices",
        )

        return client.get<List<TiingoBar>>(endpoint).mapNotNull { it.asDTO() }
    }

    suspend fun profile(symbol: String): CompanyProfileDTO {
        val endpoint = Endpoint(
            provider = DataProviderID.Tiingo,
            baseURL = BASE_URL,
            path = "/tiingo/daily/${symbol.lowercase()}",
            headers = authHeaders(),
            label = "tiingo metadata",
        )
        val meta = client.get<TiingoMetadata>(endpoint)
        return CompanyProfileDTO(
            symbol = meta.ticker.uppercase(),
            name = meta.name,
            exchange = meta.exchangeCode,
            sector = null, // Tiingo metadata carries no sector.
            industry = null,
            currency = null,
            marketCap = null,
            sharesOutstanding = null,
            cik = null,
        )
    }

    private fun Instant.day(): String {
        val date = toLocalDateTime(TimeZone.UTC).date
        return date.toString()
    }

    companion object {
        const val BASE_URL = "https://api.tiingo.com"
    }
}

/** Tiingo's `resampleFreq` vocabulary. */
val BarResolution.tiingoResampleFrequency: String
    get() = when (this) {
        BarResolution.Weekly -> "weekly"
        BarResolution.Monthly -> "monthly"
        else -> "daily"
    }

// MARK: - Wire format

/**
 * One row of `/tiingo/daily/{ticker}/prices`.
 *
 * Both raw and adjusted OHLCV are decoded. The distinction is not cosmetic:
 * for AAPL on 2020-08-25 the raw close is 499.30 while the adjusted close is
 * 120.98, because of the 4-for-1 split days later. Any return, moving average
 * or volatility figure computed on raw closes would be badly wrong across a
 * split, so `PriceBar.analysisClose` prefers the adjusted series.
 */
@Serializable
internal data class TiingoBar(
    val date: String? = null,
    @Serializable(with = LenientDoubleSerializer::class) val open: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val high: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val low: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val close: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val volume: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val adjOpen: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val adjHigh: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val adjLow: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val adjClose: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val adjVolume: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val divCash: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val splitFactor: Double? = null,
) {
    /**
     * A bar without a date or a close is not usable. Dropping it is correct;
     * substituting a neighbouring value would invent price history.
     */
    fun asDTO(): PriceBarDTO? {
        val parsed = VendorDate.instant(date) ?: return null
        val open = open ?: return null
        val high = high ?: return null
        val low = low ?: return null
        val close = close ?: return null
        return PriceBarDTO(
            date = parsed,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            adjustedClose = adjClose,
        )
    }
}

@Serializable
internal data class TiingoMetadata(
    val ticker: String,
    val name: String,
    val exchangeCode: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
)
