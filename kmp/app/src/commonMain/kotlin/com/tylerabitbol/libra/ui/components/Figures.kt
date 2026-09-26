package com.tylerabitbol.libra.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import com.tylerabitbol.libra.support.Freshness
import com.tylerabitbol.libra.viewmodels.ChartValueFormat
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
import com.tylerabitbol.libra.ui.prefersReducedMotion

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
    if (prefersReducedMotion()) {
        Text(text, style = style, color = color, modifier = modifier)
        return
    }
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
    val shown = Format.displayedSign(percent)
    val fill = when {
        shown > 0 -> LibraTheme.colors.positive
        shown < 0 -> LibraTheme.colors.negative
        else -> LibraTheme.colors.quaternaryFill
    }
    val textColor = if (shown == 0) {
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

/**
 * A page's headline price: the figure, then the day's move in its direction's
 * colour, then how old both are, on one line beneath.
 *
 * The move reads "+$2.72 (+0.80%) today" when the absolute change is known
 * and falls back to the percentage alone when it is not. Zero and missing are
 * both secondary, the same boundary as [DirectionalChangeText]; missing shows
 * a dash, never "+0.00%". [isPending] draws placeholder shapes instead,
 * for the moment before the first load has answered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeroPrice(
    price: String,
    change: Double?,
    percent: Double?,
    valueFormat: ChartValueFormat,
    modifier: Modifier = Modifier,
    freshness: Freshness? = null,
    caption: String = "today",
    isPending: Boolean = false,
) {
    // Before the first load there is no price yet, which is not the same as
    // there being none: shapes, not "Not available" in hero type.
    if (isPending) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlaceholderBar(width = 150.dp, height = 36.dp)
            PlaceholderBar(width = 190.dp, height = 16.dp)
        }
        return
    }
    // The sign as printed, so a move that rounds to zero is not coloured.
    val shown = if (change != null) Format.displayedSign(change) else Format.displayedSign(percent)
    val color = when {
        shown > 0 -> LibraTheme.colors.positive
        shown < 0 -> LibraTheme.colors.negative
        else -> LibraTheme.colors.secondaryText
    }
    val move = when {
        change != null -> moveText(change, percent, valueFormat)
        percent != null -> Format.signedPercent(percent)
        else -> null
    }
    Column(modifier) {
        AnimatedFigure(
            price,
            style = LibraType.figureHero,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (move == null) "—" else "$move $caption",
                style = LibraType.figureEmphasis,
                color = color,
                modifier = Modifier.semantics {
                    if (move == null) contentDescription = "Change ${Format.notAvailable}"
                },
            )
            if (freshness != null) {
                Text("·", style = MaterialTheme.typography.labelSmall, color = LibraTheme.colors.tertiaryText)
                FreshnessLabel(freshness)
            }
        }
    }
}

/** The number in a formatted figure, for telling which way it moved. */
private fun numeric(text: String): Double? =
    text.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()

private const val FigureMillis = 220
