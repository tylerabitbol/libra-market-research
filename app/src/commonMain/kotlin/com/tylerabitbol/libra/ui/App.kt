package com.tylerabitbol.libra.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.tylerabitbol.libra.app.AppEnvironment

/**
 * The composition root, reached the way Swift reaches it: one instance, put
 * into the environment, read by every screen that needs a provider or the
 * store.
 *
 * Swift's `@Environment(AppEnvironment.self)` is a `staticCompositionLocalOf`
 * here. Static because it is assigned once at launch and never reassigned —
 * a changing local would recompose the entire tree for no reason.
 */
val LocalAppEnvironment = staticCompositionLocalOf<AppEnvironment> {
    error("No AppEnvironment provided. Wrap the tree in LibraApp.")
}

@Composable
fun App(
    environment: AppEnvironment,
    screens: LibraScreens = libraScreens(),
) {
    CompositionLocalProvider(LocalAppEnvironment provides environment) {
        LibraTheme {
            Surface(Modifier.fillMaxSize()) {
                LibraNavigation(screens)
            }
        }
    }
}
