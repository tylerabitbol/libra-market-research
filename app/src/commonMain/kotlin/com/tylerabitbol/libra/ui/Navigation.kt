package com.tylerabitbol.libra.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/**
 * The five product areas from Section 2.
 *
 * Watchlist leads because the spec says the primary experience revolves around
 * it; Dashboard sits first only because it answers "what changed while I was
 * away" at a market level.
 */
@Serializable object DashboardRoute
@Serializable object WatchlistRoute
@Serializable object ResearchRoute
@Serializable object ScreenerRoute
@Serializable object SettingsRoute

/**
 * Destinations reached from inside a section rather than from the bar.
 *
 * Swift pushes these onto the section's own `NavigationStack`, so opening a
 * security from the Watchlist, glancing at the Dashboard and coming back
 * returns to the security. Switching tabs here saves and restores each
 * section's stack for the same reason.
 */
@Serializable data class SecurityDetailRoute(val symbol: String)

@Serializable data class BenchmarkDetailRoute(val id: String)

/**
 * One credential's entry screen.
 *
 * Carries the key's storage name, not a value: nothing about a secret travels
 * through navigation, which is saved state the app does not control.
 */
@Serializable data class SecretEntryRoute(val key: String, val provider: String)

/**
 * One graph per tab, so each tab owns a back stack.
 *
 * Swift gives every tab its own `NavigationStack`. This was one flat `NavHost`
 * with the five tab routes and the three pushed routes as siblings, and
 * `switchTo` saving state against the app's single start destination — which
 * approximates the behaviour without giving each section a stack of its own.
 * Nesting is what makes the promise in `switchTo` true.
 *
 * A route belongs to every graph it can be reached from, which is why
 * `SecurityDetailRoute` appears in four of them. The route type is the same
 * either way; what differs is the graph it is pushed onto, and therefore the
 * stack it is restored with.
 */
@Serializable object DashboardGraph
@Serializable object WatchlistGraph
@Serializable object ResearchGraph
@Serializable object ScreenerGraph
@Serializable object SettingsGraph

/** A tab: its route, its title, and the glyph SF Symbols gave the Swift app. */
enum class AppSection(
    /** The section's graph, which is what the bar navigates to. */
    val graph: Any,
    /**
     * Matched against the current destination's hierarchy to light the tab.
     * The graph, not its start destination: a security pushed inside the
     * Watchlist's graph must keep the Watchlist tab selected, and its own route
     * is not the Watchlist's.
     */
    val graphClass: KClass<*>,
    val title: String,
    val icon: ImageVector,
) {
    Dashboard(DashboardGraph, DashboardGraph::class, "Dashboard", LibraIcons.Dashboard),
    Watchlist(WatchlistGraph, WatchlistGraph::class, "Watchlist", LibraIcons.Watchlist),
    Research(ResearchGraph, ResearchGraph::class, "Research", LibraIcons.Research),
    Screener(ScreenerGraph, ScreenerGraph::class, "Screener", LibraIcons.Screener),
    Settings(SettingsGraph, SettingsGraph::class, "Settings", LibraIcons.Settings),
}

@Composable
fun LibraNavigation(
    screens: LibraScreens,
    navController: NavHostController = rememberNavController(),
    openSymbol: String? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination

    // `-LibraOpenSymbol AAPL`, pushed once rather than made the start
    // destination, so the back gesture still lands on the Dashboard.
    LaunchedEffect(openSymbol) {
        if (openSymbol != null) navController.navigate(SecurityDetailRoute(openSymbol))
    }

    Scaffold(
        // No top bar, so nothing else would keep the first row of a screen out
        // from under the status bar and the iOS notch. The bottom bar consumes
        // its own inset and Scaffold subtracts it before padding the content.
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar {
                for (section in AppSection.entries) {
                    val selected = current?.hierarchy?.any {
                        @Suppress("UNCHECKED_CAST")
                        it.hasRoute(section.graphClass as KClass<Any>)
                    } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.switchTo(section) },
                        icon = { Icon(section.icon, contentDescription = null) },
                        label = { Text(section.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController, startDestination = DashboardGraph) {
                navigation<DashboardGraph>(startDestination = DashboardRoute) {
                    composable<DashboardRoute> { screens.dashboard(navController) }
                    composable<BenchmarkDetailRoute> { entry ->
                        screens.benchmarkDetail(
                            entry.toRoute<BenchmarkDetailRoute>().id,
                            navController,
                        )
                    }
                    // Only `-LibraOpenSymbol` pushes a security here; nothing on
                    // the Dashboard links to one. It is declared so the deep
                    // link still lands on the Dashboard's stack, which is what
                    // keeps its documented behaviour — back returns there.
                    securityDetail(screens, navController)
                }
                navigation<WatchlistGraph>(startDestination = WatchlistRoute) {
                    composable<WatchlistRoute> { screens.watchlist(navController) }
                    securityDetail(screens, navController)
                }
                navigation<ResearchGraph>(startDestination = ResearchRoute) {
                    composable<ResearchRoute> { screens.research(navController) }
                    securityDetail(screens, navController)
                }
                navigation<ScreenerGraph>(startDestination = ScreenerRoute) {
                    composable<ScreenerRoute> { screens.screener(navController) }
                    securityDetail(screens, navController)
                }
                navigation<SettingsGraph>(startDestination = SettingsRoute) {
                    composable<SettingsRoute> { screens.settings(navController) }
                    composable<SecretEntryRoute> { entry ->
                        screens.secretEntry(entry.toRoute<SecretEntryRoute>(), navController)
                    }
                }
            }
        }
    }
}

/**
 * Switching tabs keeps each section's stack.
 *
 * Without `saveState`/`restoreState` the bar would behave like five buttons
 * that reset the screen, which is not what a tab is.
 */
fun NavHostController.switchTo(section: AppSection) {
    navigate(section.graph) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * `SecurityDetailRoute`, declared into whichever graph is being built.
 *
 * Four graphs can reach a security, and each needs its own destination so the
 * push lands on that section's stack. The screen is the same one; only its
 * parent differs.
 */
private fun NavGraphBuilder.securityDetail(
    screens: LibraScreens,
    navController: NavHostController,
) {
    composable<SecurityDetailRoute> { entry ->
        screens.securityDetail(entry.toRoute<SecurityDetailRoute>().symbol, navController)
    }
}

/**
 * The screens, injected rather than referenced.
 *
 * Keeps this file free of every view in the app, so navigation can be exercised
 * with stand-ins and a screen can be built before the rest exist.
 */
data class LibraScreens(
    val dashboard: @Composable (NavHostController) -> Unit,
    val watchlist: @Composable (NavHostController) -> Unit,
    val research: @Composable (NavHostController) -> Unit,
    val screener: @Composable (NavHostController) -> Unit,
    val settings: @Composable (NavHostController) -> Unit,
    val securityDetail: @Composable (String, NavHostController) -> Unit,
    val benchmarkDetail: @Composable (String, NavHostController) -> Unit,
    val secretEntry: @Composable (SecretEntryRoute, NavHostController) -> Unit,
)
