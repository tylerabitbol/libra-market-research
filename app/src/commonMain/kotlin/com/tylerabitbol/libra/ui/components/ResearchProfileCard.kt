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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.calculations.EvidenceDirection
import com.tylerabitbol.libra.calculations.ResearchComponent
import com.tylerabitbol.libra.calculations.ResearchProfile
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType

/**
 * The eleven dimensions, grouped by which way they point.
 *
 * **Challenges come first.** Section 12 asks the app to actively search for
 * disconfirming evidence, and a section that lists the supporting case first
 * buries the half a reader is least likely to go looking for on their own.
 *
 * There is no total. Section 13 asked for a 0–100 signal; the components are
 * here and the number deliberately is not, because summing a valuation
 * percentile, an insider count and a volatility rank produces a figure that
 * looks authoritative and means nothing. The footer says so on screen rather
 * than leaving its absence to be read as an oversight.
 */
@Composable
fun ResearchProfileCard(profile: ResearchProfile, modifier: Modifier = Modifier) {
    var isShowingUnavailable by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ClaimRow(profile.shapeClaim)

        if (profile.challenging.isNotEmpty()) {
            DimensionGroup("What challenges the picture", profile.challenging)
        }
        if (profile.supporting.isNotEmpty()) {
            DimensionGroup("What supports it", profile.supporting)
        }
        val mixed = profile.components.filter { it.direction == EvidenceDirection.Neutral }
        if (mixed.isNotEmpty()) {
            DimensionGroup("Neither way", mixed)
        }

        if (profile.unavailable.isNotEmpty()) {
            Text(
                if (isShowingUnavailable) {
                    "Hide what could not be measured"
                } else {
                    "${Format.count(profile.unavailable.size, "dimension")} could not be measured"
                },
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
                modifier = Modifier.clickable { isShowingUnavailable = !isShowingUnavailable },
            )

            if (isShowingUnavailable) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (component in profile.unavailable) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                component.dimension.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = LibraTheme.colors.secondaryText,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                component.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = LibraTheme.colors.tertiaryText,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                // An absent dimension is not reassurance, and saying so is the
                // point of keeping it visible at all.
                Footnote(
                    "A dimension with no data is not the same as a dimension with " +
                        "nothing to report.",
                )
            }
        }

        HorizontalDivider()
        Footnote(
            "No overall score is shown. Weighing these against one another is the " +
                "judgement this tool leaves to you — a single number would only look " +
                "like it had made it for you.",
        )
    }
}

@Composable
private fun DimensionGroup(title: String, components: List<ResearchComponent>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = LibraTheme.colors.secondaryText,
            )
            Text(
                "${components.size}",
                style = LibraType.figureSmall,
                color = LibraTheme.colors.tertiaryText,
            )
        }
        for ((index, component) in components.withIndex()) {
            DimensionRow(component)
            if (index != components.lastIndex) HorizontalDivider()
        }
    }
}

/**
 * One dimension, expandable to the arithmetic and to what it was measured
 * against.
 *
 * Deliberately untinted: "challenges" is not "bad", and colouring these red and
 * green would turn a research tool into a verdict.
 */
@Composable
private fun DimensionRow(component: ResearchComponent) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = component.claim != null) { isExpanded = !isExpanded }
                .semantics {
                    contentDescription = "${component.dimension.displayName}: " +
                        "${component.summary}. ${component.direction.displayName} " +
                        "the current picture."
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                component.dimension.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
                modifier = Modifier.weight(1f),
            )
            Text(
                component.summary,
                style = LibraType.figureEmphasis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            if (component.claim != null) {
                Text(
                    if (isExpanded) "▴" else "▾",
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.tertiaryText,
                )
            }
        }

        if (isExpanded) {
            component.claim?.let { ClaimRow(it) }
            Footnote(component.dimension.basis)
        }
    }
}
