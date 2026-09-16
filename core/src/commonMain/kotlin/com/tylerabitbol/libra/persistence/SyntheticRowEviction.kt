package com.tylerabitbol.libra.persistence

import co.touchlab.kermit.Logger
import com.tylerabitbol.libra.models.core.EventKind

/**
 * Removes rows that a keyless run wrote and nothing later could tell apart
 * from real data.
 *
 * Until `PriceBar` carried a provider, a run with no API keys stored five
 * years of sample closes — one per calendar day, weekends included, a smooth
 * ramp to a four-figure price — and the store then served them in preference
 * to fetching real ones, because they looked like a fresh held copy. Two
 * signatures identify what to remove, and neither is a guess:
 *
 * - A bar with no provider. Every write now names one, so an unattributed row
 *   is by definition one written before the column existed and of unknown
 *   origin. Bars are the one thing here that a single request restores, which
 *   is what makes deleting rather than flagging them affordable.
 * - An accession number beginning `0000000000-`. EDGAR builds accessions from
 *   the filer's own ten-digit CIK, and no filer has CIK zero. Only the mock
 *   SEC provider emits that prefix.
 *
 * Events detected *over* those series go too, but only the kinds that are
 * re-derived from bars and filings on the next visit — a synthetic "fell 4.2%"
 * headline is otherwise permanent in the Research feed, while fundamental
 * events, which the sample registry cannot produce and nothing backfills, are
 * left alone. An acknowledged event that returns unacknowledged is the
 * accepted cost.
 */
suspend fun LibraDatabase.evictSyntheticRows(): Int {
    val logger = Logger.withTag("persistence")

    // Whose derived history is now suspect: the securities that held any of
    // the rows about to go. Collected before the deletes, since afterwards
    // there is nothing left to ask.
    val affected = buildSet {
        addAll(priceBars().unattributedSymbols())
        addAll(filings().symbolsWithAccessionPrefix(MOCK_ACCESSION_PREFIX))
        addAll(insiderTransactions().symbolsWithAccessionPrefix(MOCK_ACCESSION_PREFIX))
        // Facts carry the accession of the filing they were extracted from, so
        // the same impossible-CIK test applies. No mock fundamentals provider
        // exists today, which is why these rows are not expected to be found —
        // but the eviction is what makes that a fact about the store rather
        // than a fact about the registry, and the registry is the easier of the
        // two to change by accident.
        addAll(financialFacts().symbolsWithAccessionPrefix(MOCK_ACCESSION_PREFIX))
    }

    var removed = priceBars().deleteUnattributed()
    removed += filings().deleteWithAccessionPrefix(MOCK_ACCESSION_PREFIX)
    removed += insiderTransactions().deleteWithAccessionPrefix(MOCK_ACCESSION_PREFIX)
    removed += financialFacts().deleteWithAccessionPrefix(MOCK_ACCESSION_PREFIX)

    if (affected.isNotEmpty()) {
        removed += events().deleteRederivable(
            symbols = affected.toList(),
            kinds = EventKind.rederivedFromSeries.map { it.raw }
        )
    }

    if (removed > 0) {
        logger.i { "Evicted $removed unattributed or synthetic rows" }
    }
    return removed
}

/**
 * Ten zeroes and a dash: the CIK position of an accession number, for a filer
 * that cannot exist.
 */
const val MOCK_ACCESSION_PREFIX = "0000000000-"
