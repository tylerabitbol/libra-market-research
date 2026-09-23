package com.tylerabitbol.libra.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.ui.LibraShapes
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType

/**
 * A figure that rolls when it changes: the old value slides out, the new one
 * in, in the direction the number moved when that can be told from the text.
 *
 * Only a change of the formatted string animates, so a refresh that returns
 * the same price draws nothing new.
 */
@Composable
fun AnimatedFigure(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd,
        transitionSpec = {
            val up = numeric(targetState)?.let { new ->
                numeric(initialState)?.let { old -> new >= old }
            } ?: true
            val sign = if (up) 1 else -1
            (slideInVertically(tween(FigureMillis)) { it * sign / 2 } + fadeIn(tween(FigureMillis)))
                .togetherWith(
                    slideOutVertically(tween(FigureMillis)) { -it * sign / 2 } +
                        fadeOut(tween(FigureMillis)),
                )
        },
        label = "figure",
    ) { shown ->
        Text(shown, style = style, color = color)
    }
}

/**
 * A day's change as a filled pill, the way a quote list shows it: green up,
 * red down.
 *
 * The same boundary as [DirectionalChangeText] — zero and missing are both
 * drawn in the neutral fill, and missing reads "Not available" in full to
 * accessibility while showing a dash, because a missing value is never a
 * neutral zero.
 */
@Composable
fun ChangePill(percent: Double?, modifier: Modifier = Modifier) {
    val fill = when {
        percent == null || percent == 0.0 -> LibraTheme.colors.quaternaryFill
        percent > 0 -> LibraTheme.colors.positive
        else -> LibraTheme.colors.negative
    }
    val textColor = if (percent == null || percent == 0.0) {
        LibraTheme.colors.secondaryText
    } else {
        Color.White
    }
    Text(
        if (percent == null) "—" else Format.signedPercent(percent),
        style = LibraType.figureSmall,
        color = textColor,
        textAlign = TextAlign.Center,
        modifier = modifier
            .semantics { if (percent == null) contentDescription = Format.notAvailable }
            .widthIn(min = 64.dp)
            .clip(LibraShapes.panel)
            .background(fill)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The number in a formatted figure, for telling which way it moved. */
private fun numeric(text: String): Double? =
    text.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()

private const val FigureMillis = 220
