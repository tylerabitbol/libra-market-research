package com.tylerabitbol.libra.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tylerabitbol.libra.ui.LibraTheme

/**
 * The app's section bar, drawn the way UITabBar is: a hairline on top, the
 * card fill behind, and the selected section told by colour alone.
 *
 * Replaces Material's navigation bar, whose 80dp height and pill behind the
 * selected icon were the loudest Android tell on an iPhone. The bar still
 * sits above the home indicator's inset rather than under it.
 */
@Composable
fun TabBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(LibraTheme.colors.cardFill)) {
        HorizontalDivider(thickness = 0.5.dp, color = LibraTheme.colors.separator)
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(TabBarHeight)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            content = content,
        )
    }
}

/** One section in a [TabBar]: an icon over a small label. */
@Composable
fun RowScope.TabBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else LibraTheme.colors.secondaryText,
        animationSpec = tween(TintMillis),
        label = "tab tint",
    )
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private val TabBarHeight = 50.dp
private const val TintMillis = 150
