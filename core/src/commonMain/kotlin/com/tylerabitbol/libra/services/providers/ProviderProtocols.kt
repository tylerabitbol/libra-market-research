package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.core.InsiderTransactionNature
import com.tylerabitbol.libra.models.provenance.DataProviderID
import kotlin.time.Instant

// MARK: - Transport-neutral value types
//
// Providers return these rather than stored rows. Keeping the boundary at
// plain data classes means provider work can run off the main dispatcher, and
// swapping Finnhub for another vendor touches only the mapping layer — the
// requirement in Section 18.

data class QuoteDTO(
    val symbol: String,
    val last: Double,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val previousClose: Double? = null,
    val volume: Double? = null,
    val quoteTime: Instant? = null
) {
    /**
     * Null when the provider didn't supply a previous close. Returning null
     * rather than 0 keeps an unknown change from rendering as "flat".
     */
    val change: Double? get() = previousClose?.let { last - it }

    val changePercent: Double?
        get() = previousClose?.takeIf { it != 0.0 }?.let { (last - it) / it * 100 }
}

data class PriceBarDTO(
    val date: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double? = null,
    val adjustedClose: Double? = null
) {
    /**
     * Prefers the adjusted series when available, since that is what any
     * return or moving-average calculation should be built on.
     */
    val analysisClose: Double get() = adjustedClose ?: close
}

data class CompanyProfileDTO(
    val symbol: String,
    val name: String,
    val exchange: String? = null,
    val sector: String? = null,
    val industry: String? = null,
    val currency: String? = null,
    val marketCap: Double? = null,
    val sharesOutstanding: Double? = null,
    val cik: String? = null
)

/**
 * How long a reported figure covers.
 *
 * XBRL files cumulative year-to-date figures under the *same tag* as discrete
 * quarterly ones: a Q3 10-Q carries both the three-month revenue and the
 * nine-month running total. Reading a nine-month total as a quarter makes Q3
 * look roughly three times Q2 and corrupts every growth rate computed from it,
 * so duration is classified explicitly rather than inferred from `fp`.
 */
enum class FiscalPeriodKind(val raw: String) {
    /** A single quarter, roughly 90 days. */
    Quarter("quarter"),

    /** Six months cumulative. */
    HalfYear("halfYear"),

    /** Nine months cumulative. */
    NineMonth("nineMonth"),

    /** A full year, roughly 365 days. */
    Annual("annual"),

    /** A balance-sheet item: a point in time, with no duration. */
    Instant("instant");

    /**
     * The kinds worth showing directly. Cumulative periods are kept in the
     * store but excluded from quarter-over-quarter work.
     */
    val isDiscrete: Boolean get() = this == Quarter || this == Annual || this == Instant

    companion object {
        fun fromRaw(raw: String?): FiscalPeriodKind? = entries.firstOrNull { it.raw == raw }

        /**
         * Classifies by day count, with windows wide enough for 52/53-week
         * fiscal calendars and companies whose quarters are not exactly 13 weeks.
         */
        fun classify(days: Double?): FiscalPeriodKind {
            if (days == null) return Instant
            return when {
                days < 140 -> Quarter
                days < 230 -> HalfYear
                days < 310 -> NineMonth
                else -> Annual
            }
        }
    }
}

data class FinancialFactDTO(
    val concept: FinancialConcept,
    val rawTag: String? = null,
    val periodStart: Instant? = null,
    val periodEnd: Instant,
    val fiscalYear: Int,
    val fiscalQuarter: Int? = null,
    val isAnnual: Boolean,
    val periodKind: FiscalPeriodKind,
    val value: Double,
    val unit: String,
    val filedAt: Instant? = null,
    val accessionNumber: String? = null
)

data class FilingDTO(
    val accessionNumber: String,
    val formType: String,
    val filedAt: Instant,
    val periodOfReport: Instant? = null,
    val primaryDocumentURL: String? = null,
    val filingIndexURL: String? = null
)

data class InsiderTransactionDTO(
    val accessionNumber: String,
    val insiderName: String,
    val insiderTitle: String? = null,
    val isDirector: Boolean = false,
    val isOfficer: Boolean = false,
    val isTenPercentOwner: Boolean = false,
    val transactionDate: Instant,
    val filedAt: Instant,
    val transactionCode: String,
    val isUnderTradingPlan: Boolean = false,
    val shares: Double? = null,
    val pricePerShare: Double? = null,
    val sharesOwnedAfter: Double? = null
) {
    val approximateValue: Double?
        get() {
            val shares = shares ?: return null
            val price = pricePerShare ?: return null
            return shares * price
        }

    val nature: InsiderTransactionNature
        get() = InsiderTransactionNature.classify(transactionCode, isUnderTradingPlan)
}

data class NewsItemDTO(
    val id: String,
    val headline: String,
    val summary: String? = null,
    val source: String? = null,
    val url: String? = null,
    val publishedAt: Instant,
    val relatedSymbols: List<String> = emptyList()
)

enum class EstimateMetric(val raw: String) {
    Eps("eps"),
    Revenue("revenue"),
    PriceTarget("priceTarget");

    val displayName: String
        get() = when (this) {
            Eps -> "EPS"
            Revenue -> "Revenue"
            PriceTarget -> "Price target"
        }
}

data class AnalystEstimateDTO(
    val period: Instant,
    val fiscalYear: Int,
    val fiscalQuarter: Int? = null,
    val metric: EstimateMetric,
    val consensus: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val analystCount: Int? = null,
    /**
     * When this consensus was observed. Revisions are detected by comparing
     * consecutive observations, so this field is what makes Section 8 work.
     */
    val asOf: Instant
)

/** One observation of a metric at a point in time. */
data class MetricPoint(val period: Instant, val value: Double)

/**
 * A company's current metrics plus their history.
 *
 * The history is what makes valuation interpretable. "P/E 31" says almost
 * nothing on its own; "P/E 31, 78th percentile of its own last ten years"
 * says whether that is unusual *for this company*, which is the question
 * worth asking.
 */
data class CompanyMetricsDTO(
    /** Current values, keyed by the provider's metric name. */
    val current: Map<String, Double> = emptyMap(),
    /** Historical series, keyed by metric name. */
    val annual: Map<String, List<MetricPoint>> = emptyMap(),
    val quarterly: Map<String, List<MetricPoint>> = emptyMap(),
    val asOf: Instant
) {
    fun currentValue(key: String): Double? = current[key]

    /** Prefers quarterly history, which is denser, falling back to annual. */
    fun history(key: String): List<MetricPoint> {
        val quarterlyPoints = quarterly[key] ?: emptyList()
        return if (quarterlyPoints.isEmpty()) (annual[key] ?: emptyList()) else quarterlyPoints
    }

    /**
     * Prefers annual history once it holds [minimumYears] points, falling
     * back to [history].
     *
     * For metrics whose current value covers twelve months. Finnhub's
     * quarterly margins are single quarters; a TTM margin ranked among them is
     * a smoothed figure measured against spiky ones, and one exceptional
     * quarter (GOOGL's 93.7% net margin) sets the top of the range.
     */
    fun twelveMonthHistory(key: String, minimumYears: Int): List<MetricPoint> {
        val annualPoints = annual[key] ?: emptyList()
        return if (annualPoints.size >= minimumYears) annualPoints else history(key)
    }
}

data class RatingSnapshotDTO(
    val asOf: Instant,
    val strongBuy: Int,
    val buy: Int,
    val hold: Int,
    val sell: Int,
    val strongSell: Int
) {
    val total: Int get() = strongBuy + buy + hold + sell + strongSell
}

data class MacroObservationDTO(
    val seriesID: String,
    val date: Instant,
    val value: Double
)

// MARK: - Provider interfaces

/**
 * Marker for anything the app fetches from, so capability reporting and
 * error surfaces can treat providers uniformly.
 */
interface DataProvider {
    val id: DataProviderID

    /**
     * Whether the provider has everything it needs (keys, contact email) to
     * make requests. Checked before dispatch so the UI can show an actionable
     * "add your key" state instead of a failed request.
     */
    suspend fun isConfigured(): Boolean
}

interface MarketDataProvider : DataProvider {
    suspend fun quote(symbol: String): QuoteDTO

    suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant
    ): List<PriceBarDTO>

    suspend fun profile(symbol: String): CompanyProfileDTO

    suspend fun search(query: String): List<CompanyProfileDTO>
}

interface FundamentalsProvider : DataProvider {
    /** Historical reported figures. [since] bounds how far back to pull. */
    suspend fun facts(
        symbol: String,
        cik: String?,
        concepts: List<FinancialConcept>,
        since: Instant?
    ): List<FinancialFactDTO>
}

interface AnalystDataProvider : DataProvider {
    suspend fun estimates(symbol: String, metric: EstimateMetric): List<AnalystEstimateDTO>
    suspend fun ratings(symbol: String): List<RatingSnapshotDTO>
}

interface SECDataProvider : DataProvider {
    suspend fun resolveCIK(symbol: String): String
    suspend fun filings(cik: String, formTypes: List<String>, limit: Int): List<FilingDTO>
    suspend fun insiderTransactions(cik: String, since: Instant?): List<InsiderTransactionDTO>
}

interface MacroDataProvider : DataProvider {
    suspend fun observations(
        seriesID: String,
        from: Instant?,
        to: Instant?
    ): List<MacroObservationDTO>
}

/**
 * Current metrics plus their history.
 *
 * Missed when Phase 1 ported the other protocols; added in Phase 6 when
 * `FinnhubMetricsProvider` needed something to conform to.
 */
interface CompanyMetricsProvider : DataProvider {
    suspend fun metrics(symbol: String): CompanyMetricsDTO
}

interface NewsProvider : DataProvider {
    suspend fun companyNews(symbol: String, from: Instant, to: Instant): List<NewsItemDTO>
}
