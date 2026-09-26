package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.support.Format
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Verifies a stored credential by making the cheapest real request each
 * provider offers.
 *
 * A key can only be confirmed by using it. Storing one and discovering hours
 * later that a chart is empty — because a provider answered 403 for a mistyped
 * token — is exactly the failure this removes.
 */
object ConnectionTest {

    sealed class Result {
        data class Success(val detail: String) : Result()
        data class Failure(val error: APIError) : Result()

        val isSuccess: Boolean get() = this is Success

        val message: String
            get() = when (this) {
                is Success -> detail
                is Failure -> error.recoverySuggestion
                    ?.let { "${error.shortDescription} — $it" }
                    ?: error.shortDescription
            }
    }

    suspend fun run(provider: DataProviderID, registry: ProviderRegistry): Result = try {
        val now = Clock.System.now()
        when (provider) {
            DataProviderID.Finnhub -> {
                val quote = registry.marketData.quote("AAPL")
                Result.Success("Live quote received (AAPL ${Format.currency(quote.last)}).")
            }

            DataProviderID.Tiingo -> {
                val bars = registry.marketData.bars(
                    "AAPL", BarResolution.Daily, now - 10.days, now,
                )
                if (bars.isEmpty()) {
                    Result.Failure(
                        APIError.NoData(DataProviderID.Tiingo, endpoint = "tiingo prices"),
                    )
                } else {
                    Result.Success("${bars.size} daily bars received.")
                }
            }

            DataProviderID.Alpaca -> {
                // Asked for over the last four days so the window spans a
                // weekend without the test reading as a failure on a Monday.
                val intraday = registry.marketData.bars(
                    "AAPL", BarResolution.FifteenMinute, now - 4.days, now,
                )
                if (intraday.isEmpty()) {
                    Result.Failure(
                        APIError.NoData(DataProviderID.Alpaca, endpoint = "alpaca bars"),
                    )
                } else {
                    Result.Success("${intraday.size} 15-minute IEX bars received.")
                }
            }

            DataProviderID.FRED -> {
                val macro = registry.macro
                    ?: return Result.Failure(APIError.MissingCredentials(DataProviderID.FRED))
                val observations = macro.observations("SP500", now - 10.days, now)
                val latest = observations.lastOrNull()
                    ?: return Result.Failure(
                        APIError.NoData(DataProviderID.FRED, endpoint = "SP500"),
                    )
                Result.Success("S&P 500 at ${Format.ratio(latest.value, precision = 2)}.")
            }

            DataProviderID.SEC -> {
                val sec = registry.sec
                    ?: return Result.Failure(APIError.MissingCredentials(DataProviderID.SEC))
                // Resolving a well-known ticker exercises the whole path: the
                // User-Agent EDGAR demands, the ticker map, and decoding.
                val cik = sec.resolveCIK("AAPL")
                val filings = sec.filings(cik, formTypes = emptyList(), limit = 5)
                Result.Success("CIK $cik resolved, ${filings.size} recent filings.")
            }

            DataProviderID.Computed, DataProviderID.Sample ->
                // Neither is a connection. `Sample` reaches nothing, and a test
                // that "succeeded" against invented data would report the
                // opposite of what it measured.
                Result.Failure(APIError.NoData(provider, endpoint = "connection test"))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: APIError) {
        Result.Failure(error)
    } catch (error: Exception) {
        Result.Failure(
            APIError.Transport(provider, underlying = error.message ?: "unknown error"),
        )
    }
}
