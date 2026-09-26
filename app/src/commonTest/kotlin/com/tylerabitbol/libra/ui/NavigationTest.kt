package com.tylerabitbol.libra.ui

import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * That each tab keeps its own back stack.
 *
 * No Swift counterpart, and none is possible: Swift gets this from one
 * `NavigationStack` per tab and there is nothing to assert. Here it is a
 * property of how the graph is built, and the graph was flat — five tab routes
 * and three pushed routes as siblings — while `switchTo`'s comment claimed the
 * behaviour this suite checks.
 *
 * The screens are stand-ins. `LibraScreens` is injectable for exactly this, so
 * none of the real screens, view models or providers are involved.
 */
@OptIn(ExperimentalTestApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
class NavigationTest {

    private fun stubScreens() = LibraScreens(
        dashboard = { Text("dashboard-root") },
        watchlist = { nav ->
            Text(
                "watchlist-root",
                Modifier.clickable { nav.navigate(SecurityDetailRoute("AAPL")) },
            )
        },
        research = { Text("research-root") },
        screener = { nav ->
            Text(
                "screener-root",
                Modifier.clickable { nav.navigate(SecurityDetailRoute("NVDA")) },
            )
        },
        settings = { Text("settings-root") },
        securityDetail = { symbol, _ -> Text("security-$symbol") },
        benchmarkDetail = { id, _ -> Text("benchmark-$id") },
        secretEntry = { route, _ -> Text("secret-${route.key}") },
    )

    @Test
    fun aSecurityOpenedFromTheWatchlistSurvivesATripToTheDashboard() = runComposeUiTest {
        setContent { LibraTheme { LibraNavigation(screens = stubScreens()) } }

        onNodeWithText("Watchlist").performClick()
        onNodeWithText("watchlist-root").performClick()
        onNodeWithText("security-AAPL").assertIsDisplayed()

        onNodeWithText("Dashboard").performClick()
        onNodeWithText("dashboard-root").assertIsDisplayed()

        // The whole point. A flat graph brings back the Watchlist's root here,
        // because there is no Watchlist stack for the security to have been
        // saved on.
        onNodeWithText("Watchlist").performClick()
        onNodeWithText("security-AAPL").assertIsDisplayed()
    }

    @Test
    fun theScreenersOwnStackIsKeptSeparatelyFromTheWatchlists() = runComposeUiTest {
        setContent { LibraTheme { LibraNavigation(screens = stubScreens()) } }

        onNodeWithText("Watchlist").performClick()
        onNodeWithText("watchlist-root").performClick()
        onNodeWithText("security-AAPL").assertIsDisplayed()

        onNodeWithText("Screener").performClick()
        onNodeWithText("screener-root").performClick()
        onNodeWithText("security-NVDA").assertIsDisplayed()

        // Two securities open at once, one per section, each on its own stack.
        onNodeWithText("Watchlist").performClick()
        onNodeWithText("security-AAPL").assertIsDisplayed()
    }

    @Test
    fun aTabReturnsToItsRootWhenNothingWasPushed() = runComposeUiTest {
        setContent { LibraTheme { LibraNavigation(screens = stubScreens()) } }

        onNodeWithText("Research").performClick()
        onNodeWithText("research-root").assertIsDisplayed()

        onNodeWithText("Settings").performClick()
        onNodeWithText("settings-root").assertIsDisplayed()

        onNodeWithText("Research").performClick()
        onNodeWithText("research-root").assertIsDisplayed()
    }
}
