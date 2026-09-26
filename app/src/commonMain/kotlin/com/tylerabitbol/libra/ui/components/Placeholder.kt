package com.tylerabitbol.libra.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.ui.LibraShapes
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.prefersReducedMotion

/**
 * Grey shapes where figures will be, for the first load only.
 *
 * Loading and missing are different things: this stands in for data that is
 * on its way, and never for a value the provider did not have, which stays a
 * dash. The shapes pulse slowly rather than shimmer, which is quieter and
 * reads the same way.
 */
@Composable
fun PlaceholderBar(width: Dp, height: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = width, height = height)
            .clip(PlaceholderShape)
            .background(LibraTheme.colors.quaternaryFill),
    )
}

/**
 * [count] rows shaped like a benchmark or watchlist row: a name and a
 * subtitle on the left, a figure on the right, and a line of three small
 * figures beneath. Read to accessibility as one "Loading" element.
 */
@Composable
fun PlaceholderRows(count: Int, modifier: Modifier = Modifier, withFooter: Boolean = true) {
    val pulse = rememberInfiniteTransition(label = "placeholder")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (prefersReducedMotion()) 1f else 0.45f,
        animationSpec = infiniteRepeatable(tween(PulseMillis), RepeatMode.Reverse),
        label = "placeholder alpha",
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(LibraShapes.card)
            .background(LibraTheme.colors.cardFill)
            .alpha(alpha)
            .clearAndSetSemantics { contentDescription = "Loading" },
    ) {
        repeat(count) { index ->
            if (index > 0) GroupedDivider(inset = groupedStackInset)
            Column(
                Modifier.fillMaxWidth().padding(LibraSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlaceholderBar(width = 128.dp, height = 14.dp)
                        PlaceholderBar(width = 72.dp, height = 10.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    PlaceholderBar(width = 76.dp, height = 14.dp)
                }
                if (withFooter) {
                    Row(Modifier.fillMaxWidth().height(12.dp)) {
                        repeat(3) {
                            Box(Modifier.weight(1f)) { PlaceholderBar(width = 48.dp, height = 10.dp) }
                        }
                    }
                }
            }
        }
    }
}

private val PlaceholderShape = RoundedCornerShape(4.dp)
private const val PulseMillis = 900
