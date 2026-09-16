package com.tylerabitbol.libra.models.core

enum class InsiderTransactionNature(val raw: String) {
    OpenMarketPurchase("openMarketPurchase"),
    OpenMarketSale("openMarketSale"),
    ScheduledPlan("scheduledPlan"),
    Grant("grant"),
    OptionExercise("optionExercise"),
    TaxWithholding("taxWithholding"),
    Gift("gift"),
    Other("other");

    val displayName: String
        get() = when (this) {
            OpenMarketPurchase -> "Open-market purchase"
            OpenMarketSale -> "Open-market sale"
            ScheduledPlan -> "Scheduled (10b5-1 plan)"
            Grant -> "Grant or award"
            OptionExercise -> "Option exercise"
            TaxWithholding -> "Shares withheld for tax"
            Gift -> "Gift"
            Other -> "Other"
        }

    /**
     * Whether this reflects a discretionary decision by the insider at that
     * moment. Grants, tax withholding and scheduled plan sales do not.
     */
    val isDiscretionary: Boolean
        get() = when (this) {
            OpenMarketPurchase, OpenMarketSale, Gift -> true
            ScheduledPlan, Grant, OptionExercise, TaxWithholding, Other -> false
        }

    companion object {
        /**
         * Classifies a transaction code, with the trading-plan flag taking
         * precedence over everything else.
         *
         * Shared by the stored row and the transport type rather than written
         * twice: a sale classified as discretionary in one and scheduled in the
         * other is the sort of disagreement nobody notices until it is on screen.
         */
        fun classify(code: String, isUnderTradingPlan: Boolean): InsiderTransactionNature {
            // A pre-arranged sale carries no opinion whatever code it was filed
            // under, so the plan flag is checked before the code and not after.
            if (isUnderTradingPlan) return ScheduledPlan
            return when (code.uppercase().trim()) {
                "P" -> OpenMarketPurchase
                "S" -> OpenMarketSale
                "A" -> Grant
                "M" -> OptionExercise
                "F" -> TaxWithholding
                "G" -> Gift
                else -> Other
            }
        }
    }
}

/** Form types the app treats as periodic reports. */
internal val periodicReportForms = setOf("10-K", "10-Q", "20-F", "40-F")
