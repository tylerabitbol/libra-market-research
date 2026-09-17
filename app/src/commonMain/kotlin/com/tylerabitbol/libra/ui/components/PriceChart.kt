package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.multiplatform.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.multiplatform.cartesian.Zoom
import com.patrykandpatrick.vico.multiplatform.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.multiplatform.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.multiplatform.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.multiplatform.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.multiplatform.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.multiplatform.cartesian.data.lineSeries
import com.patrykandpatrick.vico.multiplatform.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.multiplatform.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.multiplatform.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.multiplatform.common.Fill
import com.tylerabitbol.libra.models.core.ChartAxisTick
import com.tylerabitbol.libra.models.core.ChartPoint
import com.tylerabitbol.libra.models.core.ChartSegment
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.support.Format
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import com.tylerabitbol.libra.viewmodels.ChartValueFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The price line, shared by the security page and the benchmark page.
 *
 * Two renderers behind one composable. The daily line is plotted against dates;
 * the intraday line is plotted by position, because a wall-clock axis gave five
 * sixths of the width to hours in which nothing traded and squeezed each
 * session into a sliver. Both draw a close line over an area fill and nothing
 * else — which is also what makes it safe for FRED's index series, whose bars
 * carry the close in all four fields and must never be drawn as a range.
 */
@Composable
fun PriceChart(
    bars: List<PriceBar>,
    points: List<ChartPoint>,
    segments: List<ChartSegment>,
    ticks: List<ChartAxisTick>,
    isIntraday: Boolean,
    modifier: Modifier = Modifier,
    valueFormat: ChartValueFormat = ChartValueFormat.Currency,
) {
    if (isIntraday) {
        IntradayChart(points, segments, ticks, valueFormat, modifier)
    } else {
        DailyChart(bars, valueFormat, modifier)
    }
}

/**
 * The daily line, plotted against the session index with dated labels.
 *
 * Swift plots against `Date` and lets Charts choose four labels. Vico's x-axis
 * is numeric, so the dates travel in a lookup the formatter reads — which
 * incidentally fixes the thing Swift had to work around by anchoring labels
 * trailing: a positional axis cannot overflow the y-axis gutter.
 */
@Composable
private fun DailyChart(
    bars: List<PriceBar>,
    valueFormat: ChartValueFormat,
    modifier: Modifier = Modifier,
) {
    val closes = bars.map { it.analysisClose }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(bars) {
        if (closes.size < 2) return@LaunchedEffect
        producer.runTransaction {
            lineSeries { series(closes.indices.map { it.toDouble() }, closes) }
        }
    }

    val dates = remember(bars) { axisDateLabels(bars.map { it.date }) }
    // Swift let Charts choose four labels out of however many sessions the
    // range holds; a positional axis has to be told which four.
    val labelled = remember(bars) { spreadAcross(dates.indices, count = 4) }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(accentLine()),
                rangeProvider = paddedRange(closes),
            ),
            endAxis = VerticalAxis.rememberEnd(
                valueFormatter = { _, value, _ -> valueFormat.string(value) },
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = CartesianValueFormatter { _, x, _ -> dates.at(x) },
                itemPlacer = TickItemPlacer(labelled),
            ),
        ),
        modelProducer = producer,
        // Vico charts scroll by default: a layer is laid out at a fixed
        // spacing per point, so a year of sessions is far wider than the
        // viewport and only the first few days are on screen. Swift's chart
        // fits its whole range, so the content is zoomed to fit and both
        // gestures are off — this is a figure in a page that itself scrolls.
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .semantics {
                contentDescription = "Price chart"
                stateDescription = windowDescription(closes, valueFormat)
            },
    )
}

/**
 * The intraday line, plotted by position rather than by clock time.
 *
 * One Vico series per segment, so a traded run draws at full weight and the
 * step between two sessions draws thinner and dashed — the stroke's weight
 * tracks whether anyone could have traded along it. Every series carries the
 * same area fill, including the overnight link, because a notch of bare
 * background at each break reads as missing data, which was the complaint that
 * started all of this.
 */
@Composable
private fun IntradayChart(
    points: List<ChartPoint>,
    segments: List<ChartSegment>,
    ticks: List<ChartAxisTick>,
    valueFormat: ChartValueFormat,
    modifier: Modifier = Modifier,
) {
    val closes = points.map { it.close }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(segments) {
        if (points.size < 2) return@LaunchedEffect
        producer.runTransaction {
            lineSeries {
                for (segment in segments) {
                    series(
                        segment.points.map { it.position },
                        segment.points.map { it.close },
                    )
                }
            }
        }
    }

    val traded = accentLine()
    val overnight = overnightLine()
    val lines = remember(segments, traded, overnight) {
        segments.map { if (it.kind == ChartSegment.Kind.Traded) traded else overnight }
    }
    val labels = remember(ticks) { ticks.associate { it.position to it.label } }
    val sessions = segments.count { it.kind == ChartSegment.Kind.Traded }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(lines),
                rangeProvider = paddedRange(closes),
            ),
            endAxis = VerticalAxis.rememberEnd(
                valueFormatter = { _, value, _ -> valueFormat.string(value) },
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = CartesianValueFormatter { _, x, _ ->
                    labels[x] ?: labels.entries.minByOrNull { abs(it.key - x) }?.value ?: "—"
                },
                itemPlacer = TickItemPlacer(ticks.map { it.position }),
            ),
        ),
        modelProducer = producer,
        // Vico charts scroll by default: a layer is laid out at a fixed
        // spacing per point, so a year of sessions is far wider than the
        // viewport and only the first few days are on screen. Swift's chart
        // fits its whole range, so the content is zoomed to fit and both
        // gestures are off — this is a figure in a page that itself scrolls.
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            // A positional axis says nothing aloud, so the chart states in
            // words what it covers and where it ended up.
            .semantics {
                contentDescription = if (sessions == 1) {
                    "Intraday price chart, one trading session"
                } else {
                    "Intraday price chart, $sessions trading sessions"
                }
                stateDescription = windowDescription(closes, valueFormat)
            },
    )
}

/** The full-weight line every traded run is drawn with. */
@Composable
private fun accentLine(): LineCartesianLayer.Line {
    val accent = MaterialTheme.colorScheme.primary
    return LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(accent)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp),
        areaFill = LineCartesianLayer.AreaFill.single(Fill(areaBrush(accent))),
    )
}

/** The step between two sessions: thinner, dashed, and dimmer. */
@Composable
private fun overnightLine(): LineCartesianLayer.Line {
    val accent = MaterialTheme.colorScheme.primary
    return LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(accent.copy(alpha = 0.4f))),
        stroke = LineCartesianLayer.LineStroke.Dashed(
            thickness = 1.dp,
            dashLength = 2.dp,
            gapLength = 2.dp,
        ),
        areaFill = LineCartesianLayer.AreaFill.single(Fill(areaBrush(accent))),
    )
}

private fun areaBrush(accent: Color): Brush = Brush.verticalGradient(
    listOf(accent.copy(alpha = 0.25f), accent.copy(alpha = 0.02f)),
)

/**
 * Padded bounds, so a flat range does not collapse the domain.
 *
 * Neither chart's y-axis starts at zero. If the shading that follows from that
 * is misleading on the intraday chart it is misleading on the daily one too;
 * drawing the same series two different ways was the worse of the two answers.
 */
private fun paddedRange(values: List<Double>): CartesianLayerRangeProvider {
    val (floor, ceiling) = chartBounds(values)
    return CartesianLayerRangeProvider.fixed(minY = floor, maxY = ceiling)
}

internal fun chartBounds(values: List<Double>): Pair<Double, Double> {
    val low = values.minOrNull() ?: return 0.0 to 1.0
    val high = values.maxOrNull() ?: return 0.0 to 1.0
    val spread = high - low
    // A flat range would otherwise give floor == ceiling, so the padding falls
    // back to a fraction of the value itself rather than of a zero spread.
    val padding = if (spread > 0) spread * 0.08 else maxOf(abs(high) * 0.02, 0.5)
    return (low - padding) to (high + padding)
}

private val chartHeight = 190.dp

/**
 * [count] positions spread evenly across [indices], ends included.
 *
 * Fewer when there are fewer to give: a range of three sessions gets three
 * labels rather than three labels and a repeat.
 */
internal fun spreadAcross(indices: IntRange, count: Int): List<Double> {
    val size = indices.last - indices.first + 1
    if (size <= 0) return emptyList()
    if (size <= count) return indices.map { it.toDouble() }
    val step = (size - 1).toDouble() / (count - 1)
    return (0 until count).map { indices.first + (it * step).roundToInt().toDouble() }.distinct()
}

/**
 * The x-axis label for every bar, at the length the range can afford.
 *
 * Four dates have to fit side by side, and a full "Sep 18, 2025" in each slot
 * is ellipsised to "Sep 18, …", which says less than either half of it would.
 * So the day is dropped once the range runs past a year and the year is
 * dropped below one. Swift gets the same narrowing for nothing from Charts'
 * automatic axis labels.
 */
internal fun axisDateLabels(dates: List<Instant>): List<String> {
    val spansYears = dates.size > 1 && (dates.last() - dates.first()) > 370.days
    return dates.map { if (spansYears) Format.monthAndYear(it) else Format.dayAndMonth(it) }
}

/**
 * The label at a position, never empty: Vico treats an empty axis label as a
 * programming error and refuses to draw the chart.
 */
private fun List<String>.at(x: Double): String =
    getOrNull(x.toInt()) ?: lastOrNull() ?: "—"

/** What the window opened and closed at, and the move between them. */
internal fun windowDescription(closes: List<Double>, valueFormat: ChartValueFormat): String {
    val first = closes.firstOrNull() ?: return "No bars"
    val last = closes.lastOrNull() ?: return "No bars"
    // `signedPercent` takes percent units, not a fraction: a tenth is 10.0,
    // and passing 0.1 said "+0.10%" of a move that was ten percent.
    val move = if (first == 0.0) 0.0 else (last - first) / first * 100.0
    return "${valueFormat.string(first)} to ${valueFormat.string(last)}, " +
        "${Format.signedPercent(move)} across the window"
}

/**
 * Whether the y-axis is money or index points. The S&P 500 at 7691.76 is
 * points, and the spoken value said "$7,691.76" until this existed.
 */
internal fun ChartValueFormat.string(value: Double): String = when (this) {
    ChartValueFormat.Currency -> Format.currency(value)
    ChartValueFormat.Points -> Format.ratio(value, precision = 2)
}
