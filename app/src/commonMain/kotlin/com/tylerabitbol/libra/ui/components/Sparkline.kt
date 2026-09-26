package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.ui.LibraTheme

/**
 * A month of closes as a small line, coloured by its direction.
 *
 * Decoration beside figures the row already states, so it is hidden from
 * accessibility rather than described: the change it shows is read out by the
 * row's own percentage. Fewer than two closes draws nothing at all, never a
 * flat placeholder that would read as "unchanged".
 */
@Composable
fun Sparkline(closes: List<Double>, modifier: Modifier = Modifier) {
    val usable = closes.filter { it.isFinite() }
    if (usable.size < 2) return

    val first = usable.first()
    val last = usable.last()
    val color = when {
        last > first -> LibraTheme.colors.positive
        last < first -> LibraTheme.colors.negative
        else -> LibraTheme.colors.secondaryText
    }

    Canvas(modifier.size(width = 56.dp, height = 24.dp).clearAndSetSemantics {}) {
        val low = usable.min()
        val high = usable.max()
        val span = (high - low).takeIf { it > 0 } ?: 1.0
        val inset = 1.5.dp.toPx()
        val width = size.width
        val height = size.height - 2 * inset
        fun at(index: Int): Offset = Offset(
            x = width * index / (usable.size - 1),
            y = inset + (height * (1 - (usable[index] - low) / span)).toFloat(),
        )

        val line = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (index in 1 until usable.size) lineTo(at(index).x, at(index).y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(listOf(color.copy(alpha = 0.16f), color.copy(alpha = 0f))),
        )
        drawPath(
            line,
            color,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
