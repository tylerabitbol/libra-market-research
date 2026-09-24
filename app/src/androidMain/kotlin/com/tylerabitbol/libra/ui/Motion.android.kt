package com.tylerabitbol.libra.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun prefersReducedMotion(): Boolean =
    Settings.Global.getFloat(
        LocalContext.current.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
