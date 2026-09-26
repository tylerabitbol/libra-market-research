package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.Dp
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

/**
 * Where a `List` or `Form` row's content starts, and so where its separator
 * starts: UIKit's inset-grouped layout margin on a phone.
 */
val groupedRowInset = 16.dp

/**
 * The inset of the stacks the scroll screens draw by hand —
 * `Divider().padding(.leading, 12)` with `.padding(12)` rows. Tighter than a
 * `List`, deliberately: those stacks carry dense figures, not form rows.
 */
val groupedStackInset = 12.dp

/**
 * A `List` row's content insets: 16 at the sides, 11 above and below. The
 * vertical half is what makes a one-line row 44 tall and keeps multi-line
 * content off the separators, and it is what the port was missing when text
 * sat against the top and bottom of its cell.
 */
val groupedRowPadding = PaddingValues(horizontal = groupedRowInset, vertical = 11.dp)

/** A one-line row is never shorter than this. UIKit's minimum row height. */
val groupedRowMinHeight = 44.dp

/**
 * A grouped row's content, inset the way UIKit insets it.
 *
 * Applied to the content rather than the row so a full-bleed child — a swipe
 * panel — can sit outside it and still reach the card's edge.
 */
fun Modifier.groupedRowContent(): Modifier = this
    .fillMaxWidth()
    .heightIn(min = groupedRowMinHeight)
    .padding(groupedRowPadding)

@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    /** Where the separators start. Match the rows' own leading inset. */
    dividerInset: Dp = groupedRowInset,
    content: GroupedSectionScope.() -> Unit,
) {
    val rows = GroupedSectionScope().apply(content).rows

    Column(modifier.fillMaxWidth()) {
        if (header != null) SectionCaption(header, inset = dividerInset)

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
                if (index < rows.lastIndex) GroupedDivider(dividerInset)
            }
        }

        if (footer != null) GroupedFooter(footer, inset = dividerInset)
    }
}

/** The grey footnote under a group, aligned with the rows above it. */
@Composable
fun GroupedFooter(text: String, inset: Dp = groupedRowInset) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LibraTheme.colors.secondaryText,
        modifier = Modifier.padding(start = inset, end = inset, top = LibraSpacing.small),
    )
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
 * The fill goes on the row itself; the row's own content inset is
 * [groupedRowContent], applied separately so a full-bleed child — a swipe
 * background, a chart — can opt out of it.
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

/** The separator between two rows of a group. */
@Composable
fun GroupedDivider(inset: Dp = groupedRowInset) {
    HorizontalDivider(
        Modifier.padding(start = inset),
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
fun SectionCaption(title: String, inset: Dp = groupedRowInset) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = LibraTheme.colors.secondaryText,
        modifier = Modifier.padding(start = inset, end = inset, bottom = LibraSpacing.snug),
    )
}
