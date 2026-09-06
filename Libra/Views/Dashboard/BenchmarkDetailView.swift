import SwiftUI

/// The page behind a dashboard row.
///
/// Deliberately not `SecurityDetailView`. Tapping "S&P 500" could have pushed
/// the security page for SPY, and it would have hung fundamentals, filings,
/// insider trades and margin history off an index — SPDR Trust's own filings
/// presented as the S&P 500's. A benchmark gets a price, a range, and the
/// sentence saying what the price actually is.
struct BenchmarkDetailView: View {
    @Environment(AppEnvironment.self) private var app
    @State private var model: BenchmarkDetailViewModel

    init(benchmark: Benchmark) {
        _model = State(initialValue: BenchmarkDetailViewModel(benchmark: benchmark))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                chartSection
                returnsSection
            }
            .padding(16)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(model.benchmark.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            await model.refresh(registry: app.registry, snapshots: app.snapshots)
        }
        .task { model.load(registry: app.registry, snapshots: app.snapshots) }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(model.sourceLabel)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(.tertiary)
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(model.formattedLevel)
                    .font(.system(.title, design: .rounded).weight(.medium))
                    .monospacedDigit()
                if let rangeReturn = model.rangeReturn {
                    DirectionalChangeText(percent: rangeReturn.percent, font: .subheadline)
                }
            }
            if let asOf = model.latestBar?.date {
                Text("as of \(Format.shortDate(asOf))")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var chartSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Picker("Range", selection: Binding(
                get: { model.selectedRange },
                set: { model.select($0, registry: app.registry, snapshots: app.snapshots) }
            )) {
                // Index-backed benchmarks offer five ranges, not seven: FRED
                // publishes at the close, and a range that can never draw is
                // not offered.
                ForEach(model.availableRanges) { range in
                    Text(range.rawValue).tag(range)
                }
            }
            .pickerStyle(.segmented)

            switch model.chartAvailability {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, minHeight: 180)
            case .unavailable(let reason):
                ContentUnavailableView {
                    Label("Price history unavailable", systemImage: "chart.xyaxis.line")
                } description: {
                    Text(reason)
                }
                .frame(height: 180)
            case .ready:
                PriceChartView(bars: model.chartBars,
                               points: model.chartPoints,
                               segments: model.chartSegments,
                               ticks: model.chartAxisTicks,
                               isIntraday: model.selectedRange.usesIntraday,
                               valueFormat: model.valueFormat)
                if let note = model.chartNote {
                    Label(note, systemImage: "exclamationmark.triangle")
                        .font(.caption2).foregroundStyle(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                }
                caption
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }

    /// What was actually drawn, in words. A proxy says it is a proxy on every
    /// screen that shows it, and an end-of-day index says it is not live.
    private var caption: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(model.sourceExplanation, systemImage:
                    model.benchmark.isProxy ? "exclamationmark.circle" : "info.circle")
                .font(.caption2)
                .foregroundStyle(model.benchmark.isProxy ? .orange : .secondary)
                .fixedSize(horizontal: false, vertical: true)

            if model.selectedRange.usesIntraday {
                // The consolidated tape is not what drew this. Saying so is
                // the condition on which intraday was adopted at all.
                Label("IEX only — about 2.5% of US volume. Shape, not levels. "
                      + "Trading hours end to end, New York time; dashed where "
                      + "the market was shut.",
                      systemImage: "info.circle")
                    .font(.caption2).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// The same three figures the dashboard row shows, so the page and the row
    /// cannot disagree. Computed from the daily series in both places.
    private var returnsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Trailing").font(.headline)
            HStack(spacing: 0) {
                column("1D", model.daily)
                column("1W", model.weekly)
                column("1M", model.monthly)
            }
            .padding(12)
            .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 10))
        }
    }

    @ViewBuilder
    private func column(_ label: String, _ value: PeriodReturn?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 2) {
                Text(label).font(.caption2).foregroundStyle(.secondary)
                if value.map({ !$0.isFullWindow }) ?? false {
                    // The window was shorter than requested; say so rather
                    // than presenting it as a full period.
                    Image(systemName: "asterisk")
                        .font(.system(size: 6)).foregroundStyle(.orange)
                        .accessibilityLabel("Partial period — less history available than requested")
                }
            }
            DirectionalChangeText(percent: value?.percent, font: .subheadline)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    NavigationStack {
        BenchmarkDetailView(benchmark: Benchmark.market)
    }
    .environment(AppEnvironment.preview())
}
