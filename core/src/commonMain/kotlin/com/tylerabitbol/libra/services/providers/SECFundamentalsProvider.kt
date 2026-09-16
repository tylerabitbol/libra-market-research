package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.Endpoint
import com.tylerabitbol.libra.networking.HTTPClient
import com.tylerabitbol.libra.networking.serializers.VendorDate
import com.tylerabitbol.libra.services.secrets.SecretKey
import com.tylerabitbol.libra.services.secrets.SecretsStore
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Reported financials from SEC EDGAR's XBRL `companyfacts` API.
 *
 * This is the primary source: figures as the issuer filed them, free, with a
 * decade or more of history. It replaces the vendor fundamentals that
 * Finnhub's free tier withholds, and it is authoritative where the two differ.
 *
 * Three properties of XBRL shape everything below:
 *
 * 1. **Companies use different tags for the same idea.** "Revenue" may be
 *    `Revenues`, `RevenueFromContractWithCustomerExcludingAssessedTax`, or
 *    `SalesRevenueNet`. Each concept therefore has an ordered candidate list.
 * 2. **Issuers restate.** The same period appears more than once with
 *    different `accn` and `filed` values. Both are kept; the most recently
 *    filed wins for display, and the originals remain for "what did this look
 *    like at the time".
 * 3. **A missing concept is genuinely missing.** No substitution, no zero.
 */
class SECFundamentalsProvider(
    private val client: HTTPClient,
    private val secrets: SecretsStore,
) : FundamentalsProvider {

    override val id: DataProviderID = DataProviderID.SEC

    private val sec = SECProvider(client, secrets)

    override suspend fun isConfigured(): Boolean = secrets.hasValue(SecretKey.SecContactEmail)

    override suspend fun facts(
        symbol: String,
        cik: String?,
        concepts: List<FinancialConcept>,
        since: Instant?,
    ): List<FinancialFactDTO> {
        val resolved = cik ?: sec.resolveCIK(symbol)
        val response = companyFacts(resolved)
        val wanted = concepts.ifEmpty { FinancialConcept.entries }

        return wanted.flatMap { concept -> extract(concept, response, since) }
    }

    /** The raw payload, exposed so a debug build can capture it as a fixture. */
    suspend fun companyFacts(cik: String): CompanyFactsResponse {
        val padded = SECProvider.normalizedCIK(cik)
        val email = secrets.require(SecretKey.SecContactEmail, DataProviderID.SEC)
        val organization = secrets.value(SecretKey.SecOrganizationName)?.trim()
        val name = if (!organization.isNullOrEmpty()) {
            organization
        } else {
            SECProvider.DEFAULT_ORGANIZATION
        }

        val endpoint = Endpoint(
            provider = DataProviderID.SEC,
            baseURL = SECProvider.DATA_HOST,
            path = "/api/xbrl/companyfacts/CIK$padded.json",
            headers = mapOf(
                "User-Agent" to "$name $email",
                "Accept-Encoding" to "gzip, deflate",
                "Host" to "data.sec.gov",
            ),
            label = "companyfacts",
        )
        return client.get(endpoint)
    }

    companion object {

        /**
         * Merges the candidate tags in priority order, one figure per period.
         *
         * The obvious implementation — take the first tag that returns
         * anything — silently truncates the history of any issuer that changed
         * tags. NVIDIA is a live example: its recent revenue sits under one tag
         * and its older revenue under another, and first-tag-wins returned
         * FY2021 and FY2022 only, hiding four years including the ones that
         * made the company what it is. Nothing about that failure looks like a
         * failure on screen; the chart simply starts late.
         *
         * Merging by period rather than concatenating is what keeps the
         * opposite error away: an issuer reporting two candidate tags for the
         * *same* period contributes one figure, from the higher-priority tag,
         * so the totals are never doubled. `rawTag` records which tag each
         * figure came from, since a series spanning a tag change is worth being
         * able to inspect — the two tags can carry slightly different
         * definitions.
         */
        fun extract(
            concept: FinancialConcept,
            response: CompanyFactsResponse,
            since: Instant?,
        ): List<FinancialFactDTO> {
            val gaap = response.facts["us-gaap"] ?: return emptyList()

            val byPeriod = mutableMapOf<String, FinancialFactDTO>()
            val order = mutableListOf<String>()

            for (tag in concept.candidateTags) {
                val entry = gaap[tag] ?: continue
                val unit = concept.preferredUnit(entry.units.keys.toList()) ?: continue
                val rows = entry.units[unit] ?: continue

                val facts = rows
                    .mapNotNull { it.asDTO(concept, tag, unit, since) }
                    // Cumulative half-year and nine-month rows share a tag with
                    // the discrete quarters; keeping both would double-count.
                    .filter { it.periodKind.isDiscrete }

                for (fact in FactPeriods.deduplicated(facts)) {
                    val key = FactPeriods.groupKey(fact)
                    // Earlier candidates outrank later ones, so a period
                    // already filled by a higher-priority tag is left alone.
                    if (byPeriod.containsKey(key)) continue
                    byPeriod[key] = fact
                    order.add(key)
                }
            }

            return order.mapNotNull { byPeriod[it] }.sortedBy { it.periodEnd }
        }
    }
}

// MARK: - Concept mapping

/**
 * Ordered us-gaap tags to try. Order matters: the most specific and most
 * commonly used modern tag comes first, older or broader ones after.
 */
val FinancialConcept.candidateTags: List<String>
    get() = when (this) {
        FinancialConcept.Revenue -> listOf(
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "Revenues",
            "SalesRevenueNet",
            "RevenueFromContractWithCustomerIncludingAssessedTax",
        )
        FinancialConcept.CostOfRevenue -> listOf(
            "CostOfGoodsAndServicesSold", "CostOfRevenue", "CostOfGoodsSold",
        )
        FinancialConcept.GrossProfit -> listOf("GrossProfit")
        FinancialConcept.OperatingIncome -> listOf("OperatingIncomeLoss")
        FinancialConcept.NetIncome -> listOf("NetIncomeLoss", "ProfitLoss")
        FinancialConcept.EarningsPerShareDiluted -> listOf(
            "EarningsPerShareDiluted",
            "IncomeLossFromContinuingOperationsPerDilutedShare",
        )
        FinancialConcept.SharesOutstandingDiluted -> listOf(
            "WeightedAverageNumberOfDilutedSharesOutstanding",
        )
        FinancialConcept.OperatingCashFlow -> listOf(
            "NetCashProvidedByUsedInOperatingActivities",
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations",
        )
        FinancialConcept.CapitalExpenditures -> listOf(
            "PaymentsToAcquirePropertyPlantAndEquipment",
            "PaymentsToAcquireProductiveAssets",
        )
        FinancialConcept.CashAndEquivalents -> listOf(
            "CashAndCashEquivalentsAtCarryingValue",
            "CashCashEquivalentsAndShortTermInvestments",
        )
        FinancialConcept.ShortTermInvestments -> listOf(
            "ShortTermInvestments", "MarketableSecuritiesCurrent",
        )
        FinancialConcept.TotalDebt -> listOf(
            "LongTermDebtNoncurrent", "LongTermDebt", "DebtLongtermAndShorttermCombinedAmount",
        )
        FinancialConcept.TotalAssets -> listOf("Assets")
        FinancialConcept.TotalLiabilities -> listOf("Liabilities")
        FinancialConcept.StockholdersEquity -> listOf(
            "StockholdersEquity",
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
        )
    }

/**
 * XBRL reports each concept under one or more unit keys. Picking the wrong one
 * silently changes the meaning — EPS lives under "USD/shares", share counts
 * under "shares", and everything monetary under "USD".
 */
fun FinancialConcept.preferredUnit(available: List<String>): String? {
    val preference = when (this) {
        FinancialConcept.EarningsPerShareDiluted -> listOf("USD/shares")
        FinancialConcept.SharesOutstandingDiluted -> listOf("shares")
        else -> listOf("USD")
    }
    return preference.firstOrNull { it in available } ?: available.firstOrNull()
}

// MARK: - Wire format

@Serializable
data class CompanyFactsResponse(
    val cik: Int? = null,
    val entityName: String? = null,
    /** Taxonomy ("us-gaap", "dei") → tag → entry. */
    val facts: Map<String, Map<String, FactEntry>> = emptyMap(),
) {
    @Serializable
    data class FactEntry(
        val label: String? = null,
        /** Unit key ("USD", "USD/shares", "shares") → observations. */
        val units: Map<String, List<FactRow>> = emptyMap(),
    )

    @Serializable
    data class FactRow(
        /** Absent for instantaneous facts such as balance-sheet items. */
        val start: String? = null,
        val end: String,
        val `val`: Double,
        val accn: String? = null,
        val fy: Int? = null,
        /** "FY", "Q1"…"Q4". */
        val fp: String? = null,
        val form: String? = null,
        val filed: String? = null,
        val frame: String? = null,
    ) {
        fun asDTO(
            concept: FinancialConcept,
            tag: String,
            unit: String,
            since: Instant?,
        ): FinancialFactDTO? {
            val periodEnd = VendorDate.day(end) ?: return null
            if (since != null && periodEnd < since) return null

            val periodStart = VendorDate.day(start)
            // Duration is the reliable signal; `fp` alone mislabels both
            // full-year rows inside a 10-K and cumulative year-to-date rows.
            val days = periodStart?.let {
                (periodEnd - it).inWholeSeconds.toDouble() / 86_400.0
            }
            val periodKind = FiscalPeriodKind.classify(days)
            val isAnnual = periodKind == FiscalPeriodKind.Annual

            val quarter = when (fp) {
                "Q1" -> 1
                "Q2" -> 2
                "Q3" -> 3
                "Q4" -> 4
                else -> null
            }

            return FinancialFactDTO(
                concept = concept,
                rawTag = "us-gaap:$tag",
                periodStart = periodStart,
                periodEnd = periodEnd,
                fiscalYear = fy ?: periodEnd.toLocalDateTime(TimeZone.UTC).year,
                fiscalQuarter = quarter,
                isAnnual = isAnnual,
                periodKind = periodKind,
                value = `val`,
                unit = unit,
                filedAt = VendorDate.day(filed),
                accessionNumber = accn,
            )
        }
    }
}
