package com.tylerabitbol.libra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The screen each route renders.
 *
 * Built here rather than inside `LibraNavigation` so navigation stays testable
 * with stand-ins. Entries are replaced one at a time as Phase 8 works through
 * the screen order in `PLAN.md §8`; anything still unported says so on screen
 * rather than rendering an empty page that looks like a bug.
 */
fun libraScreens(): LibraScreens = LibraScreens(
    dashboard = { UnportedScreen("Dashboard") },
    watchlist = { UnportedScreen("Watchlist") },
    research = { UnportedScreen("Research") },
    screener = { UnportedScreen("Screener") },
    settings = { UnportedScreen("Settings") },
    securityDetail = { symbol, _ -> UnportedScreen("Security detail — $symbol") },
    benchmarkDetail = { id, _ -> UnportedScreen("Benchmark detail — $id") },
)

/**
 * Scaffolding, and visibly so.
 *
 * Deliberately not a blank page: a screen that renders nothing is
 * indistinguishable from one that failed to load, and this phase spends most of
 * its length with some screens built and others not.
 */
@Composable
internal fun UnportedScreen(name: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(LibraSpacing.large),
        verticalArrangement = Arrangement.spacedBy(LibraSpacing.small, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(
            "Not ported yet.",
            style = MaterialTheme.typography.bodySmall,
            color = LibraTheme.colors.secondaryText,
        )
    }
}
