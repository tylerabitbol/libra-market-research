package com.tylerabitbol.libra.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.models.core.Benchmark
import com.tylerabitbol.libra.models.core.MacroUnit
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.support.RelativeTimeText
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.ui.components.DirectionalChangeText
import com.tylerabitbol.libra.ui.components.DisclaimerBanner
import com.tylerabitbol.libra.ui.components.FreshnessLabel
import com.tylerabitbol.libra.ui.components.SampleDataBanner
import com.tylerabitbol.libra.viewmodels.BenchmarkPerformance
import com.tylerabitbol.libra.viewmodels.DashboardUiState
import com.tylerabitbol.libra.viewmodels.DashboardViewModel

/**
 * Market overview (Section 3).
 *
 * Deliberately restrained: four indexes, one volatility proxy, four macro
 * readings, and the sector grid. The spec warns against overloading this
 * screen, and the things that actually explain a move — relative performance
 * and detected changes — belong on the watchlist and detail screens where there
 * is room to show the reasoning.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    isUsingSampleData: Boolean,
    onOpenBenchmark: (Benchmark) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        // One grid for the whole screen: the sector tiles need a grid, and
        // nesting one inside a scrolling column gives it no height to work
        // with. Full-width rows span every column instead.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            contentPadding = PaddingValues(
                start = LibraSpacing.large, end = LibraSpacing.large,
                top = LibraSpacing.large,
                // Clears the floating freshness pill so it never sits on top of
                // the last row.
                bottom = 44.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small),
            verticalArrangement = Arrangement.spacedBy(LibraSpacing.small),
        ) {
            if (isUsingSampleData) {
                fullWidth { SampleDataBanner() }
            }
            // Below the sample-data warning deliberately: when both are
            // showing, "these numbers are invented" is the more urgent one.
            fullWidth { DisclaimerBanner() }

            fullWidth { SectionHeader("Market") }
            fullWidth { BenchmarkGroup(state.market, onOpenBenchmark) }

            if (state.volatility.isNotEmpty()) {
                fullWidth { SectionHeader("Volatility") }
                fullWidth { BenchmarkGroup(state.volatility, onOpenBenchmark) }
            }

            if (state.macro.isNotEmpty()) {
                fullWidth {
                    SectionHeader("Macro", subtitle = "Potentially relevant context")
                }
                fullWidth {
                    Column(verticalArrangement = Arrangement.spacedBy(LibraSpacing.small)) {
                        for (reading in state.macro) {
                            MacroCard(reading)
                        }
                    }
                }
            }

            if (state.sectors.isNotEmpty()) {
                fullWidth { SectionHeader("Sectors", subtitle = "Daily change") }
                items(state.sectors, key = { it.id }) { row ->
                    SectorTile(row) { onOpenBenchmark(row.benchmark) }
                }
            }
        }

        FreshnessLabel(
            state.overallFreshness,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LibraSpacing.small)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = LibraSpacing.medium, vertical = 6.dp),
        )
    }
}

/** A row that spans the whole grid rather than sitting in one column. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullWidth(
    content: @Composable () -> Unit,
) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
            )
        }
    }
}

@Composable
private fun BenchmarkGroup(
    rows: List<BenchmarkPerformance>,
    onOpen: (Benchmark) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        for ((index, row) in rows.withIndex()) {
            BenchmarkRow(row) { onOpen(row.benchmark) }
            if (index < rows.lastIndex) {
                HorizontalDivider(Modifier.padding(start = LibraSpacing.medium))
            }
        }
    }
}

@Composable
private fun BenchmarkRow(performance: BenchmarkPerformance, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(LibraSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        performance.benchmark.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    // A marker, not a button. This was a button opening an
                    // alert until the row became tappable — a control inside a
                    // tappable row swallowed the row's own tap, and the
                    // explanation has a better home on the page the row opens.
                    Text(
                        if (performance.benchmark.isProxy) "!" else "i",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (performance.benchmark.isProxy) {
                            LibraTheme.colors.caution
                        } else {
                            LibraTheme.colors.tertiaryText
                        },
                        modifier = Modifier.semantics {
                            contentDescription = if (performance.benchmark.isProxy) {
                                "Shows a proxy, not the index itself"
                            } else {
                                "The index itself, published end-of-day"
                            }
                        },
                    )
                }
                Text(
                    sourceLabel(performance.benchmark),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = LibraTheme.colors.tertiaryText,
                )
            }
            Text(
                performance.formattedLevel,
                style = LibraType.figureEmphasis,
                color = if (performance.level == null) {
                    LibraTheme.colors.secondaryText
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        if (performance.error != null) {
            Text(
                RelativeTimeText.status(performance.freshness),
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.caution,
            )
        } else {
            // A live price with missing history is a partial success, and
            // saying why beats leaving three cells reading "Not available".
            performance.historyError?.let {
                Text(
                    "History unavailable: ${it.shortDescription}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.caution,
                )
            }
            Row(Modifier.fillMaxWidth()) {
                ChangeColumn("1D", performance.dailyPercent)
                ChangeColumn(
                    "1W", performance.weekly?.percent,
                    partial = performance.weekly?.isFullWindow == false,
                )
                ChangeColumn(
                    "1M", performance.monthly?.percent,
                    partial = performance.monthly?.isFullWindow == false,
                )
            }
        }
    }
}

/**
 * Names what is actually being displayed: the index series, or the ETF standing
 * in for it.
 */
private fun sourceLabel(benchmark: Benchmark): String =
    benchmark.fredSeriesID?.let { "FRED $it" } ?: benchmark.etfSymbol ?: "—"

@Composable
private fun androidx.compose.foundation.layout.RowScope.ChangeColumn(
    label: String,
    value: Double?,
    partial: Boolean = false,
) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.secondaryText,
            )
            if (partial) {
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
        DirectionalChangeText(value)
    }
}

@Composable
private fun SectorTile(performance: BenchmarkPerformance, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            performance.benchmark.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.secondaryText,
            maxLines = 2,
        )
        DirectionalChangeText(
            performance.dailyPercent,
            style = LibraType.figureEmphasis,
        )
    }
}

@Composable
private fun MacroCard(reading: DashboardViewModel.MacroReading) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            reading.indicator.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.secondaryText,
            maxLines = 1,
        )

        val latest = reading.latest
        if (latest != null) {
            Text(macroValue(reading, latest.value), style = LibraType.figureEmphasis)
            Text(
                "as of ${Format.shortDate(latest.date)}",
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.tertiaryText,
            )
        } else {
            Text(
                Format.notAvailable,
                style = MaterialTheme.typography.bodyMedium,
                color = LibraTheme.colors.secondaryText,
            )
            reading.error?.let {
                Text(
                    it.shortDescription,
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.caution,
                )
            }
        }
    }
}

private fun macroValue(reading: DashboardViewModel.MacroReading, value: Double): String =
    when (reading.indicator.unit) {
        MacroUnit.Percent -> Format.percent(value, precision = 2)
        MacroUnit.Index -> Format.ratio(value, precision = 1)
        MacroUnit.Currency -> Format.compactCurrency(value)
    }
