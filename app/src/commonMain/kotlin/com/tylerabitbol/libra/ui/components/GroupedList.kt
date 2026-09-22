package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.ui.LibraShapes
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme

/**
 * The inset-grouped list, rebuilt.
 *
 * Swift never sets `.listStyle(`: `Form` and `List` default to inset-grouped on
 * iOS, and the scroll-based screens hand-roll the same look — rows flush
 * against each other inside a rounded fill, separated by a divider inset from
 * the leading edge, with a small-caps header above and a grey footnote below.
 * Compose gives none of that, so it is written out here once rather than
 * approximated differently on each screen.
 *
 * Two shapes, because Compose lists come in two shapes. A static group takes
 * its rows as a builder and knows how many there are, so it can put a divider
 * between them and round only the outer corners. A lazy list does not, so it
 * gets [groupedRow] instead, which each row applies for itself from its own
 * position.
 */

/** The leading inset on a divider. Swift's `Divider().padding(.leading, 12)`. */
private val dividerInset = 12.dp

@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: GroupedSectionScope.() -> Unit,
) {
    val rows = GroupedSectionScope().apply(content).rows

    Column(modifier.fillMaxWidth()) {
        if (header != null) SectionCaption(header)

        Column(
            Modifier
                .fillMaxWidth()
                .clip(LibraShapes.group)
                .background(LibraTheme.colors.cardFill),
        ) {
            for ((index, row) in rows.withIndex()) {
                row()
                // Between rows, never after the last one: a divider on the
                // bottom edge of a group reads as the group being cut off.
                if (index < rows.lastIndex) GroupedDivider()
            }
        }

        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
                modifier = Modifier.padding(
                    start = dividerInset,
                    end = dividerInset,
                    top = LibraSpacing.small,
                ),
            )
        }
    }
}

/** Collects a section's rows so the section can see how many there are. */
class GroupedSectionScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/**
 * A row's share of the group fill, for a list that does not know its own
 * length up front.
 *
 * Only the outer corners are rounded, so consecutive rows read as one card.
 * The fill goes on the row itself; the row's own content inset is [groupedRowPadding],
 * applied separately so a full-bleed child — a swipe background, a chart — can
 * opt out of it.
 */
@Composable
fun Modifier.groupedRow(isFirst: Boolean, isLast: Boolean): Modifier {
    val radius = 10.dp
    return this
        .fillMaxWidth()
        .clip(
            RoundedCornerShape(
                topStart = if (isFirst) radius else 0.dp,
                topEnd = if (isFirst) radius else 0.dp,
                bottomStart = if (isLast) radius else 0.dp,
                bottomEnd = if (isLast) radius else 0.dp,
            ),
        )
        .background(LibraTheme.colors.cardFill)
}

/**
 * The inset a grouped row's content sits at.
 *
 * Separate from [groupedRow] so that a child which must reach the card's edge
 * can skip it.
 */
val groupedRowPadding = dividerInset

/** The separator between two rows of a group. */
@Composable
fun GroupedDivider() {
    HorizontalDivider(
        Modifier.padding(start = dividerInset),
        color = LibraTheme.colors.separator,
    )
}

/**
 * A group's heading.
 *
 * Small, upper-cased and secondary, sitting outside the card — the grouped-list
 * header, not a title.
 */
@Composable
fun SectionCaption(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = LibraTheme.colors.secondaryText,
        modifier = Modifier.padding(
            start = dividerInset,
            end = dividerInset,
            bottom = LibraSpacing.snug,
        ),
    )
}
