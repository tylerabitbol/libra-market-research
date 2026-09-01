import SwiftUI
import Charts

/// The research page for one security (Section 5).
///
/// Ordered by what answers "what changed and how unusual is it" fastest:
/// price context first, then valuation *with its history* — which is the part a
/// normal stock app doesn't show — then fundamentals, then the filings that
/// back them.
struct SecurityDetailView: View {
    @Environment(AppEnvironment.self) private var app
    @State private var model: SecurityDetailViewModel
    @State private var isChoosingDate = false
    @State private var customDate = Date.now

    init(symbol: String) {
        _model = State(initialValue: SecurityDetailViewModel(symbol: symbol))
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                if app.isUsingSampleData { SampleDataBanner() }

                overview
                changesSection
                chartSection
                relativeSection
                researchProfileSection
                valuationSection
                fundamentalsSection
                insiderSection
                filingsSection
            }
            .padding(16)
            .padding(.bottom, 40)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(model.symbol)
        .navigationBarTitleDisplayMode(.inline)
        .overlay(alignment: .bottom) {
            FreshnessLabel(freshness: model.freshness, font: .caption)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(.regularMaterial, in: .capsule)
                .padding(.bottom, 6)
        }
        .refreshable { model.load(using: app.registry, snapshots: app.snapshots, force: true) }
        .task { model.load(using: app.registry, snapshots: app.snapshots) }
        .sheet(isPresented: $isChoosingDate) { customDateSheet }
    }

    /// A date the user picks by hand, for windows the fixed options miss —
    /// an earnings date, the day a thesis was formed.
    private var customDateSheet: some View {
        NavigationStack {
            DatePicker("Show changes since",
                       selection: $customDate,
                       in: ...Date.now,
                       displayedComponents: .date)
                .datePickerStyle(.graphical)
                .padding(16)
                .navigationTitle("Since")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { isChoosingDate = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Apply") {
                            model.changeWindow = .custom(customDate)
                            isChoosingDate = false
                        }
                    }
                }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: - Overview

    private var overview: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let profile = model.profile {
                Text(profile.name)
                    .font(.title3.weight(.semibold))
                HStack(spacing: 6) {
                    if let sector = profile.sector {
                        Text(sector).font(.caption).foregroundStyle(.secondary)
                    }
                    if let exchange = profile.exchange {
                        Text("· \(exchange)").font(.caption).foregroundStyle(.tertiary)
                    }
                }
            }

            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(Format.currency(model.displayPrice))
                    .font(.system(.largeTitle, design: .rounded).weight(.medium))
                    .monospacedDigit()
                DirectionalChangeText(percent: model.displayChangePercent,
                                      font: .headline)
            }

            // A failed refresh must not blank a page the store can fill. When
            // it does fall back, the notice dates what is on screen rather than
            // letting a stored close pass for a live price.
            if model.isShowingSavedCopy {
                Text(RelativeTimeText.status(for: .failed(
                    previous: model.savedCopyAsOf,
                    reason: model.quoteError?.shortDescription
                        ?? model.historyError?.shortDescription
                        ?? "no connection")))
                    .font(.caption).foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            } else if let error = model.quoteError {
                Text(error.recoverySuggestion ?? error.shortDescription)
                    .font(.caption).foregroundStyle(.orange)
            }

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 10)], spacing: 10) {
                MetricCell(label: "Market cap",
                           value: Format.compactCurrency(model.profile?.marketCap),
                           isAvailable: model.profile?.marketCap != nil)
                MetricCell(label: "Open", value: Format.currency(model.quote?.open),
                           isAvailable: model.quote?.open != nil)
                MetricCell(label: "Day range",
                           value: dayRange, isAvailable: model.quote?.high != nil)
                MetricCell(label: "52-week range", value: fiftyTwoWeekRange,
                           isAvailable: model.metrics?.currentValue("52WeekHigh") != nil)
                MetricCell(label: "Beta", value: Format.ratio(model.metrics?.currentValue("beta"), precision: 2),
                           isAvailable: model.metrics?.currentValue("beta") != nil)
                MetricCell(label: "Avg volume (10d)",
                           value: Format.compact(model.metrics?.currentValue("10DayAverageTradingVolume").map { $0 * 1_000_000 }),
                           isAvailable: model.metrics?.currentValue("10DayAverageTradingVolume") != nil)
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }

    /// Colour by direction, with a neutral tone when growth is unknown — an
    /// absent figure must never read as flat.
    private func growthColor(_ growth: Double?) -> Color {
        guard let growth else { return .secondary }
        return growth >= 0 ? .green : .red
    }

    private var dayRange: String {
        guard let low = model.quote?.low, let high = model.quote?.high else {
            return Format.notAvailable
        }
        return "\(Format.currency(low)) – \(Format.currency(high))"
    }

    private var fiftyTwoWeekRange: String {
        guard let low = model.metrics?.currentValue("52WeekLow"),
              let high = model.metrics?.currentValue("52WeekHigh")
        else { return Format.notAvailable }
        return "\(Format.currency(low)) – \(Format.currency(high))"
    }

    // MARK: - What changed

    /// The question the whole app exists to answer, so it sits directly under
    /// the price rather than below the fold.
    ///
    /// An empty result is stated plainly. "Nothing unusual" is a real finding
    /// and a useful one; leaving the section out entirely would make its
    /// absence indistinguishable from a section that failed to load.
    @ViewBuilder
    private var changesSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("What changed").font(.headline)
                Spacer()
                windowMenu
                if !model.availableKinds.isEmpty { kindMenu }
            }

            if let start = model.changeWindow.startDate(lastVisit: model.lastVisit) {
                Text("Since \(Format.shortDate(start))")
                    .font(.caption).foregroundStyle(.tertiary)
            }
            if let coverage = model.coverageNote {
                Text(coverage).font(.caption2).foregroundStyle(.tertiary)
            }

            if model.events.isEmpty {
                Text(emptyChangesMessage)
                    .font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(Color(.secondarySystemGroupedBackground),
                                in: .rect(cornerRadius: 12))
            } else {
                if model.lastVisit == nil {
                    Text("This is your first visit, so everything below is "
                         + "reported from the available history rather than as new.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                ForEach(model.events) { event in
                    EventCard(event: event, isNew: model.isNew(event))
                    if event.kind == .unusualPriceMove, let attribution = model.latestAttribution {
                        AttributionCard(attribution: attribution)
                    }
                    if let analysis = model.analysis(for: event) {
                        FilingAnalysisCard(analysis: analysis)
                    }
                }
            }
        }
    }

    private var emptyChangesMessage: String {
        if model.bars.isEmpty {
            return model.historyError == nil
                ? "Loading price history…"
                : "No price history loaded, so nothing can be compared."
        }
        if model.bars.count < EventDetector.minimumSample {
            return "Only \(model.bars.count) sessions of history are available. "
                + "At least \(EventDetector.minimumSample) are needed before "
                + "\"unusual\" means anything."
        }
        if !model.kindFilter.isEmpty {
            let names = model.kindFilter.map(\.displayName).sorted()
            return "Nothing of the selected kind"
                + (names.count == 1 ? " (\(names[0]))" : "s")
                + " in this window. Widen the window or clear the filter."
        }
        if case .lastVisit = model.changeWindow, model.lastVisit == nil {
            return "Nothing unusual in the recent price, volume, or volatility. "
                + "This is your first visit, so there is no earlier point to "
                + "compare against — choose a window above to look further back."
        }
        return "Nothing unusual in the price, volume, or volatility over this "
            + "window, and no new filings in it."
    }

    // MARK: - What changed: controls

    /// Follows the `Menu` pattern already in `ResearchView.sortMenu`.
    private var windowMenu: some View {
        Menu {
            ForEach(ChangeWindow.offered) { window in
                Button {
                    model.changeWindow = window
                } label: {
                    if model.changeWindow == window {
                        Label(window.displayName, systemImage: "checkmark")
                    } else {
                        Text(window.displayName)
                    }
                }
            }
            Divider()
            Button("Custom date…") { isChoosingDate = true }
        } label: {
            Label(model.changeWindow.displayName, systemImage: "calendar")
                .font(.caption)
        }
    }

    private var kindMenu: some View {
        Menu {
            Button {
                model.kindFilter = []
            } label: {
                if model.kindFilter.isEmpty {
                    Label("All kinds", systemImage: "checkmark")
                } else {
                    Text("All kinds")
                }
            }
            ForEach(model.availableKindsByCategory, id: \.category) { group in
                Section(group.category.displayName) {
                    // The category header toggles its whole group.
                    Button("All \(group.category.displayName.lowercased())") {
                        toggle(group.kinds)
                    }
                    ForEach(group.kinds, id: \.self) { kind in
                        Button {
                            toggle([kind])
                        } label: {
                            if model.kindFilter.contains(kind) {
                                Label(kind.displayName, systemImage: "checkmark")
                            } else {
                                Text(kind.displayName)
                            }
                        }
                    }
                }
            }
        } label: {
            Label(kindMenuTitle, systemImage: "line.3.horizontal.decrease.circle")
                .font(.caption)
        }
    }

    private var kindMenuTitle: String {
        switch model.kindFilter.count {
        case 0: "All kinds"
        case 1: model.kindFilter.first?.displayName ?? "1 kind"
        case let count: "\(count) kinds"
        }
    }

    /// Adds the group if any of it is missing, removes it once it is all there.
    private func toggle(_ kinds: [EventKind]) {
        if kinds.allSatisfy(model.kindFilter.contains) {
            model.kindFilter.subtract(kinds)
        } else {
            model.kindFilter.formUnion(kinds)
        }
    }

    // MARK: - Chart

    private var chartSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Price").font(.headline)
                Spacer()
                if let periodReturn = model.rangeReturn {
                    DirectionalChangeText(percent: periodReturn.percent, font: .subheadline)
                }
            }

            Picker("Range", selection: Binding(
                get: { model.selectedRange },
                set: { model.select($0, registry: app.registry) }
            )) {
                ForEach(ChartRange.allCases) { range in
                    Text(range.rawValue).tag(range)
                }
            }
            .pickerStyle(.segmented)

            if let error = model.historyError {
                ContentUnavailableView {
                    Label("Price history unavailable", systemImage: "chart.xyaxis.line")
                } description: {
                    Text(error.recoverySuggestion ?? error.shortDescription)
                }
                .frame(height: 180)
            } else if model.visibleBars.count < 2 {
                ProgressView().frame(maxWidth: .infinity, minHeight: 180)
            } else {
                priceChart
                if model.rangeReturn?.isFullWindow == false {
                    Text("Less history available than the selected range.")
                        .font(.caption2).foregroundStyle(.orange)
                }
                priceContextRows
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }

    /// Three figures, not a technical-analysis panel. Section 5 asks for
    /// context and then rules out the dashboard, so this is where the line is.
    @ViewBuilder
    private var priceContextRows: some View {
        if let context = model.priceContext {
            Divider().padding(.vertical, 2)
            VStack(alignment: .leading, spacing: 8) {
                if let claim = context.twoHundredDayClaim ?? context.fiftyDayClaim {
                    ClaimRow(claim: claim)
                }
                ClaimRow(claim: context.drawdownClaim)
                if context.deepestDrawdown < -0.05 {
                    Text("Deepest fall within this period: "
                         + Format.percent(abs(context.deepestDrawdown), precision: 1))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var priceChart: some View {
        Chart(model.visibleBars, id: \.date) { bar in
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
        .chartYScale(domain: chartFloor...chartCeiling)
        .chartYAxis { AxisMarks(position: .trailing) }
        .frame(height: 190)
    }

    /// Padded bounds so the line never sits flush against the frame, and so a
    /// quiet period doesn't get magnified into apparent volatility.
    /// Padded bounds. A flat range would otherwise give floor == ceiling and a
    /// degenerate chart domain, so the padding falls back to a fraction of the
    /// value itself rather than of a zero spread.
    private var chartBounds: (floor: Double, ceiling: Double) {
        let values = model.visibleBars.map(\.analysisClose)
        guard let low = values.min(), let high = values.max() else { return (0, 1) }
        let spread = high - low
        let padding = spread > 0 ? spread * 0.08 : max(abs(high) * 0.02, 0.5)
        return (low - padding, high + padding)
    }

    private var chartFloor: Double { chartBounds.floor }
    private var chartCeiling: Double { chartBounds.ceiling }

    // MARK: - Relative performance

    /// Section 7: how this security did against its sector and the market over
    /// the selected range, stated as arithmetic rather than as adjectives.
    ///
    /// Hidden entirely rather than shown empty when neither comparison can be
    /// made — an absent benchmark is not a finding the way "nothing unusual" is.
    @ViewBuilder
    private var relativeSection: some View {
        if model.relativeToMarket != nil || model.relativeToSector != nil {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Relative performance").font(.headline)
                    Spacer()
                    Text(model.selectedRange.rawValue)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.tertiary)
                }

                VStack(alignment: .leading, spacing: 8) {
                    returnRow(model.symbol, model.rangeReturn?.percent, isSubject: true)
                    if let sector = model.sectorBenchmark {
                        Divider()
                        returnRow(sector.displayName, model.sectorRangeReturn?.percent,
                                  isSubject: false)
                    }
                    Divider()
                    returnRow("S&P 500", model.marketRangeReturn?.percent, isSubject: false)
                }
                .padding(14)
                .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))

                VStack(alignment: .leading, spacing: 10) {
                    if let sector = model.relativeToSector, let benchmark = model.sectorBenchmark {
                        ClaimRow(claim: sector.claim(securityName: model.symbol,
                                                     benchmarkName: benchmark.displayName))
                    }
                    if let market = model.relativeToMarket {
                        ClaimRow(claim: market.claim(securityName: model.symbol,
                                                     benchmarkName: "the S&P 500"))
                    }
                    if let note = model.sectorBenchmark?.proxyNote {
                        Text(note).font(.caption2).foregroundStyle(.tertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(14)
                .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
            }
        }
    }

    private func returnRow(_ label: String, _ percent: Double?, isSubject: Bool) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(isSubject
                      ? .system(.subheadline, design: .monospaced).weight(.semibold)
                      : .subheadline)
                .foregroundStyle(isSubject ? .primary : .secondary)
            Spacer(minLength: 8)
            DirectionalChangeText(percent: percent, font: .subheadline)
        }
    }

    // MARK: - Research profile

    /// Sections 12 and 13, as components rather than a score.
    @ViewBuilder
    private var researchProfileSection: some View {
        let profile = model.researchProfile
        if !profile.measured.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("Research profile").font(.headline)
                Text("Each dimension measured against this company's own history, and "
                     + "grouped by which way it points.")
                    .font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                ResearchProfileCard(profile: profile)
            }
        }
    }

    // MARK: - Valuation

    private var valuationSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Valuation in context").font(.headline)
            Text("Each multiple ranked against this company's own history, not against other companies.")
                .font(.caption).foregroundStyle(.secondary)

            if let error = model.metricsError {
                Text(error.recoverySuggestion ?? error.shortDescription)
                    .font(.caption).foregroundStyle(.orange)
            } else if model.valuationContexts.isEmpty {
                Text(model.metrics == nil ? "Loading…"
                     : "Not enough history to rank these metrics.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                ForEach(model.valuationContexts) { entry in
                    ValuationRow(metric: entry.metric, context: entry.context, asOf: entry.asOf)
                    if entry.id != model.valuationContexts.last?.id {
                        Divider()
                    }
                }
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }

    // MARK: - Fundamentals

    private var fundamentalsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Text("Fundamentals").font(.headline)
                Text("SEC XBRL")
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(.tertiary)
            }

            if let error = model.fundamentalsError {
                Text(error.recoverySuggestion ?? error.shortDescription)
                    .font(.caption).foregroundStyle(.orange)
            } else if model.annualRevenue.isEmpty {
                Text(model.fundamentals.isEmpty ? "Loading…" : "Not reported.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Text("Annual revenue").font(.caption).foregroundStyle(.secondary)
                ForEach(Array(model.annualRevenue.prefix(6))) { entry in
                    HStack {
                        Text(entry.periodLabel)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text(Format.compactCurrency(entry.fact.value))
                            .font(.callout).monospacedDigit()
                        Text(Format.signedPercent(entry.growth, precision: 1))
                            .font(.caption).monospacedDigit()
                            .foregroundStyle(growthColor(entry.growth))
                            .frame(width: 62, alignment: .trailing)
                    }
                }

                if !model.annualFreeCashFlow.isEmpty {
                    Divider().padding(.vertical, 2)
                    Text("Free cash flow — operating cash flow less capital expenditures")
                        .font(.caption).foregroundStyle(.secondary)
                    ForEach(Array(model.annualFreeCashFlow.suffix(4).reversed())) { entry in
                        HStack {
                            Text(Format.shortDate(entry.period))
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.secondary)
                            Spacer()
                            Text(Format.compactCurrency(entry.value))
                                .font(.callout).monospacedDigit()
                        }
                    }
                }
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }

    // MARK: - Insider activity

    /// Section 10: summaries, never a raw list, and never an implication that
    /// insider activity predicts anything.
    @ViewBuilder
    private var insiderSection: some View {
        if let summary = model.insiderSummary {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("Insider activity").font(.headline)
                    Spacer()
                    Text("PRIMARY SOURCE")
                        .font(.system(.caption2, design: .monospaced).weight(.semibold))
                        .padding(.horizontal, 5).padding(.vertical, 2)
                        .background(.quaternary.opacity(0.6), in: .rect(cornerRadius: 4))
                        .foregroundStyle(.secondary)
                }

                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 20) {
                        insiderCount("Bought", summary.purchaseCount, summary.purchaseValue)
                        insiderCount("Sold", summary.saleCount, summary.saleValue)
                    }
                    if summary.scheduledCount + summary.routineCount > 0 {
                        // Stated rather than silently dropped: a reader who
                        // counts Form 4s elsewhere should be able to reconcile.
                        Text("\(summary.scheduledCount) scheduled-plan and "
                             + "\(summary.routineCount) routine transactions excluded.")
                            .font(.caption2).foregroundStyle(.tertiary)
                    }
                    Divider()
                    ClaimRow(claim: summary.claim)
                }
                .padding(14)
                .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
            }
        }
    }

    private func insiderCount(_ label: String, _ count: Int, _ value: Double?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text("\(count)")
                .font(.system(.title3, design: .rounded).weight(.medium))
                .monospacedDigit()
            Text(value.map { "~\(Format.compactCurrency($0))" } ?? Format.notAvailable)
                .font(.caption2).foregroundStyle(.tertiary)
        }
    }

    // MARK: - Filings

    private var filingsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Text("Recent filings").font(.headline)
                Text("PRIMARY SOURCE")
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(.tertiary)
            }

            if let error = model.filingsError {
                Text(error.recoverySuggestion ?? error.shortDescription)
                    .font(.caption).foregroundStyle(.orange)
            } else if model.filings.isEmpty {
                Text("Loading…").font(.caption).foregroundStyle(.secondary)
            } else {
                ForEach(model.filings.prefix(8), id: \.accessionNumber) { filing in
                    FilingRow(filing: filing)
                }
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }
}

/// One valuation metric with its position in its own history.
private struct ValuationRow: View {
    let metric: ValuationMetric
    let context: HistoricalContext
    let asOf: Date
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                isExpanded.toggle()
            } label: {
                HStack(alignment: .firstTextBaseline) {
                    Text(metric.displayName).font(.subheadline)
                    Spacer()
                    Text(metric.format(context.current))
                        .font(.system(.subheadline, design: .rounded).weight(.medium))
                        .monospacedDigit()
                    Group {
                        if context.meaningfulness.isRankable {
                            Text("\(Format.ordinal(context.percentile)) pctile")
                        } else {
                            Text("not meaningful")
                        }
                    }
                    .font(.caption).monospacedDigit()
                    .foregroundStyle(context.meaningfulness.isRankable
                                     ? AnyShapeStyle(.secondary) : AnyShapeStyle(.orange))
                    .frame(width: 100, alignment: .trailing)
                }
            }
            .buttonStyle(.plain)

            // A bar implies a position in a range. When the value cannot be
            // ranked, drawing one would assert exactly what we are refusing to.
            if context.meaningfulness.isRankable {
                PercentileBar(percentile: context.percentile)
            }

            if context.currentIsFromHistory {
                Text("As of \(Format.shortDate(asOf)) — no current figure published.")
                    .font(.caption2).foregroundStyle(.tertiary)
            }

            if isExpanded {
                VStack(alignment: .leading, spacing: 6) {
                    Text(context.descriptor)
                        .font(.caption)
                        .foregroundStyle(context.meaningfulness.isRankable
                                         ? AnyShapeStyle(.secondary) : AnyShapeStyle(.orange))
                        .fixedSize(horizontal: false, vertical: true)
                    if context.meaningfulness.isRankable {
                        HStack {
                            Text("Range").font(.caption2).foregroundStyle(.secondary)
                            Spacer()
                            Text("\(metric.format(context.minimum)) – \(metric.format(context.maximum))")
                                .font(.system(.caption2, design: .monospaced))
                        }
                        HStack {
                            Text("Median").font(.caption2).foregroundStyle(.secondary)
                            Spacer()
                            Text(metric.format(context.median))
                                .font(.system(.caption2, design: .monospaced))
                        }
                    }
                    HStack {
                        Text("Observations").font(.caption2).foregroundStyle(.secondary)
                        Spacer()
                        Text("\(context.observationCount) since \(Format.shortDate(context.earliest))")
                            .font(.system(.caption2, design: .monospaced))
                    }
                }
                .padding(10)
                .background(.quaternary.opacity(0.4), in: .rect(cornerRadius: 6))
            }
        }
        .padding(.vertical, 2)
    }
}

/// Where the current value sits in its historical range.
private struct PercentileBar: View {
    let percentile: Int

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(.quaternary).frame(height: 4)
                Capsule()
                    .fill(.tint)
                    .frame(width: max(2, geometry.size.width * CGFloat(percentile) / 100),
                           height: 4)
            }
        }
        .frame(height: 4)
        .accessibilityLabel("\(percentile)th percentile of its own history")
    }
}

private struct FilingRow: View {
    let filing: FilingDTO

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(filing.formType)
                .font(.system(.caption, design: .monospaced).weight(.medium))
                .frame(width: 46, alignment: .leading)
            VStack(alignment: .leading, spacing: 1) {
                Text("Filed \(Format.shortDate(filing.filedAt))")
                    .font(.caption)
                if let period = filing.periodOfReport {
                    Text("Period ending \(Format.shortDate(period))")
                        .font(.caption2).foregroundStyle(.tertiary)
                }
            }
            Spacer()
            // The app summarises filings; it never replaces them, so the
            // original is always one tap away.
            if let url = filing.primaryDocumentURL ?? filing.filingIndexURL {
                Link(destination: url) {
                    Image(systemName: "arrow.up.right.square").font(.caption)
                }
            }
        }
        .padding(.vertical, 3)
    }
}

#Preview {
    NavigationStack {
        SecurityDetailView(symbol: "AAPL")
    }
    .environment(AppEnvironment.preview())
}
