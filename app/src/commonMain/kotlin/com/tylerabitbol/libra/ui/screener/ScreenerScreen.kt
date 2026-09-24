package com.tylerabitbol.libra.ui.screener

import com.tylerabitbol.libra.ui.LibraIcons
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.ui.components.groupedRowContent
import com.tylerabitbol.libra.ui.components.groupedRowInset
import com.tylerabitbol.libra.ui.components.GroupedSection
import com.tylerabitbol.libra.ui.components.SwipeToDelete
import com.tylerabitbol.libra.ui.components.ScreenHeader
import com.tylerabitbol.libra.calculations.Screen
import com.tylerabitbol.libra.calculations.ScreenCombinator
import com.tylerabitbol.libra.calculations.ScreenComparison
import com.tylerabitbol.libra.calculations.ScreenField
import com.tylerabitbol.libra.calculations.ScreenRule
import com.tylerabitbol.libra.calculations.ScreenSubject
import com.tylerabitbol.libra.ui.LibraShapes
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.viewmodels.ScreenerUiState
import com.tylerabitbol.libra.viewmodels.results
import com.tylerabitbol.libra.viewmodels.untestableCount

/**
 * Section 14, scoped to what is true.
 *
 * The spec implies screening a universe; free tiers make that impossible, and
 * a screen that silently examined a dozen stocks while looking like it
 * examined a market would be worse than none. This screens what the app holds
 * and says so plainly at the top, which is the difference between a small
 * honest tool and a misleading one.
 */
@Composable
fun ScreenerScreen(
    state: ScreenerUiState,
    onChangeScreen: (Screen) -> Unit,
    onAddRule: () -> Unit,
    onRemoveRule: (Int) -> Unit,
    onSaveScreen: () -> Unit,
    onNewScreen: () -> Unit,
    onApplySaved: (Screen) -> Unit,
    onDeleteSaved: (Screen) -> Unit,
    onOpenSecurity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val results = state.results

    Column(modifier.fillMaxWidth()) {
        ScreenerToolbar(
            canSave = state.screen.rules.isNotEmpty(),
            onAddRule = onAddRule,
            onSaveScreen = onSaveScreen,
            onNewScreen = onNewScreen,
        )

        LazyColumn(
            contentPadding = PaddingValues(
                start = LibraSpacing.large,
                end = LibraSpacing.large,
                top = LibraSpacing.small,
                bottom = 32.dp,
            ),
            // UIKit's gap between grouped sections.
            verticalArrangement = Arrangement.spacedBy(LibraSpacing.wide),
        ) {
            // Swift's screener is one `List`: every block below is a section
            // of it, so every block is a grouped card with its rows inset the
            // same way, rather than loose text and buttons on the page.
            item {
                GroupedSection {
                    row { CoverageNote(state) }
                }
            }

            item {
                GroupedSection(
                    header = "Rules",
                    footer = state.untestableCount
                        .takeIf { state.screen.rules.isNotEmpty() && it > 0 }
                        ?.let(::untestableFootnote),
                ) {
                    if (state.screen.rules.isEmpty()) {
                        row { AddRuleRow(onAddRule) }
                    } else {
                        row {
                            Box(Modifier.groupedRowContent()) {
                                CombinatorPicker(state.screen, onChangeScreen)
                            }
                        }
                        for ((index, rule) in state.screen.rules.withIndex()) {
                            row {
                                SwipeToDelete(
                                    rowKey = "rule-$index-${rule.field.raw}",
                                    label = "Remove",
                                    onDelete = { onRemoveRule(index) },
                                ) {
                                    RuleEditor(
                                        rule = rule,
                                        onChange = { updated ->
                                            val rules = state.screen.rules.toMutableList()
                                            rules[index] = updated
                                            onChangeScreen(state.screen.copy(rules = rules))
                                        },
                                        onRemove = { onRemoveRule(index) },
                                    )
                                }
                            }
                        }
                        row {
                            Box(Modifier.groupedRowContent()) {
                                ScreenNameField(state.screen, onChangeScreen)
                            }
                        }
                    }
                }
            }

            if (state.savedScreens.isNotEmpty()) {
                item {
                    GroupedSection(header = "Saved screens") {
                        for (saved in state.savedScreens) {
                            row {
                                // The swipe sits inside the group, so the red
                                // panel it reveals is clipped by the group's
                                // rounded fill rather than squaring it off.
                                SwipeToDelete(
                                    rowKey = saved.id,
                                    label = "Delete",
                                    onDelete = { onDeleteSaved(saved) },
                                ) {
                                    SavedScreenRow(
                                        saved,
                                        { onApplySaved(saved) },
                                        { onDeleteSaved(saved) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                GroupedSection(header = "${results.size} of ${state.subjects.size} match") {
                    when {
                        state.subjects.isEmpty() -> row { NothingToScreen() }
                        results.isEmpty() -> row {
                            Text(
                                "No held security matches these rules.",
                                style = MaterialTheme.typography.bodySmall,
                                color = LibraTheme.colors.secondaryText,
                                modifier = Modifier.groupedRowContent(),
                            )
                        }
                        else -> for (subject in results) {
                            row {
                                ResultRow(
                                    subject = subject,
                                    fields = state.screen.rules.map { it.field },
                                    onClick = { onOpenSecurity(subject.symbol) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddRuleRow(onAddRule: () -> Unit) {
    Text(
        "Add a rule",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAddRule)
            .groupedRowContent(),
    )
}

@Composable
private fun ScreenerToolbar(
    canSave: Boolean,
    onAddRule: () -> Unit,
    onSaveScreen: () -> Unit,
    onNewScreen: () -> Unit,
) {
    var isMenuOpen by remember { mutableStateOf(false) }

    ScreenHeader("Screener") {
        Box {
            TextButton(onClick = { isMenuOpen = true }) { Text("Screen") }
            DropdownMenu(expanded = isMenuOpen, onDismissRequest = { isMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Add rule") },
                    onClick = { onAddRule(); isMenuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text("Save this screen") },
                    enabled = canSave,
                    onClick = { onSaveScreen(); isMenuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text("Start over") },
                    onClick = { onNewScreen(); isMenuOpen = false },
                )
            }
        }
    }
}

/**
 * What is actually being screened, stated before any result is shown. The
 * count is the whole point: a reader who thinks this swept the market would
 * draw conclusions the data cannot carry.
 */
@Composable
private fun CoverageNote(state: ScreenerUiState) {
    val count = state.subjects.size
    val noun = if (count == 1) "security" else "securities"
    Column(
        Modifier.groupedRowContent(),
        verticalArrangement = Arrangement.spacedBy(LibraSpacing.tight),
    ) {
        Text(
            "Screening the $count $noun this app holds data for — your watchlist " +
                "and anything you have opened. This is not a market-wide scan, and " +
                "nothing here costs an API request.",
            style = MaterialTheme.typography.bodySmall,
            color = LibraTheme.colors.secondaryText,
        )
        state.loadFailure?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.caution,
            )
        }
    }
}

@Composable
private fun CombinatorPicker(screen: Screen, onChange: (Screen) -> Unit) {
    val options = ScreenCombinator.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        for ((index, option) in options.withIndex()) {
            SegmentedButton(
                selected = screen.combinator == option,
                onClick = { onChange(screen.copy(combinator = option)) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(option.displayName, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The figure a rule tests, chosen from the fields the screener understands. */
@Composable
private fun FieldPicker(field: ScreenField, onSelect: (ScreenField) -> Unit) {
    var isOpen by remember { mutableStateOf(false) }
    Box {
        // No side padding, so the label lines up with the rows around it.
        TextButton(onClick = { isOpen = true }, contentPadding = PaddingValues(0.dp)) {
            Text(field.displayName)
        }
        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            for (option in ScreenField.entries) {
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    onClick = {
                        onSelect(option)
                        isOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RuleEditor(
    rule: ScreenRule,
    onChange: (ScreenRule) -> Unit,
    onRemove: () -> Unit,
) {
    // A row of the Rules section, so inset like one — not a card of its own.
    // Less above and below than a text row: the buttons at the top and the
    // segmented control at the bottom carry their own touch padding.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = groupedRowInset, vertical = LibraSpacing.tight),
        verticalArrangement = Arrangement.spacedBy(LibraSpacing.tight),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldPicker(rule.field) { onChange(rule.copy(field = it)) }
            // SwiftUI deleted a rule by swiping the list row. An explicit
            // control is discoverable without a gesture nothing announces.
            TextButton(onClick = onRemove, contentPadding = PaddingValues(0.dp)) { Text("Remove") }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val comparisons = ScreenComparison.entries
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                for ((index, option) in comparisons.withIndex()) {
                    SegmentedButton(
                        selected = rule.comparison == option,
                        onClick = { onChange(rule.copy(comparison = option)) },
                        shape = SegmentedButtonDefaults.itemShape(index, comparisons.size),
                    ) {
                        Text(option.displayName, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            ThresholdField(rule, onChange)
        }
    }
}

/**
 * The threshold is edited as text and only committed when it parses.
 *
 * Holding the draft as a string rather than rewriting it from the parsed
 * number on every keystroke is what lets "-", "1." and "0.0" be typed at all.
 */
@Composable
private fun ThresholdField(rule: ScreenRule, onChange: (ScreenRule) -> Unit) {
    var draft by remember(rule.id) { mutableStateOf(formatThreshold(rule.threshold)) }

    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            draft = text
            text.toDoubleOrNull()?.let { onChange(rule.copy(threshold = it)) }
        },
        singleLine = true,
        textStyle = LibraType.figureEmphasis.copy(textAlign = TextAlign.End),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .width(110.dp)
            .semantics { contentDescription = "Threshold value" },
    )
}

private fun formatThreshold(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun ScreenNameField(screen: Screen, onChange: (Screen) -> Unit) {
    OutlinedTextField(
        value = screen.name,
        onValueChange = { onChange(screen.copy(name = it)) },
        singleLine = true,
        label = { Text("Name this screen") },
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A screen that quietly drops what it could not test reports a smaller
 * universe than the user thinks they searched.
 */
private fun untestableFootnote(count: Int): String {
    val clause = if (count == 1) "security lacks" else "securities lack"
    return "$count held $clause a figure one of these rules needs, so they cannot " +
        "match. Open them once to fill in what is missing."
}

@Composable
private fun SavedScreenRow(saved: Screen, onApply: () -> Unit, onDelete: () -> Unit) {
    Row(
        // Inset inside the swipe, not around it, so the delete panel still
        // reaches the card's edge. The trailing inset is smaller because the
        // Delete button carries its own.
        Modifier.fillMaxWidth().padding(start = groupedRowInset, end = LibraSpacing.small, top = LibraSpacing.snug, bottom = LibraSpacing.snug),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onApply),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(saved.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                saved.rules.joinToString("  ·  ") { it.summary },
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.secondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}

@Composable
private fun NothingToScreen() {
    Column(
        modifier = Modifier.groupedRowContent().padding(vertical = LibraSpacing.small),
        verticalArrangement = Arrangement.spacedBy(LibraSpacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            LibraIcons.Screener,
            contentDescription = null,
            tint = LibraTheme.colors.tertiaryText,
            modifier = Modifier.size(44.dp),
        )
        Text("Nothing to screen yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add securities to your watchlist and open a few. The screener works " +
                "over what the app has already collected, so it fills in as you use " +
                "the rest of the app.",
            style = MaterialTheme.typography.bodySmall,
            color = LibraTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResultRow(
    subject: ScreenSubject,
    fields: List<ScreenField>,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .groupedRowContent(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Text(
                subject.symbol,
                style = LibraType.ticker,
            )
            Text(
                subject.name,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // The figures the screen actually tested, so a result can be checked
        // rather than taken on faith.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (field in fields.distinct().sortedBy { it.raw }) {
                subject.value(field)?.let { value ->
                    Text(
                        "${field.displayName}: ${field.format(value)}",
                        style = LibraType.figureSmall,
                        color = LibraTheme.colors.tertiaryText,
                    )
                }
            }
        }
    }
}
