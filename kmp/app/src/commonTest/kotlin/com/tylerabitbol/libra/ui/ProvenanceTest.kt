package com.tylerabitbol.libra.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tylerabitbol.libra.calculations.MoveAttribution
import com.tylerabitbol.libra.models.provenance.Claim
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.models.provenance.SourceReference
import com.tylerabitbol.libra.ui.components.AttributionCard
import com.tylerabitbol.libra.ui.components.ClaimBadge
import com.tylerabitbol.libra.ui.components.ClaimRow
import com.tylerabitbol.libra.ui.components.FigureRow
import com.tylerabitbol.libra.ui.components.MetricCell
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Provenance, as a screen reader receives it.
 *
 * `PLAN.md §8` makes the provenance label a rule rather than a decoration:
 * every figure keeps its source label, and Section 24 forbids a statement
 * reaching the user without its epistemic status. A view-model test can check
 * that a `Claim` carries the right [ClaimKind]; only a UI test can check that
 * the kind survives as far as the node tree — and that a figure cell speaks its
 * label *with* its number instead of announcing a bare "$3.10T".
 *
 * The Swift app has no UI tests, so this suite is net-new.
 */
@OptIn(ExperimentalTestApi::class)
class ProvenanceTest {

    private val retrievedAt = Instant.parse("2026-07-28T14:00:00Z")

    private fun source(detail: String, url: String? = null) = SourceReference(
        provider = DataProviderID.SEC,
        detail = detail,
        url = url,
        retrievedAt = retrievedAt,
    )

    @Test
    fun metricCellSpeaksItsLabelWithItsFigure() = runComposeUiTest {
        setContent {
            LibraTheme { MetricCell(label = "Market cap", value = "\$3.10T") }
        }
        // Not two separate announcements: the merged node carries both, which is
        // what `.accessibilityElement(children: .combine)` buys in Swift.
        onNodeWithContentDescription("Market cap: \$3.10T").assertIsDisplayed()
    }

    @Test
    fun metricCellSaysNotAvailableRatherThanNothing() = runComposeUiTest {
        setContent {
            LibraTheme {
                MetricCell(label = "P/E", value = "Not available", isAvailable = false)
            }
        }
        onNodeWithContentDescription("P/E: Not available").assertIsDisplayed()
    }

    @Test
    fun figureRowSpeaksItsLabelWithItsFigure() = runComposeUiTest {
        setContent {
            LibraTheme { FigureRow(label = "Revenue, Q2 FY2026", value = "\$30.04B") }
        }
        onNodeWithContentDescription("Revenue, Q2 FY2026: \$30.04B").assertIsDisplayed()
    }

    @Test
    fun everyClaimKindRendersItsLabelAndDefinition() = runComposeUiTest {
        setContent {
            LibraTheme {
                androidx.compose.foundation.layout.Column {
                    for (kind in ClaimKind.entries) ClaimBadge(kind)
                }
            }
        }
        for (kind in ClaimKind.entries) {
            onNodeWithText(kind.label).assertIsDisplayed()
            // The badge is four capitalised letters on screen; the definition is
            // the only thing that makes it mean something spoken aloud.
            onNodeWithContentDescription("${kind.label}. ${kind.definition}").assertIsDisplayed()
        }
    }

    @Test
    fun claimRowShowsTheArithmeticOnTap() = runComposeUiTest {
        val claim = Claim(
            kind = ClaimKind.Calculation,
            text = "Revenue grew 14.2% year-over-year.",
            sources = listOf(source("10-Q filed 2026-07-28", "https://www.sec.gov/x")),
            derivation = Derivation(
                formula = "(revenue - revenuePriorYear) / revenuePriorYear",
                inputs = listOf(
                    Derivation.Input(name = "revenue", value = "30,040,000,000"),
                    Derivation.Input(name = "revenuePriorYear", value = "26,300,000,000"),
                ),
                result = "+14.2%",
            ),
        )
        setContent { LibraTheme { ClaimRow(claim) } }

        onNodeWithText("CALCULATION").assertIsDisplayed()
        onNodeWithText(claim.text).assertIsDisplayed()

        // Section 13 forbids a black-box number: the arithmetic is one tap away.
        onNodeWithText("Show the arithmetic").performClick()
        onNodeWithText("(revenue - revenuePriorYear) / revenuePriorYear").assertIsDisplayed()
        onNodeWithText("revenuePriorYear").assertIsDisplayed()
        onNodeWithText("26,300,000,000").assertIsDisplayed()
        onNodeWithText("+14.2%").assertIsDisplayed()
        onNodeWithText("SEC EDGAR: 10-Q filed 2026-07-28", substring = true).assertIsDisplayed()

        onNodeWithText("Hide the arithmetic").performClick()
        onNodeWithText("Show the arithmetic").assertIsDisplayed()
    }

    @Test
    fun aClaimWithNothingBehindItOffersNoArithmetic() = runComposeUiTest {
        val claim = Claim(kind = ClaimKind.Fact, text = "Revenue was \$30.04B in Q2 FY2026.")
        setContent { LibraTheme { ClaimRow(claim) } }
        onNodeWithText("FACT").assertIsDisplayed()
        onNodeWithText("Show the arithmetic").assertDoesNotExist()
    }

    @Test
    fun attributionBarsSpeakTheirFiguresAndTheCardKeepsItsLabel() = runComposeUiTest {
        // A stock down 6.0% on a day the market fell 5.0%: the case the card
        // exists for. No beta and no sector, so the split is two bars.
        val attribution = MoveAttribution(
            securityMove = -6.0,
            marketMove = -5.0,
            marketName = "S&P 500",
            beta = null,
            explainedByMarket = -5.0,
            residual = -1.0,
        )
        setContent { LibraTheme { AttributionCard(attribution) } }

        // Each bar is a drawn shape; the figure only exists for a screen reader
        // because the row collapses to one element carrying it.
        onNodeWithContentDescription("Market accounts for: -5.0 pp").assertIsDisplayed()
        onNodeWithContentDescription("Unexplained: -1.0 pp").assertIsDisplayed()

        // And the card's own sentence is a judgement, badged as one.
        onNodeWithText("INTERPRETATION").assertIsDisplayed()
    }
}
