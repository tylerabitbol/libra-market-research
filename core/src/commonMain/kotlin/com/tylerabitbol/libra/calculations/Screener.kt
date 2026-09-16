package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.support.PreferenceStore
import com.tylerabitbol.libra.support.randomId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/**
 * Section 14's screener, scoped to what is true.
 *
 * The original spec implies screening a universe. Free tiers make that
 * impossible: Tiingo refills roughly one token every 80 seconds, so covering a
 * market would take days and a screen that silently examined six stocks while
 * looking like it examined six thousand is worse than no screen at all.
 *
 * So this screens **what the app already holds** — the watchlist plus anything
 * visited — reading only from the store and issuing no requests whatsoever.
 * The coverage is stated on screen rather than left to be assumed, which is
 * the difference between a small honest tool and a misleading one.
 */
data class ScreenSubject(
    val symbol: String,
    val name: String,
    val sector: String? = null,
    val price: Double? = null,
    val dailyChangePercent: Double? = null,
    /** Year-over-year revenue growth from the most recent comparable quarter. */
    val revenueGrowth: Double? = null,
    val grossMargin: Double? = null,
    val operatingMargin: Double? = null,
    /** Cash and equivalents less total debt. */
    val netCash: Double? = null,
    /** Rank of the most recent recorded change, 0–1. */
    val latestUnusualness: Double? = null,
    val daysSinceLastEvent: Double? = null
) {
    val id: String get() = symbol

    fun value(field: ScreenField): Double? = when (field) {
        ScreenField.Price -> price
        ScreenField.DailyChangePercent -> dailyChangePercent
        ScreenField.RevenueGrowth -> revenueGrowth
        ScreenField.GrossMargin -> grossMargin
        ScreenField.OperatingMargin -> operatingMargin
        ScreenField.NetCash -> netCash
        ScreenField.Unusualness -> latestUnusualness
        ScreenField.DaysSinceLastEvent -> daysSinceLastEvent
    }
}

@Serializable
enum class ScreenField(val raw: String) {
    Price("price"),
    DailyChangePercent("dailyChangePercent"),
    RevenueGrowth("revenueGrowth"),
    GrossMargin("grossMargin"),
    OperatingMargin("operatingMargin"),
    NetCash("netCash"),
    Unusualness("unusualness"),
    DaysSinceLastEvent("daysSinceLastEvent");

    val id: String get() = raw

    val displayName: String
        get() = when (this) {
            Price -> "Price"
            DailyChangePercent -> "Daily change %"
            RevenueGrowth -> "Revenue growth % YoY"
            GrossMargin -> "Gross margin %"
            OperatingMargin -> "Operating margin %"
            NetCash -> "Net cash"
            Unusualness -> "Unusualness of last change"
            DaysSinceLastEvent -> "Days since last change"
        }

    fun format(value: Double): String = when (this) {
        Price -> Format.currency(value)
        NetCash -> Format.compactCurrency(value)
        Unusualness -> Format.ratio(value, precision = 2)
        DaysSinceLastEvent -> "${value.toInt()}"
        else -> Format.percent(value, precision = 1)
    }
}

@Serializable
enum class ScreenComparison(val raw: String) {
    GreaterThan("greaterThan"),
    LessThan("lessThan");

    val id: String get() = raw
    val displayName: String get() = if (this == GreaterThan) "is above" else "is below"
    val symbol: String get() = if (this == GreaterThan) ">" else "<"

    fun matches(value: Double, threshold: Double): Boolean =
        if (this == GreaterThan) value > threshold else value < threshold
}

@Serializable
data class ScreenRule(
    val field: ScreenField = ScreenField.RevenueGrowth,
    val comparison: ScreenComparison = ScreenComparison.GreaterThan,
    val threshold: Double = 0.0,
    val id: String = randomId()
) {
    /**
     * Whether one subject satisfies this rule.
     *
     * A rule over a figure the app does not hold **fails**. It is tempting to
     * pass it through so a half-populated security is not excluded, but a
     * screen that returns companies it could not actually test is asserting
     * something it does not know — the same fabrication as rendering an absent
     * figure as zero.
     */
    fun matches(subject: ScreenSubject): Boolean {
        val value = subject.value(field) ?: return false
        return comparison.matches(value, threshold)
    }

    val summary: String
        get() = "${this.field.displayName} ${comparison.symbol} " +
            this.field.format(threshold)
}

@Serializable
enum class ScreenCombinator(val raw: String) {
    All("all"),
    Any("any");

    val id: String get() = raw
    val displayName: String get() = if (this == All) "Match all rules" else "Match any rule"
}

/**
 * A named set of rules. Serialisable so saved screens survive a relaunch
 * without a schema migration — they are user preferences, not observations, and
 * do not belong in the append-only store.
 */
@Serializable
data class Screen(
    val name: String = "",
    val combinator: ScreenCombinator = ScreenCombinator.All,
    val rules: List<ScreenRule> = emptyList(),
    val id: String = randomId()
) {
    fun matches(subject: ScreenSubject): Boolean {
        if (rules.isEmpty()) return true
        return if (combinator == ScreenCombinator.All) {
            rules.all { it.matches(subject) }
        } else {
            rules.any { it.matches(subject) }
        }
    }

    fun run(subjects: List<ScreenSubject>): List<ScreenSubject> =
        subjects.filter { matches(it) }.sortedBy { it.symbol }

    /**
     * How many subjects a rule could not be tested against, so the coverage of
     * a result is visible rather than assumed.
     */
    fun untestable(subjects: List<ScreenSubject>): Int = subjects.count { subject ->
        rules.any { subject.value(it.field) == null }
    }
}

/**
 * Saved screens, in preferences rather than in the store.
 *
 * They are user settings, not observations: nothing about a saved screen is a
 * record of what a security did, so putting it in the append-only store would
 * mean migrating a schema every time the screener grows a field.
 */
object SavedScreens {
    private const val KEY = "com.tylerabitbol.libra.savedScreens"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Screen.serializer())

    /**
     * Returns nothing rather than throwing when the stored value cannot be
     * read. A preference written by a newer build, or a half-written one, is
     * not worth refusing to open the screener over.
     */
    fun load(from: PreferenceStore): List<Screen> {
        val raw = from.getString(KEY) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun save(screens: List<Screen>, to: PreferenceStore) {
        to.setString(KEY, json.encodeToString(serializer, screens))
    }
}
