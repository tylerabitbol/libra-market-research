package com.tylerabitbol.libra.ui

import androidx.compose.foundation.isSystemInDarkTheme
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
 * The Swift app has no theme file: it leans on SwiftUI's semantic styles, and
 * `.secondary`, `.tertiary` and `.orange` between them account for nearly every
 * colour on screen. Material 3 has the first two as `onSurfaceVariant` and a
 * dimmer variant of it, and nothing at all for the third — so the semantics the
 * views actually use are named here rather than left as literals sprinkled
 * through twenty files.
 *
 * Dynamic colour is deliberately off. A figure's colour carries meaning in this
 * app — a caution is orange because it is a caution — and letting the wallpaper
 * choose would make "is this a warning?" a question about the device.
 */
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
)

private val lightColors = LibraColors(
    secondaryText = Color(0xFF5A5F66),
    tertiaryText = Color(0xFF8A9099),
    caution = Color(0xFFB8600A),
    positive = Color(0xFF1B7A3D),
    negative = Color(0xFFB3261E),
)

private val darkColors = LibraColors(
    secondaryText = Color(0xFFB3B8C0),
    tertiaryText = Color(0xFF80868F),
    caution = Color(0xFFE59A4C),
    positive = Color(0xFF5FD48A),
    negative = Color(0xFFF2B8B5),
)

val LocalLibraColors = staticCompositionLocalOf { lightColors }

/**
 * Dense, text-first type.
 *
 * Swift's views are overwhelmingly `.caption` and `.caption2` — 110 of about
 * 180 font calls — because the screens are tables of figures rather than
 * articles. Material's defaults are set for reading prose, so the body and
 * label scales are pulled in to match. Everything is in `sp`, so the type
 * scales with the reader's setting.
 */
private val libraTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelMedium = labelMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
    )
}

/**
 * Spacing, named once.
 *
 * Four steps, because the Swift app only ever uses four. A fifth would be a
 * decision nobody made.
 */
object LibraSpacing {
    val tight = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
}

/** Figures are monospaced so columns of numbers line up rather than shimmer. */
object LibraType {
    val figure: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    val figureEmphasis: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
}

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
    CompositionLocalProvider(LocalLibraColors provides if (useDarkTheme) darkColors else lightColors) {
        MaterialTheme(
            colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme(),
            typography = libraTypography,
            content = content,
        )
    }
}
