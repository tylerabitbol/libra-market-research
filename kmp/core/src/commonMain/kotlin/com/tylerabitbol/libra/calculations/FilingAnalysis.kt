package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.provenance.Claim
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.models.provenance.SourceReference
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import com.tylerabitbol.libra.support.Format
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * What a single filing actually reported, and how each figure compares with
 * the same period a year earlier — Section 9's real ask.
 *
 * The spec is explicit that the app must not "simply dump filing links into
 * the interface". Until this existed, a new filing produced "Form 10-Q filed
 * Jul 28" followed by a generic description of what a 10-Q contains: it
 * announced that a document existed without saying what the document said.
 *
 * This costs no network request. Every `companyfacts` row carries the
 * accession number of the filing that reported it, and the submissions feed
 * carries the same accession in the same dashed format, so the join between
 * "a document was filed" and "these are its figures" is already on disk.
 *
 * Three rules, each because the obvious alternative asserts something false:
 *
 * - **A filing whose figures have not been published yet says so.**
 *   `companyfacts` lags the submissions feed by hours to days. Rendering that
 *   gap as "nothing changed" would be a false statement about a real document.
 * - **Only periodic reports are analysed.** An 8-K carries no tagged
 *   financials — earnings figures live in Exhibit 99.1 as untagged HTML — so
 *   it keeps the plain card rather than acquiring an empty figures section.
 * - **The comparison duration follows the form.** A 10-K reports annual
 *   figures and a 10-Q quarterly ones. Reading a 10-K's revenue out of the
 *   quarterly series returns nothing at all.
 */
object FilingAnalysis {

    /** Forms that carry tagged financial statements. */
    val periodicForms: Set<String> = setOf("10-K", "10-Q", "20-F", "40-F")

    /** Of those, the ones whose figures cover a full year. */
    val annualForms: Set<String> = setOf("10-K", "20-F", "40-F")

    /** One reported figure, with its year-earlier comparison where one exists. */
    data class Line(
        val label: String,
        /** The figure as filed. */
        val formatted: String,
        /**
         * The year-over-year move. Null when no comparable period is held —
         * a company's first year on file has nothing to compare against.
         */
        val comparison: String?,
        /**
         * Carries its own epistemic status: a bare reported figure is a FACT,
         * a figure plus a computed change is a CALCULATION.
         */
        val claim: Claim
    ) {
        val id: String get() = label
    }

    data class Result(
        val accessionNumber: String,
        val formType: String,
        val periodEnd: Instant?,
        val lines: List<Line>,
        /** The filing is known but its XBRL has not been published yet. */
        val isAwaitingFacts: Boolean
    ) {
        val isAnnual: Boolean get() = annualForms.contains(formType)
        val periodLabel: String get() = if (isAnnual) "year" else "quarter"
    }

    /** How a figure's year-over-year move should be expressed. */
    private enum class Comparison {
        /**
         * For strictly positive levels. Refused on a non-positive base, where
         * a percentage change is meaningless rather than merely large.
         */
        Percent,

        /** For rates already in percent, compared in percentage points. */
        Points,

        /**
         * For anything that can cross zero. A company going from -$10M to
         * +$10M has improved, and "-200%" describes that as a collapse.
         */
        AbsoluteCurrency
    }

    private data class Change(val text: String, val sentence: String, val formula: String)

    // MARK: - Entry point

    /**
     * Analyses one filing against the company's reported history.
     *
     * [facts] is the full history, not just this filing's rows: the figures
     * come from the filing, the comparison comes from everything else.
     *
     * Deliberately reads the in-memory facts rather than the store. Detection
     * runs before persistence, so a store read would report the newest filing
     * — the one the user actually came to see — as still awaiting its figures.
     */
    fun analyse(filing: FilingDTO, facts: List<FinancialFactDTO>): Result? {
        if (!periodicForms.contains(filing.formType)) return null

        val reported = facts.filter { it.accessionNumber == filing.accessionNumber }
        val period = reported.maxOfOrNull { it.periodEnd }
            ?: return Result(
                accessionNumber = filing.accessionNumber,
                formType = filing.formType,
                periodEnd = filing.periodOfReport,
                lines = emptyList(),
                isAwaitingFacts = true
            )

        // A filing restates its comparatives, so its rows span several periods.
        // The one being reported is the latest of them.
        val isAnnual = annualForms.contains(filing.formType)
        val kind = if (isAnnual) FiscalPeriodKind.Annual else FiscalPeriodKind.Quarter
        val source = SourceReference(
            provider = DataProviderID.SEC,
            detail = "${filing.formType} ${filing.accessionNumber}",
            url = filing.primaryDocumentURL ?: filing.filingIndexURL,
            retrievedAt = filing.filedAt
        )
        val noun = if (isAnnual) "year" else "quarter"

        val lines = listOfNotNull(
            line(
                label = "Revenue",
                series = FundamentalDetector.flow(facts, FinancialConcept.Revenue, kind),
                period = period, noun = noun, source = source,
                format = { Format.compactCurrency(it) }, comparison = Comparison.Percent
            ),
            line(
                label = "Gross margin",
                series = FundamentalDetector.marginSeries(
                    facts, FinancialConcept.GrossProfit, kind
                ),
                period = period, noun = noun, source = source,
                format = { Format.percent(it, precision = 1) }, comparison = Comparison.Points
            ),
            line(
                label = "Operating margin",
                series = FundamentalDetector.marginSeries(
                    facts, FinancialConcept.OperatingIncome, kind
                ),
                period = period, noun = noun, source = source,
                format = { Format.percent(it, precision = 1) }, comparison = Comparison.Points
            ),
            line(
                label = "Net income",
                series = FundamentalDetector.flow(facts, FinancialConcept.NetIncome, kind),
                period = period, noun = noun, source = source,
                format = { Format.compactCurrency(it) },
                comparison = Comparison.AbsoluteCurrency
            ),
            line(
                label = "Free cash flow",
                series = FundamentalDetector.freeCashFlowSeries(facts, kind),
                period = period, noun = noun, source = source,
                format = { Format.compactCurrency(it) },
                comparison = Comparison.AbsoluteCurrency
            ),
            // Balance-sheet figures are instants whatever the form's duration.
            line(
                label = "Cash and equivalents",
                series = FundamentalDetector.instant(
                    facts, FinancialConcept.CashAndEquivalents
                ),
                period = period, noun = noun, source = source,
                format = { Format.compactCurrency(it) },
                comparison = Comparison.AbsoluteCurrency
            ),
            line(
                label = "Total debt",
                series = FundamentalDetector.instant(facts, FinancialConcept.TotalDebt),
                period = period, noun = noun, source = source,
                format = { Format.compactCurrency(it) },
                comparison = Comparison.AbsoluteCurrency
            )
        )

        return Result(
            accessionNumber = filing.accessionNumber,
            formType = filing.formType,
            periodEnd = period,
            lines = lines,
            isAwaitingFacts = false
        )
    }

    // MARK: - Line construction

    private fun line(
        label: String,
        series: List<FundamentalDetector.SeriesPoint>,
        period: Instant,
        noun: String,
        source: SourceReference,
        format: (Double) -> String,
        comparison: Comparison
    ): Line? {
        val current = value(series, period) ?: return null
        val formatted = format(current)
        val prior = FundamentalDetector.yearOverYear(series)
            .firstOrNull { matches(it.period, period) }?.prior

        val change = prior?.let { describe(current, it, comparison, format) }
        if (prior == null || change == null) {
            // Reported, with nothing to compare it against. That is a fact
            // about the filing, not a calculation, and is badged accordingly.
            return Line(
                label = label, formatted = formatted, comparison = null,
                claim = Claim(
                    kind = ClaimKind.Fact,
                    text = "$label was $formatted for the $noun ending " +
                        "${Format.shortDate(period)}.",
                    sources = listOf(source)
                )
            )
        }

        return Line(
            label = label, formatted = formatted, comparison = change.text,
            claim = Claim(
                kind = ClaimKind.Calculation,
                text = "$label was $formatted for the $noun ending " +
                    "${Format.shortDate(period)}, ${change.sentence} the same " +
                    "$noun a year earlier.",
                sources = listOf(source),
                derivation = Derivation(
                    formula = change.formula,
                    inputs = listOf(
                        Derivation.Input("this $noun", formatted, source),
                        Derivation.Input("a year earlier", format(prior))
                    ),
                    result = change.text
                )
            )
        )
    }

    private fun describe(
        current: Double,
        prior: Double,
        comparison: Comparison,
        format: (Double) -> String
    ): Change? = when (comparison) {
        Comparison.Percent -> {
            // A percentage change off a non-positive base is not a growth rate
            // in any direction a reader would guess.
            if (prior <= 0) {
                null
            } else {
                val change = (current - prior) / prior * 100
                Change(
                    text = "${Format.signedPercent(change, precision = 1)} YoY",
                    sentence = "${if (change >= 0) "up" else "down"} " +
                        "${Format.percent(abs(change), precision = 1)} from",
                    formula = "(current - prior) ÷ prior"
                )
            }
        }

        Comparison.Points -> {
            val change = current - prior
            Change(
                text = "${Format.percentagePoints(change)} YoY",
                sentence = "${if (change >= 0) "up" else "down"} " +
                    "${Format.percentagePoints(abs(change), signed = false)} from",
                formula = "current - prior"
            )
        }

        Comparison.AbsoluteCurrency -> {
            val change = current - prior
            val magnitude = Format.compactCurrency(abs(change))
            Change(
                text = "${if (change >= 0) "+" else "−"}$magnitude YoY",
                sentence = "${if (change >= 0) "up" else "down"} $magnitude from",
                formula = "current - prior"
            )
        }
    }

    /**
     * Periods come from one source and should match exactly, but a few days
     * of tolerance costs nothing and survives a fiscal calendar that shifts
     * its closing date.
     */
    private fun matches(lhs: Instant, rhs: Instant, toleranceDays: Int = 5): Boolean =
        (lhs - rhs).absoluteValue <= toleranceDays.days

    private fun value(
        series: List<FundamentalDetector.SeriesPoint>,
        period: Instant
    ): Double? = series.lastOrNull { matches(it.period, period) }?.value
}
