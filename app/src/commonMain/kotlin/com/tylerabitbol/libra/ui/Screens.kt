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
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.services.secrets.SecretKey
import com.tylerabitbol.libra.ui.settings.SecretEntryScreen
import com.tylerabitbol.libra.ui.dashboard.DashboardHost
import com.tylerabitbol.libra.ui.settings.SettingsScreen
import com.tylerabitbol.libra.ui.watchlist.WatchlistHost

/**
 * The screen each route renders.
 *
 * Built here rather than inside `LibraNavigation` so navigation stays testable
 * with stand-ins. Entries are replaced one at a time as Phase 8 works through
 * the screen order in `PLAN.md §8`; anything still unported says so on screen
 * rather than rendering an empty page that looks like a bug.
 */
fun libraScreens(): LibraScreens = LibraScreens(
    dashboard = { navController ->
        DashboardHost(
            environment = LocalAppEnvironment.current,
            onOpenBenchmark = { navController.navigate(BenchmarkDetailRoute(it.id)) },
        )
    },
    watchlist = { navController ->
        WatchlistHost(
            environment = LocalAppEnvironment.current,
            onOpenSecurity = { navController.navigate(SecurityDetailRoute(it)) },
        )
    },
    research = { UnportedScreen("Research") },
    screener = { UnportedScreen("Screener") },
    settings = { navController ->
        SettingsScreen(
            environment = LocalAppEnvironment.current,
            onOpenKey = { key, provider ->
                navController.navigate(SecretEntryRoute(key.raw, provider.raw))
            },
        )
    },
    securityDetail = { symbol, _ -> UnportedScreen("Security detail — $symbol") },
    benchmarkDetail = { id, _ -> UnportedScreen("Benchmark detail — $id") },
    secretEntry = { route, navController ->
        val key = SecretKey.fromRaw(route.key)
        val provider = DataProviderID.fromRaw(route.provider)
        if (key == null || provider == null) {
            // Only reachable if a saved back stack outlives a rename, which is
            // exactly what `SecretKey`'s explicit storage names exist to make
            // survivable. Going back beats rendering an entry field for a key
            // the app no longer has.
            navController.popBackStack()
        } else {
            SecretEntryScreen(
                key = key,
                provider = provider,
                environment = LocalAppEnvironment.current,
                onSaved = { navController.popBackStack() },
            )
        }
    },
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
