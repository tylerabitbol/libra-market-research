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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.calculations.HistoricalContext
import com.tylerabitbol.libra.calculations.ValuationMetric
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.ui.LibraShapes
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.ui.components.ClaimRow
import com.tylerabitbol.libra.ui.components.LocalUrlOpener
import com.tylerabitbol.libra.viewmodels.SecurityDetailUiState
import com.tylerabitbol.libra.viewmodels.annualFreeCashFlow
import com.tylerabitbol.libra.viewmodels.annualRevenue
import com.tylerabitbol.libra.viewmodels.insiderSummary
import com.tylerabitbol.libra.viewmodels.valuationContexts
import kotlin.time.Instant

// MARK: - Valuation

@Composable
internal fun ValuationSection(state: SecurityDetailUiState) {
    Card {
        Text("Valuation in context", style = MaterialTheme.typography.titleMedium)
        Text(
            "Each multiple ranked against this company's own history, not " +
                "against other companies.",
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.secondaryText,
        )

        val error = state.metricsError
        val contexts = state.valuationContexts
        when {
            error != null -> Text(
                error.recoverySuggestion ?: error.shortDescription,
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.caution,
            )
            contexts.isEmpty() -> Text(
                if (state.metrics == null) {
                    "Loading…"
                } else {
                    "Not enough history to rank these metrics."
                },
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.secondaryText,
            )
            else -> for ((index, entry) in contexts.withIndex()) {
                ValuationRow(entry.metric, entry.context, entry.asOf)
                if (index != contexts.lastIndex) HorizontalDivider()
            }
        }
    }
}

/** One valuation metric with its position in its own history. */
@Composable
private fun ValuationRow(metric: ValuationMetric, context: HistoricalContext, asOf: Instant) {
    var isExpanded by remember { mutableStateOf(false) }
    val rankable = context.meaningfulness.isRankable

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded },
            horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                metric.displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                metric.format(context.current),
                style = LibraType.figureEmphasis,
            )
            Text(
                if (rankable) "${Format.ordinal(context.percentile)} pctile" else "not meaningful",
                style = LibraType.figureSmall,
                color = if (rankable) {
                    LibraTheme.colors.secondaryText
                } else {
                    LibraTheme.colors.caution
                },
                textAlign = TextAlign.End,
                modifier = Modifier.width(96.dp),
            )
        }

        // A bar implies a position in a range. When the value cannot be
        // ranked, drawing one would assert exactly what we are refusing to.
        if (rankable) PercentileBar(context.percentile)

        if (context.currentIsFromHistory) {
            Text(
                "As of ${Format.shortDate(asOf)} — no current figure published.",
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.tertiaryText,
            )
        }

        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(LibraShapes.panel)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    context.descriptor,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rankable) {
                        LibraTheme.colors.secondaryText
                    } else {
                        LibraTheme.colors.caution
                    },
                )
                if (rankable) {
                    DetailRow(
                        "Range",
                        "${metric.format(context.minimum)} – ${metric.format(context.maximum)}",
                    )
                    DetailRow("Median", metric.format(context.median))
                }
                DetailRow(
                    "Observations",
                    "${context.observationCount} since ${Format.shortDate(context.earliest)}",
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.secondaryText,
        )
        Text(
            value,
            style = LibraType.figureSmall,
        )
    }
}

/** Where the current value sits in its historical range. */
@Composable
private fun PercentileBar(percentile: Int) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(LibraTheme.colors.tertiaryText.copy(alpha = 0.3f))
            .clearAndSetSemantics {
                contentDescription = "${Format.ordinal(percentile)} percentile of its own history"
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction = (percentile.coerceIn(0, 100) / 100f))
                .height(4.dp)
                .clip(CircleShape)
                .background(accent),
        )
    }
}

// MARK: - Fundamentals

@Composable
internal fun FundamentalsSection(state: SecurityDetailUiState) {
    Card {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Fundamentals", style = MaterialTheme.typography.titleMedium)
            Text(
                "SEC XBRL",
                style = LibraType.codeSmallEmphasis,
                color = LibraTheme.colors.tertiaryText,
            )
        }

        val error = state.fundamentalsError
        val revenue = state.annualRevenue
        when {
            error != null -> Text(
                error.recoverySuggestion ?: error.shortDescription,
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.caution,
            )
            revenue.isEmpty() -> Text(
                if (state.fundamentals.isEmpty()) "Loading…" else "Not reported.",
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.secondaryText,
            )
            else -> {
                Text(
                    "Annual revenue",
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.secondaryText,
                )
                for (entry in revenue.take(6)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.periodLabel,
                            style = LibraType.codeSmall,
                            color = LibraTheme.colors.secondaryText,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            Format.compactCurrency(entry.fact.value),
                            style = LibraType.figureEmphasis,
                        )
                        Text(
                            Format.signedPercent(entry.growth, precision = 1),
                            style = LibraType.figureSmall,
                            // A neutral tone when growth is unknown — an absent
                            // figure must never read as flat.
                            color = when {
                                entry.growth == null -> LibraTheme.colors.secondaryText
                                entry.growth!! >= 0 -> LibraTheme.colors.positive
                                else -> LibraTheme.colors.negative
                            },
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(62.dp),
                        )
                    }
                }

                val cashFlow = state.annualFreeCashFlow
                if (cashFlow.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    Text(
                        "Free cash flow — operating cash flow less capital expenditures",
                        style = MaterialTheme.typography.labelSmall,
                        color = LibraTheme.colors.secondaryText,
                    )
                    for (entry in cashFlow.takeLast(4).reversed()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                Format.shortDate(entry.period),
                                style = LibraType.codeSmall,
                                color = LibraTheme.colors.secondaryText,
                            )
                            Text(
                                Format.compactCurrency(entry.value),
                                style = LibraType.figureEmphasis,
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Insider activity

/**
 * Section 10: summaries, never a raw list, and never an implication that
 * insider activity predicts anything.
 */
@Composable
internal fun InsiderSection(state: SecurityDetailUiState) {
    val summary = state.insiderSummary ?: return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Insider activity", style = MaterialTheme.typography.titleMedium)
            PrimarySourceTag()
        }

        Card {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                InsiderCount("Bought", summary.purchaseCount, summary.purchaseValue)
                InsiderCount("Sold", summary.saleCount, summary.saleValue)
            }
            if (summary.scheduledCount + summary.routineCount > 0) {
                // Stated rather than silently dropped: a reader who counts
                // Form 4s elsewhere should be able to reconcile.
                Text(
                    "${summary.scheduledCount} scheduled-plan and " +
                        "${summary.routineCount} routine transactions excluded.",
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.tertiaryText,
                )
            }
            HorizontalDivider()
            ClaimRow(summary.claim)
        }
    }
}

@Composable
private fun InsiderCount(label: String, count: Int, value: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.secondaryText,
        )
        Text(
            "$count",
            style = LibraType.figureTitle,
        )
        Text(
            value?.let { "~${Format.compactCurrency(it)}" } ?: Format.notAvailable,
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.tertiaryText,
        )
    }
}

@Composable
private fun PrimarySourceTag() {
    Text(
        "PRIMARY SOURCE",
        style = LibraType.codeSmallEmphasis,
        color = LibraTheme.colors.secondaryText,
        modifier = Modifier
            .clip(LibraShapes.chip)
            .background(LibraTheme.colors.tertiaryText.copy(alpha = 0.2f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

// MARK: - Filings

@Composable
internal fun FilingsSection(state: SecurityDetailUiState) {
    Card {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recent filings", style = MaterialTheme.typography.titleMedium)
            Text(
                "PRIMARY SOURCE",
                style = LibraType.codeSmallEmphasis,
                color = LibraTheme.colors.tertiaryText,
            )
        }

        val error = state.filingsError
        when {
            error != null -> Text(
                error.recoverySuggestion ?: error.shortDescription,
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.caution,
            )
            state.filings.isEmpty() -> Text(
                "Loading…",
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.secondaryText,
            )
            else -> for (filing in state.filings.take(8)) {
                FilingRow(filing)
            }
        }
    }
}

@Composable
private fun FilingRow(filing: FilingDTO) {
    val openUrl = LocalUrlOpener.current
    val url = filing.primaryDocumentURL ?: filing.filingIndexURL

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            filing.formType,
            style = LibraType.codeSmall.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.width(46.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                "Filed ${Format.shortDate(filing.filedAt)}",
                style = MaterialTheme.typography.labelSmall,
            )
            filing.periodOfReport?.let {
                Text(
                    "Period ending ${Format.shortDate(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LibraTheme.colors.tertiaryText,
                )
            }
        }
        // The app summarises filings; it never replaces them, so the original
        // is always one tap away.
        url?.let {
            Text(
                "↗",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { openUrl(it) }
                    .semanticsOpen(filing.formType),
            )
        }
    }
}

private fun Modifier.semanticsOpen(formType: String): Modifier =
    clearAndSetSemantics { contentDescription = "Open the $formType filing on SEC.gov" }
