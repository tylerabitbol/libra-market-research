package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Pull down to run [onRefresh], with the spinner held for exactly as long as
 * the suspend takes.
 *
 * The spinner's state lives here rather than in each host, so a screen only
 * says what refreshing means. [content] must scroll vertically for the pull
 * to reach it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    var isRefreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                try {
                    currentOnRefresh()
                } finally {
                    isRefreshing = false
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        content()
    }
}
