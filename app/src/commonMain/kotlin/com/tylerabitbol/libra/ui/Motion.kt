package com.tylerabitbol.libra.ui

import androidx.compose.runtime.Composable

/**
 * Whether the person has asked the system for less motion: Reduce Motion on
 * iOS, animator duration scale 0 on Android.
 *
 * Read once per composition; the rolling figures, the range thumb's spring and
 * the placeholders' pulse all fall back to an immediate change when it is set.
 * Screen fades stay, as UIKit keeps its crossfades under Reduce Motion.
 */
@Composable
expect fun prefersReducedMotion(): Boolean
