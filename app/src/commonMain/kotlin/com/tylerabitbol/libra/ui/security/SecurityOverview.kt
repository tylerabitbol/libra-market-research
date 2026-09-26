package com.tylerabitbol.libra.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.support.RelativeTimeText
import com.tylerabitbol.libra.support.Freshness
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.ui.components.HeroPrice
import com.tylerabitbol.libra.ui.components.RangeBar
import com.tylerabitbol.libra.viewmodels.SecurityDetailUiState
import com.tylerabitbol.libra.viewmodels.ChartValueFormat
import com.tylerabitbol.libra.viewmodels.displayChange
import com.tylerabitbol.libra.viewmodels.displayChangePercent
import com.tylerabitbol.libra.viewmodels.displayPrice
import com.tylerabitbol.libra.viewmodels.freshness
import com.tylerabitbol.libra.viewmodels.isShowingSavedCopy
import com.tylerabitbol.libra.viewmodels.savedCopyAsOf

// MARK: - Overview

@Composable
internal fun Overview(state: SecurityDetailUiState) {
    Card {
        state.profile?.let { profile ->
            Text(
                profile.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                profile.sector?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = LibraTheme.colors.secondaryText,
                    )
                }
                profile.exchange?.let {
                    Text(
                        "· $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = LibraTheme.colors.tertiaryText,
                    )
                }
            }
        }

        HeroPrice(
            price = Format.currency(state.displayPrice),
            change = state.displayChange,
            percent = state.displayChangePercent,
            valueFormat = ChartValueFormat.Currency,
            freshness = state.freshness.takeUnless { state.isShowingSavedCopy },
            isPending = state.displayPrice == null && !state.hasCompletedLoad,
        )

        // A failed refresh must not blank a page the store can fill. When it
        // does fall back, the notice dates what is on screen rather than
        // letting a stored close pass for a live price.
        if (state.isShowingSavedCopy) {
            Text(
                RelativeTimeText.status(
                    Freshness.Failed(
                        previous = state.savedCopyAsOf,
                        reason = state.quoteError?.shortDescription
                            ?: state.historyError?.shortDescription
                            ?: "no connection",
                    ),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.caution,
            )
        } else {
            state.quoteError?.let {
                Text(
                    it.recoverySuggestion ?: it.shortDescription,
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.caution,
                )
            }
        }

        MetricGrid(state)
    }
}

/**
 * The quote's key figures, as compact as they can be and still be read.
 *
 * This was a fixed-height grid of six cells in the figure style, where the two
 * ranges wrapped. The ranges are now bars with their ends printed beneath, and
 * the four single figures a two-column table, label and value on one line —
 * about half the height, with the price's place in each range visible.
 */
@Composable
private fun MetricGrid(state: SecurityDetailUiState) {
    val metrics = state.metrics
    val averageVolume = metrics?.currentValue("10DayAverageTradingVolume")
    val beta = metrics?.currentValue("beta")

    Column(verticalArrangement = Arrangement.spacedBy(LibraSpacing.medium)) {
        Row(horizontalArrangement = Arrangement.spacedBy(LibraSpacing.large)) {
            RangeBar(
                "Day range",
                low = state.quote?.low,
                high = state.quote?.high,
                last = state.displayPrice,
                modifier = Modifier.weight(1f),
            )
            RangeBar(
                "52-week range",
                low = metrics?.currentValue("52WeekLow"),
                high = metrics?.currentValue("52WeekHigh"),
                last = state.displayPrice,
                modifier = Modifier.weight(1f),
            )
        }
        Column {
            HorizontalDivider(color = LibraTheme.colors.separator)
            StatPair(
                "Market cap", Format.compactCurrency(state.profile?.marketCap),
                "Open", Format.currency(state.quote?.open),
            )
            HorizontalDivider(color = LibraTheme.colors.separator)
            // "(Finnhub)" stays: the page fits its own beta further down, and
            // two betas under one name would read as a contradiction.
            StatPair(
                "Beta (Finnhub)", Format.ratio(beta, precision = 2),
                "Avg vol (10d)", Format.compact(averageVolume?.let { it * 1_000_000 }),
            )
        }
    }
}

/** Two label–value cells side by side, one table row. */
@Composable
private fun StatPair(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = LibraSpacing.snug),
        horizontalArrangement = Arrangement.spacedBy(LibraSpacing.large),
    ) {
        StatCell(leftLabel, leftValue, Modifier.weight(1f))
        StatCell(rightLabel, rightValue, Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val available = value != Format.notAvailable
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.secondaryText,
            maxLines = 1,
        )
        Text(
            // "Not available" is too wide for half a row; a dash in the
            // secondary colour says the same, and the description says it
            // in full.
            if (available) value else "—",
            style = LibraType.figure,
            color = if (available) {
                MaterialTheme.colorScheme.onSurface
            } else {
                LibraTheme.colors.tertiaryText
            },
            maxLines = 1,
        )
    }
}
