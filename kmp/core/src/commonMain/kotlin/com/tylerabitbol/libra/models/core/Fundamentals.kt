package com.tylerabitbol.libra.models.core

/**
 * The financial concepts the app understands, mapped to the US-GAAP tags
 * issuers actually use. Several concepts have multiple accepted tags because
 * companies genuinely differ; resolution order matters and is handled in the
 * SEC provider, not here.
 */
enum class FinancialConcept(val raw: String) {
    Revenue("revenue"),
    CostOfRevenue("costOfRevenue"),
    GrossProfit("grossProfit"),
    OperatingIncome("operatingIncome"),
    NetIncome("netIncome"),
    EarningsPerShareDiluted("earningsPerShareDiluted"),
    SharesOutstandingDiluted("sharesOutstandingDiluted"),
    OperatingCashFlow("operatingCashFlow"),
    CapitalExpenditures("capitalExpenditures"),
    CashAndEquivalents("cashAndEquivalents"),
    ShortTermInvestments("shortTermInvestments"),
    TotalDebt("totalDebt"),
    TotalAssets("totalAssets"),
    TotalLiabilities("totalLiabilities"),
    StockholdersEquity("stockholdersEquity");

    val displayName: String
        get() = when (this) {
            Revenue -> "Revenue"
            CostOfRevenue -> "Cost of revenue"
            GrossProfit -> "Gross profit"
            OperatingIncome -> "Operating income"
            NetIncome -> "Net income"
            EarningsPerShareDiluted -> "EPS (diluted)"
            SharesOutstandingDiluted -> "Diluted shares"
            OperatingCashFlow -> "Operating cash flow"
            CapitalExpenditures -> "Capital expenditures"
            CashAndEquivalents -> "Cash and equivalents"
            ShortTermInvestments -> "Short-term investments"
            TotalDebt -> "Total debt"
            TotalAssets -> "Total assets"
            TotalLiabilities -> "Total liabilities"
            StockholdersEquity -> "Stockholders' equity"
        }

    companion object {
        fun fromRaw(raw: String?): FinancialConcept? = entries.firstOrNull { it.raw == raw }
    }
}
