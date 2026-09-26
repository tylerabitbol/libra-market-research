package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tylerabitbol.libra.ui.LibraSpacing

/**
 * A tab's title, with its controls on the same line.
 *
 * Every Swift tab carries a `.navigationTitle` drawn as a large title, with its
 * toolbar items above it. The shell has no navigation bar, so each tab draws
 * this instead — one component, so the five titles sit at the same size and
 * the same distance from the content below, which three hand-rolled headers
 * and two missing ones did not.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Tall enough for a text button, so a tab with actions and a tab
            // without them put their first row at the same height.
            .heightIn(min = 52.dp)
            .padding(start = LibraSpacing.large, end = LibraSpacing.small, top = LibraSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(LibraSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            // UIKit's large title: 34 bold. Compose's type scale has no slot
            // at that size and weight, so it is spelled out here, once.
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 34.sp,
                lineHeight = 41.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        actions()
    }
}
