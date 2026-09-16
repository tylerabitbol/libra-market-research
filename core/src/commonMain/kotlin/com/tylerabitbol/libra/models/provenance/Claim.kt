package com.tylerabitbol.libra.models.provenance

import com.tylerabitbol.libra.support.randomId
import kotlin.time.Instant

/**
 * The epistemic status of a statement the app puts on screen.
 *
 * Section 24 of the product spec is a hard requirement: the app must never
 * present a hypothesis as a fact. Encoding that as a type rather than a UI
 * convention means an unlabelled statement cannot reach the user — there is
 * no way to construct a [Claim] without declaring what kind of claim it is.
 *
 * Declaration order is most to least certain, so the natural enum ordering is
 * the confidence ordering; Swift spelled this out as an explicit `rank`.
 */
enum class ClaimKind(val raw: String) {
    /** Reported directly by a primary source. "Revenue was $30.0B in Q2 FY2026." */
    Fact("fact"),

    /** Derived deterministically from facts. "Revenue grew 14.2% year-over-year." */
    Calculation("calculation"),

    /** A judgement about what the calculations show. "Revenue growth has accelerated." */
    Interpretation("interpretation"),

    /** A possible explanation, explicitly unproven. "This may indicate improving demand." */
    Hypothesis("hypothesis");

    val label: String
        get() = when (this) {
            Fact -> "FACT"
            Calculation -> "CALCULATION"
            Interpretation -> "INTERPRETATION"
            Hypothesis -> "HYPOTHESIS"
        }

    /** Shown in the UI when the user asks what a label means. */
    val definition: String
        get() = when (this) {
            Fact -> "Reported directly by a primary source."
            Calculation -> "Computed from reported figures using a formula you can inspect."
            Interpretation -> "A judgement about what the figures show. Reasonable people may disagree."
            Hypothesis -> "A possible explanation. Not established, and not a prediction."
        }
}

/** Identifies which upstream service a piece of data came from. */
enum class DataProviderID(val raw: String) {
    SEC("sec"),
    Finnhub("finnhub"),
    Tiingo("tiingo"),
    Alpaca("alpaca"),
    FRED("fred"),
    Computed("computed"),

    /**
     * Synthetic figures the app generates for itself: previews, tests, and a
     * run with no API keys. Not a vendor, and never a source of truth — the
     * store refuses to persist anything carrying this identity, because a
     * sample bar on disk is indistinguishable from a real one afterwards.
     */
    Sample("sample");

    val displayName: String
        get() = when (this) {
            SEC -> "SEC EDGAR"
            Finnhub -> "Finnhub"
            Tiingo -> "Tiingo"
            Alpaca -> "Alpaca (IEX)"
            FRED -> "FRED"
            Computed -> "Calculated locally"
            Sample -> "Sample data"
        }

    /**
     * Primary sources are filings from the issuer itself. The spec treats SEC
     * as authoritative where it and a vendor disagree.
     */
    val isPrimarySource: Boolean get() = this == SEC

    /**
     * True for identities that produce invented numbers. Everything that
     * writes to the store checks this.
     */
    val isSynthetic: Boolean get() = this == Sample
}

/**
 * A pointer back to where a piece of information came from.
 *
 * Every [Claim] must carry at least one of these for facts, so the user can
 * always get from a sentence on screen to the underlying record.
 */
data class SourceReference(
    val provider: DataProviderID,
    /** Human-readable description of the specific record, e.g. "10-Q filed 2026-07-28". */
    val detail: String,
    /** Link to the original document where one exists. SEC filings always have one. */
    val url: String? = null,
    val retrievedAt: Instant
) {
    val id: String
        get() = "${provider.raw}:$detail:${retrievedAt.toEpochMilliseconds() / 1000.0}"
}

/**
 * The arithmetic behind a [ClaimKind.Calculation] claim, in a form the UI can
 * display.
 *
 * Serializable (Phase 3) so a stored event can carry its arithmetic back out of
 * the store. Without it, an event read from disk had no derivation, and
 * `headlineClaim` therefore badged a measured calculation as a FACT — the app
 * misdescribing its own epistemic status, which is the one thing Section 24
 * exists to stop.
 */
data class Derivation(
    /** e.g. "(revenue - revenuePriorYear) / revenuePriorYear" */
    val formula: String,
    /** Named inputs with their values, e.g. ("revenue", "30,040,000,000"). */
    val inputs: List<Input>,
    val result: String
) {
    data class Input(
        val name: String,
        val value: String,
        val source: SourceReference? = null
    ) {
        val id: String get() = name
    }
}

/**
 * A single statement the app is prepared to show the user, carrying its own
 * epistemic status and its trail back to the source data.
 */
data class Claim(
    val kind: ClaimKind,
    val text: String,
    val sources: List<SourceReference> = emptyList(),
    /**
     * For calculations: the formula and inputs, so the user can verify the
     * number rather than trust it. Section 13 forbids black-box values.
     */
    val derivation: Derivation? = null,
    val id: String = randomId()
) {
    /**
     * True when the claim can be traced to something the user can open and
     * read. Interpretations and hypotheses inherit traceability from the
     * claims they rest on.
     */
    val isTraceable: Boolean get() = sources.isNotEmpty() || derivation != null
}
