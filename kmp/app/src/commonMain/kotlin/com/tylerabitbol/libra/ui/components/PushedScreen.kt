package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.tylerabitbol.libra.ui.LibraIcons

/**
 * A screen reached by a push rather than by the tab bar.
 *
 * Swift gets this from `NavigationStack`: a title bar, a back chevron, and an
 * edge-swipe. Compose Multiplatform gives none of the three, and the shell is a
 * bottom `NavigationBar` with no top bar at all — so before this existed, a
 * security page could only be left by tapping a tab, which reset that tab.
 * Android's system back hid the problem; iOS has no gesture to hide it with.
 *
 * The bar belongs to the screen, not to the shell's `Scaffold`. Putting it in
 * the shell would draw a second title above the five tab roots, which already
 * draw their own header rows — the toolbar-becomes-a-header-row decision under
 * `KNOWN_ISSUES.md` → UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushedScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(LibraIcons.Back, contentDescription = "Back")
                }
            },
            // Zero, deliberately. `LibraNavigation`'s Scaffold consumes
            // `WindowInsets.safeDrawing` before any of this composes, so a bar
            // that added the status-bar inset again would push itself down by
            // the height of the notch.
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}
