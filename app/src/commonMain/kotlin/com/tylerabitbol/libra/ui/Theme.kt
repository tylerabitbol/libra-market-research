package com.tylerabitbol.libra.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's colours, type and spacing.
 *
 * The Swift app has no theme file and no colour assets: every colour in it is a
 * UIKit semantic — `.secondary`, `Color(.secondarySystemGroupedBackground)`,
 * `.orange`. That is why its dark mode needed no code, and it is also why there
 * is nothing here to copy from. Both schemes are written out by hand below,
 * transcribed from the UIKit tokens rather than chosen.
 *
 * Dynamic colour is deliberately off. A figure's colour carries meaning in this
 * app — a caution is orange because it is a caution — and letting the wallpaper
 * choose would make "is this a warning?" a question about the device.
 */

// ---------------------------------------------------------------------------
// The UIKit tokens, at their published values.
// ---------------------------------------------------------------------------

private object IOS {
    // Backgrounds. The grouped family, because every Libra screen is a grouped
    // list or imitates one.
    val groupedBackgroundLight = Color(0xFFF2F2F7)
    val groupedBackgroundDark = Color(0xFF000000)
    val cardLight = Color(0xFFFFFFFF)
    val cardDark = Color(0xFF1C1C1E)
    val nestedLight = Color(0xFFF2F2F7)
    val nestedDark = Color(0xFF2C2C2E)

    // Labels. UIKit publishes these as a tint plus an alpha; they are resolved
    // to opaque values here, composited against the *card*, because that is
    // what supporting text sits on in nearly every case. Compose composites
    // translucent text differently from UIKit, and left translucent the
    // tertiary tier drifts visibly.
    val labelLight = Color(0xFF000000)
    val labelDark = Color(0xFFFFFFFF)
    val secondaryLabelLight = Color(0xFF8A8A8E) // #3C3C43 @ 60% on white
    val secondaryLabelDark = Color(0xFF98989F) // #EBEBF5 @ 60% on #1C1C1E
    val tertiaryLabelLight = Color(0xFFC5C5C7) // @ 30%
    val tertiaryLabelDark = Color(0xFF5A5A5F) // @ 30%
    val quaternaryLabelLight = Color(0xFFDCDCDD) // @ 18%
    val quaternaryLabelDark = Color(0xFF3D3D40) // @ 16%
    val separatorLight = Color(0xFFC6C6C8) // #3C3C43 @ 29%
    val separatorDark = Color(0xFF404044) // #545458 @ 65%

    // The system colours the app assigns meaning to.
    val blueLight = Color(0xFF007AFF)
    val blueDark = Color(0xFF0A84FF)
    val greenLight = Color(0xFF34C759)
    val greenDark = Color(0xFF30D158)
    val redLight = Color(0xFFFF3B30)
    val redDark = Color(0xFFFF453A)
    val orangeLight = Color(0xFFFF9500)
    val orangeDark = Color(0xFFFF9F0A)
    val purpleLight = Color(0xFFAF52DE)
    val purpleDark = Color(0xFFBF5AF2)
}

// ---------------------------------------------------------------------------
// The semantic colours the views name.
// ---------------------------------------------------------------------------

@Immutable
data class LibraColors(
    /** Supporting text: labels, units, dates. SwiftUI's `.secondary`. */
    val secondaryText: Color,
    /** Dimmer still: provenance, footnotes, disabled rows. `.tertiary`. */
    val tertiaryText: Color,
    /**
     * Caveats: a proxy standing in for the real series, a stale copy, a
     * coverage gap. Orange in Swift, and the one colour the app uses to mean
     * "true, but read the qualifier".
     */
    val caution: Color,
    /** A move up, and a passing connection test. */
    val positive: Color,
    /** A move down, and a failing one. */
    val negative: Color,
    /** The page behind everything. `systemGroupedBackground`. */
    val groupedBackground: Color,
    /** What a card is filled with. `secondarySystemGroupedBackground`. */
    val cardFill: Color,
    /** A panel nested inside a card. `tertiarySystemGroupedBackground`. */
    val nestedFill: Color,
    /** Hairlines: dividers, the border on the freshness pill. */
    val separator: Color,
    /** The faintest fill UIKit offers. `quaternaryLabel`, used as a track. */
    val quaternaryFill: Color,
    /** `ClaimBadge` tints. Kind of statement, never good-versus-bad news. */
    val claimFact: Color,
    val claimCalculation: Color,
    val claimInterpretation: Color,
    val claimHypothesis: Color,
)

internal val lightColors = LibraColors(
    secondaryText = IOS.secondaryLabelLight,
    tertiaryText = IOS.tertiaryLabelLight,
    caution = IOS.orangeLight,
    positive = IOS.greenLight,
    negative = IOS.redLight,
    groupedBackground = IOS.groupedBackgroundLight,
    cardFill = IOS.cardLight,
    nestedFill = IOS.nestedLight,
    separator = IOS.separatorLight,
    quaternaryFill = IOS.quaternaryLabelLight,
    claimFact = IOS.labelLight,
    claimCalculation = IOS.blueLight,
    claimInterpretation = IOS.purpleLight,
    claimHypothesis = IOS.orangeLight,
)

internal val darkColors = LibraColors(
    secondaryText = IOS.secondaryLabelDark,
    tertiaryText = IOS.tertiaryLabelDark,
    caution = IOS.orangeDark,
    positive = IOS.greenDark,
    negative = IOS.redDark,
    groupedBackground = IOS.groupedBackgroundDark,
    cardFill = IOS.cardDark,
    nestedFill = IOS.nestedDark,
    separator = IOS.separatorDark,
    quaternaryFill = IOS.quaternaryLabelDark,
    claimFact = IOS.labelDark,
    claimCalculation = IOS.blueDark,
    claimInterpretation = IOS.purpleDark,
    claimHypothesis = IOS.orangeDark,
)

val LocalLibraColors = staticCompositionLocalOf { lightColors }

/**
 * The tint alphas Swift uses, named once.
 *
 * The pattern is everywhere in the Swift source:
 * `.background(tint.opacity(0.15), in: .rect(cornerRadius: 4))` with
 * `.foregroundStyle(tint)`. Three alphas cover every instance of it.
 */
object LibraAlpha {
    /** A tinted chip or badge behind its own colour. */
    const val chipFill = 0.15f
    /** A full-width banner. Slightly weaker, because it is a larger area. */
    const val bannerFill = 0.12f
    /** A filter chip that is currently on. */
    const val chipSelected = 0.18f
}

// ---------------------------------------------------------------------------
// The Material scheme.
// ---------------------------------------------------------------------------

/**
 * Every slot is named.
 *
 * Material fills anything left out with its baseline purple, and the slots the
 * app never reads are exactly the ones its *components* read — a menu, a sheet,
 * the navigation bar's selected pill. Leaving them unset is how an app ends up
 * lilac in the places nobody screenshotted.
 *
 * Two of these are worth reading twice:
 *
 * `surfaceVariant` is the card fill and `surface` is the page behind it, which
 * is the opposite way round from Material's own convention. That is not a
 * mistake: the views were written against grouped-list semantics, where the
 * card is the raised thing, and all nineteen card sites already say
 * `surfaceVariant`. Renaming them would be churn in service of a convention
 * this app does not follow.
 *
 * `secondaryContainer` paints the navigation bar's selected indicator. It is
 * the purple pill.
 */
internal val libraLightScheme = lightColorScheme(
    primary = IOS.blueLight,
    onPrimary = Color.White,
    primaryContainer = IOS.blueLight.copy(alpha = LibraAlpha.chipFill),
    onPrimaryContainer = IOS.blueLight,
    secondary = IOS.blueLight,
    onSecondary = Color.White,
    secondaryContainer = IOS.blueLight.copy(alpha = LibraAlpha.chipFill),
    onSecondaryContainer = IOS.blueLight,
    tertiary = IOS.purpleLight,
    onTertiary = Color.White,
    background = IOS.groupedBackgroundLight,
    onBackground = IOS.labelLight,
    surface = IOS.groupedBackgroundLight,
    onSurface = IOS.labelLight,
    surfaceVariant = IOS.cardLight,
    onSurfaceVariant = IOS.secondaryLabelLight,
    surfaceContainerLowest = IOS.cardLight,
    surfaceContainerLow = IOS.cardLight,
    surfaceContainer = IOS.cardLight,
    surfaceContainerHigh = IOS.nestedLight,
    surfaceContainerHighest = IOS.nestedLight,
    inverseSurface = IOS.labelLight,
    inverseOnSurface = IOS.cardLight,
    outline = IOS.separatorLight,
    outlineVariant = IOS.quaternaryLabelLight,
    error = IOS.redLight,
    onError = Color.White,
    errorContainer = IOS.redLight.copy(alpha = LibraAlpha.bannerFill),
    onErrorContainer = IOS.redLight,
    scrim = Color.Black.copy(alpha = 0.32f),
)

internal val libraDarkScheme = darkColorScheme(
    primary = IOS.blueDark,
    onPrimary = Color.White,
    primaryContainer = IOS.blueDark.copy(alpha = LibraAlpha.chipFill),
    onPrimaryContainer = IOS.blueDark,
    secondary = IOS.blueDark,
    onSecondary = Color.White,
    secondaryContainer = IOS.blueDark.copy(alpha = LibraAlpha.chipFill),
    onSecondaryContainer = IOS.blueDark,
    tertiary = IOS.purpleDark,
    onTertiary = Color.White,
    background = IOS.groupedBackgroundDark,
    onBackground = IOS.labelDark,
    surface = IOS.groupedBackgroundDark,
    onSurface = IOS.labelDark,
    surfaceVariant = IOS.cardDark,
    onSurfaceVariant = IOS.secondaryLabelDark,
    surfaceContainerLowest = IOS.groupedBackgroundDark,
    surfaceContainerLow = IOS.cardDark,
    surfaceContainer = IOS.cardDark,
    surfaceContainerHigh = IOS.nestedDark,
    surfaceContainerHighest = IOS.nestedDark,
    inverseSurface = IOS.labelDark,
    inverseOnSurface = IOS.cardDark,
    outline = IOS.separatorDark,
    outlineVariant = IOS.quaternaryLabelDark,
    error = IOS.redDark,
    onError = Color.White,
    errorContainer = IOS.redDark.copy(alpha = LibraAlpha.bannerFill),
    onErrorContainer = IOS.redDark,
    scrim = Color.Black.copy(alpha = 0.32f),
)

// ---------------------------------------------------------------------------
// Type and spacing.
// ---------------------------------------------------------------------------

/**
 * Dense, text-first type.
 *
 * Swift's views are overwhelmingly `.caption` and `.caption2` — 110 of about
 * 180 font calls — because the screens are tables of figures rather than
 * articles. Material's defaults are set for reading prose, so the body and
 * label scales are pulled in to match. Everything is in `sp`, so the type
 * scales with the reader's setting.
 */
private fun libraTypography(text: FontFamily): Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = text),
        displayMedium = displayMedium.copy(fontFamily = text),
        displaySmall = displaySmall.copy(fontFamily = text),
        headlineLarge = headlineLarge.copy(fontFamily = text),
        headlineMedium = headlineMedium.copy(fontFamily = text),
        headlineSmall = headlineSmall.copy(fontFamily = text),
        titleLarge = titleLarge.copy(fontFamily = text, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = titleMedium.copy(fontFamily = text, fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = titleSmall.copy(fontFamily = text),
        bodyLarge = bodyLarge.copy(fontFamily = text),
        bodyMedium = bodyMedium.copy(fontFamily = text, fontSize = 14.sp, lineHeight = 19.sp),
        bodySmall = bodySmall.copy(fontFamily = text, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = labelLarge.copy(fontFamily = text),
        labelMedium = labelMedium.copy(fontFamily = text, fontSize = 12.sp, lineHeight = 15.sp),
        labelSmall = labelSmall.copy(fontFamily = text, fontSize = 11.sp, lineHeight = 14.sp),
    )
}

/**
 * Spacing, named once.
 *
 * Swift uses eight steps — 2, 4, 6, 8, 10, 12, 16, 20, with 10 the most common
 * — plus two fixed insets. The four original names keep their original values,
 * so no existing call site changes meaning.
 */
object LibraSpacing {
    val hair = 2.dp
    val tight = 4.dp
    val snug = 6.dp
    val small = 8.dp
    val base = 10.dp
    val medium = 12.dp
    val large = 16.dp
    val wide = 20.dp

    /** Margin from the edge of the screen. */
    val screen = 16.dp

    /** Padding inside a card. Swift's is 14, which is not on the scale. */
    val card = 14.dp

    /** Bottom inset so scrolled content clears the floating freshness pill. */
    val pillClearance = 44.dp
}

/**
 * Corner radii, as a ladder rather than a literal.
 *
 * Swift uses six, and uses them consistently: the smaller the thing, the
 * tighter the corner. The port already followed the same ladder — these are the
 * radii it was already writing out by hand.
 */
object LibraShapes {
    /** A tiny inline marker, like the RARE badge. */
    val badge = RoundedCornerShape(3.dp)
    /** A tinted chip or claim badge. */
    val chip = RoundedCornerShape(4.dp)
    /** The derivation panel behind an expanded claim. */
    val panel = RoundedCornerShape(6.dp)
    /** A small card, or a banner. */
    val smallCard = RoundedCornerShape(8.dp)
    /** A stack of rows read as one group. */
    val group = RoundedCornerShape(10.dp)
    /** The standard card. Nineteen of them in the Swift app. */
    val card = RoundedCornerShape(12.dp)
}

/**
 * The named styles, one per job Libra's fonts do.
 *
 * Sizes are the Material scale this app already uses, so nothing reflows; what
 * changes is the family, the weight and the digits.
 *
 * `tnum` is the tabular-figures feature. Swift reaches it through
 * `.monospacedDigit()`, applied at eight separate sites; baking it into the
 * figure styles is the same intent and harder to forget.
 */
object LibraType {
    // Displayed numbers: rounded, medium, tabular.
    val figureSmall: TextStyle
        @Composable @ReadOnlyComposable get() = rounded(MaterialTheme.typography.labelSmall)

    val figure: TextStyle
        @Composable @ReadOnlyComposable get() = rounded(MaterialTheme.typography.bodySmall)

    val figureEmphasis: TextStyle
        @Composable @ReadOnlyComposable get() = rounded(MaterialTheme.typography.bodyMedium)

    val figureTitle: TextStyle
        @Composable @ReadOnlyComposable get() = rounded(MaterialTheme.typography.titleMedium)

    val figureLarge: TextStyle
        @Composable @ReadOnlyComposable get() = rounded(MaterialTheme.typography.titleLarge)

    /** The one big number at the top of a security. Swift's `.largeTitle`. */
    val figureHero: TextStyle
        @Composable @ReadOnlyComposable get() = rounded(MaterialTheme.typography.headlineMedium)

    // Raw data: tickers, form types, tags, formulas.
    val codeSmall: TextStyle
        @Composable @ReadOnlyComposable get() = mono(MaterialTheme.typography.labelSmall)

    val codeSmallEmphasis: TextStyle
        @Composable @ReadOnlyComposable
        get() = mono(MaterialTheme.typography.labelSmall, FontWeight.SemiBold)

    val code: TextStyle
        @Composable @ReadOnlyComposable get() = mono(MaterialTheme.typography.bodySmall)

    /** A ticker at row size. */
    val tickerSmall: TextStyle
        @Composable @ReadOnlyComposable
        get() = mono(MaterialTheme.typography.bodySmall, FontWeight.SemiBold)

    /** A ticker where it is the subject of the row. */
    val ticker: TextStyle
        @Composable @ReadOnlyComposable
        get() = mono(MaterialTheme.typography.bodyMedium, FontWeight.SemiBold)
}

@Composable
@ReadOnlyComposable
private fun rounded(base: TextStyle): TextStyle = base.copy(
    fontFamily = LocalLibraFonts.current.rounded,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
)

@Composable
@ReadOnlyComposable
private fun mono(base: TextStyle, weight: FontWeight? = null): TextStyle = base.copy(
    fontFamily = LocalLibraFonts.current.mono,
    fontWeight = weight ?: base.fontWeight,
)

/** Shorthand for the semantic colours, so views read `LibraTheme.colors.caution`. */
object LibraTheme {
    val colors: LibraColors
        @Composable @ReadOnlyComposable
        get() = LocalLibraColors.current
}

@Composable
fun LibraTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val fonts = libraFontFamilies()
    CompositionLocalProvider(
        LocalLibraColors provides if (useDarkTheme) darkColors else lightColors,
        LocalLibraFonts provides fonts,
    ) {
        MaterialTheme(
            colorScheme = if (useDarkTheme) libraDarkScheme else libraLightScheme,
            typography = libraTypography(fonts.text),
            content = content,
        )
    }
}
