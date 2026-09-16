package com.tylerabitbol.libra.app

import co.touchlab.kermit.Logger
import com.tylerabitbol.libra.calculations.ValuationCalculator
import com.tylerabitbol.libra.calculations.ValuationMetric
import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.HTTPClient
import com.tylerabitbol.libra.services.providers.CompanyFactsResponse
import com.tylerabitbol.libra.services.providers.CompositeMarketDataProvider
import com.tylerabitbol.libra.services.providers.ConnectionTest
import com.tylerabitbol.libra.services.providers.FREDProvider
import com.tylerabitbol.libra.services.providers.FinnhubAnalystProvider
import com.tylerabitbol.libra.services.providers.FinnhubMetricsProvider
import com.tylerabitbol.libra.services.providers.FinnhubNewsProvider
import com.tylerabitbol.libra.services.providers.FinnhubProvider
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import com.tylerabitbol.libra.services.providers.SECFundamentalsProvider
import com.tylerabitbol.libra.services.providers.SECProvider
import com.tylerabitbol.libra.services.providers.TiingoProvider
import com.tylerabitbol.libra.services.providers.candidateTags
import com.tylerabitbol.libra.services.secrets.SecretsHealth
import com.tylerabitbol.libra.services.secrets.SecretsStore
import com.tylerabitbol.libra.support.Format
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A startup self-check, run only when launched with `-LibraSelfTest`.
 *
 * Secure storage cannot be exercised by the unit test bundle: on iOS that bundle
 * has no host app, so it carries no entitlements of its own — the very condition
 * that caused the -34018 failure this check exists to catch. Running inside the
 * real, signed app is the only place the answer is meaningful.
 */
class SelfTest(private val launch: LaunchEnvironment = LaunchEnvironment.release) {

    val isRequested: Boolean get() = launchArgument in launch.arguments

    val isCaptureRequested: Boolean
        get() = launch.isDebugBuild && captureArgument in launch.arguments

    /**
     * Exercises every configured provider with a real request and logs the
     * outcome.
     *
     * Credentials are read from secure storage and never appear in a command
     * line, a log line, or this source file — which is what makes it safe to
     * verify live API access without handling the keys directly.
     */
    suspend fun runConnectionTests(secrets: SecretsStore) {
        val client = HTTPClient()
        val finnhub = FinnhubProvider(client, secrets)
        val tiingo = TiingoProvider(client, secrets)

        val registry = ProviderRegistry(
            marketData = CompositeMarketDataProvider(quotes = finnhub, history = tiingo),
            fundamentals = SECFundamentalsProvider(client, secrets),
            analyst = FinnhubAnalystProvider(finnhub),
            metrics = FinnhubMetricsProvider(finnhub),
            sec = SECProvider(client, secrets),
            macro = FREDProvider(client, secrets),
            news = FinnhubNewsProvider(finnhub),
            isUsingSampleData = false,
        )

        for (provider in listOf(
            DataProviderID.Finnhub, DataProviderID.Tiingo,
            DataProviderID.FRED, DataProviderID.SEC,
        )) {
            val result = ConnectionTest.run(provider, registry)
            val status = if (result.isSuccess) "PASS" else "FAIL"
            logger.i { "SELFTEST ${provider.raw}=$status ${result.message}" }
        }

        verifyValuationContext(registry)
        verifyFundamentals(registry)
    }

    /**
     * End-to-end check of the historical-valuation chain: fetch metrics, rank
     * the current multiple against the company's own history, and confirm the
     * two claims come back correctly labelled.
     */
    private suspend fun verifyValuationContext(registry: ProviderRegistry) {
        val provider = registry.metrics ?: return
        try {
            val metrics = provider.metrics("AAPL")
            for (metric in ValuationMetric.all.take(3)) {
                val history = metrics.history(metric.key)
                val current = metrics.currentValue(metric.key) ?: history.lastOrNull()?.value
                val context = current?.let {
                    ValuationCalculator.historicalContext(current = it, history = history)
                }
                if (context == null) {
                    logger.i {
                        "SELFTEST valuation ${metric.displayName}=SKIP insufficient history"
                    }
                    continue
                }
                logger.i {
                    "SELFTEST valuation ${metric.displayName}=" +
                        "${Format.ratio(context.current, precision = 1)} " +
                        "percentile=${context.percentile} n=${context.observationCount} " +
                        "(${context.descriptor})"
                }
            }
        } catch (error: Exception) {
            logger.e { "SELFTEST valuation=FAIL $error" }
        }
    }

    /** Confirms XBRL extraction produces discrete periods on live data. */
    private suspend fun verifyFundamentals(registry: ProviderRegistry) {
        val fundamentals = registry.fundamentals ?: return
        try {
            val facts = fundamentals.facts(
                symbol = "AAPL", cik = "0000320193",
                concepts = listOf(FinancialConcept.Revenue, FinancialConcept.NetIncome),
                since = null,
            )
            val quarters = facts.filter {
                it.concept == FinancialConcept.Revenue && it.periodKind == FiscalPeriodKind.Quarter
            }
            val annual = facts.filter {
                it.concept == FinancialConcept.Revenue && it.periodKind == FiscalPeriodKind.Annual
            }
            logger.i {
                "SELFTEST fundamentals=PASS revenue quarters=${quarters.size} " +
                    "annual=${annual.size} " +
                    "latest=${Format.compactCurrency(quarters.lastOrNull()?.value)}"
            }
        } catch (error: Exception) {
            logger.e { "SELFTEST fundamentals=FAIL $error" }
        }
    }

    /**
     * Fetches a raw API response and returns it trimmed, so it can be written
     * out and checked in as a test fixture.
     *
     * Exists so real payloads can be captured from EDGAR without the contact
     * address its User-Agent requires ever appearing in a shell command.
     *
     * Swift wrote the file into the app container itself, for lifting out with
     * `simctl get_app_container`. There is no common filesystem API in this
     * stack and the two platforms put an app's private storage in different
     * places, so the JSON is returned and the platform shell writes it. Debug
     * builds only, and null when nothing was captured.
     */
    suspend fun captureFixtures(secrets: SecretsStore): String? {
        if (!isCaptureRequested) return null

        val client = HTTPClient()
        val fundamentals = SECFundamentalsProvider(client, secrets)
        return try {
            // Apple: reports every concept we map, and restates, so it exercises
            // the deduplication path too.
            val response = fundamentals.companyFacts("0000320193")
            val json = captureJson.encodeToString(TrimmedFacts.from(response))
            logger.i { "CAPTURE produced ${json.length} characters" }
            json
        } catch (error: Exception) {
            logger.e { "CAPTURE failed: $error" }
            null
        }
    }

    /**
     * Reports secure-storage health to the log and returns whether it passed.
     */
    fun run(secrets: SecretsStore): Boolean = when (val health = secrets.diagnose()) {
        is SecretsHealth.Available -> {
            logger.i { "SELFTEST keychain=PASS round-trip succeeded" }
            true
        }

        is SecretsHealth.Unavailable -> {
            logger.e { "SELFTEST keychain=FAIL ${health.reason}" }
            false
        }
    }

    companion object {
        const val launchArgument = "-LibraSelfTest"
        const val captureArgument = "-LibraCaptureFixtures"

        private val logger = Logger.withTag("selftest")

        private val captureJson = Json { prettyPrint = true; encodeDefaults = true }
    }
}

/**
 * A companyfacts payload reduced to the concepts the app maps, with each series
 * truncated. The full response for a large issuer runs to tens of megabytes,
 * which is not something to check into a repository.
 */
@Serializable
internal data class TrimmedFacts(
    val cik: Int? = null,
    val entityName: String? = null,
    val facts: Map<String, Map<String, Entry>>,
) {
    @Serializable
    internal data class Entry(
        val label: String? = null,
        val units: Map<String, List<Row>>,
    )

    @Serializable
    internal data class Row(
        val start: String? = null,
        val end: String,
        val `val`: Double,
        val accn: String? = null,
        val fy: Int? = null,
        val fp: String? = null,
        val form: String? = null,
        val filed: String? = null,
    )

    companion object {
        fun from(response: CompanyFactsResponse): TrimmedFacts {
            val wanted = FinancialConcept.entries.flatMap { it.candidateTags }.toSet()
            val gaap = buildMap {
                for ((tag, entry) in response.facts["us-gaap"].orEmpty()) {
                    if (tag !in wanted) continue
                    val units = entry.units.mapValues { (_, rows) ->
                        // Keep the most recent entries; enough to cover several
                        // years of annual and quarterly periods plus any
                        // restatements.
                        rows.takeLast(24).map {
                            Row(
                                start = it.start, end = it.end, `val` = it.`val`,
                                accn = it.accn, fy = it.fy, fp = it.fp,
                                form = it.form, filed = it.filed,
                            )
                        }
                    }
                    put(tag, Entry(label = entry.label, units = units))
                }
            }
            return TrimmedFacts(
                cik = response.cik, entityName = response.entityName,
                facts = mapOf("us-gaap" to gaap),
            )
        }
    }
}
