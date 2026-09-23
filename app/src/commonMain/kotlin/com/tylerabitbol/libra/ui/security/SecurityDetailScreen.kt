package com.tylerabitbol.libra.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.calculations.ChangeWindow
import kotlin.time.Clock
import kotlin.time.Instant
import com.tylerabitbol.libra.calculations.EventDetector
import com.tylerabitbol.libra.calculations.PeriodReturn
import com.tylerabitbol.libra.models.core.ChartAvailability
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.support.RelativeTimeText
import com.tylerabitbol.libra.support.Freshness
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.components.libraCard
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.ui.components.menuCheckmark
import com.tylerabitbol.libra.ui.components.AttributionCard
import com.tylerabitbol.libra.ui.components.ClaimRow
import com.tylerabitbol.libra.ui.components.DirectionalChangeText
import com.tylerabitbol.libra.ui.components.EventCard
import com.tylerabitbol.libra.ui.components.FilingAnalysisCard
import com.tylerabitbol.libra.ui.components.PinnedFreshnessLabel
import com.tylerabitbol.libra.ui.components.RangeBar
import com.tylerabitbol.libra.ui.components.PriceChart
import com.tylerabitbol.libra.ui.components.ResearchProfileCard
import com.tylerabitbol.libra.ui.components.SampleDataBanner
import com.tylerabitbol.libra.viewmodels.SecurityDetailUiState
import com.tylerabitbol.libra.viewmodels.analysis
import com.tylerabitbol.libra.viewmodels.availableKinds
import com.tylerabitbol.libra.viewmodels.availableKindsByCategory
import com.tylerabitbol.libra.viewmodels.chartAvailability
import com.tylerabitbol.libra.viewmodels.chartAxisTicks
import com.tylerabitbol.libra.viewmodels.chartBars
import com.tylerabitbol.libra.viewmodels.chartNote
import com.tylerabitbol.libra.viewmodels.chartPoints
import com.tylerabitbol.libra.viewmodels.chartSegments
import com.tylerabitbol.libra.viewmodels.coverageNote
import com.tylerabitbol.libra.viewmodels.displayChangePercent
import com.tylerabitbol.libra.viewmodels.displayPrice
import com.tylerabitbol.libra.viewmodels.freshness
import com.tylerabitbol.libra.viewmodels.isNew
import com.tylerabitbol.libra.viewmodels.isShowingSavedCopy
import com.tylerabitbol.libra.viewmodels.latestAttribution
import com.tylerabitbol.libra.viewmodels.marketRangeReturn
import com.tylerabitbol.libra.viewmodels.newSinceLastVisit
import com.tylerabitbol.libra.viewmodels.priceContext
import com.tylerabitbol.libra.viewmodels.rangeReturn
import com.tylerabitbol.libra.viewmodels.relativeToMarket
import com.tylerabitbol.libra.viewmodels.relativeToSector
import com.tylerabitbol.libra.viewmodels.researchProfile
import com.tylerabitbol.libra.viewmodels.savedCopyAsOf
import com.tylerabitbol.libra.viewmodels.sectorRangeReturn

/**
 * The research page for one security (Section 5).
 *
 * Ordered by what answers "what changed and how unusual is it" fastest:
 * price context first, then valuation *with its history* — which is the part a
 * normal stock app doesn't show — then fundamentals, then the filings that
 * back them.
 */
@Composable
fun SecurityDetailScreen(
    state: SecurityDetailUiState,
    isUsingSampleData: Boolean,
    onSelectRange: (ChartRange) -> Unit,
    onSetChangeWindow: (ChangeWindow) -> Unit,
    onSetKindFilter: (Set<EventKind>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(LibraSpacing.large)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (isUsingSampleData) SampleDataBanner()

            Overview(state)
            ChangesSection(state, onSetChangeWindow, onSetKindFilter)
            ChartSection(state, onSelectRange)
            RelativeSection(state)
            ResearchProfileSection(state)
            ValuationSection(state)
            FundamentalsSection(state)
            InsiderSection(state)
            FilingsSection(state)
        }

        // Pinned rather than scrolled away: how old the page is qualifies
        // every figure on it, so it must not be something you scroll past.
        PinnedFreshnessLabel(
            freshness = state.freshness,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
        )
    }
}

// MARK: - Overview

@Composable
private fun Overview(state: SecurityDetailUiState) {
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                Format.currency(state.displayPrice),
                style = LibraType.figureHero,
            )
            DirectionalChangeText(
                state.displayChangePercent,
                style = LibraType.figureEmphasis,
            )
        }

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
private fun ChangesSection(
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

// MARK: - Chart

@Composable
private fun ChartSection(state: SecurityDetailUiState, onSelectRange: (ChartRange) -> Unit) {
    Card {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Price", style = MaterialTheme.typography.titleMedium)
            state.rangeReturn?.let { DirectionalChangeText(it.percent) }
        }

        val ranges = ChartRange.entries
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
private fun RelativeSection(state: SecurityDetailUiState) {
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
    val endsElsewhere = period != null && subjectEnd != null &&
        Format.shortDate(period.endDate) != Format.shortDate(subjectEnd)
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
            if (endsElsewhere && period != null) {
                Text(
                    "to ${Format.shortDate(period.endDate)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.tertiaryText,
                )
            }
        }
        DirectionalChangeText(period?.percent)
    }
}

// MARK: - Research profile

/** Sections 12 and 13, as components rather than a score. */
@Composable
private fun ResearchProfileSection(state: SecurityDetailUiState) {
    val profile = state.researchProfile
    if (profile.measured.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Research profile", style = MaterialTheme.typography.titleMedium)
        Text(
            "Each dimension measured against this company's own history, and " +
                "grouped by which way it points.",
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.secondaryText,
        )
        ResearchProfileCard(profile)
    }
}

/** The grouped-background card every section on this page sits in. */
@Composable
internal fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .libraCard(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
