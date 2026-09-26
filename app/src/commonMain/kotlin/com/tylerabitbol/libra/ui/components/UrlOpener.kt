package com.tylerabitbol.libra.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How a source link is opened.
 *
 * SwiftUI's `Link` needs nothing: the platform handles it. Compose has no
 * multiplatform equivalent, and the two shells differ — an `Intent` on Android,
 * `UIApplication.openURL` on iOS — so the capability is provided at the root
 * and the cards stay free of platform code. The default does nothing, which is
 * the right behaviour in a test and a preview.
 */
val LocalUrlOpener = staticCompositionLocalOf<(String) -> Unit> { {} }
