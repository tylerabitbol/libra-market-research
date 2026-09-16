package com.tylerabitbol.libra.ui.components

import com.patrykandpatrick.vico.multiplatform.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.multiplatform.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.multiplatform.cartesian.layer.CartesianLayerDimensions

/**
 * Labels the x-axis at the positions the model chose, and nowhere else.
 *
 * Vico's own item placers put labels at a fixed *spacing* — every k-th x value.
 * Neither chart's labels are spaced that way: the intraday chart labels the
 * start of each trading session, which fall wherever the sessions happen to
 * fall, and the daily chart labels a handful of dates out of hundreds.
 *
 * Returning `""` from the value formatter for the positions in between is not
 * the way to express that. Vico raises
 * `CartesianValueFormatter.format returned an empty string` and the chart
 * fails to draw, which is exactly what the 1D and 5D ranges did before this
 * existed — the formatter is asked to label positions the placer picked, so
 * choosing the positions is the placer's job.
 */
internal class TickItemPlacer(positions: List<Double>) : HorizontalAxis.ItemPlacer {

    private val positions = positions.sorted()

    override fun getLabelValues(
        context: CartesianDrawingContext,
        visibleXRange: ClosedFloatingPointRange<Double>,
        fullXRange: ClosedFloatingPointRange<Double>,
        maxLabelWidth: Float,
    ): List<Double> = positions.filter { it in visibleXRange }

    /** Every label, so the axis reserves room for the widest one. */
    override fun getWidthMeasurementLabelValues(
        context: CartesianMeasuringContext,
        layerDimensions: CartesianLayerDimensions,
        fullXRange: ClosedFloatingPointRange<Double>,
    ): List<Double> = positions

    override fun getHeightMeasurementLabelValues(
        context: CartesianMeasuringContext,
        layerDimensions: CartesianLayerDimensions,
        fullXRange: ClosedFloatingPointRange<Double>,
        maxLabelWidth: Float,
    ): List<Double> = positions

    /**
     * A label is centred on its position, so one sitting at either end of the
     * domain needs half its width of room beside it or it is clipped. This is
     * the thing Swift worked around by anchoring the trailing label.
     */
    override fun getStartLayerMargin(
        context: CartesianMeasuringContext,
        layerDimensions: CartesianLayerDimensions,
        tickThickness: Float,
        maxLabelWidth: Float,
    ): Float = maxLabelWidth / 2

    override fun getEndLayerMargin(
        context: CartesianMeasuringContext,
        layerDimensions: CartesianLayerDimensions,
        tickThickness: Float,
        maxLabelWidth: Float,
    ): Float = maxLabelWidth / 2
}
