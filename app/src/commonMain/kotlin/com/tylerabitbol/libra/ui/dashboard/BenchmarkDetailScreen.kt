package com.tylerabitbol.libra.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.calculations.PeriodReturn
import com.tylerabitbol.libra.models.core.ChartAvailability
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.ui.components.DirectionalChangeText
import com.tylerabitbol.libra.ui.components.Footnote
import com.tylerabitbol.libra.ui.components.PriceChart
import com.tylerabitbol.libra.viewmodels.BenchmarkDetailUiState
import com.tylerabitbol.libra.viewmodels.availableRanges
import com.tylerabitbol.libra.viewmodels.chartAvailability
import com.tylerabitbol.libra.viewmodels.chartAxisTicks
import com.tylerabitbol.libra.viewmodels.chartBars
import com.tylerabitbol.libra.viewmodels.chartNote
import com.tylerabitbol.libra.viewmodels.chartPoints
import com.tylerabitbol.libra.viewmodels.chartSegments
import com.tylerabitbol.libra.viewmodels.daily
import com.tylerabitbol.libra.viewmodels.formattedLevel
import com.tylerabitbol.libra.viewmodels.latestBar
import com.tylerabitbol.libra.viewmodels.monthly
import com.tylerabitbol.libra.viewmodels.rangeReturn
import com.tylerabitbol.libra.viewmodels.sourceExplanation
import com.tylerabitbol.libra.viewmodels.sourceLabel
import com.tylerabitbol.libra.viewmodels.valueFormat
import com.tylerabitbol.libra.viewmodels.weekly

/**
 * The page behind a dashboard row.
 *
 * Deliberately not the security page. Tapping "S&P 500" could have opened the
 * security page for SPY, and it would have hung fundamentals, filings, insider
 * trades and margin history off an index — SPDR Trust's own filings presented
 * as the S&P 500's. A benchmark gets a price, a range, and the sentence saying
 * what the price actually is.
 */
@Composable
fun BenchmarkDetailScreen(
    state: BenchmarkDetailUiState,
    onSelectRange: (ChartRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(LibraSpacing.large),
        verticalArrangement = Arrangement.spacedBy(LibraSpacing.large),
    ) {
        Header(state)
        ChartSection(state, onSelectRange)
        ReturnsSection(state)
    }
}

@Composable
private fun Header(state: BenchmarkDetailUiState) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LibraSpacing.tight),
    ) {
        Text(
            state.sourceLabel,
            style = LibraType.code,
            color = LibraTheme.colors.tertiaryText,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                state.formattedLevel,
                style = LibraType.figureLarge,
            )
            state.rangeReturn?.let { DirectionalChangeText(it.percent) }
        }
        state.latestBar?.date?.let {
            Text(
                "as of ${Format.shortDate(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
            )
        }
    }
}

@Composable
private fun ChartSection(
    state: BenchmarkDetailUiState,
    onSelectRange: (ChartRange) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Index-backed benchmarks offer five ranges, not seven: FRED publishes
        // at the close, and a range that can never draw is not offered.
        val ranges = state.availableRanges
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            for ((index, range) in ranges.withIndex()) {
                SegmentedButton(
                    selected = range == state.selectedRange,
                    onClick = { onSelectRange(range) },
                    shape = SegmentedButtonDefaults.itemShape(index, ranges.size),
                ) {
                    Text(range.raw, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        when (val availability = state.chartAvailability) {
            is ChartAvailability.Loading -> Box(
                Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is ChartAvailability.Unavailable -> Column(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                verticalArrangement = Arrangement.spacedBy(
                    LibraSpacing.small,
                    Alignment.CenterVertically,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Price history unavailable",
                    style = MaterialTheme.typography.titleMedium,
                )
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
                    valueFormat = state.valueFormat,
                )
                state.chartNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = LibraTheme.colors.caution,
                    )
                }
                ChartCaption(state)
            }
        }
    }
}

/**
 * What was actually drawn, in words. A proxy says it is a proxy on every screen
 * that shows it, and an end-of-day index says it is not live.
 */
@Composable
private fun ChartCaption(state: BenchmarkDetailUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            state.sourceExplanation,
            style = MaterialTheme.typography.labelSmall,
            color = if (state.benchmark.isProxy) {
                LibraTheme.colors.caution
            } else {
                LibraTheme.colors.secondaryText
            },
        )
        if (state.selectedRange.usesIntraday) {
            // The consolidated tape is not what drew this. Saying so is the
            // condition on which intraday was adopted at all.
            Footnote(
                "IEX only — about 2.5% of US volume. Shape, not levels. Trading hours " +
                    "end to end, New York time; dashed where the market was shut.",
            )
        }
    }
}

/**
 * The same three figures the dashboard row shows, so the page and the row
 * cannot disagree. Computed from the daily series in both places.
 */
@Composable
private fun ReturnsSection(state: BenchmarkDetailUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(LibraSpacing.small)) {
        Text(
            "Trailing",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(LibraSpacing.medium),
        ) {
            ReturnColumn("1D", state.daily)
            ReturnColumn("1W", state.weekly)
            ReturnColumn("1M", state.monthly)
        }
    }
}

@Composable
private fun RowScope.ReturnColumn(label: String, value: PeriodReturn?) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.secondaryText,
            )
            if (value?.isFullWindow == false) {
                // The window was shorter than requested; say so rather than
                // presenting it as a full period.
                Text(
                    "*",
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.caution,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "Partial period — less history available than requested"
                    },
                )
            }
        }
        DirectionalChangeText(value?.percent, style = LibraType.figureEmphasis)
    }
}
