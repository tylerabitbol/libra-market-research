package com.tylerabitbol.libra.ui.research

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.ui.components.menuCheckmark
import com.tylerabitbol.libra.models.core.EvidenceCategory
import com.tylerabitbol.libra.ui.LibraAlpha
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.components.EventCard
import com.tylerabitbol.libra.viewmodels.ResearchUiState
import com.tylerabitbol.libra.viewmodels.ResearchViewModel
import com.tylerabitbol.libra.viewmodels.availableCategories
import com.tylerabitbol.libra.viewmodels.visibleEvents

/**
 * Every change the detectors have recorded, across all securities.
 *
 * Deliberately a record rather than a live scan: the feed is built from what
 * was stored during ordinary use, so opening it costs no API requests and
 * cannot be misread as a market-wide sweep it never performed.
 */
@Composable
fun ResearchScreen(
    state: ResearchUiState,
    onSelectSort: (ResearchViewModel.Sort) -> Unit,
    onSelectCategory: (EvidenceCategory?) -> Unit,
    onOpenSecurity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ResearchToolbar(
            sort = state.sort,
            canSort = state.events.isNotEmpty(),
            onSelectSort = onSelectSort,
        )

        if (state.events.isEmpty()) {
            EmptyResearch()
        } else {
            Feed(state, onSelectCategory, onOpenSecurity)
        }
    }
}

@Composable
private fun ResearchToolbar(
    sort: ResearchViewModel.Sort,
    canSort: Boolean,
    onSelectSort: (ResearchViewModel.Sort) -> Unit,
) {
    var isSortMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LibraSpacing.large, vertical = LibraSpacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Research", style = MaterialTheme.typography.titleLarge)
        if (canSort) {
            Box {
                TextButton(onClick = { isSortMenuOpen = true }) {
                    Text("Sort: ${sort.displayName}")
                }
                DropdownMenu(
                    expanded = isSortMenuOpen,
                    onDismissRequest = { isSortMenuOpen = false },
                ) {
                    for (option in ResearchViewModel.Sort.entries) {
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            // Swift's sort menu is a `Picker`, which marks the
                            // current choice. This menu had no indicator at
                            // all: the label said "Sort: Most recent" and the
                            // open menu then said nothing about which row that
                            // was.
                            leadingIcon = menuCheckmark(option == sort),
                            onClick = {
                                onSelectSort(option)
                                isSortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Feed(
    state: ResearchUiState,
    onSelectCategory: (EvidenceCategory?) -> Unit,
    onOpenSecurity: (String) -> Unit,
) {
    val categories = state.availableCategories

    LazyColumn(
        contentPadding = PaddingValues(
            start = LibraSpacing.large,
            end = LibraSpacing.large,
            top = LibraSpacing.large,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(LibraSpacing.medium),
    ) {
        item {
            Text(
                "Detected while you were researching. Each is measured against " +
                    "that security's own history.",
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.secondaryText,
            )
        }

        // One category is no choice at all; a filter row offering a single
        // bucket implies the others exist and are empty.
        if (categories.size > 1) {
            item { CategoryFilter(categories, state.kindFilter, onSelectCategory) }
        }

        state.loadFailure?.let { failure ->
            item {
                Text(
                    failure,
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.caution,
                )
            }
        }

        items(
            count = state.visibleEvents.size,
            key = { state.visibleEvents[it].id },
        ) { index ->
            val entry = state.visibleEvents[index]
            EventCard(
                event = entry.event,
                symbol = entry.symbol,
                modifier = Modifier.clickable { onOpenSecurity(entry.symbol) },
            )
        }
    }
}

@Composable
private fun CategoryFilter(
    categories: List<EvidenceCategory>,
    selected: EvidenceCategory?,
    onSelect: (EvidenceCategory?) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small),
    ) {
        FilterChip("All", selected == null) { onSelect(null) }
        for (category in categories) {
            FilterChip(category.displayName, selected == category) { onSelect(category) }
        }
    }
}

/**
 * A filter pill. Plain rather than tinted by category — the categories are
 * kinds of evidence, not levels of severity.
 */
@Composable
private fun FilterChip(title: String, isOn: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        title,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = if (isOn) accent else LibraTheme.colors.secondaryText,
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (isOn) {
                    accent.copy(alpha = LibraAlpha.chipSelected)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
            )
            .clickable(onClick = onClick)
            .semantics { selected = isOn }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun EmptyResearch() {
    Column(
        modifier = Modifier.fillMaxSize().padding(LibraSpacing.large),
        verticalArrangement = Arrangement.spacedBy(LibraSpacing.medium, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing detected yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Changes are recorded as you open securities. Visit one from your " +
                "watchlist, and anything unusual in its price, volume, volatility, " +
                "or filings will collect here.",
            style = MaterialTheme.typography.bodySmall,
            color = LibraTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        // The reason an open session is missing from a feed the user expects to
        // be complete. Said here rather than left to be discovered.
        Text(
            "A move in a session that is still open is shown on the security's " +
                "own page but not recorded here until it closes — a reading taken " +
                "at midday is not yet what happened that day.",
            style = MaterialTheme.typography.bodySmall,
            color = LibraTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
    }
}
