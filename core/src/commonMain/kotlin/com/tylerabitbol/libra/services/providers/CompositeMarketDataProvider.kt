package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import kotlin.time.Instant

/**
 * Presents Finnhub, Tiingo and Alpaca as one [MarketDataProvider].
 *
 * The split exists because no single free tier covers every need: Finnhub has
 * live quotes but no history, Tiingo has deep adjusted history but nothing
 * intraday, Alpaca has intraday but only IEX's share of the tape. That is a
 * data-sourcing detail, not something view models should know about —
 * `DashboardViewModel` and every view continue to depend only on
 * [MarketDataProvider], exactly as they did against the mocks.
 */
class CompositeMarketDataProvider(
    /** Live prices, company profiles, symbol search. */
    val quotes: FinnhubProvider,
    /** Daily and coarser price history: consolidated and split-adjusted. */
    val history: TiingoProvider,
    /**
     * Intraday bars, when an Alpaca key pair has been entered. Null when it
     * has not: the other two are enough for every range but 1D and 5D.
     */
    val intraday: AlpacaProvider? = null,
) : MarketDataProvider {

    override val id: DataProviderID = DataProviderID.Finnhub

    override suspend fun isConfigured(): Boolean =
        quotes.isConfigured() && history.isConfigured()

    override suspend fun quote(symbol: String): QuoteDTO = quotes.quote(symbol)

    /**
     * Routes by resolution: intraday to Alpaca, daily and coarser to Tiingo.
     *
     * Deliberately never falls back across that line. Serving daily bars when
     * five-minute ones were asked for would mislabel the resolution, and every
     * calculation built on top of it would be wrong while looking fine.
     */
    override suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        if (!resolution.isDailyOrCoarser) {
            val intraday = intraday
                ?: throw APIError.MissingCredentials(DataProviderID.Alpaca)
            return intraday.bars(symbol, resolution, from, to)
        }
        return history.bars(symbol, resolution, from, to)
    }

    /**
     * Prefers Finnhub, which carries sector, market cap and shares
     * outstanding. Falls back to Tiingo's thinner metadata only when Finnhub
     * has nothing, so a symbol Finnhub doesn't cover still resolves to a name.
     */
    override suspend fun profile(symbol: String): CompanyProfileDTO = try {
        quotes.profile(symbol)
    } catch (error: APIError) {
        when (error) {
            is APIError.NotFound, is APIError.NoData -> history.profile(symbol)
            else -> throw error
        }
    }

    override suspend fun search(query: String): List<CompanyProfileDTO> = quotes.search(query)
}

/**
 * Adapts [FinnhubProvider] to the analyst and news protocols.
 *
 * Kept separate from the provider itself so that the protocol conformances
 * stay declarative and the 403-only endpoints are visible in one place.
 */
class FinnhubAnalystProvider(val provider: FinnhubProvider) : AnalystDataProvider {
    override val id: DataProviderID = DataProviderID.Finnhub

    override suspend fun isConfigured(): Boolean = provider.isConfigured()

    /**
     * Not available on the free tier. Throwing [APIError.NotEntitled] is what
     * lets the UI say "not in your plan" instead of rendering an empty chart.
     */
    override suspend fun estimates(
        symbol: String,
        metric: EstimateMetric,
    ): List<AnalystEstimateDTO> = provider.estimates(symbol, metric)

    override suspend fun ratings(symbol: String): List<RatingSnapshotDTO> =
        provider.ratings(symbol)
}

class FinnhubMetricsProvider(val provider: FinnhubProvider) : CompanyMetricsProvider {
    override val id: DataProviderID = DataProviderID.Finnhub

    override suspend fun isConfigured(): Boolean = provider.isConfigured()

    override suspend fun metrics(symbol: String): CompanyMetricsDTO = provider.metrics(symbol)
}

class FinnhubNewsProvider(val provider: FinnhubProvider) : NewsProvider {
    override val id: DataProviderID = DataProviderID.Finnhub

    override suspend fun isConfigured(): Boolean = provider.isConfigured()

    override suspend fun companyNews(
        symbol: String,
        from: Instant,
        to: Instant,
    ): List<NewsItemDTO> = provider.companyNews(symbol, from, to)
}
