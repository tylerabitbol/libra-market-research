package com.tylerabitbol.libra.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tylerabitbol.libra.app.AppEnvironment
import com.tylerabitbol.libra.models.core.Benchmark
import com.tylerabitbol.libra.ui.settings.collectAsStateValue
import com.tylerabitbol.libra.viewmodels.DashboardViewModel

/**
 * Binds the dashboard to its view model.
 *
 * Keyed on whether the registry is still synthetic, exactly as Swift's
 * `.task(id:)` is: entering a key rebuilds the registry, and the screen must
 * reload against the real providers rather than keep showing sample numbers.
 */
@Composable
fun DashboardHost(
    environment: AppEnvironment,
    onOpenBenchmark: (Benchmark) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = viewModel { DashboardHolder() }.model
    val state = model.state.collectAsStateValue()
    val registry = environment.registry.collectAsStateValue()

    LaunchedEffect(registry.isUsingSampleData) {
        model.load(registry)
    }

    DashboardScreen(
        state = state,
        isUsingSampleData = environment.isUsingSampleData,
        onOpenBenchmark = onOpenBenchmark,
        onRefresh = { model.refresh(registry) },
        modifier = modifier,
    )
}

/**
 * Keeps the dashboard's model for as long as the tab's back stack lives.
 *
 * `remember` held it in the composition, and leaving the tab disposes that:
 * any load in flight was cancelled and coming back refetched all 21 requests
 * from empty. Navigation keeps an entry's `ViewModelStore` while a tab's state
 * is saved, so the rows, and a load still running, survive the switch.
 */
private class DashboardHolder : ViewModel() {
    val model = DashboardViewModel(viewModelScope)
}
