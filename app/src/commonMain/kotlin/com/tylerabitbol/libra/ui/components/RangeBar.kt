package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType

/**
 * A low-to-high range as a track, with a marker where the last price sits.
 *
 * Replaces "$236.65 – $344.57" set in a figure style, which wrapped in a grid
 * cell and gave no sense of where in the range the price is. The ends are
 * still printed, so the bar adds position without taking a number away.
 *
 * With no [last], or a last outside the range (a quote newer than the range
 * it is drawn against), the track is drawn without a marker rather than with
 * one pinned to an end, which would claim a position the data does not give.
 */
@Composable
fun RangeBar(
    label: String,
    low: Double?,
    high: Double?,
    last: Double?,
    modifier: Modifier = Modifier,
) {
    val position = rangePosition(low, high, last)
    val description = if (low == null || high == null) {
        "$label: ${Format.notAvailable}"
    } else {
        "$label: ${Format.currency(low)} to ${Format.currency(high)}" +
            (if (position != null && last != null) ", last ${Format.currency(last)}" else "")
    }

    val track = LibraTheme.colors.quaternaryFill
    val marker = MaterialTheme.colorScheme.onSurface
    val ring = LibraTheme.colors.cardFill

    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = description
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.secondaryText,
        )
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val thickness = 4.dp.toPx()
            val top = (size.height - thickness) / 2
            drawRoundRect(
                color = track,
                topLeft = Offset(0f, top),
                size = Size(size.width, thickness),
                cornerRadius = CornerRadius(thickness / 2),
            )
            if (position != null) {
                val radius = 4.dp.toPx()
                val x = radius + (size.width - 2 * radius) * position.toFloat()
                val centre = Offset(x, size.height / 2)
                drawCircle(ring, radius = radius + 1.5.dp.toPx(), center = centre)
                drawCircle(marker, radius = radius, center = centre)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (low == null) Format.notAvailable else Format.currency(low),
                style = LibraType.figureSmall,
                color = LibraTheme.colors.secondaryText,
            )
            Text(
                if (high == null) "" else Format.currency(high),
                style = LibraType.figureSmall,
                color = LibraTheme.colors.secondaryText,
            )
        }
    }
}

/**
 * Where [last] sits between [low] and [high], from 0 to 1, or null when that
 * cannot be said: a missing end, an empty range, or a last outside it.
 */
internal fun rangePosition(low: Double?, high: Double?, last: Double?): Double? {
    if (low == null || high == null || last == null) return null
    if (!low.isFinite() || !high.isFinite() || !last.isFinite()) return null
    if (high <= low) return null
    if (last < low || last > high) return null
    return (last - low) / (high - low)
}
