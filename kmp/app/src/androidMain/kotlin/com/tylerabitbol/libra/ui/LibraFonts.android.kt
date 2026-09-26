package com.tylerabitbol.libra.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Metric-similar open faces standing in for SF, which cannot ship in an APK.
 *
 * - Inter for SF Pro Text.
 * - Nunito for SF Pro Rounded. *Nunito*, not Nunito Sans: the two are one
 *   project, and Nunito Sans is the version whose terminals are not rounded,
 *   which is the one property this face is here for. Its digits are all the
 *   same width out of the box, so figures line up with or without `tnum`.
 * - JetBrains Mono for SF Mono.
 *
 * Each is a single variable font, so every weight the styles ask for is its own
 * entry pointing at the same file with the weight axis set. Variable fonts need
 * API 26, which is minSdk. The weights listed are the ones the type scale and
 * `LibraType` actually use; anything else is synthesised from the nearest one.
 *
 * All three are SIL OFL 1.1. The licences ship in `res/raw` beside them, and
 * Settings → About credits them.
 */
private val textWeights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

private fun variableFamily(resource: Int, weights: List<FontWeight>) = FontFamily(
    weights.map { weight ->
        Font(
            resource,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    },
)

private val resolved = LibraFontFamilies(
    text = variableFamily(R.font.inter, textWeights),
    // Figures are always medium; the others cover a caller that overrides it.
    rounded = variableFamily(R.font.nunito, listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)),
    mono = variableFamily(R.font.jetbrains_mono, listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)),
)

@Composable
actual fun libraFontFamilies(): LibraFontFamilies = resolved

actual val bundledFontCredit: String? =
    "Type is set in Inter, Nunito and JetBrains Mono, each used under the SIL Open " +
        "Font License 1.1."
