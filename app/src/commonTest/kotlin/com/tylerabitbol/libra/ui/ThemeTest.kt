package com.tylerabitbol.libra.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The palette, checked as data.
 *
 * A Compose UI test cannot assert a rendered colour, so the thing worth testing
 * is the thing that was actually wrong: slots left at Material's baseline. This
 * runs without composition and catches exactly that.
 */
class ThemeTest {

    /** The slots the app or its components read. Named, so a gap is a failure. */
    private fun slots(scheme: ColorScheme): Map<String, Color> = mapOf(
        "primary" to scheme.primary,
        "onPrimary" to scheme.onPrimary,
        "primaryContainer" to scheme.primaryContainer,
        "onPrimaryContainer" to scheme.onPrimaryContainer,
        "secondary" to scheme.secondary,
        "secondaryContainer" to scheme.secondaryContainer,
        "onSecondaryContainer" to scheme.onSecondaryContainer,
        "tertiary" to scheme.tertiary,
        "background" to scheme.background,
        "onBackground" to scheme.onBackground,
        "surface" to scheme.surface,
        "onSurface" to scheme.onSurface,
        "surfaceVariant" to scheme.surfaceVariant,
        "onSurfaceVariant" to scheme.onSurfaceVariant,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
        "outline" to scheme.outline,
        "outlineVariant" to scheme.outlineVariant,
        "error" to scheme.error,
        "onError" to scheme.onError,
        "errorContainer" to scheme.errorContainer,
        "onErrorContainer" to scheme.onErrorContainer,
        "scrim" to scheme.scrim,
    )

    @Test
    fun noSlotTheAppReadsIsStillTheMaterialBaseline() {
        val baselineLight = slots(lightColorScheme())
        val baselineDark = slots(darkColorScheme())

        val stillBaseline = slots(libraLightScheme).filter { (name, color) ->
            // `onPrimary`/`onError` are white in both palettes, and white is
            // white. A match there says nothing.
            color != Color.White && baselineLight[name] == color
        } + slots(libraDarkScheme).filter { (name, color) ->
            color != Color.White && baselineDark[name] == color
        }

        assertTrue(
            stillBaseline.isEmpty(),
            "Left at Material's baseline: ${stillBaseline.keys.sorted()}",
        )
    }

    @Test
    fun theSelectedTabPillIsBlueRatherThanLilac() {
        // `secondaryContainer` paints the indicator. Material's baseline is
        // #E8DEF8, which is what made the tab bar lilac.
        assertEquals(Color(0xFF007AFF).copy(alpha = LibraAlpha.chipFill), libraLightScheme.secondaryContainer)
        assertEquals(Color(0xFF0A84FF).copy(alpha = LibraAlpha.chipFill), libraDarkScheme.secondaryContainer)
    }

    @Test
    fun theMeaningfulColoursAreTheIosSystemColours() {
        assertEquals(Color(0xFF34C759), lightColors.positive)
        assertEquals(Color(0xFF30D158), darkColors.positive)
        assertEquals(Color(0xFFFF3B30), lightColors.negative)
        assertEquals(Color(0xFFFF453A), darkColors.negative)
        assertEquals(Color(0xFFFF9500), lightColors.caution)
        assertEquals(Color(0xFFFF9F0A), darkColors.caution)
    }

    @Test
    fun darkIsNotLight() {
        val light = slots(libraLightScheme)
        val dark = slots(libraDarkScheme)
        // Some slots are legitimately shared — white on an accent, the scrim.
        val shared = setOf("onPrimary", "onSecondary", "onError", "onTertiary", "scrim")
        val identical = light.filter { (name, color) ->
            name !in shared && dark[name] == color
        }
        assertTrue(identical.isEmpty(), "Same in both appearances: ${identical.keys.sorted()}")
    }

    @Test
    fun theInterpretationBadgeHasADarkVariant() {
        // It used to be a hardcoded #7A5AF8 with no dark value at all.
        assertNotEquals(lightColors.claimInterpretation, darkColors.claimInterpretation)
        assertEquals(Color(0xFFAF52DE), lightColors.claimInterpretation)
        assertEquals(Color(0xFFBF5AF2), darkColors.claimInterpretation)
    }

    @Test
    fun everySemanticColourDiffersBetweenAppearances() {
        val l = lightColors
        val d = darkColors
        val pairs = listOf(
            "secondaryText" to (l.secondaryText to d.secondaryText),
            "tertiaryText" to (l.tertiaryText to d.tertiaryText),
            "caution" to (l.caution to d.caution),
            "positive" to (l.positive to d.positive),
            "negative" to (l.negative to d.negative),
            "groupedBackground" to (l.groupedBackground to d.groupedBackground),
            "cardFill" to (l.cardFill to d.cardFill),
            "nestedFill" to (l.nestedFill to d.nestedFill),
            "separator" to (l.separator to d.separator),
            "quaternaryFill" to (l.quaternaryFill to d.quaternaryFill),
            "claimFact" to (l.claimFact to d.claimFact),
            "claimCalculation" to (l.claimCalculation to d.claimCalculation),
        )
        val same = pairs.filter { it.second.first == it.second.second }.map { it.first }
        assertTrue(same.isEmpty(), "Same in both appearances: $same")
    }
}
