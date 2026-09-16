package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.provenance.Claim
import com.tylerabitbol.libra.models.provenance.ClaimKind

/**
 * The eleven dimensions of Section 13, and Section 12's search for evidence
 * that argues the other way.
 *
 * **There is no score.** Section 13 asked for "Research Signal: 82/100" with a
 * breakdown behind it. The breakdown is here; the number is not, and its
 * absence is the design rather than an omission. Summing a valuation
 * percentile, an insider count and a volatility rank into one integer produces
 * exactly the authoritative-looking, meaningless figure this codebase refuses
 * in four other places — a P/E ranked against a company's own history means
 * something, and averaged with a momentum rank it means nothing at all. The
 * components are what a reader can act on; the total would only look like it.
 *
 * Two rules make the disconfirming half real:
 *
 * - **Direction is computed per dimension from the same arithmetic that
 *   produces the figure**, not chosen afterwards. A system that classifies
 *   evidence only once it knows the conclusion is a bull-case finder wearing
 *   a skeptic's label.
 * - **A dimension with no data is [EvidenceDirection.Unavailable], never
 *   neutral.** Treating an absent figure as "nothing to worry about" is the
 *   fabrication Section 21 forbids, applied to judgement rather than to numbers.
 */
enum class ResearchDimension(val raw: String) {
    Momentum("momentum"),
    RelativeStrength("relativeStrength"),
    RevenueTrend("revenueTrend"),
    EarningsTrend("earningsTrend"),
    Profitability("profitability"),
    Valuation("valuation"),
    BalanceSheet("balanceSheet"),
    AnalystPosture("analystPosture"),
    InsiderActivity("insiderActivity"),
    SectorStrength("sectorStrength"),
    Volatility("volatility");

    val id: String get() = raw

    val displayName: String
        get() = when (this) {
            Momentum -> "Momentum"
            RelativeStrength -> "Relative strength"
            RevenueTrend -> "Revenue trend"
            EarningsTrend -> "Earnings trend"
            Profitability -> "Profitability"
            Valuation -> "Valuation"
            BalanceSheet -> "Balance sheet"
            AnalystPosture -> "Analyst posture"
            InsiderActivity -> "Insider activity"
            SectorStrength -> "Sector strength"
            Volatility -> "Volatility"
        }

    /**
     * What this dimension is measured against, shown so the reader knows the
     * yardstick without leaving the screen.
     */
    val basis: String
        get() = when (this) {
            Momentum -> "Trailing return over the selected range."
            RelativeStrength -> "Return against the S&P 500 over the same window."
            RevenueTrend -> "Year-over-year revenue growth, against its own history."
            EarningsTrend -> "Year-over-year net income growth, against its own history."
            Profitability -> "Operating margin, against this company's own range."
            Valuation ->
                "Multiples ranked against this company's own past, not against peers."
            BalanceSheet -> "Total debt against cash and equivalents, as filed."
            AnalystPosture -> "Published ratings and reported earnings surprises."
            InsiderActivity ->
                "Discretionary open-market transactions, excluding scheduled plans."
            SectorStrength -> "The sector's return against the S&P 500."
            Volatility -> "Realised volatility, recent window against the prior one."
        }
}

/**
 * Which way a piece of evidence points.
 *
 * Never a recommendation, and never aggregated. "Challenging" means the figure
 * argues against the picture the other figures paint — it does not mean sell,
 * and a company can be worth holding with half its evidence challenging.
 */
enum class EvidenceDirection(val raw: String) {
    Supportive("supportive"),
    Challenging("challenging"),
    Neutral("neutral"),

    /**
     * No data. Kept distinct from neutral on purpose: an absent figure is not
     * reassurance.
     */
    Unavailable("unavailable");

    val displayName: String
        get() = when (this) {
            Supportive -> "Supports"
            Challenging -> "Challenges"
            Neutral -> "Mixed"
            Unavailable -> "Not available"
        }
}

data class ResearchComponent(
    val dimension: ResearchDimension,
    /** The figure itself, in a few words. */
    val summary: String,
    val direction: EvidenceDirection,
    /** The arithmetic, so the reader can check rather than trust. */
    val claim: Claim?
) {
    val id: String get() = dimension.raw

    companion object {
        fun unavailable(dimension: ResearchDimension, reason: String): ResearchComponent =
            ResearchComponent(
                dimension = dimension, summary = reason,
                direction = EvidenceDirection.Unavailable, claim = null
            )
    }
}

/**
 * Every dimension the app can currently speak to, with the disconfirming ones
 * separated out because Section 12 asks for them by name.
 */
data class ResearchProfile(val components: List<ResearchComponent>) {

    val supporting: List<ResearchComponent>
        get() = components.filter { it.direction == EvidenceDirection.Supportive }

    val challenging: List<ResearchComponent>
        get() = components.filter { it.direction == EvidenceDirection.Challenging }

    val unavailable: List<ResearchComponent>
        get() = components.filter { it.direction == EvidenceDirection.Unavailable }

    val measured: List<ResearchComponent>
        get() = components.filter { it.direction != EvidenceDirection.Unavailable }

    /**
     * True when the evidence points both ways at once — the case worth saying
     * out loud, because a reader scanning a list of green rows will not see it.
     */
    val isConflicted: Boolean get() = supporting.isNotEmpty() && challenging.isNotEmpty()

    /**
     * An INTERPRETATION about the shape of the evidence, never about the
     * security. It counts dimensions; it does not weigh them, because the
     * weighting is exactly the judgement the app refuses to make for you.
     */
    val shapeClaim: Claim
        get() {
            val text = when {
                measured.isEmpty() ->
                    "Not enough data to characterise the evidence either way."

                isConflicted ->
                    "The evidence points both ways: ${supporting.size} " +
                        (if (supporting.size == 1) "dimension supports" else "dimensions support") +
                        " the current picture and ${challenging.size} " +
                        (if (challenging.size == 1) "challenges" else "challenge") + " it. " +
                        "Which matters more is a judgement about this business, not a count."

                challenging.isEmpty() ->
                    "No dimension the app can measure currently argues against the picture. " +
                        "That is a statement about what is measured here, not an absence of risk."

                else ->
                    "No dimension the app can measure currently supports the picture."
            }
            return Claim(kind = ClaimKind.Interpretation, text = text)
        }
}
