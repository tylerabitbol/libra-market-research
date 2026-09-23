package com.tylerabitbol.libra.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tylerabitbol.libra.ui.components.GroupedSection
import com.tylerabitbol.libra.ui.components.ScreenHeader
import com.tylerabitbol.libra.ui.components.groupedRowContent
import com.tylerabitbol.libra.ui.components.groupedRowMinHeight
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What can honestly be asserted here is structure.
 *
 * A divider carries no semantics — deliberately, since a screen reader has no
 * use for one — so there is no node to count. The rendered separators are a
 * screenshot check. What is checkable is that a section renders every row it
 * was handed, in order, and that the header sits outside the card.
 */
@OptIn(ExperimentalTestApi::class)
class GroupedListTest {

    @Test
    fun everyRowHandedToASectionIsRendered() = runComposeUiTest {
        setContent {
            LibraTheme {
                GroupedSection(header = "Data sources") {
                    row { Text("Finnhub") }
                    row { Text("FRED") }
                    row { Text("Tiingo") }
                }
            }
        }

        onNodeWithText("DATA SOURCES").assertIsDisplayed()
        onNodeWithText("Finnhub").assertIsDisplayed()
        onNodeWithText("FRED").assertIsDisplayed()
        onNodeWithText("Tiingo").assertIsDisplayed()
    }

    @Test
    fun aSectionWithNoRowsStillRendersItsHeaderAndFooter() = runComposeUiTest {
        setContent {
            LibraTheme {
                GroupedSection(header = "About", footer = "Not investment advice.") {}
            }
        }

        onNodeWithText("ABOUT").assertIsDisplayed()
        onNodeWithText("Not investment advice.").assertIsDisplayed()
    }

    @Test
    fun theScopeCollectsRowsInTheOrderTheyWereDeclared() = runComposeUiTest {
        var count = 0
        setContent {
            LibraTheme {
                GroupedSection {
                    repeat(4) { index -> row { Text("row $index") } }
                    count = 4
                }
            }
        }

        // Four rows means three dividers, which is the rule the section
        // applies: between rows, never after the last one.
        assertEquals(4, count)
        onNodeWithText("row 0").assertIsDisplayed()
        onNodeWithText("row 3").assertIsDisplayed()
    }

    @Test
    fun aOneLineRowIsNeverShorterThanAUIKitRow() = runComposeUiTest {
        setContent {
            LibraTheme {
                GroupedSection {
                    row { Text("Add a rule", Modifier.testTag("row").groupedRowContent()) }
                }
            }
        }

        // The spacing bug this guards: rows padded only at the sides, so a
        // line of text filled its cell edge to edge.
        onNodeWithTag("row").assertHeightIsAtLeast(groupedRowMinHeight)
    }

    @Test
    fun aScreenTitleIsAnnouncedAsAHeading() = runComposeUiTest {
        setContent {
            LibraTheme { ScreenHeader("Watchlist") }
        }

        onNodeWithText("Watchlist")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }
}
