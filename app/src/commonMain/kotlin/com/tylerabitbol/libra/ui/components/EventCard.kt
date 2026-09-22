package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.ui.LibraAlpha
import com.tylerabitbol.libra.ui.LibraShapes
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import kotlin.math.max
import kotlin.math.min

/**
 * One detected change, rendered with its epistemic labels intact.
 *
 * The headline is a CALCULATION — measured arithmetic, with the derivation one
 * tap away. The "why this matters" text is an INTERPRETATION and is badged
 * differently, because it is a judgement about what the figures show rather
 * than a measurement. Neither ever becomes a suggestion to buy or sell.
 */
@Composable
fun EventCard(
    event: DetectedEventDTO,
    modifier: Modifier = Modifier,
    /** Shown when the card appears in a feed mixing several securities. */
    symbol: String? = null,
    isNew: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .libraCard(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EventHeader(event, symbol, isNew)

        ClaimRow(event.headlineClaim)

        if (event.detailLines.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (line in event.detailLines) {
                    Text(line, style = LibraType.figure, color = LibraTheme.colors.secondaryText)
                }
            }
        }

        if (event.unusualness > 0) {
            UnusualnessMeter(event.unusualness)
        }

        event.contextClaim?.let {
            HorizontalDivider()
            ClaimRow(it)
        }
    }
}

@Composable
private fun EventHeader(event: DetectedEventDTO, symbol: String?, isNew: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Swift leads with `event.kind.systemImage`. SF Symbols has no
        // multiplatform counterpart, and the kind is already named in words
        // beside it, so the glyph is dropped rather than redrawn eighteen times
        // for decoration.
        if (symbol != null) {
            Text(
                symbol,
                style = LibraType.tickerSmall,
            )
        }
        Text(
            event.kind.displayName,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = LibraTheme.colors.secondaryText,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (isNew) {
            Tag("NEW", MaterialTheme.colorScheme.primary)
        }
        if (event.isProvisional) {
            Tag(
                "SESSION OPEN",
                LibraTheme.colors.caution,
                accessibilityLabel = "Session still open. This reading can change " +
                    "before the close and is not yet recorded.",
            )
        }
        Text(
            Format.dayAndMonth(event.occurredAt),
            style = LibraType.figure,
            color = LibraTheme.colors.tertiaryText,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun Tag(text: String, tint: Color, accessibilityLabel: String? = null) {
    Text(
        text,
        style = LibraType.codeSmallEmphasis,
        color = tint,
        modifier = Modifier
            .libraChip(tint)
            .then(
                if (accessibilityLabel == null) {
                    Modifier
                } else {
                    Modifier.semantics { contentDescription = accessibilityLabel }
                },
            ),
    )
}

/**
 * How unusual a reading is relative to the security's own recent history.
 *
 * Labelled in words as well as drawn, and deliberately not tinted red or green:
 * unusual is not the same as bad, and this figure carries no direction. It is a
 * position within an observed sample, never a probability — a bar that implied
 * "2% chance" would be a fabricated statistic.
 */
@Composable
fun UnusualnessMeter(value: Double, modifier: Modifier = Modifier) {
    val descriptor = unusualnessDescriptor(value)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Unusual for this security: $descriptor. " +
                    "Measured against its own recent history. Not a measure of " +
                    "importance, direction, or likelihood."
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(
                "Unusual for this security",
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
            )
            Text(
                descriptor,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = LibraTheme.colors.secondaryText,
            )
        }
        ProportionBar(fraction = min(max(value, 0.0), 1.0).toFloat(), fromEnd = false)
    }
}

internal fun unusualnessDescriptor(value: Double): String = when {
    value < 0.5 -> "Within its usual range"
    value < 0.9 -> "Above its usual range"
    value < 0.99 -> "Rare for this security"
    else -> "Among the most extreme in the sample"
}
