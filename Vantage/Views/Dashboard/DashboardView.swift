import SwiftUI

/// Market overview (Section 3).
///
/// Deliberately restrained: four indexes, one volatility proxy, four macro
/// readings, and the sector grid. The spec warns against overloading this
/// screen, and the things that actually explain a move — relative performance
/// and detected changes — belong on the watchlist and detail screens where
/// there is room to show the reasoning.
struct DashboardView: View {
    @Environment(AppEnvironment.self) private var app
    @State private var model = DashboardViewModel()

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                if app.isUsingSampleData {
                    SampleDataBanner()
                }

                // Below the sample-data warning deliberately: when both are
                // showing, "these numbers are invented" is the more urgent one.
                DisclaimerBanner()

                section("Market", rows: model.market)

                if !model.volatility.isEmpty {
                    section("Volatility", rows: model.volatility)
                }

                macroSection

                sectorSection
            }
            .padding(16)
            // Clears the floating freshness pill so it never sits on top of
            // the last row.
            .padding(.bottom, 44)
        }
        .background(Color(.systemGroupedBackground))
        .navigationDestination(for: Benchmark.self) { BenchmarkDetailView(benchmark: $0) }
        .overlay(alignment: .bottom) { footer }
        .refreshable { await model.refresh(using: app.registry) }
        .task(id: app.registry.isUsingSampleData) {
            model.load(using: app.registry)
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private func section(_ title: String, rows: [BenchmarkPerformance]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(title: title)
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    NavigationLink(value: row.benchmark) {
                        BenchmarkRow(performance: row)
                    }
                    .buttonStyle(.plain)
                    if index < rows.count - 1 { Divider().padding(.leading, 12) }
                }
            }
            .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 10))
        }
    }

    @ViewBuilder
    private var macroSection: some View {
        if !model.macro.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                SectionHeader(title: "Macro", subtitle: "Potentially relevant context")
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 10)], spacing: 10) {
                    ForEach(model.macro) { reading in
                        MacroCard(reading: reading)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var sectorSection: some View {
        if !model.sectors.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                SectionHeader(title: "Sectors", subtitle: "Daily change")
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 8)], spacing: 8) {
                    ForEach(model.sectors) { row in
                        NavigationLink(value: row.benchmark) {
                            SectorTile(performance: row)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var footer: some View {
        FreshnessLabel(freshness: model.overallFreshness, font: .caption)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(.regularMaterial, in: .capsule)
            .padding(.bottom, 8)
    }
}

private struct SectionHeader: View {
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title)
                .font(.headline)
            if let subtitle {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct BenchmarkRow: View {
    let performance: BenchmarkPerformance

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 4) {
                        Text(performance.benchmark.displayName)
                            .font(.subheadline.weight(.medium))
                        // A marker, not a button. This was a button opening an
                        // alert until the row became tappable — a control
                        // inside a NavigationLink's label swallowed the row's
                        // own tap, and the explanation has a better home on
                        // the page the row now opens.
                        Image(systemName: performance.benchmark.isProxy
                              ? "exclamationmark.circle" : "info.circle")
                            .font(.caption2)
                            .foregroundStyle(performance.benchmark.isProxy
                                             ? AnyShapeStyle(.orange) : AnyShapeStyle(.tertiary))
                            .accessibilityLabel(performance.benchmark.isProxy
                                                ? "Shows a proxy, not the index itself"
                                                : "The index itself, published end-of-day")
                    }
                    Text(sourceLabel)
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundStyle(.tertiary)
                }
                Spacer()
                Text(performance.formattedLevel)
                    .font(.system(.subheadline, design: .rounded).weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(performance.level == nil ? .secondary : .primary)
                Image(systemName: "chevron.right")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }

            if performance.error != nil {
                Text(RelativeTimeText.status(for: performance.freshness))
                    .font(.caption2)
                    .foregroundStyle(.orange)
            } else {
                // A live price with missing history is a partial success, and
                // saying why beats leaving three cells reading "Not available".
                if let historyError = performance.historyError {
                    Text("History unavailable: \(historyError.shortDescription)")
                        .font(.caption2)
                        .foregroundStyle(.orange)
                }
                HStack(spacing: 0) {
                    changeColumn("1D", performance.dailyPercent)
                    changeColumn("1W", performance.weekly?.percent,
                                 partial: performance.weekly.map { !$0.isFullWindow } ?? false)
                    changeColumn("1M", performance.monthly?.percent,
                                 partial: performance.monthly.map { !$0.isFullWindow } ?? false)
                }
            }
        }
        .padding(12)
        .contentShape(.rect)
    }

    /// Names what is actually being displayed: the index series, or the ETF
    /// standing in for it.
    private var sourceLabel: String {
        if let series = performance.benchmark.fredSeriesID { return "FRED \(series)" }
        return performance.benchmark.etfSymbol ?? "—"
    }

    @ViewBuilder
    private func changeColumn(_ label: String, _ value: Double?, partial: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(spacing: 2) {
                Text(label)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                if partial {
                    // The window was shorter than requested; say so rather than
                    // presenting it as a full period.
                    Image(systemName: "asterisk")
                        .font(.system(size: 6))
                        .foregroundStyle(.orange)
                        .accessibilityLabel("Partial period — less history available than requested")
                }
            }
            DirectionalChangeText(percent: value, font: .caption)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct SectorTile: View {
    let performance: BenchmarkPerformance

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(performance.benchmark.displayName)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(2, reservesSpace: true)
            DirectionalChangeText(percent: performance.dailyPercent, font: .subheadline.weight(.medium))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 8))
        .contentShape(.rect)
    }
}

private struct MacroCard: View {
    let reading: DashboardViewModel.MacroReading

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(reading.indicator.displayName)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            if let latest = reading.latest {
                Text(formatted(latest.value))
                    .font(.system(.title3, design: .rounded).weight(.medium))
                    .monospacedDigit()
                Text("as of \(Format.shortDate(latest.date))")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            } else {
                Text(Format.notAvailable)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let error = reading.error {
                    Text(error.shortDescription)
                        .font(.caption2)
                        .foregroundStyle(.orange)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 8))
    }

    private func formatted(_ value: Double) -> String {
        switch reading.indicator.unit {
        case .percent: Format.percent(value, precision: 2)
        case .index: Format.ratio(value, precision: 1)
        case .currency: Format.compactCurrency(value)
        }
    }
}

#Preview {
    NavigationStack {
        DashboardView()
            .navigationTitle("Dashboard")
    }
    .environment(AppEnvironment.preview())
}
