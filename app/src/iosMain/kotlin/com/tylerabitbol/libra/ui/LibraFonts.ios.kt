package com.tylerabitbol.libra.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/**
 * All three faces come from the system, so nothing is bundled on iOS.
 *
 * `FontFamily.Default` and `FontFamily.Monospace` already resolve to SF Pro and
 * SF Mono. Rounded is the one that needs asking for by name, and the name is
 * not the one Apple documents: `SF Pro Rounded` and `.SF UI Rounded` both miss.
 * What CoreText answers to — and therefore what Skia answers to — is
 * `.AppleSystemUIFontRounded`. The leading dot marks it private, which is why
 * it does not appear in a font-family listing; matching it by name still works
 * and is what `.system(design: .rounded)` does underneath.
 *
 * Resolved once: the match walks the system font tables, and the families do
 * not change while the app is running.
 */
private const val ROUNDED = ".AppleSystemUIFontRounded"

private val resolved: LibraFontFamilies by lazy {
    val rounded = FontMgr.default.matchFamilyStyle(ROUNDED, FontStyle.NORMAL)
    LibraFontFamilies(
        text = FontFamily.Default,
        // If the private name ever stops matching, figures render in the
        // default face rather than disappearing.
        rounded = rounded?.let { FontFamily(Typeface(it)) } ?: FontFamily.Default,
        mono = FontFamily.Monospace,
    )
}

@Composable
actual fun libraFontFamilies(): LibraFontFamilies = resolved
