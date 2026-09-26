package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.networking.serializers.VendorDate
import com.tylerabitbol.libra.support.XMLTree
import kotlin.time.Instant

/**
 * Parses an SEC ownership document — Form 3, 4 or 5 — into transactions.
 *
 * Section 10 asks for insider activity, and the provider previously refused
 * rather than returning an empty array, which was the right failure: an empty
 * list reads as "no insider trades" when it means "not implemented".
 *
 * Two things about this document deserve stating.
 *
 * **The 10b5-1 flag is looked for in two places.** A sale made under a
 * pre-arranged plan carries no opinion about the company — it was scheduled
 * months earlier — and mixing it in with discretionary selling is the single
 * most misleading thing this feature could do. The SEC added an explicit
 * checkbox for it in 2023, but filings predating that indicate it only in a
 * footnote, and filing agents differ in how they tag it. So both are read: any
 * element whose name mentions 10b5, and any footnote text that does.
 *
 * **Values are wrapped.** Most fields appear as
 * `<transactionShares><value>100</value></transactionShares>`, but some agents
 * emit the text directly on the outer element. Both are accepted.
 */
object Form4Parser {

    fun parse(
        document: String,
        accessionNumber: String,
        filedAt: Instant,
    ): List<InsiderTransactionDTO> {
        val root = XMLTree.parse(document) ?: throw APIError.Decoding(
            DataProviderID.SEC,
            endpoint = "Form 4",
            underlying = "Malformed ownership XML",
        )

        val owner = root.first("reportingOwner")
        val name = owner?.first("reportingOwnerId")?.value("rptOwnerName") ?: "Unknown"
        val relationship = owner?.first("reportingOwnerRelationship")
        val isDirector = flag(relationship?.value("isDirector"))
        val isOfficer = flag(relationship?.value("isOfficer"))
        val isTenPercent = flag(relationship?.value("isTenPercentOwner"))
        val title = relationship?.value("officerTitle")

        // Footnote text lives at the end of the document and transactions
        // point at it by id. Applying a plan footnote to every line — rather
        // than to the lines that reference it — marks discretionary purchases
        // as scheduled and hides exactly the insider buying worth seeing.
        val planFootnoteIDs = root.descendants("footnote")
            .filter { it.text.contains("10b5-1", ignoreCase = true) }
            .mapNotNull { it.attributes["id"] }
            .toSet()

        val transactions = root.descendants("nonDerivativeTransaction") +
            root.descendants("derivativeTransaction")

        return transactions.mapNotNull { node ->
            val code = node.first("transactionCoding")?.value("transactionCode")
            if (code.isNullOrEmpty()) return@mapNotNull null
            val date = VendorDate.day(node.first("transactionDate")?.valueOrText())
                ?: return@mapNotNull null

            val amounts = node.first("transactionAmounts")
            val shares = amounts?.first("transactionShares")?.valueDouble()
            val price = amounts?.first("transactionPricePerShare")?.valueDouble()
            val owned = node.first("postTransactionAmounts")
                ?.first("sharesOwnedFollowingTransaction")?.valueDouble()

            // Either the modern checkbox on this line, or a footnote this line
            // actually references.
            val hasPlanElement = node
                .descendants { it.name.contains("10b5", ignoreCase = true) }
                .any { flag(it.valueOrText()) }
            val referencesPlanFootnote = node.descendants("footnoteId")
                .any { it.attributes["id"] in planFootnoteIDs }

            InsiderTransactionDTO(
                accessionNumber = accessionNumber,
                insiderName = name,
                insiderTitle = title,
                isDirector = isDirector,
                isOfficer = isOfficer,
                isTenPercentOwner = isTenPercent,
                transactionDate = date,
                filedAt = filedAt,
                transactionCode = code.trim(),
                isUnderTradingPlan = hasPlanElement || referencesPlanFootnote,
                shares = shares,
                pricePerShare = price,
                sharesOwnedAfter = owned,
            )
        }
    }

    /** SEC booleans arrive as 0/1, and occasionally as true/false. */
    private fun flag(raw: String?): Boolean {
        val trimmed = raw?.trim()?.lowercase() ?: return false
        return trimmed == "1" || trimmed == "true"
    }
}
