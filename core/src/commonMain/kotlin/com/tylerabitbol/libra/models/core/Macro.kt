package com.tylerabitbol.libra.models.core

enum class MacroUnit(val raw: String) {
    Percent("percent"),
    Index("index"),
    Currency("currency")
}

/** The macro indicators from Section 11, with the FRED series that back them. */
data class MacroIndicator(
    val id: String,
    val seriesID: String,
    val displayName: String,
    val unit: MacroUnit,
    /**
     * Plain-language note on what the series measures. Shown in the UI so the
     * number is interpretable without leaving the app.
     */
    val note: String
) {
    companion object {
        val defaults: List<MacroIndicator> = listOf(
            MacroIndicator(
                "cpi", "CPIAUCSL", "CPI", MacroUnit.Index,
                "Consumer Price Index, all urban consumers, seasonally adjusted."
            ),
            MacroIndicator(
                "coreCPI", "CPILFESL", "Core CPI", MacroUnit.Index,
                "CPI excluding food and energy."
            ),
            MacroIndicator(
                "unemployment", "UNRATE", "Unemployment", MacroUnit.Percent,
                "Civilian unemployment rate."
            ),
            MacroIndicator(
                "fedFunds", "DFF", "Fed funds rate", MacroUnit.Percent,
                "Effective federal funds rate, daily."
            ),
            MacroIndicator(
                "twoYear", "DGS2", "2Y Treasury", MacroUnit.Percent,
                "2-year Treasury constant maturity yield."
            ),
            MacroIndicator(
                "tenYear", "DGS10", "10Y Treasury", MacroUnit.Percent,
                "10-year Treasury constant maturity yield."
            ),
            MacroIndicator(
                "yieldCurve", "T10Y2Y", "10Y–2Y spread", MacroUnit.Percent,
                "10-year minus 2-year Treasury yield."
            ),
            MacroIndicator(
                "gdp", "GDPC1", "Real GDP", MacroUnit.Currency,
                "Real gross domestic product, chained 2017 dollars."
            ),
            MacroIndicator(
                "inflationExpectations", "T5YIE", "5Y inflation breakeven", MacroUnit.Percent,
                "5-year breakeven inflation rate implied by TIPS."
            ),
            MacroIndicator(
                "sentiment", "UMCSENT", "Consumer sentiment", MacroUnit.Index,
                "University of Michigan consumer sentiment index."
            )
        )
    }
}
