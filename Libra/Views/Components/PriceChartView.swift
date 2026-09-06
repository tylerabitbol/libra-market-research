import SwiftUI
import Charts

/// The price line, shared by the security page and the benchmark page.
///
/// Two renderers behind one view. The daily line is plotted against dates; the
/// intraday line is plotted by position, because a wall-clock axis gave five
/// sixths of the width to hours in which nothing traded and squeezed each
/// session into a sliver. Both draw a close line over an area fill and nothing
/// else — which is also what makes it safe for FRED's index series, whose bars
/// carry the close in all four fields and must never be drawn as a range.
struct PriceChartView: View {
    /// Whether the y-axis is money or index points. The S&P 500 at 7691.76 is
    /// points, and the spoken value said "$7,691.76" until this existed.
    enum ValueFormat {
        case currency, points

        func string(_ value: Double) -> String {
            switch self {
            case .currency: Format.currency(value)
            case .points: Format.ratio(value, precision: 2)
            }
        }
    }

    let bars: [PriceBar]
    let points: [ChartPoint]
    let segments: [ChartSegment]
    let ticks: [ChartAxisTick]
    let isIntraday: Bool
    var valueFormat: ValueFormat = .currency

    var body: some View {
        if isIntraday {
            intradayChart
        } else {
            dailyChart
        }
    }

    /// The intraday line, plotted by position rather than by clock time.
    ///
    /// The interpolation is linear rather than monotone: a smooth curve
    /// between two five-minute prints invents a path the price never took,
    /// which matters more at this resolution than at daily.
    ///
    /// The fill beneath is the same one the daily chart uses. Neither chart's
    /// y-axis starts at zero, so if the shading is misleading here it is
    /// misleading there too; drawing the same series two different ways was
    /// the worse of the two answers.
    private var intradayChart: some View {
        let labels = Dictionary(ticks.map { ($0.position, $0.label) },
                                uniquingKeysWith: { first, _ in first })

        return Chart {
            intradayFill(points)
            ForEach(segments) { segment in
                segmentMarks(segment)
            }
        }
        .accessibilityLabel(intradayChartLabel)
        .accessibilityValue(intradayChartValue)
        .chartYScale(domain: chartFloor...chartCeiling)
        .chartXScale(domain: -0.5...(Double(max(points.count, 2)) - 0.5))
        .chartYAxis { AxisMarks(position: .trailing) }
        .chartXAxis {
            AxisMarks(values: ticks.map(\.position)) { value in
                AxisGridLine()
                if let position = value.as(Double.self), let label = labels[position] {
                    AxisValueLabel(label)
                }
            }
        }
        .frame(height: 190)
    }

    /// One unbroken area under the whole window, including under the step
    /// between sessions — a notch of bare background at each break would read
    /// as missing data, which was the complaint that started all of this.
    @ChartContentBuilder
    private func intradayFill(_ points: [ChartPoint]) -> some ChartContent {
        ForEach(points) { point in
            AreaMark(x: .value("Position", point.position),
                     yStart: .value("Low", chartFloor),
                     yEnd: .value("Close", point.close))
                .foregroundStyle(.linearGradient(
                    colors: [.accentColor.opacity(0.25), .accentColor.opacity(0.02)],
                    startPoint: .top, endPoint: .bottom))
                .interpolationMethod(.linear)
        }
    }

    /// A traded run is the full-weight line; the step between two sessions is
    /// thinner and dashed, so the stroke's weight tracks whether anyone could
    /// have traded along it.
    ///
    /// Kept as a function taking one segment. Inlining it back into the chart
    /// body is what made the type-checker give up on the expression.
    @ChartContentBuilder
    private func segmentMarks(_ segment: ChartSegment) -> some ChartContent {
        let traded = segment.kind == .traded
        let colour: Color = traded ? .accentColor : .accentColor.opacity(0.4)
        let stroke = traded
            ? StrokeStyle(lineWidth: 2)
            : StrokeStyle(lineWidth: 1, dash: [2, 2])

        ForEach(segment.points) { point in
            LineMark(x: .value("Position", point.position),
                     y: .value("Close", point.close),
                     series: .value("Run", segment.id))
                .foregroundStyle(colour)
                .lineStyle(stroke)
                .interpolationMethod(.linear)
        }
    }

    /// A positional axis says nothing aloud, so the chart states in words
    /// what it covers and where it ended up.
    private var intradayChartLabel: String {
        let sessions = segments.filter { $0.kind == .traded }.count
        return sessions == 1
            ? "Intraday price chart, one trading session"
            : "Intraday price chart, \(sessions) trading sessions"
    }

    private var intradayChartValue: String {
        guard let first = points.first, let last = points.last else {
            return "No bars"
        }
        let move = first.close == 0 ? 0 : (last.close - first.close) / first.close
        return "\(valueFormat.string(first.close)) to \(valueFormat.string(last.close)), "
            + "\(Format.signedPercent(move)) across the window"
    }

    private var dailyChart: some View {
        Chart(bars, id: \.date) { bar in
            AreaMark(x: .value("Date", bar.date),
                     yStart: .value("Low", chartFloor),
                     yEnd: .value("Close", bar.analysisClose))
                .foregroundStyle(.linearGradient(
                    colors: [.accentColor.opacity(0.25), .accentColor.opacity(0.02)],
                    startPoint: .top, endPoint: .bottom))
            LineMark(x: .value("Date", bar.date),
                     y: .value("Close", bar.analysisClose))
                .foregroundStyle(Color.accentColor)
                .interpolationMethod(.monotone)
        }
        .accessibilityLabel("Price chart")
        .accessibilityValue(dailyChartValue)
        .chartYScale(domain: chartFloor...chartCeiling)
        .chartYAxis { AxisMarks(position: .trailing) }
        // Labels grow leftwards from their mark rather than centring on it.
        // The y-axis sits in a trailing gutter, so a centred label on the last
        // mark overflowed into that gutter and was truncated — the final date
        // on a 1M chart rendered as "A…". Anchoring it trailing keeps the
        // whole date inside the plot.
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { value in
                AxisGridLine()
                AxisValueLabel(centered: false, anchor: .topTrailing)
            }
        }
        .frame(height: 190)
    }

    private var dailyChartValue: String {
        guard let first = bars.first, let last = bars.last else { return "No bars" }
        let move = first.analysisClose == 0
            ? 0
            : (last.analysisClose - first.analysisClose) / first.analysisClose
        return "\(valueFormat.string(first.analysisClose)) to "
            + "\(valueFormat.string(last.analysisClose)), "
            + "\(Format.signedPercent(move)) across the window"
    }

    /// Padded bounds. A flat range would otherwise give floor == ceiling and a
    /// degenerate chart domain, so the padding falls back to a fraction of the
    /// value itself rather than of a zero spread.
    private var chartBounds: (floor: Double, ceiling: Double) {
        let values = bars.map(\.analysisClose)
        guard let low = values.min(), let high = values.max() else { return (0, 1) }
        let spread = high - low
        let padding = spread > 0 ? spread * 0.08 : max(abs(high) * 0.02, 0.5)
        return (low - padding, high + padding)
    }

    private var chartFloor: Double { chartBounds.floor }
    private var chartCeiling: Double { chartBounds.ceiling }
}
