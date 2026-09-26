package com.tylerabitbol.libra.viewmodels

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.services.mock.SampleData
import com.tylerabitbol.libra.services.providers.CompanyProfileDTO
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FundamentalsProvider
import com.tylerabitbol.libra.services.providers.InsiderTransactionDTO
import com.tylerabitbol.libra.services.providers.MacroDataProvider
import com.tylerabitbol.libra.services.providers.MarketDataProvider
import com.tylerabitbol.libra.services.providers.PriceBarDTO
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import com.tylerabitbol.libra.services.providers.QuoteDTO
import com.tylerabitbol.libra.services.providers.SECDataProvider
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What a view model actually asked the network for.
 *
 * Swift used an `actor`; the Kotlin equivalent that works from a non-suspending
 * accessor is a compare-and-set loop, which is what the secrets store already
 * does for the same reason.
 */
@OptIn(ExperimentalAtomicApi::class)
class CallLog {
    private val quoteSymbols = AtomicReference<List<String>>(emptyList())
    private val barSymbols = AtomicReference<List<String>>(emptyList())

    /** Every symbol quoted, in order, including repeats. */
    val symbols: List<String> get() = quoteSymbols.load()
    val quotes: Int get() = quoteSymbols.load().size
    val bars: Int get() = barSymbols.load().size

    fun recordQuote(symbol: String) = append(quoteSymbols, symbol)
    fun recordBars(symbol: String) = append(barSymbols, symbol)

    private fun append(target: AtomicReference<List<String>>, value: String) {
        while (true) {
            val current = target.load()
            if (target.compareAndSet(current, current + value)) return
        }
    }
}

/**
 * Counts what it is asked for, and can be told to fail — the offline case.
 *
 * [barsFor] lets a suite decide what a history request returns without another
 * stub: the budget tests want an empty series, the hydration tests want a
 * plausible one.
 */
class StubMarketProvider(
    val log: CallLog,
    val isOffline: Boolean = false,
    val last: Double = 200.0,
    val previousClose: Double = 190.0,
    val barsFor: (String, BarResolution, Instant, Instant) -> List<PriceBarDTO> =
        { symbol, resolution, from, to -> SampleData.bars(symbol, resolution, from, to) },
) : MarketDataProvider {
    override val id: DataProviderID = DataProviderID.Finnhub

    override suspend fun isConfigured(): Boolean = true

    override suspend fun quote(symbol: String): QuoteDTO {
        log.recordQuote(symbol)
        if (isOffline) throw APIError.Transport(DataProviderID.Finnhub, "offline")
        return QuoteDTO(
            symbol = symbol, last = last, open = null, high = null, low = null,
            previousClose = previousClose, volume = null, quoteTime = Clock.System.now(),
        )
    }

    override suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        log.recordBars(symbol)
        if (isOffline) throw APIError.Transport(DataProviderID.Tiingo, "offline")
        return barsFor(symbol, resolution, from, to)
    }

    override suspend fun profile(symbol: String): CompanyProfileDTO =
        throw APIError.NotFound(DataProviderID.Finnhub, "profile")

    override suspend fun search(query: String): List<CompanyProfileDTO> = emptyList()
}

class StubFundamentalsProvider(val stored: List<FinancialFactDTO>) : FundamentalsProvider {
    override val id: DataProviderID = DataProviderID.SEC
    override suspend fun isConfigured(): Boolean = true
    override suspend fun facts(
        symbol: String,
        cik: String?,
        concepts: List<FinancialConcept>,
        since: Instant?,
    ): List<FinancialFactDTO> = stored
}

class StubSECProvider : SECDataProvider {
    override val id: DataProviderID = DataProviderID.SEC
    override suspend fun isConfigured(): Boolean = true
    override suspend fun resolveCIK(symbol: String): String = "0000000320"
    override suspend fun filings(
        cik: String,
        formTypes: List<String>,
        limit: Int,
    ): List<FilingDTO> = emptyList()

    override suspend fun insiderTransactions(
        cik: String,
        since: Instant?,
    ): List<InsiderTransactionDTO> = emptyList()
}

fun stubRegistry(
    log: CallLog,
    offline: Boolean = false,
    macro: MacroDataProvider? = null,
    fundamentals: FundamentalsProvider? = null,
    sec: SECDataProvider? = null,
) = ProviderRegistry(
    marketData = StubMarketProvider(log, offline),
    fundamentals = fundamentals, analyst = null, metrics = null, sec = sec,
    macro = macro, news = null, isUsingSampleData = false,
)
