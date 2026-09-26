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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Intraday price bars from Alpaca's IEX feed.
 *
 * Tiingo's free tier is end-of-day only, so the 1D and 5D chart ranges had
 * nothing behind them. Alpaca's free plan serves minute-level bars at 200
 * requests a minute, which covers them.
 *
 * **The IEX feed is roughly 2.5% of US equity volume.** That is why the
 * ROADMAP declined Alpaca as a history source and why these bars are for
 * drawing only. A volume anomaly computed against 2.5% of the real tape would
 * be meaningless, and a return computed from IEX prints can differ from the
 * consolidated tape. Nothing here reaches `EventDetector`,
 * `FundamentalDetector`, `RelativeAnalysis` or `ReturnCalculator.priceContext`:
 * the view model keeps this series in `intradayBars`, separate from `bars`, so
 * a calculation cannot be handed one of these by forgetting.
 *
 * Daily and coarser bars stay on Tiingo, which is consolidated and
 * split-adjusted. Swapping those for an IEX-only series would quietly degrade
 * every calculation in the app.
 */
class AlpacaProvider(
    private val client: HTTPClient,
    private val secrets: SecretsStore,
) {
    val id: DataProviderID = DataProviderID.Alpaca

    /**
     * Both halves are required. One alone authenticates nothing, so treating
     * a half-entered pair as configured would produce 403s that look like
     * outages.
     */
    suspend fun isConfigured(): Boolean =
        secrets.hasValue(SecretKey.AlpacaKeyID) && secrets.hasValue(SecretKey.AlpacaSecretKey)

    /**
     * Credentials go in headers, so they never enter the URL and therefore
     * never reach [Endpoint.cacheKey].
     */
    private fun authHeaders(): Map<String, String> = mapOf(
        "APCA-API-KEY-ID" to secrets.require(SecretKey.AlpacaKeyID, DataProviderID.Alpaca),
        "APCA-API-SECRET-KEY" to
            secrets.require(SecretKey.AlpacaSecretKey, DataProviderID.Alpaca),
        "Accept" to "application/json",
    )

    suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        // The mirror image of Tiingo's refusal. Asking Alpaca for daily bars
        // would return an IEX-only daily series that looks exactly like the
        // consolidated one and is not.
        val timeframe = resolution.alpacaTimeframe
            ?: throw APIError.NotEntitled(DataProviderID.Alpaca, endpoint = "daily bars")

        val headers = authHeaders()
        val collected = mutableListOf<PriceBarDTO>()
        var pageToken: String? = null

        repeat(MAXIMUM_PAGES) {
            val query = buildList {
                add(QueryItem("symbols", symbol.uppercase()))
                add(QueryItem("timeframe", timeframe))
                add(QueryItem("start", from.toString()))
                add(QueryItem("end", to.toString()))
                add(QueryItem("feed", "iex"))
                // Raw prints jump across a split. The chart is the one place a
                // split would be visible as a cliff, so ask for the adjusted
                // series; `adjustedClose` stays null because these closes *are*
                // the adjusted ones.
                add(QueryItem("adjustment", "split"))
                add(QueryItem("limit", PAGE_LIMIT.toString()))
                pageToken?.let { add(QueryItem("page_token", it)) }
            }

            val endpoint = Endpoint(
                provider = DataProviderID.Alpaca,
                baseURL = BASE_URL,
                path = "/v2/stocks/bars",
                queryItems = query,
                headers = headers,
                label = "alpaca bars",
            )

            val page: AlpacaBarsResponse = client.get(endpoint)
            collected += page.bars?.get(symbol.uppercase()).orEmpty().mapNotNull { it.asDTO() }

            val next = page.nextPageToken
            if (next.isNullOrEmpty()) return collected.sortedBy { it.date }
            pageToken = next
        }

        return collected.sortedBy { it.date }
    }

    companion object {
        const val BASE_URL = "https://data.alpaca.markets"

        /**
         * Alpaca caps a page at 10,000 bars. A full session of one-minute bars
         * is 390, so a single page covers every range the app offers — but the
         * token is followed anyway rather than trusted not to appear. Silently
         * dropping the tail of a series is the kind of defect that reads as a
         * quiet market.
         */
        const val PAGE_LIMIT = 10_000
        const val MAXIMUM_PAGES = 10
    }
}

/**
 * Alpaca's `timeframe` vocabulary, and null for anything daily or coarser —
 * which Alpaca can serve but this app must not take from it.
 */
val BarResolution.alpacaTimeframe: String?
    get() = when (this) {
        BarResolution.OneMinute -> "1Min"
        BarResolution.FiveMinute -> "5Min"
        BarResolution.FifteenMinute -> "15Min"
        BarResolution.Hourly -> "1Hour"
        BarResolution.Daily, BarResolution.Weekly, BarResolution.Monthly -> null
    }

// MARK: - Wire format

/**
 * `{"bars": {"AAPL": [...]}, "next_page_token": "..."}`
 *
 * `bars` is keyed by symbol and is **absent, not empty**, when the window
 * contains no trades — a weekend, or a session that has not opened. Decoding
 * it as nullable is what lets the view model tell "no trades" apart from a
 * failed request.
 */
@Serializable
internal data class AlpacaBarsResponse(
    val bars: Map<String, List<AlpacaBar>>? = null,
    @SerialName("next_page_token") val nextPageToken: String? = null,
)

/**
 * One bar. Alpaca uses single-letter keys: `t` timestamp, `o` open, `h` high,
 * `l` low, `c` close, `v` volume, `n` trade count, `vw` volume-weighted price.
 */
@Serializable
internal data class AlpacaBar(
    val t: String? = null,
    @Serializable(with = LenientDoubleSerializer::class) val o: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val h: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val l: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val c: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val v: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val n: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val vw: Double? = null,
) {
    /**
     * A bar without a timestamp or a close cannot be drawn. Dropping it is
     * correct; interpolating would invent price history.
     */
    fun asDTO(): PriceBarDTO? {
        val date = VendorDate.instant(t) ?: return null
        val open = o ?: return null
        val high = h ?: return null
        val low = l ?: return null
        val close = c ?: return null
        return PriceBarDTO(
            date = date,
            open = open,
            high = high,
            low = low,
            close = close,
            // IEX volume, not consolidated volume. Carried so the series is
            // complete, and kept away from every volume calculation by the
            // view model rather than by being dropped here.
            volume = v,
            // These closes are already split-adjusted by the `adjustment`
            // parameter, so there is no separate adjusted series to report.
            adjustedClose = null,
        )
    }
}
