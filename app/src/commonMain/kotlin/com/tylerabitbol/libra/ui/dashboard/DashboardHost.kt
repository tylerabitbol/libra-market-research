package com.tylerabitbol.libra.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
    val scope = rememberCoroutineScope()
    val model = remember { DashboardViewModel(scope) }
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
