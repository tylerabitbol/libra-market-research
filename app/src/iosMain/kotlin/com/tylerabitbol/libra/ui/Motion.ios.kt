package com.tylerabitbol.libra.ui

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
actual fun prefersReducedMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
