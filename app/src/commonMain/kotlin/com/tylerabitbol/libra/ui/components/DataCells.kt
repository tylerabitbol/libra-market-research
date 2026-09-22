package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.max
import kotlin.math.roundToInt
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.support.Freshness
import com.tylerabitbol.libra.support.RelativeTimeText
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType

/**
 * A labelled value that renders "Not available" honestly when the figure is
 * absent, rather than showing a zero or an empty cell that reads as one.
 */
@Composable
fun MetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    isAvailable: Boolean = true,
    alignEnd: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = LibraTheme.colors.secondaryText,
        )
        Text(
            value,
            style = LibraType.figureEmphasis,
            color = if (isAvailable) {
                MaterialTheme.colorScheme.onSurface
            } else {
                LibraTheme.colors.secondaryText
            },
        )
        if (secondary != null) {
            Text(
                secondary,
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.tertiaryText,
            )
        }
    }
}

/**
 * Direction-coloured variant for places where the dashboard genuinely benefits
 * from scanning colour, e.g. a dense benchmark grid.
 */
@Composable
fun DirectionalChangeText(
    percent: Double?,
    modifier: Modifier = Modifier,
    precision: Int = 2,
    style: TextStyle = LibraType.figure,
) {
    Text(
        Format.signedPercent(percent, precision = precision),
        style = style,
        color = directionColor(percent),
        modifier = modifier,
    )
}

@Composable
private fun directionColor(percent: Double?): Color = when {
    percent == null -> LibraTheme.colors.secondaryText
    percent > 0 -> LibraTheme.colors.positive
    percent < 0 -> LibraTheme.colors.negative
    else -> LibraTheme.colors.secondaryText
}

/** The "Updated 4 minutes ago" line required by Section 20. */
@Composable
fun FreshnessLabel(
    freshness: Freshness,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
) {
    val isProblem = when (freshness) {
        is Freshness.Stale, is Freshness.Failed, is Freshness.Missing -> true
        is Freshness.Fresh, is Freshness.Refreshing -> false
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (freshness is Freshness.Refreshing) {
            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
        } else if (isProblem) {
            Text("!", style = style, color = LibraTheme.colors.caution)
        }
        Text(
            RelativeTimeText.status(freshness),
            style = style,
            color = if (isProblem) LibraTheme.colors.caution else LibraTheme.colors.tertiaryText,
        )
    }
}

/**
 * [FreshnessLabel] pinned over a page that scrolls under it.
 *
 * SwiftUI floats it on `.regularMaterial` — a real backdrop blur, which
 * separates it from whatever it covers. Compose Multiplatform has no
 * cross-platform equivalent, and the two honest substitutes are a translucent
 * scrim or an opaque fill. A scrim without blur smears the content behind it,
 * so this is opaque: the card fill, with a hairline separator border to hold
 * the edge where the pill sits over a card of the same colour.
 *
 * No shadow. The Swift app has none anywhere, and a drop shadow under a pill
 * that nothing else in the app casts reads as a different app's component.
 */
@Composable
fun PinnedFreshnessLabel(freshness: Freshness, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = LibraTheme.colors.cardFill,
        border = BorderStroke(Dp.Hairline, LibraTheme.colors.separator),
    ) {
        FreshnessLabel(
            freshness,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** A right-aligned figure beside its label, the shape most rows on the app take. */
@Composable
fun FigureRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isAvailable: Boolean = true,
    emphasis: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = LibraTheme.colors.secondaryText,
        )
        Text(
            value,
            style = if (emphasis) {
                LibraType.figure.copy(fontWeight = FontWeight.SemiBold)
            } else {
                LibraType.figure
            },
            color = if (isAvailable) {
                MaterialTheme.colorScheme.onSurface
            } else {
                LibraTheme.colors.secondaryText
            },
        )
    }
}

/**
 * A track with a fill of the given fraction, anchored at whichever end the sign
 * calls for — a negative contribution grows leftwards, so the two directions do
 * not read as the same bar.
 */
@Composable
internal fun ProportionBar(fraction: Float, fromEnd: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Layout(
            content = {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(LibraTheme.colors.secondaryText),
                )
            },
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        ) { measurables, constraints ->
            // A minimum of two pixels, so a contribution that rounds to nothing
            // is still visibly present rather than an empty track.
            val width = max(2, (constraints.maxWidth * fraction).roundToInt())
            val placeable = measurables.first().measure(
                constraints.copy(minWidth = width, maxWidth = width),
            )
            layout(constraints.maxWidth, placeable.height) {
                placeable.placeRelative(
                    x = if (fromEnd) constraints.maxWidth - width else 0,
                    y = 0,
                )
            }
        }
    }
}

/** A dimmed aside: a caveat, a basis, a note about what is missing. */
@Composable
internal fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = LibraTheme.colors.tertiaryText,
    )
}
