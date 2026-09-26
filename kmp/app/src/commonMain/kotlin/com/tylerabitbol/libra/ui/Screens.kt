package com.tylerabitbol.libra.ui

import com.tylerabitbol.libra.models.core.Benchmark
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.services.secrets.SecretKey
import com.tylerabitbol.libra.ui.research.ResearchHost
import com.tylerabitbol.libra.ui.screener.ScreenerHost
import com.tylerabitbol.libra.ui.security.SecurityDetailHost
import com.tylerabitbol.libra.ui.settings.SecretEntryScreen
import com.tylerabitbol.libra.ui.dashboard.BenchmarkDetailHost
import com.tylerabitbol.libra.ui.dashboard.DashboardHost
import com.tylerabitbol.libra.ui.settings.SettingsScreen
import com.tylerabitbol.libra.ui.watchlist.WatchlistHost

/**
 * The screen each route renders.
 *
 * Built here rather than inside `LibraNavigation` so navigation stays testable
 * with stand-ins. Every route in `PLAN.md §8` is ported; the placeholder this
 * file carried while the screens were being built is gone.
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
    research = { navController ->
        ResearchHost(
            environment = LocalAppEnvironment.current,
            onOpenSecurity = { navController.navigate(SecurityDetailRoute(it)) },
        )
    },
    screener = { navController ->
        ScreenerHost(
            environment = LocalAppEnvironment.current,
            onOpenSecurity = { navController.navigate(SecurityDetailRoute(it)) },
        )
    },
    settings = { navController ->
        SettingsScreen(
            environment = LocalAppEnvironment.current,
            onOpenKey = { key, provider ->
                navController.navigate(SecretEntryRoute(key.raw, provider.raw))
            },
        )
    },
    securityDetail = { symbol, navController ->
        SecurityDetailHost(
            symbol = symbol,
            environment = LocalAppEnvironment.current,
            onBack = { navController.popBackStack() },
        )
    },
    benchmarkDetail = { id, navController ->
        // Same reasoning as `secretEntry`: the route carries the stable id, not
        // the value, so a restored back stack cannot resurrect a benchmark the
        // catalog has since dropped.
        val benchmark = Benchmark.all.firstOrNull { it.id == id }
        if (benchmark == null) {
            navController.popBackStack()
        } else {
            BenchmarkDetailHost(
                benchmark = benchmark,
                environment = LocalAppEnvironment.current,
                onBack = { navController.popBackStack() },
            )
        }
    },
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
                onBack = { navController.popBackStack() },
            )
        }
    },
)
