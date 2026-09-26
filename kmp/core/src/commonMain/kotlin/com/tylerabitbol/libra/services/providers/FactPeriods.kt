package com.tylerabitbol.libra.services.providers

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Keying and collapsing reported figures by the period they describe.
 *
 * Lives here rather than on the SEC provider because three callers need it and
 * only one of them is a provider: the store collapses restatements on read,
 * the restatement detector groups versions of a period, and the provider
 * deduplicates what it extracts. In Swift these were static members of
 * `SECFundamentalsProvider`, which is why the store imported a provider to
 * read its own rows.
 */
object FactPeriods {

    /**
     * Year and month of the period end.
     *
     * Month granularity, not the exact date: a 52/53-week fiscal calendar
     * moves the closing date by days between years, and two filings of the
     * same quarter can disagree by a day. Keying on the exact instant would
     * treat those as different periods and keep both.
     */
    fun periodKey(date: Instant, zone: TimeZone = TimeZone.UTC): String {
        val parts = date.toLocalDateTime(zone)
        return "${parts.year}-${parts.month.ordinal + 1}"
    }

    /**
     * Keyed on the period the figure actually describes, *and* on its
     * duration.
     *
     * XBRL's `fy` is the filing's fiscal context, not the fact's, so two
     * different periods can share it — keying on `fy` silently drops one. And
     * a fiscal year ends on the same day as its own fourth quarter, so a key
     * without the duration pairs Apple's Q4 revenue against its full-year
     * revenue and reports a restatement that never happened.
     */
    fun groupKey(fact: FinancialFactDTO): String =
        "${periodKey(fact.periodEnd)}-${fact.periodKind.raw}"

    /**
     * One row per period: the most recently filed version of each.
     *
     * The *current* view of a company's history, which is what the analysis
     * layer wants. The superseded rows are still on disk; reading them back is
     * what `factRevisions` is for.
     *
     * Expects rows for a single concept. Handed a mixed-concept array it would
     * collapse revenue and net income for the same quarter into one row and
     * discard the loser, so every caller groups by concept first.
     */
    fun deduplicated(facts: List<FinancialFactDTO>): List<FinancialFactDTO> {
        val latest = mutableMapOf<String, FinancialFactDTO>()
        for (fact in facts) {
            val key = groupKey(fact)
            val existing = latest[key]
            if (existing == null) {
                latest[key] = fact
            } else {
                val existingFiled = existing.filedAt
                val candidateFiled = fact.filedAt
                // A row with no filing date loses to one that has it, and two
                // undated rows keep the first seen — same ordering Swift got
                // from `.distantPast`.
                if (candidateFiled != null &&
                    (existingFiled == null || candidateFiled > existingFiled)
                ) {
                    latest[key] = fact
                }
            }
        }
        return latest.values.sortedBy { it.periodEnd }
    }
}
