package com.tylerabitbol.libra.ui.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tylerabitbol.libra.app.AppEnvironment
import com.tylerabitbol.libra.persistence.WatchlistMember
import com.tylerabitbol.libra.ui.settings.collectAsStateValue
import com.tylerabitbol.libra.viewmodels.SymbolSearchViewModel
import com.tylerabitbol.libra.viewmodels.WatchlistViewModel
import kotlinx.coroutines.launch

/**
 * Binds the watchlist screen to its view model and to membership on disk.
 *
 * Swift's view reads `@Query` and writes through `modelContext`, so its view
 * and its storage are the same object. Here the membership read is an explicit
 * DAO call held in state, re-run whenever the list changes — which is also what
 * makes "the row is gone" testable without a running database.
 */
@Composable
fun WatchlistHost(
    environment: AppEnvironment,
    onOpenSecurity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val holder = viewModel { WatchlistHolder() }
    val model = holder.model
    val search = remember { SymbolSearchViewModel(scope) }
    val state = model.state.collectAsStateValue()
    val searchState = search.state.collectAsStateValue()
    val watchlist = environment.watchlist.collectAsStateValue()
    val snapshots = environment.snapshots.collectAsStateValue()
    val registry = environment.registry.collectAsStateValue()

    var members by holder.members
    var isAdding by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // Bumped by every membership write, so the read below re-runs without the
    // screen having to know what changed.
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(watchlist, revision) {
        members = watchlist?.members().orEmpty()
    }

    // `snapshots` is a key: the store attaches after launch, and a list loaded
    // against a null store never read its stored prices, events or sparklines.
    // The security page had the same defect and was fixed the same way.
    LaunchedEffect(members, registry, snapshots) {
        model.load(members, registry, snapshots)
    }

    var isRefreshing by remember { mutableStateOf(false) }

    WatchlistScreen(
        state = state,
        isUsingSampleData = environment.isUsingSampleData,
        onSelectSort = model::setSort,
        onOpenSecurity = onOpenSecurity,
        onAddSecurity = { isAdding = true },
        onRemove = { symbol ->
            scope.launch {
                try {
                    watchlist?.remove(symbol)
                    revision++
                } catch (error: Exception) {
                    saveError = error.message ?: "Could not remove $symbol."
                }
            }
        },
        saveError = saveError,
        onDismissError = { saveError = null },
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                try {
                    model.refresh(members, registry, snapshots)
                } finally {
                    isRefreshing = false
                }
            }
        },
        onQuickAdd = { profile ->
            scope.launch {
                try {
                    watchlist?.add(profile, members.size)
                    revision++
                } catch (error: Exception) {
                    saveError = error.message ?: "Could not add ${profile.symbol}."
                }
            }
        },
        modifier = modifier,
    )

    if (isAdding) {
        AddSymbolSheet(
            state = searchState,
            onQueryChange = { search.search(it, registry) },
            onSelect = { profile ->
                scope.launch {
                    try {
                        watchlist?.add(profile, members.size)
                        revision++
                    } catch (error: Exception) {
                        saveError = error.message ?: "Could not add ${profile.symbol}."
                    }
                    isAdding = false
                }
            },
            onDismiss = { isAdding = false },
        )
    }
}

/**
 * Keeps the watchlist's model, and the membership it was loaded with, for as
 * long as the tab's back stack lives. Held by `remember`, both were rebuilt on
 * every return to the tab, and the list reloaded from an empty membership.
 */
private class WatchlistHolder : ViewModel() {
    val model = WatchlistViewModel(viewModelScope)
    val members = mutableStateOf(emptyList<WatchlistMember>())
}
