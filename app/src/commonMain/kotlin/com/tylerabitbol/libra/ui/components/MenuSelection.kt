package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.ui.LibraIcons

/**
 * The selected row of a menu, marked the way SwiftUI marks it.
 *
 * A `Picker` inside a `Menu`, and `Label(…, systemImage: "checkmark")`, both
 * draw a checkmark beside the current choice. `DropdownMenu` has neither, so
 * the port prefixed its labels with `"✓ "` and padded the rest with three
 * spaces to keep them aligned — legible, but a glyph pretending to be an icon,
 * and three spaces pretending to be layout.
 *
 * `leadingIcon` is the real slot. The unselected case still occupies it, with a
 * spacer the same size, because a leading icon that appears and disappears
 * shifts every label in the menu as the selection moves.
 */
@Composable
fun menuCheckmark(isSelected: Boolean): @Composable () -> Unit = {
    if (isSelected) {
        Icon(
            LibraIcons.Check,
            // Named rather than null: this is the only thing distinguishing
            // the current choice, so a screen reader has to hear it.
            contentDescription = "Selected",
            modifier = Modifier.size(SIZE),
        )
    } else {
        Spacer(Modifier.size(SIZE))
    }
}

private val SIZE = 18.dp
