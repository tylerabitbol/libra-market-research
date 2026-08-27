import SwiftUI

/// Every change the detectors have recorded, across all securities.
///
/// Deliberately a record rather than a live scan: the feed is built from what
/// was stored during ordinary use, so opening it costs no API requests and
/// cannot be misread as a market-wide sweep it never performed.
struct ResearchView: View {
    @Environment(AppEnvironment.self) private var app
    @State private var model = ResearchViewModel()

    var body: some View {
        Group {
            if model.events.isEmpty {
                emptyState
            } else {
                feed
            }
        }
        .background(Color(.systemGroupedBackground))
        .toolbar {
            if !model.events.isEmpty {
                ToolbarItem(placement: .topBarTrailing) { sortMenu }
            }
        }
        .refreshable { await model.load(snapshots: app.snapshots) }
        .task { await model.load(snapshots: app.snapshots) }
    }

    private var feed: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                Text("Detected while you were researching. Each is measured "
                     + "against that security's own history.")
                    .font(.caption).foregroundStyle(.secondary)

                if model.availableCategories.count > 1 { categoryFilter }

                if let failure = model.loadFailure {
                    Text(failure).font(.caption).foregroundStyle(.orange)
                }

                ForEach(model.visibleEvents) { entry in
                    NavigationLink(value: entry.symbol) {
                        EventCard(event: entry.event, symbol: entry.symbol)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
            .padding(.bottom, 32)
        }
        .navigationDestination(for: String.self) { SecurityDetailView(symbol: $0) }
    }

    private var categoryFilter: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                FilterChip(title: "All", isOn: model.kindFilter == nil) {
                    model.kindFilter = nil
                }
                ForEach(model.availableCategories) { category in
                    FilterChip(title: category.displayName,
                               isOn: model.kindFilter == category) {
                        model.kindFilter = category
                    }
                }
            }
        }
        .scrollClipDisabled()
    }

    private var sortMenu: some View {
        Menu {
            Picker("Sort", selection: Binding(
                get: { model.sort },
                set: { model.sort = $0 }
            )) {
                ForEach(ResearchViewModel.Sort.allCases) { Text($0.rawValue).tag($0) }
            }
        } label: {
            Label("Sort", systemImage: "arrow.up.arrow.down")
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("Nothing detected yet", systemImage: "magnifyingglass")
        } description: {
            Text("Changes are recorded as you open securities. Visit one from "
                 + "your watchlist, and anything unusual in its price, volume, "
                 + "volatility, or filings will collect here.\n\n"
                 + "A move in a session that is still open is shown on the "
                 + "security's own page but not recorded here until it closes — "
                 + "a reading taken at midday is not yet what happened that day.")
        }
    }
}

/// A filter pill. Plain rather than tinted by category — the categories are
/// kinds of evidence, not levels of severity.
private struct FilterChip: View {
    let title: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.caption.weight(.medium))
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(isOn ? AnyShapeStyle(.tint.opacity(0.18))
                                 : AnyShapeStyle(.quaternary.opacity(0.5)),
                            in: .capsule)
                .foregroundStyle(isOn ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? [.isSelected] : [])
    }
}

#Preview {
    NavigationStack { ResearchView() }
        .environment(AppEnvironment.preview())
}
