package com.tylerabitbol.libra.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import com.tylerabitbol.libra.calculations.PeriodReturn
import com.tylerabitbol.libra.models.core.ChartAvailability
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.ui.components.ClaimRow
import com.tylerabitbol.libra.ui.components.DirectionalChangeText
import com.tylerabitbol.libra.ui.components.PriceChart
import com.tylerabitbol.libra.ui.components.RangePicker
import com.tylerabitbol.libra.ui.components.periodLabel
import com.tylerabitbol.libra.viewmodels.SecurityDetailUiState
import com.tylerabitbol.libra.viewmodels.analysis
import com.tylerabitbol.libra.viewmodels.chartAvailability
import com.tylerabitbol.libra.viewmodels.chartAxisTicks
import com.tylerabitbol.libra.viewmodels.chartBars
import com.tylerabitbol.libra.viewmodels.chartNote
import com.tylerabitbol.libra.viewmodels.chartPoints
import com.tylerabitbol.libra.viewmodels.chartSegments
import com.tylerabitbol.libra.viewmodels.marketRangeReturn
import com.tylerabitbol.libra.viewmodels.priceContext
import com.tylerabitbol.libra.viewmodels.rangeReturn
import com.tylerabitbol.libra.viewmodels.relativeToMarket
import com.tylerabitbol.libra.viewmodels.relativeToSector
import com.tylerabitbol.libra.viewmodels.sectorRangeReturn

// MARK: - Chart

@Composable
internal fun ChartSection(state: SecurityDetailUiState, onSelectRange: (ChartRange) -> Unit) {
    Card {
        // The range's move is in the chart's own summary now, computed from the
        // line it sits above; a second figure here was measured from a
        // slightly different start bar and could disagree with it.
        Text("Price", style = MaterialTheme.typography.titleMedium)

        val ranges = ChartRange.entries
        RangePicker(ranges, state.selectedRange, onSelectRange)

        when (val availability = state.chartAvailability) {
            is ChartAvailability.Loading -> Box(
                Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is ChartAvailability.Unavailable -> Column(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                verticalArrangement = Arrangement.spacedBy(
                    LibraSpacing.small,
                    Alignment.CenterVertically,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Price history unavailable", style = MaterialTheme.typography.titleMedium)
                Text(
                    availability.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = LibraTheme.colors.secondaryText,
                    textAlign = TextAlign.Center,
                )
            }

            is ChartAvailability.Ready -> {
                PriceChart(
                    bars = state.chartBars,
                    points = state.chartPoints,
                    segments = state.chartSegments,
                    ticks = state.chartAxisTicks,
                    isIntraday = state.selectedRange.usesIntraday,
                    periodLabel = state.selectedRange.periodLabel,
                )
                state.chartNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = LibraTheme.colors.caution,
                    )
                }
                if (state.selectedRange.usesIntraday) {
                    // The consolidated tape is not what drew this. Saying so is
                    // the condition on which intraday was adopted at all.
                    Text(
                        "IEX only — about 2.5% of US volume. Shape, not levels. " +
                            "Trading hours end to end, New York time; dashed " +
                            "where the market was shut.",
                        style = MaterialTheme.typography.labelSmall,
                        color = LibraTheme.colors.secondaryText,
                    )
                } else if (state.rangeReturn?.isFullWindow == false) {
                    Text(
                        "Less history available than the selected range.",
                        style = MaterialTheme.typography.labelSmall,
                        color = LibraTheme.colors.caution,
                    )
                }
                PriceContextRows(state)
            }
        }
    }
}

/**
 * Three figures, not a technical-analysis panel. Section 5 asks for context
 * and then rules out the dashboard, so this is where the line is.
 */
@Composable
private fun PriceContextRows(state: SecurityDetailUiState) {
    val context = state.priceContext ?: return
    HorizontalDivider(Modifier.padding(vertical = 2.dp))
    Column(verticalArrangement = Arrangement.spacedBy(LibraSpacing.small)) {
        (context.twoHundredDayClaim ?: context.fiftyDayClaim)?.let { ClaimRow(it) }
        ClaimRow(context.drawdownClaim)
        if (context.deepestDrawdown < -0.05) {
            Text(
                "Deepest fall within this period: " +
                    Format.percent(-context.deepestDrawdown, precision = 1),
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.secondaryText,
            )
        }
    }
}

// MARK: - Relative performance

/**
 * Section 7: how this security did against its sector and the market over the
 * selected range, stated as arithmetic rather than as adjectives.
 *
 * Hidden entirely rather than shown empty when neither comparison can be made
 * — an absent benchmark is not a finding the way "nothing unusual" is.
 */
@Composable
internal fun RelativeSection(state: SecurityDetailUiState) {
    if (state.relativeToMarket == null && state.relativeToSector == null) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Relative performance", style = MaterialTheme.typography.titleMedium)
            Text(
                state.selectedRange.raw,
                style = LibraType.codeSmall,
                color = LibraTheme.colors.tertiaryText,
            )
        }

        Card {
            val subjectEnd = state.rangeReturn?.endDate
            ReturnRow(state.symbol, state.rangeReturn, isSubject = true)
            state.sectorBenchmark?.let {
                HorizontalDivider()
                ReturnRow(it.displayName, state.sectorRangeReturn, isSubject = false, subjectEnd)
            }
            HorizontalDivider()
            // FRED publishes a session late, so this row usually ends a day
            // before the one above it; the row says so rather than invite a
            // subtraction across two windows.
            ReturnRow("S&P 500", state.marketRangeReturn, isSubject = false, subjectEnd)
        }

        Card {
            val sector = state.relativeToSector
            val benchmark = state.sectorBenchmark
            if (sector != null && benchmark != null) {
                ClaimRow(sector.claim(state.symbol, benchmark.displayName))
            }
            state.relativeToMarket?.let {
                ClaimRow(it.claim(state.symbol, "the S&P 500"))
            }
            state.sectorBenchmark?.proxyNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.tertiaryText,
                )
            }
        }
    }
}

/**
 * One leg's return over the range. [subjectEnd] is the security's last bar:
 * when this leg ends on a different day, the row names its own end date.
 */
@Composable
private fun ReturnRow(
    label: String,
    period: PeriodReturn?,
    isSubject: Boolean,
    subjectEnd: Instant? = null,
) {
    val ownEnd = period?.endDate?.takeIf {
        subjectEnd != null && Format.shortDate(it) != Format.shortDate(subjectEnd)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                label,
                style = if (isSubject) {
                    LibraType.ticker
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = if (isSubject) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    LibraTheme.colors.secondaryText
                },
            )
            if (ownEnd != null) {
                Text(
                    "to ${Format.shortDate(ownEnd)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.tertiaryText,
                )
            }
        }
        DirectionalChangeText(period?.percent)
    }
}
