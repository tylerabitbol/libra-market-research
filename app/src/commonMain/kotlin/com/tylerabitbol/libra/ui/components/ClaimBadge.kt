package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.models.provenance.Claim
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.models.provenance.SourceReference
import com.tylerabitbol.libra.ui.LibraShapes
import com.tylerabitbol.libra.ui.LibraAlpha
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType

/** The FACT / CALCULATION / INTERPRETATION / HYPOTHESIS label from Section 24. */
@Composable
fun ClaimBadge(kind: ClaimKind, modifier: Modifier = Modifier) {
    val tint = kind.tint()
    Text(
        kind.label,
        style = LibraType.codeSmallEmphasis,
        color = tint,
        modifier = modifier
            .libraChip(tint)
            .semantics { contentDescription = "${kind.label}. ${kind.definition}" },
    )
}

/**
 * Deliberately not a red/green confidence gradient — these distinguish *kind*
 * of statement, not good versus bad news.
 */
@Composable
private fun ClaimKind.tint(): Color = when (this) {
    ClaimKind.Fact -> LibraTheme.colors.claimFact
    ClaimKind.Calculation -> LibraTheme.colors.claimCalculation
    ClaimKind.Interpretation -> LibraTheme.colors.claimInterpretation
    ClaimKind.Hypothesis -> LibraTheme.colors.claimHypothesis
}

/**
 * A claim rendered with its label, and its derivation available on tap.
 *
 * Section 13 forbids a mysterious number: if the app computed something, the
 * user must be able to see exactly how.
 */
@Composable
fun ClaimRow(claim: Claim, modifier: Modifier = Modifier) {
    var isShowingDerivation by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Swift aligns on `.firstTextBaseline`: the badge's label and the
        // claim's first line share a baseline. Top-aligned, the smaller badge
        // text sat visibly high against the line beside it.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ClaimBadge(claim.kind, Modifier.alignByBaseline())
            Text(
                claim.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.alignByBaseline(),
            )
        }

        if (claim.derivation != null || claim.sources.isNotEmpty()) {
            Text(
                if (isShowingDerivation) "Hide the arithmetic" else "Show the arithmetic",
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
                modifier = Modifier.clickable { isShowingDerivation = !isShowingDerivation },
            )
        }

        if (isShowingDerivation) {
            DerivationDetail(claim.derivation, claim.sources)
        }
    }
}

@Composable
private fun DerivationDetail(derivation: Derivation?, sources: List<SourceReference>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LibraShapes.panel)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (derivation != null) {
            Text(
                derivation.formula,
                style = LibraType.code,
                color = LibraTheme.colors.secondaryText,
            )
            for (input in derivation.inputs) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(input.name, style = MaterialTheme.typography.bodySmall)
                    Text(input.value, style = LibraType.code)
                }
            }
            HorizontalDivider(color = LibraTheme.colors.separator)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(
                    "Result",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    derivation.result,
                    style = LibraType.code.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        for (source in sources) {
            // Swift renders a `Link` when the source has a URL. Opening one is
            // a platform capability rather than a Compose one, so the handler
            // is injected by the screen; see `SourceLine`.
            SourceLine(source)
        }
    }
}

/**
 * One source, opened when it has somewhere to go.
 *
 * The opener is a [LocalUrlOpener] rather than a parameter threaded through
 * every card, because the two platforms open a URL differently and no view
 * between here and the screen has an opinion about it.
 */
@Composable
fun SourceLine(source: SourceReference, modifier: Modifier = Modifier) {
    val opener = LocalUrlOpener.current
    val url = source.url
    val text = "${source.provider.displayName}: ${source.detail}"
    Text(
        if (url == null) text else "$text ↗",
        style = MaterialTheme.typography.bodySmall,
        color = if (url == null) {
            LibraTheme.colors.secondaryText
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = if (url == null) {
            modifier
        } else {
            modifier.clickable { opener(url) }
        },
    )
}
