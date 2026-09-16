package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.models.core.InsiderTransactionNature
import com.tylerabitbol.libra.models.provenance.Claim
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.models.provenance.SourceReference
import com.tylerabitbol.libra.services.providers.InsiderTransactionDTO
import com.tylerabitbol.libra.support.Format
import kotlin.time.Instant

/**
 * Section 10's summaries, rather than a raw list of Form 4 lines.
 *
 * The spec is explicit: "Do not simply display a raw list." It is also
 * explicit that the app must not imply insider activity predicts returns, and
 * the claim below says so in as many words.
 *
 * Everything here turns on one distinction. A sale under a 10b5-1 plan was
 * scheduled months before it executed and carries no opinion about the
 * company; a grant is compensation; shares withheld for tax are an
 * administrative consequence of vesting. Counting those alongside a decision
 * to buy or sell on the open market produces a number that looks like insider
 * sentiment and is mostly payroll.
 */
object InsiderActivity {

    data class Summary(
        val purchases: List<InsiderTransactionDTO>,
        val sales: List<InsiderTransactionDTO>,
        /**
         * Filed but excluded from the counts, and reported separately so the
         * exclusion is visible rather than silent.
         */
        val scheduledCount: Int,
        val routineCount: Int,
        val windowStart: Instant,
        val windowEnd: Instant
    ) {
        val purchaseCount: Int get() = purchases.size
        val saleCount: Int get() = sales.size
        val purchaseValue: Double? get() = total(purchases)
        val saleValue: Double? get() = total(sales)
        val hasDiscretionaryActivity: Boolean get() = purchaseCount + saleCount > 0

        private fun total(transactions: List<InsiderTransactionDTO>): Double? {
            val values = transactions.mapNotNull { it.approximateValue }
            return if (values.isEmpty()) null else values.sum()
        }

        /**
         * Approximate because Form 4 reports a price per transaction line, and
         * a single decision is often filed as several lines at several prices.
         */
        val claim: Claim
            get() = Claim(
                kind = ClaimKind.Calculation,
                text = "$purchaseCount discretionary open-market purchase" +
                    "${if (purchaseCount == 1) "" else "s"} and $saleCount sale" +
                    "${if (saleCount == 1) "" else "s"} between " +
                    "${Format.shortDate(windowStart)} and ${Format.shortDate(windowEnd)}. " +
                    "$scheduledCount scheduled-plan and $routineCount routine " +
                    "transactions are excluded because they carry no decision. " +
                    "Insider activity does not predict returns.",
                sources = listOf(
                    SourceReference(
                        provider = DataProviderID.SEC,
                        detail = "Form 4 filings",
                        retrievedAt = windowEnd
                    )
                ),
                derivation = Derivation(
                    formula = "count of transactions where nature is discretionary",
                    inputs = listOf(
                        Derivation.Input("purchases", "$purchaseCount"),
                        Derivation.Input("sales", "$saleCount"),
                        Derivation.Input(
                            "approximate purchase value",
                            Format.compactCurrency(purchaseValue)
                        ),
                        Derivation.Input(
                            "approximate sale value",
                            Format.compactCurrency(saleValue)
                        ),
                        Derivation.Input("excluded as scheduled", "$scheduledCount"),
                        Derivation.Input("excluded as routine", "$routineCount")
                    ),
                    result = "$purchaseCount bought, $saleCount sold"
                )
            )
    }

    fun summarize(transactions: List<InsiderTransactionDTO>): Summary? {
        val earliest = transactions.minOfOrNull { it.transactionDate } ?: return null
        val latest = transactions.maxOfOrNull { it.transactionDate } ?: return null

        val purchases = mutableListOf<InsiderTransactionDTO>()
        val sales = mutableListOf<InsiderTransactionDTO>()
        var scheduled = 0
        var routine = 0

        for (transaction in transactions) {
            when (transaction.nature) {
                InsiderTransactionNature.OpenMarketPurchase -> purchases.add(transaction)
                InsiderTransactionNature.OpenMarketSale -> sales.add(transaction)
                InsiderTransactionNature.ScheduledPlan -> scheduled += 1
                else -> routine += 1
            }
        }

        return Summary(
            purchases = purchases, sales = sales,
            scheduledCount = scheduled, routineCount = routine,
            windowStart = earliest, windowEnd = latest
        )
    }

    /**
     * Events for transactions that reflect a decision, filed since the user
     * last looked.
     *
     * Scheduled sales are deliberately not events. A plan executing on
     * schedule is not news, and a feed that reported every one of them would
     * bury the purchases that are.
     */
    fun events(
        transactions: List<InsiderTransactionDTO>,
        since: Instant?,
        limit: Int = 5
    ): List<DetectedEventDTO> {
        if (since == null) return emptyList()
        return transactions
            .filter { it.nature.isDiscretionary && it.filedAt > since }
            .sortedByDescending { it.transactionDate }
            .take(limit)
            .map { transaction ->
                val verb =
                    if (transaction.nature == InsiderTransactionNature.OpenMarketPurchase) {
                        "bought"
                    } else {
                        "sold"
                    }
                val lines = mutableListOf(transaction.nature.displayName)
                transaction.shares?.let { lines.add("Shares: ${Format.compact(it)}") }
                transaction.approximateValue?.let {
                    lines.add("Approximate value: ${Format.compactCurrency(it)}")
                }
                transaction.sharesOwnedAfter?.let {
                    lines.add("Held afterwards: ${Format.compact(it)}")
                }
                DetectedEventDTO.create(
                    kind = EventKind.InsiderTransaction,
                    occurredAt = transaction.filedAt,
                    headline = "${transaction.insiderName} $verb on " +
                        Format.shortDate(transaction.transactionDate),
                    detailLines = lines,
                    context = "An officer or director dealing in their own company's shares " +
                        "outside a pre-arranged plan. This is a fact about one person's " +
                        "decision, not a signal about the company — insider transactions " +
                        "do not predict returns, and a single sale has many innocent " +
                        "explanations.",
                    // A transaction either happened or did not; there is no
                    // sample to rank it against.
                    unusualness = 0.0,
                    sourceDetails = listOf("Form 4 — ${transaction.accessionNumber}")
                )
            }
    }
}
