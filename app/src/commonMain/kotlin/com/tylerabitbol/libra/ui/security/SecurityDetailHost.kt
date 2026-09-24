package com.tylerabitbol.libra.ui.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.tylerabitbol.libra.app.AppEnvironment
import com.tylerabitbol.libra.ui.components.PushedScreen
import com.tylerabitbol.libra.ui.components.Refreshable
import com.tylerabitbol.libra.ui.settings.collectAsStateValue
import com.tylerabitbol.libra.viewmodels.SecurityDetailViewModel

/** Binds one security's page to its view model. */
@Composable
fun SecurityDetailHost(
    symbol: String,
    environment: AppEnvironment,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val model = remember(symbol) { SecurityDetailViewModel(symbol, scope) }
    val state = model.state.collectAsStateValue()
    val registry = environment.registry.collectAsStateValue()
    val snapshots = environment.snapshots.collectAsStateValue()

    // `snapshots` is a key, not just an argument. The store attaches after
    // launch, so a page composed before that — which is what `-LibraOpenSymbol`
    // does — would otherwise load once against a null store and never look
    // again: no stored history, no last visit, and "What changed" reading
    // "Nothing unusual in this window" for a security that has changed.
    // `ResearchHost` and `ScreenerHost` already key on it; this one did not.
    LaunchedEffect(symbol, registry, snapshots) { model.load(registry, snapshots) }

    // Swift's `.navigationTitle(model.symbol)`.
    PushedScreen(title = symbol, onBack = onBack, modifier = modifier) {
        Refreshable(onRefresh = { model.refresh(registry, snapshots) }) {
            SecurityDetailScreen(
                state = state,
                isUsingSampleData = environment.isUsingSampleData,
                onSelectRange = { model.select(it, registry, snapshots) },
                onSetChangeWindow = model::setChangeWindow,
                onSetKindFilter = model::setKindFilter,
            )
        }
    }
}
