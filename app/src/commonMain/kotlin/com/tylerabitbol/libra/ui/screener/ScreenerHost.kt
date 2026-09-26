package com.tylerabitbol.libra.ui.screener

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.tylerabitbol.libra.app.AppEnvironment
import com.tylerabitbol.libra.ui.settings.collectAsStateValue
import com.tylerabitbol.libra.viewmodels.ScreenerViewModel

/** Binds the screener to its view model. */
@Composable
fun ScreenerHost(
    environment: AppEnvironment,
    onOpenSecurity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The real preference store, so saved screens survive a relaunch. Tests
    // construct the view model themselves with the in-memory one.
    val model = remember { ScreenerViewModel(environment.preferences) }
    val state = model.state.collectAsStateValue()
    val snapshots = environment.snapshots.collectAsStateValue()

    LaunchedEffect(snapshots) { model.load(snapshots) }

    ScreenerScreen(
        state = state,
        onChangeScreen = model::setScreen,
        onAddRule = model::addRule,
        onRemoveRule = { model.removeRules(setOf(it)) },
        onSaveScreen = model::saveCurrentScreen,
        onNewScreen = model::newScreen,
        onApplySaved = model::apply,
        onDeleteSaved = model::delete,
        onOpenSecurity = onOpenSecurity,
        modifier = modifier,
    )
}
