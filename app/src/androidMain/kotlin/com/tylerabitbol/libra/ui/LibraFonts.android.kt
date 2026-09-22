package com.tylerabitbol.libra.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * Android has no equivalent of SF Pro Rounded, and SF Pro cannot be shipped in
 * an APK, so matching Libra here means bundling metric-similar open faces —
 * Inter, Nunito Sans and JetBrains Mono.
 *
 * Until those files are in the tree this returns the platform's own, which is
 * Roboto for text and rounded alike. Figures are then the right *weight* and
 * tabular, but not rounded.
 */
@Composable
actual fun libraFontFamilies(): LibraFontFamilies = LibraFontFamilies(
    text = FontFamily.Default,
    rounded = FontFamily.Default,
    mono = FontFamily.Monospace,
)
