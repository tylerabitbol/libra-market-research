package com.tylerabitbol.libra.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow

/**
 * A `StateFlow`'s current value, recomposing when it changes.
 *
 * Shorthand for `collectAsState().value`, which reads badly in the middle of a
 * layout and appears on every screen in this app.
 */
@Composable
internal fun <T> StateFlow<T>.collectAsStateValue(): T {
    val value by collectAsState()
    return value
}
