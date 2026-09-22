package com.tylerabitbol.libra.ui.watchlist

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.support.RelativeTimeText
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.ui.components.DirectionalChangeText
import com.tylerabitbol.libra.ui.components.FreshnessLabel
import com.tylerabitbol.libra.ui.components.SampleDataBanner
import com.tylerabitbol.libra.viewmodels.WatchlistRow
import com.tylerabitbol.libra.viewmodels.WatchlistUiState
import com.tylerabitbol.libra.viewmodels.WatchlistViewModel

/**
 * The watchlist (Section 15).
 *
 * Membership lives in the database so the list renders instantly from disk and
 * stays usable offline; prices arrive afterwards and fill in.
 */
@Composable
fun WatchlistScreen(
    state: WatchlistUiState,
    isUsingSampleData: Boolean,
    onSelectSort: (WatchlistViewModel.SortOrder) -> Unit,
    onOpenSecurity: (String) -> Unit,
    onAddSecurity: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    saveError: String? = null,
    onDismissError: () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        WatchlistToolbar(
            sort = state.sort,
            canSort = state.rows.isNotEmpty(),
            onSelectSort = onSelectSort,
            onAddSecurity = onAddSecurity,
        )

        // A failed write used to vanish silently, leaving the user believing a
        // security had been added when it had not.
        saveError?.let {
            SaveErrorBanner(it, onDismissError)
        }

        if (state.rows.isEmpty()) {
            EmptyWatchlist(onAddSecurity)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(LibraSpacing.large),
                verticalArrangement = Arrangement.spacedBy(LibraSpacing.small),
            ) {
                if (isUsingSampleData) {
                    item { SampleDataBanner() }
                }

                items(state.sortedRows, key = { it.symbol }) { row ->
                    WatchlistRowView(
                        row = row,
                        onClick = { onOpenSecurity(row.symbol) },
                        onRemove = { onRemove(row.symbol) },
                    )
                    HorizontalDivider()
                }

                item { FreshnessLabel(state.freshness) }
            }
        }
    }
}

@Composable
private fun WatchlistToolbar(
    sort: WatchlistViewModel.SortOrder,
    canSort: Boolean,
    onSelectSort: (WatchlistViewModel.SortOrder) -> Unit,
    onAddSecurity: () -> Unit,
) {
    var isSortMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LibraSpacing.large, vertical = LibraSpacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Watchlist", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small)) {
            if (canSort) {
                Box {
                    TextButton(onClick = { isSortMenuOpen = true }) {
                        Text("Sort: ${sort.displayName}")
                    }
                    DropdownMenu(
                        expanded = isSortMenuOpen,
                        onDismissRequest = { isSortMenuOpen = false },
                    ) {
                        for (order in WatchlistViewModel.SortOrder.entries) {
                            DropdownMenuItem(
                                text = { Text(order.displayName) },
                                onClick = {
                                    onSelectSort(order)
                                    isSortMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onAddSecurity) { Text("Add") }
        }
    }
}

@Composable
private fun EmptyWatchlist(onAddSecurity: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(LibraSpacing.large),
        verticalArrangement = Arrangement.spacedBy(
            LibraSpacing.medium,
            Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No securities yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add a company to start tracking what changes underneath it.",
            style = MaterialTheme.typography.bodySmall,
            color = LibraTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAddSecurity) { Text("Add a security") }
    }
}

@Composable
private fun SaveErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LibraSpacing.large)
            .clip(RoundedCornerShape(8.dp))
            .background(LibraTheme.colors.negative.copy(alpha = 0.12f))
            .padding(LibraSpacing.medium),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Couldn't save",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
            )
        }
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

@Composable
private fun WatchlistRowView(
    row: WatchlistRow,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LibraSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.symbol,
                    style = LibraType.ticker,
                )
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = LibraTheme.colors.secondaryText,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val error = row.error
                if (!row.hasValue && error != null) {
                    Text(
                        error.shortDescription,
                        style = MaterialTheme.typography.labelSmall,
                        color = LibraTheme.colors.caution,
                    )
                } else {
                    // A price from disk beats an error message. The refresh
                    // failing does not make the last known price untrue — it
                    // makes it old, which is what the stamp below says.
                    Text(
                        Format.currency(row.last),
                        style = LibraType.figureEmphasis,
                        color = if (row.isStoredCopy) {
                            LibraTheme.colors.secondaryText
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    DirectionalChangeText(row.changePercent)
                    row.asOf?.let {
                        Text(
                            RelativeTimeText.string(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = LibraTheme.colors.tertiaryText,
                        )
                    }
                }
            }
            TextButton(onClick = onRemove) {
                Text("Remove", style = MaterialTheme.typography.labelSmall)
            }
        }
        WatchlistRowContext(row)
    }
}

/**
 * What the detectors last recorded, and how this moved against the market.
 *
 * Both come from data already in hand — the event from the store, the benchmark
 * from one quote shared by every row. Nothing here costs a request that scales
 * with the length of the list.
 */
@Composable
private fun WatchlistRowContext(row: WatchlistRow) {
    val event = row.latestEvent
    val versus = row.versusMarket
    if (event == null && versus == null) return

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (event != null) {
            Text(
                event.kind.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.secondaryText,
                maxLines = 1,
            )
            if (event.unusualness >= 0.9) {
                Text(
                    "RARE",
                    style = LibraType.codeSmallEmphasis,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Box(Modifier.weight(1f))
        if (versus != null) {
            // Percentage points, not percent, and labelled: this is a
            // difference between two returns, not a return.
            Text(
                "${Format.percentagePoints(versus)} vs S&P",
                style = LibraType.figureSmall,
                color = LibraTheme.colors.tertiaryText,
            )
        }
    }
}
