package com.tylerabitbol.libra.ui.screener

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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.calculations.Screen
import com.tylerabitbol.libra.calculations.ScreenCombinator
import com.tylerabitbol.libra.calculations.ScreenComparison
import com.tylerabitbol.libra.calculations.ScreenField
import com.tylerabitbol.libra.calculations.ScreenRule
import com.tylerabitbol.libra.calculations.ScreenSubject
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
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
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(LibraSpacing.medium),
        ) {
            item { CoverageNote(state) }

            item { SectionHeader("Rules") }
            if (state.screen.rules.isEmpty()) {
                item {
                    TextButton(onClick = onAddRule) { Text("Add a rule") }
                }
            } else {
                item { CombinatorPicker(state.screen, onChangeScreen) }

                itemsIndexed(state.screen.rules) { index, rule ->
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

                item { ScreenNameField(state.screen, onChangeScreen) }

                if (state.untestableCount > 0) {
                    item { UntestableFootnote(state.untestableCount) }
                }
            }

            if (state.savedScreens.isNotEmpty()) {
                item { SectionHeader("Saved screens") }
                items(state.savedScreens.size, key = { state.savedScreens[it].id }) { index ->
                    val saved = state.savedScreens[index]
                    SavedScreenRow(saved, { onApplySaved(saved) }, { onDeleteSaved(saved) })
                }
            }

            item { SectionHeader("${results.size} of ${state.subjects.size} match") }
            when {
                state.subjects.isEmpty() -> item { NothingToScreen() }
                results.isEmpty() -> item {
                    Text(
                        "No held security matches these rules.",
                        style = MaterialTheme.typography.labelSmall,
                        color = LibraTheme.colors.secondaryText,
                    )
                }
                else -> items(results.size, key = { results[it].symbol }) { index ->
                    val subject = results[index]
                    ResultRow(
                        subject = subject,
                        fields = state.screen.rules.map { it.field },
                        onClick = { onOpenSecurity(subject.symbol) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ScreenerToolbar(
    canSave: Boolean,
    onAddRule: () -> Unit,
    onSaveScreen: () -> Unit,
    onNewScreen: () -> Unit,
) {
    var isMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LibraSpacing.large, vertical = LibraSpacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Screener", style = MaterialTheme.typography.titleLarge)
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
    Column(verticalArrangement = Arrangement.spacedBy(LibraSpacing.tight)) {
        Text(
            "Screening the $count $noun this app holds data for — your watchlist " +
                "and anything you have opened. This is not a market-wide scan, and " +
                "nothing here costs an API request.",
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.secondaryText,
        )
        state.loadFailure?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.caution,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = LibraSpacing.small),
    )
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
        TextButton(onClick = { isOpen = true }) { Text(field.displayName) }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(LibraSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldPicker(rule.field) { onChange(rule.copy(field = it)) }
            // SwiftUI deleted a rule by swiping the list row. An explicit
            // control is discoverable without a gesture nothing announces.
            TextButton(onClick = onRemove) { Text("Remove") }
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
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
        ),
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
@Composable
private fun UntestableFootnote(count: Int) {
    val clause = if (count == 1) "security lacks" else "securities lack"
    Text(
        "$count held $clause a figure one of these rules needs, so they cannot " +
            "match. Open them once to fill in what is missing.",
        style = MaterialTheme.typography.labelSmall,
        color = LibraTheme.colors.secondaryText,
    )
}

@Composable
private fun SavedScreenRow(saved: Screen, onApply: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
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
        modifier = Modifier.fillMaxWidth().padding(vertical = LibraSpacing.large),
        verticalArrangement = Arrangement.spacedBy(LibraSpacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Text(
                subject.symbol,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                subject.name,
                style = MaterialTheme.typography.labelSmall,
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
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = LibraTheme.colors.tertiaryText,
                    )
                }
            }
        }
    }
}
