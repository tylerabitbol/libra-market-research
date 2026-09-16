package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.calculations.FilingAnalysis
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType

/**
 * What a filing actually reported, shown beneath the card announcing it.
 *
 * Section 9: "Do not simply dump filing links into the interface. Extract
 * useful metadata and summarize what changed." A card saying a 10-Q exists,
 * with a link, is the thing that instruction rules out.
 *
 * Each figure carries its own epistemic status rather than the card carrying
 * one for all of them. A figure with a comparable period behind it is a
 * CALCULATION with arithmetic to show; a figure in a company's first year on
 * file is a FACT with nothing to compare against, and badging the two the same
 * would overstate the second.
 */
@Composable
fun FilingAnalysisCard(analysis: FilingAnalysis.Result, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "What this filing reported",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = LibraTheme.colors.secondaryText,
            )
            Text(
                "PRIMARY SOURCE",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = LibraTheme.colors.secondaryText,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }

        when {
            analysis.isAwaitingFacts -> Text(
                "EDGAR has not published this filing's structured figures yet. They " +
                    "usually follow the document itself within a day or two.",
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
            )

            analysis.lines.isEmpty() -> Text(
                "This filing reported no figures among those tracked.",
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
            )

            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((index, line) in analysis.lines.withIndex()) {
                        FilingFigureRow(line)
                        if (index != analysis.lines.lastIndex) HorizontalDivider()
                    }
                }
                Footnote(
                    "Each figure is compared with the same ${analysis.periodLabel} a year " +
                        "earlier, so seasonality is already removed.",
                )
            }
        }
    }
}

/** One figure, expandable to the arithmetic behind it. */
@Composable
private fun FilingFigureRow(line: FilingAnalysis.Line) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .semantics {
                    contentDescription = "${line.label}, ${line.formatted}" +
                        (line.comparison?.let { ", $it" } ?: "")
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                line.label,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
                modifier = Modifier.weight(1f),
            )
            Text(line.formatted, style = LibraType.figureEmphasis)
            if (line.comparison != null) {
                // Deliberately not tinted. A margin narrowing is not bad news
                // the way a price fall reads as bad news, and colouring it would
                // make a judgement the figure does not support.
                Text(
                    line.comparison!!,
                    style = LibraType.figure,
                    color = LibraTheme.colors.secondaryText,
                )
            }
            Text(
                if (isExpanded) "▴" else "▾",
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.tertiaryText,
            )
        }

        if (isExpanded) {
            ClaimRow(line.claim)
        }
    }
}
