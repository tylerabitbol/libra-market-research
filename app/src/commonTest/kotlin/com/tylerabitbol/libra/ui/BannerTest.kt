package com.tylerabitbol.libra.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tylerabitbol.libra.ui.components.DisclaimerBanner
import com.tylerabitbol.libra.ui.components.SampleDataBanner
import kotlin.test.Test

/**
 * The two banners the README treats as product rules.
 *
 * The Swift app has no UI tests, so this suite is net-new: `SampleDataBanner`
 * saying the numbers are invented is the single most important sentence the app
 * renders, and "it is still on screen" is not something a view-model test can
 * answer.
 */
@OptIn(ExperimentalTestApi::class)
class BannerTest {

    @Test
    fun sampleDataBannerNamesItsNumbersAsSynthetic() = runComposeUiTest {
        setContent { LibraTheme { SampleDataBanner() } }
        onNodeWithText("Sample data").assertIsDisplayed()
        onNodeWithText("randomly generated", substring = true).assertIsDisplayed()
    }

    @Test
    fun disclaimerBannerSaysItIsNotAdvice() = runComposeUiTest {
        setContent { LibraTheme { DisclaimerBanner() } }
        onNodeWithText("not investment advice", substring = true).assertIsDisplayed()
    }
}
