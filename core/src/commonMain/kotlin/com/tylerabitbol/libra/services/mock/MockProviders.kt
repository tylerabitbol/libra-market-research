package com.tylerabitbol.libra.services.mock

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.services.providers.CompanyProfileDTO
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.services.providers.InsiderTransactionDTO
import com.tylerabitbol.libra.services.providers.MacroDataProvider
import com.tylerabitbol.libra.services.providers.MacroObservationDTO
import com.tylerabitbol.libra.services.providers.MarketDataProvider
import com.tylerabitbol.libra.services.providers.NewsItemDTO
import com.tylerabitbol.libra.services.providers.NewsProvider
import com.tylerabitbol.libra.services.providers.PriceBarDTO
import com.tylerabitbol.libra.services.providers.QuoteDTO
import com.tylerabitbol.libra.services.providers.SECDataProvider
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Deterministic sample data for previews, tests and offline development.
 *
 * Everything here is synthetic. It is seeded per symbol so a given ticker
 * always produces the same series — which is what makes it usable in tests —
 * but the numbers are invented and must never be presented as real. Any view
 * running against these providers is expected to show the sample-data banner;
 * see `ProviderRegistry.isUsingSampleData`.
 */
class SeededGenerator(seed: String) {
    private var state: ULong

    init {
        // FNV-1a over the seed string, so the same symbol always starts the
        // same way across launches and across machines.
        var hash = 0xcbf29ce484222325UL
        for (byte in seed.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x100000001b3UL
        }
        state = if (hash == 0UL) 0x9E3779B97F4A7C15UL else hash
    }

    fun next(): ULong {
        // xorshift64*
        state = state xor (state shr 12)
        state = state xor (state shl 25)
        state = state xor (state shr 27)
        return state * 2685821657736338717UL
    }

    /**
     * A uniform draw in `[lower, upper)`.
     *
     * Swift's `Double.random(in:using:)` is not specified precisely enough to
     * reproduce, so this maps the top 53 bits — the mantissa width of a
     * `Double` — rather than trying to match it bit for bit. The generator
     * itself is ported exactly, so the series stays deterministic, which is the
     * property the tests depend on.
     */
    fun nextDouble(lower: Double, upper: Double): Double {
        val unit = (next() shr 11).toDouble() / (1UL shl 53).toDouble()
        return lower + unit * (upper - lower)
    }
}

object SampleData {
    val symbols = listOf("NVDA", "AAPL", "MSFT", "COST", "JPM")

    val profiles: Map<String, CompanyProfileDTO> = mapOf(
        "NVDA" to CompanyProfileDTO(
            symbol = "NVDA", name = "NVIDIA Corporation", exchange = "NASDAQ",
            sector = "Information Technology", industry = "Semiconductors",
            currency = "USD", marketCap = 3_100_000_000_000.0,
            sharesOutstanding = 24_400_000_000.0, cik = "0001045810",
        ),
        "AAPL" to CompanyProfileDTO(
            symbol = "AAPL", name = "Apple Inc.", exchange = "NASDAQ",
            sector = "Information Technology", industry = "Technology Hardware",
            currency = "USD", marketCap = 3_400_000_000_000.0,
            sharesOutstanding = 15_000_000_000.0, cik = "0000320193",
        ),
        "MSFT" to CompanyProfileDTO(
            symbol = "MSFT", name = "Microsoft Corporation", exchange = "NASDAQ",
            sector = "Information Technology", industry = "Software",
            currency = "USD", marketCap = 3_200_000_000_000.0,
            sharesOutstanding = 7_430_000_000.0, cik = "0000789019",
        ),
        "COST" to CompanyProfileDTO(
            symbol = "COST", name = "Costco Wholesale Corporation", exchange = "NASDAQ",
            sector = "Consumer Staples", industry = "Consumer Staples Merchandise Retail",
            currency = "USD", marketCap = 400_000_000_000.0,
            sharesOutstanding = 443_000_000.0, cik = "0000909832",
        ),
        "JPM" to CompanyProfileDTO(
            symbol = "JPM", name = "JPMorgan Chase & Co.", exchange = "NYSE",
            sector = "Financials", industry = "Diversified Banks",
            currency = "USD", marketCap = 700_000_000_000.0,
            sharesOutstanding = 2_800_000_000.0, cik = "0000019617",
        ),
    )

    /** A random walk with a mild drift, seeded by symbol. */
    fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        val generator = SeededGenerator("$symbol-${resolution.raw}")
        val step = when (resolution) {
            BarResolution.OneMinute -> 60.seconds
            BarResolution.FiveMinute -> 300.seconds
            BarResolution.FifteenMinute -> 900.seconds
            BarResolution.Hourly -> 3600.seconds
            BarResolution.Daily -> 86_400.seconds
            BarResolution.Weekly -> 604_800.seconds
            BarResolution.Monthly -> 2_592_000.seconds
        }

        var price = generator.nextDouble(80.0, 420.0)
        val result = mutableListOf<PriceBarDTO>()
        var cursor = from

        while (cursor <= to) {
            val drift = generator.nextDouble(-0.025, 0.027)
            val open = price
            price = maxOf(1.0, price * (1 + drift))
            val high = maxOf(open, price) * generator.nextDouble(1.000, 1.012)
            val low = minOf(open, price) * generator.nextDouble(0.988, 1.000)
            val volume = generator.nextDouble(8_000_000.0, 60_000_000.0)
            result.add(
                PriceBarDTO(
                    date = cursor, open = open, high = high, low = low,
                    close = price, volume = volume, adjustedClose = price,
                ),
            )
            cursor += step
        }
        return result
    }
}

class MockMarketDataProvider(
    /** Set to simulate failures and verify the UI degrades rather than crashes. */
    val failure: APIError? = null,
) : MarketDataProvider {
    /**
     * Not [DataProviderID.Finnhub]. A mock that answers to a vendor's identity
     * is a mock whose output cannot be told from that vendor's afterwards —
     * which is exactly how synthetic bars ended up on disk looking like
     * Tiingo's.
     */
    override val id: DataProviderID = DataProviderID.Sample

    override suspend fun isConfigured(): Boolean = true

    override suspend fun quote(symbol: String): QuoteDTO {
        failure?.let { throw it }
        val now = Clock.System.now()
        val recent = SampleData.bars(symbol, BarResolution.Daily, now - 5.days, now)
        val today = recent.lastOrNull()
            ?: throw APIError.NoData(DataProviderID.Sample, endpoint = "quote")
        return QuoteDTO(
            symbol = symbol.uppercase(),
            last = today.close,
            open = today.open,
            high = today.high,
            low = today.low,
            previousClose = recent.dropLast(1).lastOrNull()?.close,
            volume = today.volume,
            quoteTime = today.date,
        )
    }

    override suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        failure?.let { throw it }
        return SampleData.bars(symbol, resolution, from, to)
    }

    override suspend fun profile(symbol: String): CompanyProfileDTO {
        failure?.let { throw it }
        return SampleData.profiles[symbol.uppercase()]
            ?: throw APIError.NotFound(DataProviderID.Sample, endpoint = "profile")
    }

    override suspend fun search(query: String): List<CompanyProfileDTO> {
        failure?.let { throw it }
        val needle = query.uppercase()
        return SampleData.profiles.values
            .filter { it.symbol.contains(needle) || it.name.uppercase().contains(needle) }
            .sortedBy { it.symbol }
    }
}

class MockSECDataProvider(val failure: APIError? = null) : SECDataProvider {
    override val id: DataProviderID = DataProviderID.Sample

    override suspend fun isConfigured(): Boolean = true

    override suspend fun resolveCIK(symbol: String): String {
        failure?.let { throw it }
        return SampleData.profiles[symbol.uppercase()]?.cik
            ?: throw APIError.NotFound(DataProviderID.Sample, endpoint = "company_tickers")
    }

    override suspend fun filings(
        cik: String,
        formTypes: List<String>,
        limit: Int,
    ): List<FilingDTO> {
        failure?.let { throw it }
        val forms = formTypes.ifEmpty { listOf("10-Q", "8-K", "10-K") }
        val now = Clock.System.now()
        val link = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=$cik"
        return (0 until minOf(limit, 6)).map { index ->
            FilingDTO(
                // The impossible-CIK prefix. `evictSyntheticRows` keys on it,
                // so a sample filing that reached the store is removable.
                accessionNumber = "0000000000-00-${index.toString().padStart(6, '0')}",
                formType = forms[index % forms.size],
                filedAt = now - (index * 21).days,
                periodOfReport = now - (index * 21 + 30).days,
                primaryDocumentURL = link,
                filingIndexURL = link,
            )
        }
    }

    override suspend fun insiderTransactions(
        cik: String,
        since: Instant?,
    ): List<InsiderTransactionDTO> {
        failure?.let { throw it }
        val generator = SeededGenerator("insider-$cik")
        val codes = listOf("P", "S", "A", "M", "F")
        val now = Clock.System.now()
        return (0 until 8).map { index ->
            val code = codes[index % codes.size]
            InsiderTransactionDTO(
                accessionNumber = "0000000000-00-9${index}0000",
                insiderName = "Sample Insider ${index + 1}",
                insiderTitle = if (index % 3 == 0) "Chief Financial Officer" else "Director",
                isDirector = index % 3 != 0,
                isOfficer = index % 3 == 0,
                isTenPercentOwner = false,
                transactionDate = now - (index * 9).days,
                filedAt = now - (index * 9).days + 1.days,
                transactionCode = code,
                isUnderTradingPlan = code == "S" && index % 2 == 0,
                shares = round(generator.nextDouble(500.0, 25_000.0)),
                pricePerShare = generator.nextDouble(80.0, 420.0),
                sharesOwnedAfter = round(generator.nextDouble(20_000.0, 400_000.0)),
            )
        }
    }
}

class MockMacroDataProvider(val failure: APIError? = null) : MacroDataProvider {
    override val id: DataProviderID = DataProviderID.Sample

    override suspend fun isConfigured(): Boolean = true

    override suspend fun observations(
        seriesID: String,
        from: Instant?,
        to: Instant?,
    ): List<MacroObservationDTO> {
        failure?.let { throw it }
        val generator = SeededGenerator("fred-$seriesID")
        val end = to ?: Clock.System.now()
        val start = from ?: (end - 730.days)
        var value = generator.nextDouble(1.5, 6.0)
        var cursor = start
        val result = mutableListOf<MacroObservationDTO>()
        while (cursor <= end) {
            value = maxOf(0.0, value + generator.nextDouble(-0.08, 0.08))
            result.add(MacroObservationDTO(seriesID = seriesID, date = cursor, value = value))
            cursor += 30.days
        }
        return result
    }
}

class MockNewsProvider(val failure: APIError? = null) : NewsProvider {
    override val id: DataProviderID = DataProviderID.Sample

    override suspend fun isConfigured(): Boolean = true

    override suspend fun companyNews(
        symbol: String,
        from: Instant,
        to: Instant,
    ): List<NewsItemDTO> {
        failure?.let { throw it }
        val now = Clock.System.now()
        return (0 until 5).map { index ->
            NewsItemDTO(
                id = "$symbol-sample-$index",
                headline = "Sample headline ${index + 1} for ${symbol.uppercase()}",
                summary = "Placeholder summary. This is synthetic sample data, not real news.",
                source = "Sample Source",
                url = null,
                publishedAt = now - (index * 6).hours,
                relatedSymbols = listOf(symbol.uppercase()),
            )
        }
    }
}
