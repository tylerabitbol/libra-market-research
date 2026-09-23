package com.tylerabitbol.libra.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.ui.LibraTheme

/**
 * The chart's range control: a track with a thumb that slides to the chosen
 * range, as UIKit's segmented control does.
 *
 * Replaces Material's outlined segmented buttons, whose per-segment borders
 * and check glyph were the most dated thing on either chart card.
 */
@Composable
fun RangePicker(
    ranges: List<ChartRange>,
    selected: ChartRange,
    onSelect: (ChartRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ranges.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val index = ranges.indexOf(selected).coerceAtLeast(0)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(TrackShape)
            .background(LibraTheme.colors.quaternaryFill)
            .padding(2.dp),
    ) {
        val segment = maxWidth / ranges.size
        val offset by animateDpAsState(
            targetValue = segment * index,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 600f),
            label = "range thumb",
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(ThumbShape)
                .background(LibraTheme.colors.cardFill)
                .border(0.5.dp, LibraTheme.colors.separator, ThumbShape),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
            for (range in ranges) {
                val isSelected = range == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (!isSelected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(range)
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        range.raw,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            LibraTheme.colors.secondaryText
                        },
                    )
                }
            }
        }
    }
}

/** How a range is named in the chart's summary: "Past month", not "1M". */
val ChartRange.periodLabel: String
    get() = when (this) {
        ChartRange.OneDay -> "Latest session, from the open"
        ChartRange.FiveDay -> "Past 5 sessions"
        ChartRange.OneMonth -> "Past month"
        ChartRange.ThreeMonth -> "Past 3 months"
        ChartRange.SixMonth -> "Past 6 months"
        ChartRange.OneYear -> "Past year"
        ChartRange.FiveYear -> "Past 5 years"
    }

private val TrackShape = RoundedCornerShape(9.dp)
private val ThumbShape = RoundedCornerShape(7.dp)
