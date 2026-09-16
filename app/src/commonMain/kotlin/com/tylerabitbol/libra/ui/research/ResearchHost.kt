package com.tylerabitbol.libra.ui.research

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.tylerabitbol.libra.app.AppEnvironment
import com.tylerabitbol.libra.ui.settings.collectAsStateValue
import com.tylerabitbol.libra.viewmodels.ResearchViewModel
import kotlinx.coroutines.launch

/** Binds the research feed to its view model. */
@Composable
fun ResearchHost(
    environment: AppEnvironment,
    onOpenSecurity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val model = remember { ResearchViewModel() }
    val state = model.state.collectAsStateValue()
    val snapshots = environment.snapshots.collectAsStateValue()

    // Reloads when the store attaches, so the feed is not permanently empty for
    // anyone who reaches this tab before persistence finishes opening.
    LaunchedEffect(snapshots) { model.load(snapshots) }

    ResearchScreen(
        state = state,
        onSelectSort = model::setSort,
        onSelectCategory = model::setKindFilter,
        onOpenSecurity = onOpenSecurity,
        modifier = modifier,
    )
}
