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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.calculations.MoveAttribution
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.ui.LibraShapes
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import kotlin.math.abs
import kotlin.math.min

/**
 * How much of a move the market accounts for — the deterministic half of "why
 * did it change".
 *
 * A stock down 6% on a day the market fell 5% is a completely different
 * situation from the same 6% on a flat day, and an ordinary stock app renders
 * them identically. This card is the difference.
 */
@Composable
fun AttributionCard(attribution: MoveAttribution, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .libraCard(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "How much was the market?",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = LibraTheme.colors.secondaryText,
            )
            Text(
                attribution.leaning.displayName,
                style = LibraType.codeSmallEmphasis,
                color = LibraTheme.colors.secondaryText,
                modifier = Modifier
                    .clip(LibraShapes.chip)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }

        AttributionSplit(attribution)

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (line in attribution.detailLines) {
                Text(line, style = LibraType.figure, color = LibraTheme.colors.secondaryText)
            }
        }

        if (attribution.sector?.isProxy == true) {
            Footnote(
                "FRED publishes no sector index, so a sector ETF stands in. The sector's " +
                    "own market exposure is removed before it is used, so the market is " +
                    "not counted twice.",
            )
        }

        if (attribution.isMarketProxy) {
            Footnote(
                "The S&P 500 index publishes only at the close, so an ETF stands in for " +
                    "the open session. It tracks the index closely but not exactly.",
            )
        }

        HorizontalDivider()
        ClaimRow(attribution.claim)

        attribution.beta?.let { ClaimRow(it.claim) }
        attribution.sectorFactorClaim?.let { ClaimRow(it) }
    }
}

/**
 * Bars on a shared scale.
 *
 * Deliberately not a pie or a percentage split: the parts can point in opposite
 * directions — a stock can rise on a falling market, and a sector can fall while
 * the market rises — and any "share of the move" framing breaks down entirely
 * when they do.
 */
@Composable
private fun AttributionSplit(attribution: MoveAttribution) {
    val sector = attribution.sector
    val magnitude = maxOf(
        abs(attribution.explainedByMarket),
        abs(attribution.residual),
        abs(sector?.explained ?: 0.0),
        0.0001,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AttributionBar("Market accounts for", attribution.explainedByMarket, magnitude)
        if (sector != null) {
            AttributionBar("${sector.name}, beyond the market", sector.explained, magnitude)
        }
        AttributionBar(
            if (sector == null) "Unexplained" else "Unexplained by either",
            attribution.residual,
            magnitude,
        )
    }
}

@Composable
private fun AttributionBar(label: String, value: Double, magnitude: Double) {
    val formatted = Format.percentagePoints(value)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The bar itself is a drawn shape and carries nothing for a screen
            // reader. Collapsing the row to one element with the figure in it
            // is the only way the split is readable without sight.
            .clearAndSetSemantics { contentDescription = "$label: $formatted" },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
            )
            Text(formatted, style = LibraType.figure)
        }
        ProportionBar(fraction = min(abs(value) / magnitude, 1.0).toFloat(), fromEnd = value < 0)
    }
}
