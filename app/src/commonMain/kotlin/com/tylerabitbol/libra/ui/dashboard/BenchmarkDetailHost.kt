package com.tylerabitbol.libra.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.tylerabitbol.libra.app.AppEnvironment
import com.tylerabitbol.libra.models.core.Benchmark
import com.tylerabitbol.libra.ui.components.PushedScreen
import com.tylerabitbol.libra.ui.settings.collectAsStateValue
import com.tylerabitbol.libra.viewmodels.BenchmarkDetailViewModel

/** Binds one benchmark's page to its view model. */
@Composable
fun BenchmarkDetailHost(
    benchmark: Benchmark,
    environment: AppEnvironment,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val model = remember(benchmark) { BenchmarkDetailViewModel(benchmark, scope) }
    val state = model.state.collectAsStateValue()
    val registry = environment.registry.collectAsStateValue()
    val snapshots = environment.snapshots.collectAsStateValue()

    // Keyed on `snapshots` for the same reason `SecurityDetailHost` is: the
    // store attaches after launch, and a page composed before that would load
    // once against a null store and never look again.
    LaunchedEffect(benchmark, registry, snapshots) {
        model.load(registry, snapshots)
    }

    // Swift's `.navigationTitle(model.benchmark.displayName)`.
    PushedScreen(title = benchmark.displayName, onBack = onBack, modifier = modifier) {
        BenchmarkDetailScreen(
            state = state,
            onSelectRange = { model.select(it, registry, snapshots) },
        )
    }
}
