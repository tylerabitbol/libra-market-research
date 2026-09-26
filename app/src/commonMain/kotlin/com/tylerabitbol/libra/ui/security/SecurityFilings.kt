package com.tylerabitbol.libra.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.ui.LibraShapes
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.ui.components.ClaimRow
import com.tylerabitbol.libra.ui.components.LocalUrlOpener
import com.tylerabitbol.libra.viewmodels.SecurityDetailUiState
import com.tylerabitbol.libra.viewmodels.insiderSummary

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
