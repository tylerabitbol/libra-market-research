package com.tylerabitbol.libra.calculations

import kotlin.test.Test
import com.tylerabitbol.libra.support.InMemoryPreferenceStore
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Section 14, scoped to what the app holds.
 *
 * The rule that matters most is what happens to a figure the app does not
 * have. A screen that passes untestable subjects through returns companies it
 * could not actually test — asserting something it does not know, which is the
 * same fabrication as rendering an absent figure as zero.
 *
 * The two store-backed cases live in `ScreenerStoreTest`, beside the rest of
 * the persistence suite.
 */
class ScreenerTest {

    private fun subject(
        symbol: String,
        revenueGrowth: Double? = null,
        operatingMargin: Double? = null,
        netCash: Double? = null,
        dailyChange: Double? = null
    ): ScreenSubject = ScreenSubject(
        symbol = symbol,
        name = "$symbol Inc.",
        sector = null,
        dailyChangePercent = dailyChange,
        revenueGrowth = revenueGrowth,
        operatingMargin = operatingMargin,
        netCash = netCash
    )

    private fun rule(
        field: ScreenField,
        comparison: ScreenComparison,
        threshold: Double
    ): ScreenRule = ScreenRule(field = field, comparison = comparison, threshold = threshold)

    @Test
    fun untestableSubjectsDoNotMatch() {
        val screen = Screen(
            rules = listOf(rule(ScreenField.RevenueGrowth, ScreenComparison.GreaterThan, 10.0))
        )

        // Tempting to let it through so a half-populated security is not
        // excluded — but then the screen returns a company it never tested.
        assertFalse(screen.matches(subject("UNKNOWN")))
        assertTrue(screen.matches(subject("KNOWN", revenueGrowth = 15.0)))
    }

    @Test
    fun untestableCountIsVisible() {
        val screen = Screen(
            rules = listOf(rule(ScreenField.OperatingMargin, ScreenComparison.GreaterThan, 10.0))
        )
        val subjects = listOf(
            subject("A", operatingMargin = 20.0),
            subject("B"),
            subject("C")
        )
        assertEquals(listOf("A"), screen.run(subjects).map { it.symbol })
        // A screen that silently drops what it could not test reports a smaller
        // universe than the user thinks they searched.
        assertEquals(2, screen.untestable(subjects))
    }

    @Test
    fun combinatorsCompose() {
        val subjects = listOf(
            subject("BOTH", revenueGrowth = 20.0, operatingMargin = 30.0),
            subject("ONE", revenueGrowth = 20.0, operatingMargin = 2.0),
            subject("NEITHER", revenueGrowth = 1.0, operatingMargin = 2.0)
        )
        val rules = listOf(
            rule(ScreenField.RevenueGrowth, ScreenComparison.GreaterThan, 10.0),
            rule(ScreenField.OperatingMargin, ScreenComparison.GreaterThan, 10.0)
        )

        val all = Screen(combinator = ScreenCombinator.All, rules = rules)
        assertEquals(listOf("BOTH"), all.run(subjects).map { it.symbol })

        val any = Screen(combinator = ScreenCombinator.Any, rules = rules)
        assertEquals(listOf("BOTH", "ONE"), any.run(subjects).map { it.symbol })
    }

    @Test
    fun comparisonsHandleNegatives() {
        val subjects = listOf(
            subject("NETCASH", netCash = 5_000.0),
            subject("NETDEBT", netCash = -5_000.0)
        )

        val below = Screen(
            rules = listOf(rule(ScreenField.NetCash, ScreenComparison.LessThan, 0.0))
        )
        assertEquals(listOf("NETDEBT"), below.run(subjects).map { it.symbol })

        val above = Screen(
            rules = listOf(rule(ScreenField.NetCash, ScreenComparison.GreaterThan, 0.0))
        )
        assertEquals(listOf("NETCASH"), above.run(subjects).map { it.symbol })
    }

    @Test
    fun aScreenSurvivesASerializationRoundTrip() {
        // Stands in for the first half of Swift's `savedScreensRoundTrip`. The
        // other half is the preference store itself, which arrives in Phase 4;
        // this pins the part that would silently drop a rule.
        val screen = Screen(
            name = "Improving margins",
            combinator = ScreenCombinator.Any,
            rules = listOf(
                rule(ScreenField.OperatingMargin, ScreenComparison.GreaterThan, 15.0),
                rule(ScreenField.RevenueGrowth, ScreenComparison.GreaterThan, 5.0)
            )
        )
        val restored = Json.decodeFromString<Screen>(Json.encodeToString(screen))

        assertEquals("Improving margins", restored.name)
        assertEquals(ScreenCombinator.Any, restored.combinator)
        assertEquals(2, restored.rules.size)
        assertEquals(screen, restored)
    }

    @Test
    fun savedScreensRoundTripThroughPreferences() {
        val preferences = InMemoryPreferenceStore()
        val screen = Screen(
            name = "Improving margins",
            combinator = ScreenCombinator.Any,
            rules = listOf(
                rule(ScreenField.OperatingMargin, ScreenComparison.GreaterThan, 15.0),
                rule(ScreenField.RevenueGrowth, ScreenComparison.GreaterThan, 5.0)
            )
        )
        SavedScreens.save(listOf(screen), preferences)

        val loaded = SavedScreens.load(preferences)
        assertEquals(1, loaded.size)
        assertEquals("Improving margins", loaded.first().name)
        assertEquals(ScreenCombinator.Any, loaded.first().combinator)
        assertEquals(2, loaded.first().rules.size)
    }

    @Test
    fun anUnreadablePreferenceYieldsNoScreensRatherThanAFailure() {
        // No Swift counterpart: `JSONDecoder` there returned nil and the call
        // site already coped. Worth pinning here because a preference written
        // by a newer build must not stop the screener opening.
        val preferences = InMemoryPreferenceStore(
            mapOf("com.tylerabitbol.libra.savedScreens" to "{not json")
        )
        assertTrue(SavedScreens.load(preferences).isEmpty())
    }

    @Test
    fun emptyScreenMatchesAll() {
        val screen = Screen()
        val subjects = listOf(subject("A"), subject("B"))
        // An empty screen is not a filter that excludes; it is the absence of
        // one, and returning nothing would read as "no matches".
        assertEquals(2, screen.run(subjects).size)
    }
}
