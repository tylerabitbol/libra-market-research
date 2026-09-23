package com.tylerabitbol.libra.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/**
 * The three typefaces, and the three jobs they do.
 *
 * Libra uses all of SwiftUI's font designs and gives each one a job. Prose and
 * labels are the default face. *Displayed numbers* are rounded, medium weight,
 * with tabular digits. Monospace is kept for tickers, form types, formulas and
 * anything else that is raw data rather than a reading.
 *
 * The port had collapsed all three into monospace, including the figures, which
 * is the one case Libra never uses it for.
 */
@Immutable
data class LibraFontFamilies(
    /** Labels and prose. SF Pro Text. */
    val text: FontFamily,
    /** Displayed numbers. SF Pro Rounded. */
    val rounded: FontFamily,
    /** Tickers, form types, formulas. SF Mono. */
    val mono: FontFamily,
)

/**
 * The platform's answer to those three.
 *
 * Composable because a bundled font resolves in composition, even though the
 * iOS implementation does not need to.
 */
@Composable
expect fun libraFontFamilies(): LibraFontFamilies

/**
 * The fallback is deliberately the platform's own: if a face cannot be
 * resolved, text still renders, in the wrong face rather than not at all.
 */
val LocalLibraFonts = staticCompositionLocalOf {
    LibraFontFamilies(
        text = FontFamily.Default,
        rounded = FontFamily.Default,
        mono = FontFamily.Monospace,
    )
}

/**
 * The attribution the bundled faces call for, or null where nothing is bundled.
 *
 * iOS draws SF from the system and has nothing to credit.
 */
expect val bundledFontCredit: String?
