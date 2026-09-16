package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme

/**
 * The standing "this is not advice" notice, on the first screen rather than
 * buried in Settings.
 *
 * It lived only in Settings → About, which meant a reader could use every
 * screen in the app without ever seeing it. Deliberately quieter than
 * [SampleDataBanner]: that one warns that the numbers on screen are invented,
 * which must stay the loudest thing in the app, and two orange banners
 * competing would blunt it.
 */
@Composable
fun DisclaimerBanner(modifier: Modifier = Modifier) {
    Banner(
        modifier = modifier,
        background = MaterialTheme.colorScheme.surfaceVariant,
        glyph = "i",
        glyphColor = LibraTheme.colors.secondaryText,
        title = "Research tool — not investment advice",
        body = "Libra analyses published information. It makes no recommendations, " +
            "and data may be delayed or inaccurate.",
    )
}

/**
 * Shown whenever the view behind it is rendering synthetic data.
 *
 * This is not decoration. Sample prices are plausible-looking random walks, and
 * a research tool that displays them without saying so is actively misleading —
 * the one failure mode worse than showing nothing at all.
 */
@Composable
fun SampleDataBanner(modifier: Modifier = Modifier) {
    Banner(
        modifier = modifier,
        background = LibraTheme.colors.caution.copy(alpha = 0.12f),
        glyph = "!",
        glyphColor = LibraTheme.colors.caution,
        title = "Sample data",
        body = "These numbers are randomly generated, not real market data. " +
            "Add your API keys in Settings.",
        accessibilityLabel = "Warning: sample data. These numbers are randomly " +
            "generated, not real market data.",
    )
}

@Composable
private fun Banner(
    background: Color,
    glyph: String,
    glyphColor: Color,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    accessibilityLabel: String? = null,
) {
    val label = accessibilityLabel ?: "$title. $body"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = LibraSpacing.medium, vertical = 10.dp)
            // One announcement rather than three fragments, which is what
            // SwiftUI's `.accessibilityElement(children: .combine)` buys.
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = glyphColor,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
            )
        }
    }
}
