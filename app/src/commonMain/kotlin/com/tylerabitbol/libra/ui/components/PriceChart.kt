package com.tylerabitbol.libra.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.multiplatform.cartesian.CartesianChart
import com.patrykandpatrick.vico.multiplatform.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.multiplatform.cartesian.Zoom
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.Axis
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.multiplatform.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.multiplatform.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.multiplatform.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.multiplatform.cartesian.data.lineSeries
import com.patrykandpatrick.vico.multiplatform.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.multiplatform.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.multiplatform.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.multiplatform.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.multiplatform.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.multiplatform.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.multiplatform.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.multiplatform.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.multiplatform.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.multiplatform.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.multiplatform.common.DashedShape
import com.patrykandpatrick.vico.multiplatform.common.Fill
import com.patrykandpatrick.vico.multiplatform.common.Position
import com.patrykandpatrick.vico.multiplatform.common.component.LineComponent
import com.patrykandpatrick.vico.multiplatform.common.component.ShapeComponent
import com.patrykandpatrick.vico.multiplatform.common.component.rememberLineComponent
import com.patrykandpatrick.vico.multiplatform.common.component.rememberTextComponent
import com.tylerabitbol.libra.calculations.ChartSeriesBuilder
import com.tylerabitbol.libra.calculations.ChartSummary
import com.tylerabitbol.libra.models.core.ChartAxisTick
import com.tylerabitbol.libra.models.core.ChartPoint
import com.tylerabitbol.libra.models.core.ChartSegment
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.support.Format
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.viewmodels.ChartValueFormat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.toLocalDateTime

/**
 * The price line, shared by the security page and the benchmark page.
 *
 * Two renderers behind one composable. The daily line is plotted against dates;
 * the intraday line is plotted by position, because a wall-clock axis gave five
 * sixths of the width to hours in which nothing traded and squeezed each
 * session into a sliver. Both draw a close line over an area fill and nothing
 * else — which is also what makes it safe for FRED's index series, whose bars
 * carry the close in all four fields and must never be drawn as a range.
 *
 * Above the line sits a summary of it: the move across the window, and the
 * highest and lowest close. Press and drag on the line and the summary follows
 * the finger instead — that point's date, its price, and the move to it from
 * the window's start. Both are computed from the closes the line draws
 * ([ChartSummary]), so the figures above and the line below cannot disagree.
 *
 * The line takes the colour of its direction across the window, and a dashed
 * baseline marks where it started, so "up or down since the start" is visible
 * before any number is read.
 *
 * [periodLabel] names the window in the summary: "Past month", "Today".
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
    periodLabel: String = "Across the window",
) {
    val closes = remember(bars, points, isIntraday) {
        if (isIntraday) points.map { it.close } else bars.map { it.analysisClose }
    }
    val dates = remember(bars, points, isIntraday) {
        if (isIntraday) points.map { it.date } else bars.map { it.date }
    }
    val summary = remember(closes) { ChartSummary.of(closes) }

    // Which point the finger is on, as an index into `closes`; null when the
    // line is not being touched. Reset whenever the series changes.
    var scrubbed by remember(closes) { mutableStateOf<Int?>(null) }
    val haptics = LocalHapticFeedback.current
    val onScrub: (Int?) -> Unit = { index ->
        val clamped = index?.coerceIn(0, (closes.size - 1).coerceAtLeast(0))
        if (clamped != scrubbed) {
            if (clamped != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            scrubbed = clamped
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (summary != null) {
            ChartSummaryStrip(summary, closes, dates, scrubbed, isIntraday, valueFormat, periodLabel)
        }
        if (isIntraday) {
            IntradayChart(points, segments, ticks, valueFormat, summary, onScrub)
        } else {
            DailyChart(bars, valueFormat, summary, onScrub)
        }
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
    summary: ChartSummary?,
    onScrub: (Int?) -> Unit,
) {
    val closes = remember(bars) { bars.map { it.analysisClose } }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(bars) {
        if (closes.size < 2) return@LaunchedEffect
        producer.runTransaction {
            lineSeries { series(closes.indices.map { it.toDouble() }, closes) }
        }
    }

    val dates = remember(bars) { axisDateLabels(bars.map { it.date }) }
    val labelled = remember(bars) { spreadAcross(dates.indices, count = 4) }
    val line = directionLine(summary)

    ChartHost(
        chart = rememberPriceChart(
            lines = listOf(line),
            closes = closes,
            summary = summary,
            valueFormat = valueFormat,
            bottomAxis = rememberBottomAxis(
                formatter = CartesianValueFormatter { _, x, _ -> dates.at(x) },
                itemPlacer = TickItemPlacer(labelled),
            ),
            onScrub = onScrub,
        ),
        producer = producer,
        description = "Price chart",
        state = windowDescription(closes, valueFormat),
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
    summary: ChartSummary?,
    onScrub: (Int?) -> Unit,
) {
    val closes = remember(points) { points.map { it.close } }
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

    val traded = directionLine(summary)
    val overnight = overnightLine(summary)
    val lines = remember(segments, traded, overnight) {
        segments.map { if (it.kind == ChartSegment.Kind.Traded) traded else overnight }
    }
    val labels = remember(ticks) { ticks.associate { it.position to it.label } }
    val sessions = segments.count { it.kind == ChartSegment.Kind.Traded }

    ChartHost(
        chart = rememberPriceChart(
            lines = lines,
            closes = closes,
            summary = summary,
            valueFormat = valueFormat,
            bottomAxis = rememberBottomAxis(
                formatter = CartesianValueFormatter { _, x, _ ->
                    labels[x] ?: labels.entries.minByOrNull { abs(it.key - x) }?.value ?: "—"
                },
                itemPlacer = TickItemPlacer(ticks.map { it.position }),
            ),
            onScrub = onScrub,
        ),
        producer = producer,
        description = if (sessions == 1) {
            "Intraday price chart, one trading session"
        } else {
            "Intraday price chart, $sessions trading sessions"
        },
        state = windowDescription(closes, valueFormat),
    )
}

/**
 * The host both charts share: fixed height, no scroll and no zoom.
 *
 * A Vico host scrolls unless told to fit, and a year of sessions then drew
 * only its first eight bars (`KNOWN_ISSUES.md`, "UI"). The press-to-scrub
 * marker does not change that: it reads a press, not a drag of the viewport.
 */
@Composable
private fun ChartHost(
    chart: CartesianChart,
    producer: CartesianChartModelProducer,
    description: String,
    state: String,
) {
    CartesianChartHost(
        chart = chart,
        modelProducer = producer,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
            .semantics {
                contentDescription = description
                stateDescription = state
            },
    )
}

/**
 * The chart both renderers draw into: quiet axes, a baseline at the window's
 * first close, and a press-and-drag marker that reports where it is.
 */
@Composable
private fun rememberPriceChart(
    lines: List<LineCartesianLayer.Line>,
    closes: List<Double>,
    summary: ChartSummary?,
    valueFormat: ChartValueFormat,
    bottomAxis: HorizontalAxis<Axis.Position.Horizontal.Bottom>,
    onScrub: (Int?) -> Unit,
): CartesianChart {
    val separator = LibraTheme.colors.separator
    val axisLabel = LibraType.figureSmall.copy(color = LibraTheme.colors.tertiaryText)
    val spread = summary?.let { it.high - it.low } ?: 0.0

    val currentOnScrub by rememberUpdatedState(onScrub)
    val listener = remember {
        object : CartesianMarkerVisibilityListener {
            override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                currentOnScrub(targets.firstOrNull()?.x?.roundToInt())
            }

            override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                currentOnScrub(targets.firstOrNull()?.x?.roundToInt())
            }

            override fun onHidden(marker: CartesianMarker) {
                currentOnScrub(null)
            }
        }
    }

    val baseline = summary?.first
    val baselineColor = LibraTheme.colors.secondaryText.copy(alpha = 0.45f)
    val decorations = remember(baseline, baselineColor) {
        if (baseline == null) {
            emptyList()
        } else {
            listOf(
                HorizontalLine(
                    y = { baseline },
                    line = LineComponent(
                        fill = Fill(baselineColor),
                        thickness = 1.dp,
                        shape = DashedShape(dashLength = 3.dp, gapLength = 3.dp),
                    ),
                ),
            )
        }
    }

    return rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(lines),
            rangeProvider = paddedRange(closes),
        ),
        endAxis = VerticalAxis.rememberEnd(
            line = null,
            tick = null,
            label = rememberAxisLabelComponent(style = axisLabel),
            guideline = rememberAxisGuidelineComponent(
                fill = Fill(separator.copy(alpha = 0.5f)),
                thickness = 0.5.dp,
            ),
            verticalLabelPosition = Position.Vertical.Top,
            itemPlacer = remember { VerticalAxis.ItemPlacer.count({ 4 }) },
            valueFormatter = { _, value, _ -> valueFormat.axisString(value, spread) },
        ),
        bottomAxis = bottomAxis,
        marker = rememberScrubMarker(),
        markerVisibilityListener = listener,
        decorations = decorations,
        markerController = CartesianMarkerController.rememberShowOnPress(),
    )
}

@Composable
private fun rememberBottomAxis(
    formatter: CartesianValueFormatter,
    itemPlacer: HorizontalAxis.ItemPlacer,
): HorizontalAxis<Axis.Position.Horizontal.Bottom> = HorizontalAxis.rememberBottom(
    line = rememberAxisLineComponent(fill = Fill(LibraTheme.colors.separator), thickness = 0.5.dp),
    tick = null,
    guideline = null,
    label = rememberAxisLabelComponent(
        style = LibraType.figureSmall.copy(color = LibraTheme.colors.tertiaryText),
    ),
    valueFormatter = formatter,
    itemPlacer = itemPlacer,
)

/**
 * A vertical guide and a ringed dot where the finger is. The marker's own
 * label is drawn invisibly: the summary strip above the chart carries the
 * reading, where it does not cover the line.
 */
@Composable
private fun rememberScrubMarker(): CartesianMarker {
    val ring = LibraTheme.colors.cardFill
    return rememberDefaultCartesianMarker(
        label = rememberTextComponent(style = TextStyle(color = Color.Transparent, fontSize = 1.sp)),
        indicator = { color ->
            ShapeComponent(
                fill = Fill(color),
                shape = CircleShape,
                strokeFill = Fill(ring),
                strokeThickness = 2.dp,
            )
        },
        indicatorSize = 10.dp,
        guideline = rememberLineComponent(
            fill = Fill(LibraTheme.colors.secondaryText.copy(alpha = 0.5f)),
            thickness = 1.dp,
        ),
    )
}

/**
 * The move across the window, or to the point being touched, above the line.
 *
 * Two lines of fixed height whether or not the line is being touched, so the
 * chart below does not jump when a finger lands on it.
 */
@Composable
private fun ChartSummaryStrip(
    summary: ChartSummary,
    closes: List<Double>,
    dates: List<Instant>,
    scrubbed: Int?,
    isIntraday: Boolean,
    valueFormat: ChartValueFormat,
    periodLabel: String,
) {
    val index = scrubbed?.takeIf { it in closes.indices && it in dates.indices }
    val value = index?.let { closes[it] } ?: summary.last
    val color = directionColor(summary.direction(value))

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                moveText(value - summary.first, summary.percentFrom(value), valueFormat),
                style = LibraType.figureTitle,
                color = color,
            )
            Text(
                if (index == null) {
                    periodLabel
                } else {
                    "${pointLabel(dates[index], isIntraday)} · ${valueFormat.string(value)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = LibraTheme.colors.secondaryText,
                maxLines = 1,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ExtremeLine("High", valueFormat.string(summary.high))
            ExtremeLine("Low", valueFormat.string(summary.low))
        }
    }
}

@Composable
private fun ExtremeLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.tertiaryText,
        )
        Text(value, style = LibraType.figureSmall, color = LibraTheme.colors.secondaryText)
    }
}

/** "+$12.34 (+3.80%)", "−12.34 (−0.16%)". A zero move carries no sign. */
internal fun moveText(change: Double, percent: Double?, valueFormat: ChartValueFormat): String {
    val sign = when {
        change > 0 -> "+"
        change < 0 -> "−"
        else -> ""
    }
    val magnitude = valueFormat.string(abs(change))
    val share = percent?.let { " (${Format.signedPercent(it)})" } ?: ""
    return "$sign$magnitude$share"
}

/** A touched point's date, with the time on an intraday line. */
private fun pointLabel(date: Instant, isIntraday: Boolean): String {
    if (!isIntraday) return Format.shortDate(date)
    val zone = ChartSeriesBuilder.marketZone
    val parts = date.toLocalDateTime(zone)
    val hour = when {
        parts.hour == 0 -> 12
        parts.hour > 12 -> parts.hour - 12
        else -> parts.hour
    }
    val suffix = if (parts.hour < 12) "AM" else "PM"
    val minute = parts.minute.toString().padStart(2, '0')
    return "${Format.dayAndMonth(date, zone)}, $hour:$minute $suffix"
}

/** The colour of a direction: the same rule as `DirectionalChangeText`. */
@Composable
private fun directionColor(direction: Int): Color = when {
    direction > 0 -> LibraTheme.colors.positive
    direction < 0 -> LibraTheme.colors.negative
    else -> LibraTheme.colors.secondaryText
}

/** The full-weight line every traded run is drawn with, in the window's colour. */
@Composable
private fun directionLine(summary: ChartSummary?): LineCartesianLayer.Line {
    val color = directionColor(summary?.direction ?: 0)
    return remember(color) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp),
            areaFill = LineCartesianLayer.AreaFill.single(Fill(areaBrush(color))),
        )
    }
}

/** The step between two sessions: thinner, dashed, and dimmer. */
@Composable
private fun overnightLine(summary: ChartSummary?): LineCartesianLayer.Line {
    val color = directionColor(summary?.direction ?: 0)
    return remember(color) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(color.copy(alpha = 0.4f))),
            stroke = LineCartesianLayer.LineStroke.Dashed(
                thickness = 1.dp,
                dashLength = 2.dp,
                gapLength = 2.dp,
            ),
            areaFill = LineCartesianLayer.AreaFill.single(Fill(areaBrush(color))),
        )
    }
}

private fun areaBrush(color: Color): Brush = Brush.verticalGradient(
    listOf(color.copy(alpha = 0.18f), color.copy(alpha = 0f)),
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
    val padding = if (spread > 0) spread * 0.08 else maxOf(abs(high) * 0.02, 0.5)
    return (low - padding) to (high + padding)
}

private val chartHeight = 190.dp

/**
 * [count] positions spread evenly across [indices], one at the middle of
 * each equal slot. Not at the ends: a label centred on the first or last bar
 * needs half its width of room beyond the line, and that room was an empty
 * margin either side of every daily chart.
 *
 * Fewer when there are fewer to give: a range of three sessions gets three
 * labels rather than three labels and a repeat.
 */
internal fun spreadAcross(indices: IntRange, count: Int): List<Double> {
    val size = indices.last - indices.first + 1
    if (size <= 0) return emptyList()
    if (size <= count) return indices.map { it.toDouble() }
    return (0 until count).map { indices.first + ((2 * it + 1) * size) / (2 * count) }
        .distinct()
        .map { it.toDouble() }
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

/**
 * An axis label, without cents once the window spans ten units or more:
 * "$340", not "$340.00", beside gridlines three dollars apart and more.
 */
internal fun ChartValueFormat.axisString(value: Double, spread: Double): String {
    val precision = if (spread >= 10) 0 else 2
    return when (this) {
        ChartValueFormat.Currency -> Format.currency(value, precision = precision)
        ChartValueFormat.Points -> Format.ratio(value, precision = precision)
    }
}
