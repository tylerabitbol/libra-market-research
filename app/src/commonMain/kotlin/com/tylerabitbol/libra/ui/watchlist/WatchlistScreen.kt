package com.tylerabitbol.libra.ui.watchlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.tylerabitbol.libra.services.providers.CompanyProfileDTO
import com.tylerabitbol.libra.ui.LibraIcons
import com.tylerabitbol.libra.ui.components.AnimatedFigure
import com.tylerabitbol.libra.ui.components.ChangePill
import com.tylerabitbol.libra.ui.components.PlaceholderBar
import com.tylerabitbol.libra.ui.components.Sparkline
import com.tylerabitbol.libra.ui.components.SwipeToDelete
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.tylerabitbol.libra.ui.components.groupedRowContent
import com.tylerabitbol.libra.ui.components.groupedRowInset
import com.tylerabitbol.libra.ui.components.groupedRow
import com.tylerabitbol.libra.ui.components.GroupedDivider
import com.tylerabitbol.libra.ui.components.ScreenHeader
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.support.RelativeTimeText
import com.tylerabitbol.libra.ui.LibraAlpha
import com.tylerabitbol.libra.ui.LibraShapes
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
@OptIn(ExperimentalMaterial3Api::class)
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
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onQuickAdd: (CompanyProfileDTO) -> Unit = {},
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
            EmptyWatchlist(onAddSecurity, onQuickAdd)
        } else PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                // The header row above already carries its own bottom space;
                // a full 16 on top of it opened a gap no iOS list has.
                contentPadding = PaddingValues(
                    start = LibraSpacing.large,
                    end = LibraSpacing.large,
                    top = LibraSpacing.small,
                    bottom = LibraSpacing.large,
                ),
                // No blanket spacing: the rows below are one grouped card and
                // must sit flush. The loose items pad themselves.
            ) {
                if (isUsingSampleData) {
                    item {
                        Column(Modifier.padding(bottom = LibraSpacing.medium)) {
                            SampleDataBanner()
                        }
                    }
                }

                itemsIndexed(state.sortedRows, key = { _, row -> row.symbol }) { index, row ->
                    // A lazy list cannot be wrapped in one card, so each row
                    // carries its own share of the group fill and rounds only
                    // the corners that are actually on the outside.
                    Column(
                        Modifier.groupedRow(
                            isFirst = index == 0,
                            isLast = index == state.sortedRows.lastIndex,
                        ),
                    ) {
                        // Swipe left to remove, as a SwiftUI list row does. The
                        // gesture is not the only way: a long press offers the
                        // same action, and so does the accessibility actions
                        // menu, so nothing depends on discovering the swipe.
                        SwipeToDelete(
                            rowKey = row.symbol,
                            label = "Remove",
                            onDelete = { onRemove(row.symbol) },
                        ) {
                            WatchlistRowView(
                                row = row,
                                onClick = { onOpenSecurity(row.symbol) },
                                onRemove = { onRemove(row.symbol) },
                                modifier = Modifier.groupedRowContent(),
                                isLoading = state.isLoading,
                            )
                        }
                        if (index < state.sortedRows.lastIndex) GroupedDivider()
                    }
                }

                item {
                    // The section footer: aligned with the rows' content, as
                    // a `List` footer is, rather than with the card's edge.
                    Column(Modifier.padding(horizontal = groupedRowInset, vertical = LibraSpacing.small)) {
                        FreshnessLabel(state.freshness)
                    }
                }
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

    ScreenHeader("Watchlist") {
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

@Composable
private fun EmptyWatchlist(
    onAddSecurity: () -> Unit,
    onQuickAdd: (CompanyProfileDTO) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(LibraSpacing.large),
        verticalArrangement = Arrangement.spacedBy(
            LibraSpacing.medium,
            Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            LibraIcons.Watchlist,
            contentDescription = null,
            tint = LibraTheme.colors.tertiaryText,
            modifier = Modifier.size(44.dp),
        )
        Text("No securities yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add a company to start tracking what changes underneath it.",
            style = MaterialTheme.typography.bodySmall,
            color = LibraTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAddSecurity) { Text("Search for a security") }
        Text(
            "Or start with one of these",
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.tertiaryText,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small)) {
            for (suggestion in starterSuggestions) {
                SuggestionChip(
                    onClick = { onQuickAdd(suggestion) },
                    label = { Text(suggestion.symbol, style = LibraType.tickerSmall) },
                )
            }
        }
    }
}

/**
 * Three large, liquid companies an empty list can start from. Only the symbol
 * and name are given; everything else is fetched as for any other addition.
 */
private val starterSuggestions = listOf(
    CompanyProfileDTO(symbol = "AAPL", name = "Apple Inc."),
    CompanyProfileDTO(symbol = "MSFT", name = "Microsoft Corporation"),
    CompanyProfileDTO(symbol = "NVDA", name = "NVIDIA Corporation"),
)

@Composable
private fun SaveErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LibraSpacing.large)
            .clip(LibraShapes.smallCard)
            .background(LibraTheme.colors.negative.copy(alpha = LibraAlpha.bannerFill))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchlistRowView(
    row: WatchlistRow,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    var isMenuOpen by remember { mutableStateOf(false) }

    // Clickable before the inset, so the whole cell answers a tap and not only
    // the text inside it. Remove is not drawn in the row: it is a swipe, a
    // long press, and an accessibility action, which between them reach every
    // way of using the screen without a button on every line.
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { isMenuOpen = true },
                    onLongClickLabel = "More actions",
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Remove ${row.symbol}") {
                            onRemove()
                            true
                        },
                    )
                }
                .then(modifier),
            verticalArrangement = Arrangement.spacedBy(LibraSpacing.tight),
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
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Sparkline(row.sparkline)
                Column(
                    Modifier.widthIn(min = 76.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val error = row.error
                    if (!row.hasValue && error != null) {
                        Text(
                            error.shortDescription,
                            style = MaterialTheme.typography.labelSmall,
                            color = LibraTheme.colors.caution,
                        )
                    } else if (!row.hasValue && isLoading) {
                        // On its way, not missing: shapes, not dashes.
                        PlaceholderBar(width = 72.dp, height = 16.dp)
                        PlaceholderBar(width = 64.dp, height = 20.dp)
                    } else {
                        // A price from disk beats an error message. The refresh
                        // failing does not make the last known price untrue — it
                        // makes it old, which is what the stamp below says.
                        AnimatedFigure(
                            Format.currency(row.last),
                            style = LibraType.figureEmphasis,
                            color = if (row.isStoredCopy) {
                                LibraTheme.colors.secondaryText
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        ChangePill(row.changePercent)
                    }
                }
            }
            WatchlistRowContext(row)
        }

        DropdownMenu(expanded = isMenuOpen, onDismissRequest = { isMenuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Open ${row.symbol}") },
                onClick = {
                    isMenuOpen = false
                    onClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Remove from Watchlist", color = LibraTheme.colors.negative) },
                onClick = {
                    isMenuOpen = false
                    onRemove()
                },
            )
        }
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
    val asOf = row.asOf
    if (event == null && versus == null && asOf == null) return

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
                        .clip(LibraShapes.badge)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = LibraAlpha.chipFill))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Box(Modifier.weight(1f))
        if (asOf != null) {
            Text(
                RelativeTimeText.string(asOf),
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.tertiaryText,
            )
        }
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
