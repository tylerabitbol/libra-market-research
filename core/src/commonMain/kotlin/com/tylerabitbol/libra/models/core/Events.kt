package com.tylerabitbol.libra.models.core

import com.tylerabitbol.libra.models.provenance.Claim
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.models.provenance.SourceReference
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The change types from Section 4. */
enum class EventKind(val raw: String) {
    UnusualPriceMove("unusualPriceMove"),
    UnusualVolume("unusualVolume"),
    VolatilityShift("volatilityShift"),
    AnalystEstimateRevision("analystEstimateRevision"),
    PriceTargetChange("priceTargetChange"),
    RatingChange("ratingChange"),
    EarningsSurprise("earningsSurprise"),
    InsiderTransaction("insiderTransaction"),
    NewFiling("newFiling"),
    FundamentalShift("fundamentalShift"),
    MarginChange("marginChange"),
    RevenueGrowthChange("revenueGrowthChange"),
    FreeCashFlowChange("freeCashFlowChange"),
    DebtChange("debtChange"),
    ValuationChange("valuationChange"),
    SectorRelativeMove("sectorRelativeMove"),
    MarketRelativeMove("marketRelativeMove"),
    MajorNews("majorNews"),
    Other("other");

    val displayName: String
        get() = when (this) {
            UnusualPriceMove -> "Unusual price move"
            UnusualVolume -> "Unusual volume"
            VolatilityShift -> "Volatility shift"
            AnalystEstimateRevision -> "Estimate revision"
            PriceTargetChange -> "Price target change"
            RatingChange -> "Rating change"
            EarningsSurprise -> "Earnings surprise"
            InsiderTransaction -> "Insider transaction"
            NewFiling -> "New filing"
            FundamentalShift -> "Fundamental change"
            MarginChange -> "Margin change"
            RevenueGrowthChange -> "Revenue growth change"
            FreeCashFlowChange -> "Free cash flow change"
            DebtChange -> "Debt change"
            ValuationChange -> "Valuation change"
            SectorRelativeMove -> "Sector-relative move"
            MarketRelativeMove -> "Market-relative move"
            MajorNews -> "News"
            Other -> "Other"
        }

    /**
     * SF Symbol name from the Swift app. Kept verbatim as the semantic
     * identity of the icon; Phase 8 maps these onto Material icons in one
     * place rather than scattering icon choices through the views.
     */
    val systemImage: String
        get() = when (this) {
            UnusualPriceMove, MarketRelativeMove, SectorRelativeMove -> "arrow.up.arrow.down"
            UnusualVolume -> "chart.bar"
            VolatilityShift -> "waveform.path.ecg"
            AnalystEstimateRevision, PriceTargetChange, RatingChange -> "pencil.line"
            EarningsSurprise -> "exclamationmark.bubble"
            InsiderTransaction -> "person.badge.key"
            NewFiling -> "doc.text"
            FundamentalShift, MarginChange, RevenueGrowthChange,
            FreeCashFlowChange, DebtChange -> "building.columns"
            ValuationChange -> "tag"
            MajorNews -> "newspaper"
            Other -> "circle"
        }

    /** Which of the Section 12 evidence buckets this belongs to. */
    val evidenceCategory: EvidenceCategory
        get() = when (this) {
            UnusualPriceMove, UnusualVolume, VolatilityShift -> EvidenceCategory.MarketStructure
            SectorRelativeMove -> EvidenceCategory.Industry
            MarketRelativeMove -> EvidenceCategory.MarketWide
            else -> EvidenceCategory.CompanySpecific
        }

    companion object {
        fun fromRaw(raw: String?): EventKind? = entries.firstOrNull { it.raw == raw }

        /**
         * Kinds the detectors rebuild from the underlying series on every visit,
         * as opposed to those that only ever accrue once.
         *
         * Deleting one of these costs nothing permanent: the bars, filings and
         * Form 4 lines it was derived from produce it again. Fundamental kinds are
         * deliberately absent — `FundamentalDetector` has no backfill, so a
         * margin change from four quarters ago exists only as the stored row.
         */
        val rederivedFromSeries: List<EventKind> = listOf(
            UnusualPriceMove, UnusualVolume, VolatilityShift,
            SectorRelativeMove, MarketRelativeMove,
            NewFiling, InsiderTransaction
        )
    }
}

/** The evidence organisation from Section 12. */
enum class EvidenceCategory(val raw: String) {
    CompanySpecific("companySpecific"),
    Industry("industry"),
    Macroeconomic("macroeconomic"),
    MarketWide("marketWide"),
    MarketStructure("marketStructure");

    val id: String get() = raw

    val displayName: String
        get() = when (this) {
            CompanySpecific -> "Company-specific"
            Industry -> "Industry"
            Macroeconomic -> "Macroeconomic"
            MarketWide -> "Market-wide"
            MarketStructure -> "Market structure"
        }
}

/**
 * A detected change as a value type.
 *
 * Detectors are pure functions and must stay testable without a database, and
 * the stored row cannot cross a coroutine boundary safely. [DetectedEventDTO]
 * is what detection produces; the store converts it into a row.
 *
 * Construct through [create], which clamps [unusualness] to 0...1 exactly as
 * the Swift initialiser did.
 */
data class DetectedEventDTO(
    val kind: EventKind,
    val occurredAt: Instant,
    val headline: String,
    val detailLines: List<String> = emptyList(),
    /** The "why this matters" text: what the numbers mean, never what to do. */
    val context: String? = null,
    /**
     * 0–1, relative to the security's own history. Not importance, not
     * direction, and explicitly not a probability — see `EventDetector`.
     */
    val unusualness: Double = 0.0,
    val sourceDetails: List<String> = emptyList(),
    val sourceURLs: List<String> = emptyList(),
    /** The arithmetic, so the figure can be checked rather than trusted. */
    val derivation: Derivation? = null,
    /**
     * True when the event describes a session that has not closed.
     *
     * Provisional events are shown but never stored. A move of +8% at midday
     * can finish the day at +2%, and the permanent record must not keep the
     * midday reading as though it were what happened. The closed session is
     * detected and stored on a later visit, from the bars.
     */
    val isProvisional: Boolean = false
) {
    val id: String get() = naturalKey

    /**
     * Identity for deduplication: one event of a kind per day per security.
     *
     * Detection re-runs on every visit, and the same Tuesday volume spike must
     * not accumulate a row per refresh. Day granularity rather than the exact
     * timestamp because [occurredAt] for a bar-derived event is the session,
     * and sessions are days.
     */
    val naturalKey: String get() = "${kind.raw}|${dayKey(occurredAt)}"

    /**
     * The headline, labelled by what kind of statement it actually is.
     *
     * Not every event is a calculation. "8-K filed Aug 16" is a FACT reported
     * by a primary source — the SEC — and badging it CALCULATION overstates
     * the app's involvement while understating the claim's authority. A
     * detector that measured something carries a derivation; one that merely
     * observed a document does not, and that is exactly the distinction
     * Section 24 asks the app to make.
     */
    val headlineClaim: Claim
        get() = Claim(
            kind = if (derivation != null) ClaimKind.Calculation else ClaimKind.Fact,
            text = headline,
            sources = sources,
            derivation = derivation
        )

    /**
     * Source references built from the stored detail/URL pairs, so the trail
     * back to the original document renders through `ClaimRow` like any other.
     */
    val sources: List<SourceReference>
        get() {
            if (sourceURLs.isEmpty() && sourceDetails.isEmpty()) return emptyList()
            val provider =
                if (kind == EventKind.NewFiling) DataProviderID.SEC else DataProviderID.Computed
            return sourceDetails.mapIndexed { index, detail ->
                SourceReference(
                    provider = provider,
                    detail = detail,
                    url = sourceURLs.getOrNull(index),
                    retrievedAt = occurredAt
                )
            }
        }

    /**
     * The context as an interpretation — a judgement about what the figures
     * show. Deliberately a weaker claim than the headline, and never a fact.
     */
    val contextClaim: Claim?
        get() = context?.let { Claim(kind = ClaimKind.Interpretation, text = it) }

    companion object {
        /** Mirrors the Swift initialiser, which clamps unusualness to 0...1. */
        fun create(
            kind: EventKind,
            occurredAt: Instant,
            headline: String,
            detailLines: List<String> = emptyList(),
            context: String? = null,
            unusualness: Double = 0.0,
            sourceDetails: List<String> = emptyList(),
            sourceURLs: List<String> = emptyList(),
            derivation: Derivation? = null,
            isProvisional: Boolean = false
        ): DetectedEventDTO = DetectedEventDTO(
            kind = kind,
            occurredAt = occurredAt,
            headline = headline,
            detailLines = detailLines,
            context = context,
            unusualness = unusualness.coerceIn(0.0, 1.0),
            sourceDetails = sourceDetails,
            sourceURLs = sourceURLs,
            derivation = derivation,
            isProvisional = isProvisional
        )

        /** ISO-8601 day, in UTC — matching Swift's `.iso8601` default zone. */
        fun dayKey(date: Instant): String = date.toLocalDateTime(TimeZone.UTC).date.toString()
    }
}
