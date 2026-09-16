package com.tylerabitbol.libra.models.core

import co.touchlab.kermit.Logger

/**
 * The indexes and sector proxies the dashboard tracks (Section 3) and that
 * relative analysis measures against (Section 7).
 *
 * A benchmark can be backed by two different things, and which one is used
 * changes what the number means:
 *
 * - [fredSeriesID] is the **real index** — the actual S&P 500 level, the
 *   actual VIX. Free from FRED, daily history back decades, but close-only and
 *   end-of-day.
 * - [etfSymbol] is a **tradable proxy**. Live and intraday with volume, but an
 *   ETF is not the index: it carries fees and can trade at a premium or
 *   discount.
 *
 * Where both exist the real index is preferred and the ETF is the intraday
 * fallback. Sectors have no FRED equivalent, so they remain proxies and say so.
 */
data class Benchmark(
    val id: String,
    val displayName: String,
    /** FRED series for the genuine index, when one exists. */
    val fredSeriesID: String?,
    /** Tradable ETF, for intraday moves or where no index series exists. */
    val etfSymbol: String?,
    /**
     * Explains the substitution whenever the displayed figure is not the thing
     * named. Null when [fredSeriesID] supplies the real index.
     */
    val proxyNote: String?,
    val category: Category
) {
    enum class Category(val raw: String) {
        BroadMarket("broadMarket"),
        Volatility("volatility"),
        Sector("sector");

        val id: String get() = raw

        val displayName: String
            get() = when (this) {
                BroadMarket -> "Market"
                Volatility -> "Volatility"
                Sector -> "Sectors"
            }
    }

    /** True when the displayed value is a stand-in rather than the named index. */
    val isProxy: Boolean get() = fredSeriesID == null && proxyNote != null

    /** Whether this benchmark's headline value comes from a real index series. */
    val hasRealIndex: Boolean get() = fredSeriesID != null

    companion object {
        private val logger = Logger.withTag("benchmarks")

        /**
         * The reference market for attribution — separating "the market moved"
         * from "this company moved". The real index, since a beta measured
         * against an ETF inherits the ETF's tracking error.
         */
        const val marketSeriesID = "SP500"

        /**
         * Stands in for the index during an open session, because FRED publishes
         * the index only at the close. Labelled a proxy wherever it is shown.
         */
        const val marketProxySymbol = "SPY"

        val broadMarket: List<Benchmark> = listOf(
            Benchmark(
                "sp500", "S&P 500", "SP500", "SPY", null, Category.BroadMarket
            ),
            Benchmark(
                "nasdaq", "Nasdaq Composite", "NASDAQCOM", "QQQ", null, Category.BroadMarket
            ),
            Benchmark(
                "dow", "Dow Jones Industrial Average", "DJIA", "DIA", null, Category.BroadMarket
            ),
            // No FRED series for the Russell 2000, so this one stays a proxy.
            Benchmark(
                "russell2000", "Russell 2000", null, "IWM",
                "iShares Russell 2000 ETF, used as a proxy for the index.",
                Category.BroadMarket
            )
        )

        /**
         * The real CBOE VIX, daily back to 1990. This replaces the VIX-futures ETF
         * that earlier stood in for it — those futures decay over time and track
         * the index only loosely, which made the substitution actively misleading.
         */
        val volatility: List<Benchmark> = listOf(
            Benchmark("vix", "VIX", "VIXCLS", null, null, Category.Volatility)
        )

        private fun sector(id: String, name: String, symbol: String) = Benchmark(
            id = id,
            displayName = name,
            fredSeriesID = null,
            etfSymbol = symbol,
            proxyNote = "$symbol sector ETF, used as a proxy for the $name sector.",
            category = Category.Sector
        )

        /**
         * The eleven GICS sectors via SPDR sector ETFs. FRED publishes no sector
         * index, so every row here is explicitly a proxy.
         */
        val sectors: List<Benchmark> = listOf(
            sector("tech", "Information Technology", "XLK"),
            sector("financials", "Financials", "XLF"),
            sector("healthcare", "Health Care", "XLV"),
            sector("energy", "Energy", "XLE"),
            sector("discretionary", "Consumer Discretionary", "XLY"),
            sector("staples", "Consumer Staples", "XLP"),
            sector("industrials", "Industrials", "XLI"),
            sector("materials", "Materials", "XLB"),
            sector("realestate", "Real Estate", "XLRE"),
            sector("utilities", "Utilities", "XLU"),
            sector("communications", "Communication Services", "XLC")
        )

        val all: List<Benchmark> = broadMarket + volatility + sectors

        /** The market benchmark other things are measured against by default. */
        val market: Benchmark get() = broadMarket[0]

        /**
         * Industry-level labels mapped up to their GICS sector.
         *
         * Deliberately omits anything genuinely diversified — a conglomerate or a
         * "diversified financial services" issuer has no single sector, and naming
         * one would be the guess this whole function exists to avoid.
         */
        private val industryToSector: Map<String, String> = mapOf(
            // Information Technology
            "semiconductors" to "tech", "software" to "tech", "technology" to "tech",
            "hardware" to "tech", "electronic equipment" to "tech", "it services" to "tech",

            // Health Care
            "pharmaceuticals" to "healthcare", "biotechnology" to "healthcare",
            "health care" to "healthcare", "medical devices" to "healthcare",
            "life sciences tools & services" to "healthcare",

            // Financials
            "banking" to "financials", "financial services" to "financials",
            "insurance" to "financials", "capital markets" to "financials",
            "consumer finance" to "financials",

            // Consumer Discretionary
            "retail" to "discretionary", "automobiles" to "discretionary",
            "hotels, restaurants & leisure" to "discretionary",
            "textiles apparel & luxury goods" to "discretionary",
            "leisure products" to "discretionary",

            // Consumer Staples
            "food products" to "staples", "beverages" to "staples", "tobacco" to "staples",
            "household products" to "staples", "consumer products" to "staples",

            // Communication Services
            "media" to "communications", "communications" to "communications",
            "telecommunication" to "communications", "entertainment" to "communications",

            // Industrials
            "aerospace & defense" to "industrials", "machinery" to "industrials",
            "transportation" to "industrials", "logistics & transportation" to "industrials",
            "airlines" to "industrials", "construction" to "industrials",
            "industrial conglomerates" to "industrials",
            "commercial services & supplies" to "industrials",

            // Energy
            "energy" to "energy", "oil & gas" to "energy",

            // Materials
            "chemicals" to "materials", "metals & mining" to "materials",
            "packaging" to "materials", "paper & forest products" to "materials",
            "building materials" to "materials",

            // Utilities
            "utilities" to "utilities", "electric utilities" to "utilities",
            "gas utilities" to "utilities", "water utilities" to "utilities",

            // Real Estate
            "real estate" to "realestate", "reit" to "realestate"
        )

        /**
         * The sector benchmark a company maps to, for Section 7 comparisons.
         *
         * Null when the label doesn't match anything we track — better than silently
         * comparing a bank against the technology sector. A company with no sector
         * benchmark reports "could not be measured" rather than a guess, and that
         * behaviour is deliberate: widening coverage must not come at the cost of
         * inventing a parent for a label that has none.
         *
         * Two passes. The substring pass handles labels that already contain a
         * sector name — Finnhub's "Technology" inside "Information Technology".
         * The table above handles industry-level labels that share no substring
         * with their sector at all: NVIDIA reports "Semiconductors", which is
         * unmistakably Information Technology and matches none of it textually.
         */
        fun sector(matching: String?): Benchmark? {
            if (matching == null) return null
            val needle = matching.lowercase().trim()

            val substringMatch = sectors.firstOrNull { benchmark ->
                val name = benchmark.displayName.lowercase()
                name == needle || needle.contains(name) || name.contains(needle)
            }
            if (substringMatch != null) return substringMatch

            val id = industryToSector[needle]
            if (id != null) return sectors.firstOrNull { it.id == id }

            // Logged rather than swallowed: the table above was written from the
            // labels seen so far, not from a published list, so the honest way to
            // extend it is from labels actually encountered.
            logger.i { "No sector benchmark for industry $matching" }
            return null
        }
    }
}
