package com.tylerabitbol.libra.ui.security

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.calculations.ChangeWindow
import kotlin.time.Clock
import kotlin.time.Instant
import com.tylerabitbol.libra.calculations.EventDetector
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.components.menuCheckmark
import com.tylerabitbol.libra.ui.components.AttributionCard
import com.tylerabitbol.libra.ui.components.EventCard
import com.tylerabitbol.libra.ui.components.FilingAnalysisCard
import com.tylerabitbol.libra.viewmodels.SecurityDetailUiState
import com.tylerabitbol.libra.viewmodels.analysis
import com.tylerabitbol.libra.viewmodels.availableKinds
import com.tylerabitbol.libra.viewmodels.availableKindsByCategory
import com.tylerabitbol.libra.viewmodels.coverageNote
import com.tylerabitbol.libra.viewmodels.isNew
import com.tylerabitbol.libra.viewmodels.latestAttribution
import com.tylerabitbol.libra.viewmodels.newSinceLastVisit

// MARK: - What changed

/**
 * The question the whole app exists to answer, so it sits directly under the
 * price rather than below the fold.
 *
 * An empty result is stated plainly. "Nothing unusual" is a real finding and a
 * useful one; leaving the section out entirely would make its absence
 * indistinguishable from a section that failed to load.
 */
@Composable
internal fun ChangesSection(
    state: SecurityDetailUiState,
    onSetChangeWindow: (ChangeWindow) -> Unit,
    onSetKindFilter: (Set<EventKind>) -> Unit,
) {
    // Null until the reader opens or shuts the panel themselves. Until then it
    // follows the panel's own purpose: open when something happened since the
    // last visit, shut when nothing did. A section with nothing new in it
    // should not push the chart off the screen to say so.
    var expanded by remember(state.symbol) { mutableStateOf<Boolean?>(null) }
    val isShowing = expanded ?: state.newSinceLastVisit.isNotEmpty()
    val summary = collapsedChangesSummary(state)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { expanded = !isShowing }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "What changed"
                        stateDescription = if (isShowing) "Expanded" else "Collapsed"
                    },
            ) {
                Text("What changed", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isShowing) "▾" else "▸",
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.secondaryText,
                )
            }
            if (isShowing) {
                Row {
                    WindowMenu(state.changeWindow, onSetChangeWindow)
                    if (state.availableKinds.isNotEmpty()) {
                        KindMenu(state, onSetKindFilter)
                    }
                }
            }
        }

        if (isShowing) {
            ChangesBody(state)
        } else {
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.secondaryText,
            )
        }
    }
}

/**
 * What the header stands in for while the panel is shut. A count only helps if
 * it says how far back it counted.
 *
 * "Nothing unusual" is a real finding, and saying it plainly is the point of
 * the section — but only once there is something to say it about. Detection
 * runs off stored history, and a page that is still loading has none, so the
 * empty state and the not-yet state used to render identically. Reaching a
 * security by `-LibraOpenSymbol` showed "Nothing unusual in this window" for a
 * security that had two changes, because the launch argument arrives before
 * the data does. Stating that it is still looking costs one line and is the
 * difference between a finding and a guess.
 */
private fun collapsedChangesSummary(state: SecurityDetailUiState): String {
    if (state.events.isEmpty() && state.isLoading) return "Looking for changes…"
    if (state.events.isEmpty()) return "Nothing unusual in this window."
    val count = state.events.size
    val new = state.newSinceLastVisit.size
    var summary = Format.count(count, "change")
    if (new > 0) summary += " · $new new"
    state.changeWindow.startDate(state.lastVisit)?.let {
        summary += " since ${Format.shortDate(it)}"
    }
    return summary
}

@Composable
private fun ChangesBody(state: SecurityDetailUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.changeWindow.startDate(state.lastVisit)?.let {
            Text(
                "Since ${Format.shortDate(it)}",
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.tertiaryText,
            )
        }
        state.coverageNote?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.tertiaryText,
            )
        }

        if (state.events.isEmpty()) {
            Card {
                Text(
                    emptyChangesMessage(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.secondaryText,
                )
            }
        } else {
            if (state.lastVisit == null) {
                Text(
                    "This is your first visit, so everything below is reported " +
                        "from the available history rather than as new.",
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.secondaryText,
                )
            }
            for (event in state.events) {
                EventCard(event = event, isNew = state.isNew(event))
                if (event.kind == EventKind.UnusualPriceMove) {
                    state.latestAttribution?.let { AttributionCard(it) }
                }
                state.analysis(event)?.let { FilingAnalysisCard(it) }
            }
        }
    }
}

/** Why the panel is empty, which is never the same reason twice. */
private fun emptyChangesMessage(state: SecurityDetailUiState): String {
    if (state.bars.isEmpty()) {
        return if (state.historyError == null) {
            "Loading price history…"
        } else {
            "No price history loaded, so nothing can be compared."
        }
    }
    // Bars are in but the page has not finished: detection also reads the
    // stored history and the last visit, and neither is necessarily there yet.
    // Without this the panel states a finding before it has the inputs for one.
    if (state.isLoading) {
        return "Still loading this security's data, so nothing has been compared yet."
    }
    if (state.bars.size < EventDetector.minimumSample) {
        return "Only ${Format.count(state.bars.size, "session")} of history is " +
            "available. At least ${EventDetector.minimumSample} are needed " +
            "before \"unusual\" means anything."
    }
    if (state.kindFilter.isNotEmpty()) {
        val names = state.kindFilter.map { it.displayName }.sorted()
        val subject = if (names.size == 1) " (${names[0]})" else "s"
        return "Nothing of the selected kind$subject in this window. Widen the " +
            "window or clear the filter."
    }
    if (state.changeWindow is ChangeWindow.LastVisit && state.lastVisit == null) {
        return "Nothing unusual in the recent price, volume, or volatility. " +
            "This is your first visit, so there is no earlier point to compare " +
            "against — choose a window above to look further back."
    }
    return "Nothing unusual in the price, volume, or volatility over this " +
        "window, and no new filings in it."
}

/**
 * A date the reader picks by hand, for windows the fixed options miss — an
 * earnings date, the day a thesis was formed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowMenu(current: ChangeWindow, onSelect: (ChangeWindow) -> Unit) {
    var isOpen by remember { mutableStateOf(false) }
    var isChoosingDate by remember { mutableStateOf(false) }

    if (isChoosingDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (current as? ChangeWindow.Custom)
                ?.date?.toEpochMilliseconds(),
            // A window that opens in the future selects nothing, so the field
            // cannot be set to a date no data can exist for.
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= Clock.System.now().toEpochMilliseconds()
            },
        )
        DatePickerDialog(
            onDismissRequest = { isChoosingDate = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedDateMillis != null,
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            onSelect(ChangeWindow.Custom(Instant.fromEpochMilliseconds(it)))
                        }
                        isChoosingDate = false
                    },
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { isChoosingDate = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(pickerState, title = { Text("Show changes since") })
        }
    }

    Box {
        TextButton(onClick = { isOpen = true }) {
            Text(current.displayName, style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            for (window in ChangeWindow.offered) {
                DropdownMenuItem(
                    text = { Text(window.displayName) },
                    leadingIcon = menuCheckmark(window == current),
                    onClick = {
                        onSelect(window)
                        isOpen = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Custom date…") },
                leadingIcon = menuCheckmark(false),
                onClick = {
                    isChoosingDate = true
                    isOpen = false
                },
            )
        }
    }
}

@Composable
private fun KindMenu(state: SecurityDetailUiState, onSelect: (Set<EventKind>) -> Unit) {
    var isOpen by remember { mutableStateOf(false) }
    val filter = state.kindFilter
    val title = when (filter.size) {
        0 -> "All kinds"
        1 -> filter.first().displayName
        else -> "${filter.size} kinds"
    }

    Box {
        TextButton(onClick = { isOpen = true }) {
            Text(title, style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            DropdownMenuItem(
                text = { Text("All kinds") },
                leadingIcon = menuCheckmark(filter.isEmpty()),
                onClick = {
                    onSelect(emptySet())
                    isOpen = false
                },
            )
            for ((category, kinds) in state.availableKindsByCategory) {
                HorizontalDivider()
                Text(
                    category.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.secondaryText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                // The category header toggles its whole group.
                DropdownMenuItem(
                    text = { Text("All ${category.displayName.lowercase()}") },
                    leadingIcon = menuCheckmark(kinds.all { it in filter }),
                    onClick = { onSelect(toggled(filter, kinds)) },
                )
                for (kind in kinds) {
                    DropdownMenuItem(
                        text = { Text(kind.displayName) },
                        leadingIcon = menuCheckmark(kind in filter),
                        onClick = { onSelect(toggled(filter, listOf(kind))) },
                    )
                }
            }
        }
    }
}

/** Adds the group if any of it is missing, removes it once it is all there. */
private fun toggled(filter: Set<EventKind>, kinds: List<EventKind>): Set<EventKind> =
    if (kinds.all { it in filter }) filter - kinds.toSet() else filter + kinds.toSet()
