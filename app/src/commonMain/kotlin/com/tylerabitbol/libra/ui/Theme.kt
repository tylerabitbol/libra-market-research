package com.tylerabitbol.libra.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/// Placeholder theme. Phase 8 replaces this with the real palette; for now it
/// only has to be dense, text-first and legible in both schemes.
@Composable
fun LibraTheme(useDarkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
