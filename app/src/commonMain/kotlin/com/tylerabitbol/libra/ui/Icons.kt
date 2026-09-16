package com.tylerabitbol.libra.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The five tab glyphs, drawn here rather than depended on.
 *
 * Swift gets these from SF Symbols, which has no multiplatform equivalent:
 * JetBrains stopped publishing `material-icons-core` after Compose 1.7.3, and
 * the androidx artifact that replaced it is Android-only. Five outlines at
 * twenty-four points is less code than the shim would be, and it keeps the
 * icons matched to the SF Symbols the Swift app names.
 */
object LibraIcons {

    /** `chart.line.uptrend.xyaxis` — a rising line over an axis. */
    val Dashboard: ImageVector = icon("Dashboard") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(3f, 4f); lineTo(3f, 20f); lineTo(21f, 20f)
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(6f, 16f); lineTo(11f, 10f); lineTo(14f, 13f); lineTo(20f, 6f)
        }
    }

    /** `list.bullet.rectangle` — ruled rows in a frame. */
    val Watchlist: ImageVector = icon("Watchlist") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(8f, 9f); lineTo(16f, 9f)
            moveTo(8f, 13f); lineTo(16f, 13f)
            moveTo(8f, 16f); lineTo(13f, 16f)
        }
    }

    /** `magnifyingglass`. */
    val Research: ImageVector = icon("Research") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(15.5f, 10.5f)
            arcToRelative(5f, 5f, 0f, true, true, -10f, 0f)
            arcToRelative(5f, 5f, 0f, true, true, 10f, 0f)
            close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(14.5f, 14.5f); lineTo(20f, 20f)
        }
    }

    /** `line.3.horizontal.decrease.circle` — a narrowing stack, i.e. a filter. */
    val Screener: ImageVector = icon("Screener") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(6f, 12f); lineTo(18f, 12f)
            moveTo(9f, 17f); lineTo(15f, 17f)
        }
    }

    /** `gearshape`, reduced to a ring and its teeth. */
    val Settings: ImageVector = icon("Settings") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(15.2f, 12f)
            arcToRelative(3.2f, 3.2f, 0f, true, true, -6.4f, 0f)
            arcToRelative(3.2f, 3.2f, 0f, true, true, 6.4f, 0f)
            close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(12f, 3f); lineTo(12f, 6f)
            moveTo(12f, 18f); lineTo(12f, 21f)
            moveTo(3f, 12f); lineTo(6f, 12f)
            moveTo(18f, 12f); lineTo(21f, 12f)
            moveTo(5.6f, 5.6f); lineTo(7.8f, 7.8f)
            moveTo(16.2f, 16.2f); lineTo(18.4f, 18.4f)
            moveTo(18.4f, 5.6f); lineTo(16.2f, 7.8f)
            moveTo(7.8f, 16.2f); lineTo(5.6f, 18.4f)
        }
    }

    private fun icon(name: String, body: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
            // Tinted by `Icon`, which paints the whole vector in the current
            // content colour; the black above is only a placeholder.
            autoMirror = false,
        ).apply(body).build()
}

/** Every stroke in this file is a rounded outline, as SF Symbols' are. */
private fun ImageVector.Builder.path(
    stroke: SolidColor,
    strokeLineWidth: Float,
    body: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
) = path(
    fill = null,
    stroke = stroke,
    strokeLineWidth = strokeLineWidth,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = body,
)
