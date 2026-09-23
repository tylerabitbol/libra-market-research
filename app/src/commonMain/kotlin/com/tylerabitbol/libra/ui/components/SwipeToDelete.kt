package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.ui.LibraTheme

/**
 * A row that can also be swiped away, the way `.swipeActions` lets a SwiftUI
 * list row be swiped away.
 *
 * The port replaced the gesture with a visible Remove button, and that button
 * stays: a swipe that nothing announces is not discoverable, and it is
 * unreachable to anyone driving the screen with a keyboard or a screen reader.
 * Swift has both too — `.swipeActions` in addition to whatever the row draws.
 * So this adds the gesture back beside the control rather than in place of it.
 *
 * [rowKey] identifies the row. The dismiss state has to be scoped to it: a
 * `LazyColumn` reuses composables across items, so without the key a row that
 * slid away would hand its half-dismissed state to whichever row took its
 * place in the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(
    rowKey: Any,
    label: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    key(rowKey) {
        val state = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    onDelete()
                    true
                } else {
                    false
                }
            },
        )

        SwipeToDismissBox(
            state = state,
            modifier = modifier,
            // One direction only. A row that can be thrown off either edge is
            // twice as easy to lose to a stray horizontal drag on a screen
            // that scrolls vertically.
            enableDismissFromStartToEnd = false,
            backgroundContent = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(LibraTheme.colors.negative)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            },
        ) {
            // Opaque, so the red panel behind it is revealed by the swipe
            // rather than showing through the row. The card's fill, because
            // every swipeable row sits in a grouped card — the page colour
            // here drew each one as a grey stripe across a white group.
            Box(Modifier.background(LibraTheme.colors.cardFill)) { content() }
        }
    }
}
