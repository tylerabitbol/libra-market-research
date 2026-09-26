package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.ui.LibraAlpha
import com.tylerabitbol.libra.ui.LibraShapes
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The card, as one pattern.
 *
 * Swift repeats this nineteen times: fourteen points of padding and a
 * twelve-point rounded fill of `secondarySystemGroupedBackground`, separated
 * from the page by fill contrast alone. The refresh (`PLAN.md` Stage 5) rounds
 * it to fourteen and gives it a hairline edge in light mode, where the fill
 * contrast is a few percent; still no shadow.
 */
@Composable
@ReadOnlyComposable
fun Modifier.libraCard(shape: Shape = LibraShapes.card): Modifier =
    clip(shape)
        .background(LibraTheme.colors.cardFill)
        .border(0.5.dp, LibraTheme.colors.cardEdge, shape)
        .padding(LibraSpacing.card)

/**
 * The tinted chip, as one pattern.
 *
 * `.background(tint.opacity(0.15), in: .rect(cornerRadius: 4))` with
 * `.foregroundStyle(tint)`, which is on nearly every screen. The caller still
 * sets the text colour, because the point of the pattern is that the fill and
 * the text are the same colour.
 */
fun Modifier.libraChip(
    tint: Color,
    alpha: Float = LibraAlpha.chipFill,
    shape: Shape = LibraShapes.chip,
): Modifier =
    clip(shape)
        .background(tint.copy(alpha = alpha))
        .padding(horizontal = 5.dp, vertical = 2.dp)
