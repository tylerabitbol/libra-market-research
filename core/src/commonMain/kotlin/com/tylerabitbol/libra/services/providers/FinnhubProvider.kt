package com.tylerabitbol.libra.services.providers

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Quotes, company data, analyst ratings and news from Finnhub.
 *
 * This account's tier was measured rather than assumed. Available: `quote`,
 * `profile2`, `metric`, `recommendation`, `stock/earnings`,
 * `insider-transactions`, `company-news`. Not available (403): `candle`,
 * `eps-estimate`, `revenue-estimate`, `price-target`, and index symbols.
 *
 * Price history therefore comes from Tiingo, and the estimate-revision work in
 * Section 8 is reported as a capability gap instead of rendering empty.
 */
class FinnhubProvider(
    private val client: HTTPClient,
    private val secrets: SecretsStore,
) {
    val id: DataProviderID = DataProviderID.Finnhub

    suspend fun isConfigured(): Boolean = secrets.hasValue(SecretKey.FinnhubAPIKey)

    /**
     * Finnhub authenticates by query parameter. [Endpoint.cacheKey] strips the
     * token explicitly (see [Endpoint.credentialQueryNames]) so a credential
     * can never become part of a cache key.
     */
    private fun endpoint(
        path: String,
        query: List<QueryItem> = emptyList(),
        label: String,
    ): Endpoint {
        val token = secrets.require(SecretKey.FinnhubAPIKey, DataProviderID.Finnhub)
        return Endpoint(
            provider = DataProviderID.Finnhub,
            baseURL = BASE_URL,
            path = path,
            queryItems = query + QueryItem("token", token),
            label = label,
        )
    }

    // MARK: - Market data

    suspend fun quote(symbol: String): QuoteDTO {
        val raw: FinnhubQuote = client.get(
            endpoint(
                path = "/quote",
                query = listOf(QueryItem("symbol", symbol.uppercase())),
                label = "quote",
            ),
        )
        // Finnhub answers unknown symbols with a 200 and an all-zero body
        // rather than a 404. Treating that as a real price of $0.00 would be a
        // fabricated quote, so it is reported as no data.
        val last = raw.c
        if (last == null || last <= 0) {
            throw APIError.NoData(DataProviderID.Finnhub, endpoint = "quote")
        }
        return QuoteDTO(
            symbol = symbol.uppercase(),
            last = last,
            open = raw.o.nonZero(),
            high = raw.h.nonZero(),
            low = raw.l.nonZero(),
            previousClose = raw.pc.nonZero(),
            volume = null, // Not carried by /quote.
            quoteTime = raw.t?.let { VendorDate.epochSeconds(it.toLong()) },
        )
    }

    suspend fun profile(symbol: String): CompanyProfileDTO {
        val raw: FinnhubProfile = client.get(
            endpoint(
                path = "/stock/profile2",
                query = listOf(QueryItem("symbol", symbol.uppercase())),
                label = "profile2",
            ),
        )
        val ticker = raw.ticker
        if (ticker.isNullOrEmpty()) {
            throw APIError.NotFound(DataProviderID.Finnhub, endpoint = "profile2")
        }
        return CompanyProfileDTO(
            symbol = ticker.uppercase(),
            name = raw.name ?: ticker,
            exchange = raw.exchange,
            sector = raw.finnhubIndustry, // Finnhub's sector-level label.
            industry = raw.finnhubIndustry,
            currency = raw.currency,
            // Reported in millions of the listing currency.
            marketCap = raw.marketCapitalization?.let { it * 1_000_000 },
            sharesOutstanding = raw.shareOutstanding?.let { it * 1_000_000 },
            cik = null,
        )
    }

    suspend fun search(query: String): List<CompanyProfileDTO> {
        val raw: FinnhubSearchResponse = client.get(
            endpoint(
                path = "/search",
                query = listOf(QueryItem("q", query), QueryItem("exchange", "US")),
                label = "search",
            ),
        )
        return raw.result.orEmpty().mapNotNull { item ->
            val symbol = item.symbol ?: return@mapNotNull null
            val description = item.description ?: return@mapNotNull null
            CompanyProfileDTO(symbol = symbol.uppercase(), name = description)
        }
    }

    // MARK: - Analyst

    /**
     * Rating distribution over time. Estimate revisions (`eps-estimate`,
     * `revenue-estimate`, `price-target`) are not on this tier and throw
     * [APIError.NotEntitled], which the UI renders as a capability gap.
     */
    suspend fun ratings(symbol: String): List<RatingSnapshotDTO> {
        val rows: List<FinnhubRecommendation> = client.get(
            endpoint(
                path = "/stock/recommendation",
                query = listOf(QueryItem("symbol", symbol.uppercase())),
                label = "recommendation",
            ),
        )
        return rows.mapNotNull { row ->
            val date = VendorDate.day(row.period) ?: return@mapNotNull null
            RatingSnapshotDTO(
                asOf = date,
                strongBuy = row.strongBuy ?: 0,
                buy = row.buy ?: 0,
                hold = row.hold ?: 0,
                sell = row.sell ?: 0,
                strongSell = row.strongSell ?: 0,
            )
        }.sortedBy { it.asOf }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun estimates(symbol: String, metric: EstimateMetric): List<AnalystEstimateDTO> {
        throw APIError.NotEntitled(
            DataProviderID.Finnhub,
            endpoint = "${metric.displayName} estimates",
        )
    }

    /**
     * Reported EPS against consensus, per quarter — the basis for Section 4's
     * earnings-surprise events.
     */
    suspend fun earningsSurprises(symbol: String): List<EarningsSurpriseDTO> {
        val rows: List<FinnhubEarnings> = client.get(
            endpoint(
                path = "/stock/earnings",
                query = listOf(QueryItem("symbol", symbol.uppercase())),
                label = "earnings",
            ),
        )
        return rows.mapNotNull { row ->
            val date = VendorDate.day(row.period) ?: return@mapNotNull null
            EarningsSurpriseDTO(
                period = date,
                fiscalYear = row.year,
                fiscalQuarter = row.quarter,
                estimate = row.estimate,
                actual = row.actual,
                surprise = row.surprise,
                surprisePercent = row.surprisePercent,
            )
        }.sortedBy { it.period }
    }

    // MARK: - Metrics

    /**
     * Current metrics plus the historical ratio series.
     *
     * The `series` block is the reason this endpoint matters: it carries years
     * of P/E, P/S, margins and ROE, which is what turns a bare multiple into
     * "78th percentile of its own history". Finnhub's free tier includes it
     * even though it withholds candles and estimates.
     */
    suspend fun metrics(symbol: String): CompanyMetricsDTO {
        val raw: FinnhubMetrics = client.get(
            endpoint(
                path = "/stock/metric",
                query = listOf(
                    QueryItem("symbol", symbol.uppercase()),
                    QueryItem("metric", "all"),
                ),
                label = "metric",
            ),
        )
        val current = raw.metric
        if (current.isNullOrEmpty()) {
            throw APIError.NoData(DataProviderID.Finnhub, endpoint = "metric")
        }

        return CompanyMetricsDTO(
            // Finnhub mixes numbers and strings (dates such as 52WeekHighDate)
            // in one object, so non-numeric entries are dropped rather than
            // coerced.
            current = current.mapNotNull { (key, value) ->
                value.numericOrNull()?.let { key to it }
            }.toMap(),
            annual = series(raw.series?.annual),
            quarterly = series(raw.series?.quarterly),
            asOf = Clock.System.now(),
        )
    }

    // MARK: - News

    suspend fun companyNews(symbol: String, from: Instant, to: Instant): List<NewsItemDTO> {
        val rows: List<FinnhubNews> = client.get(
            endpoint(
                path = "/company-news",
                query = listOf(
                    QueryItem("symbol", symbol.uppercase()),
                    QueryItem("from", from.day()),
                    QueryItem("to", to.day()),
                ),
                label = "company-news",
            ),
        )
        return rows.mapNotNull { row ->
            val headline = row.headline
            val datetime = row.datetime
            if (headline.isNullOrEmpty() || datetime == null) return@mapNotNull null
            NewsItemDTO(
                id = row.id?.toString() ?: "$symbol-${datetime.toLong()}",
                headline = headline,
                summary = row.summary,
                source = row.source,
                url = row.url,
                publishedAt = Instant.fromEpochSeconds(datetime.toLong()),
                relatedSymbols = listOf(symbol.uppercase()),
            )
        }.sortedByDescending { it.publishedAt }
    }

    private fun Instant.day(): String = toLocalDateTime(TimeZone.UTC).date.toString()

    companion object {
        const val BASE_URL = "https://finnhub.io/api/v1"

        internal fun series(
            block: Map<String, List<FinnhubSeriesPoint>>?,
        ): Map<String, List<MetricPoint>> {
            if (block == null) return emptyMap()
            return block.mapNotNull { (key, points) ->
                val mapped = points.mapNotNull { point ->
                    val value = point.v ?: return@mapNotNull null
                    val date = VendorDate.day(point.period) ?: return@mapNotNull null
                    MetricPoint(period = date, value = value)
                }.sortedBy { it.period }
                if (mapped.isEmpty()) null else key to mapped
            }.toMap()
        }

        /**
         * Finnhub uses 0 for "not supplied" in quote payloads. Zero is never a
         * real price, so it maps to null rather than to a fabricated value.
         */
        private fun Double?.nonZero(): Double? = this?.takeIf { it != 0.0 }
    }
}

/** Reported versus expected EPS for one fiscal period. */
data class EarningsSurpriseDTO(
    val period: Instant,
    val fiscalYear: Int? = null,
    val fiscalQuarter: Int? = null,
    val estimate: Double? = null,
    val actual: Double? = null,
    val surprise: Double? = null,
    val surprisePercent: Double? = null,
)

// MARK: - Wire format

@Serializable
internal data class FinnhubQuote(
    @Serializable(with = LenientDoubleSerializer::class) val c: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val d: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val dp: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val h: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val l: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val o: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val pc: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val t: Double? = null,
)

@Serializable
internal data class FinnhubProfile(
    val ticker: String? = null,
    val name: String? = null,
    val exchange: String? = null,
    val currency: String? = null,
    val finnhubIndustry: String? = null,
    @Serializable(with = LenientDoubleSerializer::class)
    val marketCapitalization: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class)
    val shareOutstanding: Double? = null,
)

@Serializable
internal data class FinnhubSearchResponse(val result: List<Item>? = null) {
    @Serializable
    data class Item(val symbol: String? = null, val description: String? = null)
}

@Serializable
internal data class FinnhubRecommendation(
    val period: String? = null,
    val strongBuy: Int? = null,
    val buy: Int? = null,
    val hold: Int? = null,
    val sell: Int? = null,
    val strongSell: Int? = null,
)

@Serializable
internal data class FinnhubEarnings(
    val period: String? = null,
    val year: Int? = null,
    val quarter: Int? = null,
    @Serializable(with = LenientDoubleSerializer::class) val estimate: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val actual: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val surprise: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class) val surprisePercent: Double? = null,
)

@Serializable
internal data class FinnhubMetrics(
    /**
     * Left as raw JSON. Finnhub's metric object mixes numbers, strings and
     * nulls under one schema; decoding it as `Map<String, Double>` fails
     * outright on the first date string such as `52WeekHighDate`.
     */
    val metric: Map<String, JsonElement>? = null,
    val series: Series? = null,
) {
    @Serializable
    data class Series(
        val annual: Map<String, List<FinnhubSeriesPoint>>? = null,
        val quarterly: Map<String, List<FinnhubSeriesPoint>>? = null,
    )
}

@Serializable
internal data class FinnhubSeriesPoint(
    val period: String,
    @Serializable(with = LenientDoubleSerializer::class) val v: Double? = null,
)

/**
 * Keeps only entries that arrived as JSON numbers.
 *
 * Deliberately stricter than `LenientDoubleSerializer`: a metric whose value
 * is the *string* "2025-08-14" is a date, and parsing what looks numeric out
 * of this object would put a year where a ratio belongs.
 */
internal fun JsonElement.numericOrNull(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.doubleOrNull?.takeIf { it.isFinite() }
}

@Serializable
internal data class FinnhubNews(
    val id: Long? = null,
    @Serializable(with = LenientDoubleSerializer::class) val datetime: Double? = null,
    val headline: String? = null,
    val summary: String? = null,
    val source: String? = null,
    val url: String? = null,
)
