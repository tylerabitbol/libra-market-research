import SwiftUI

/// The five product areas from Section 2. Watchlist leads because the spec
/// says the primary experience revolves around it; Dashboard sits first only
/// because it answers "what changed while I was away" at a market level.
enum AppSection: String, CaseIterable, Identifiable, Hashable {
    case dashboard, watchlist, research, screener, settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .dashboard: "Dashboard"
        case .watchlist: "Watchlist"
        case .research: "Research"
        case .screener: "Screener"
        case .settings: "Settings"
        }
    }

    var systemImage: String {
        switch self {
        case .dashboard: "chart.line.uptrend.xyaxis"
        case .watchlist: "list.bullet.rectangle"
        case .research: "magnifyingglass"
        case .screener: "line.3.horizontal.decrease.circle"
        case .settings: "gearshape"
        }
    }
}

struct RootView: View {
    @State private var selection: AppSection = .dashboard
    @State private var appEnvironment = AppEnvironment()
    @Environment(\.modelContext) private var modelContext

    var body: some View {
        TabView(selection: $selection) {
            ForEach(AppSection.allCases) { section in
                Tab(section.title, systemImage: section.systemImage, value: section) {
                    NavigationStack {
                        content(for: section)
                            .navigationTitle(section.title)
                    }
                }
            }
        }
        .environment(appEnvironment)
        .task {
            DeveloperOptions.seedWatchlist(context: modelContext)
            appEnvironment.attach(container: modelContext.container)
        }
    }

    @ViewBuilder
    private func content(for section: AppSection) -> some View {
        switch section {
        case .dashboard: DashboardView()
        case .watchlist: WatchlistView()
        case .research: ResearchView()
        case .screener: ScreenerView()
        case .settings: SettingsView()
        }
    }
}

#Preview {
    RootView()
        .modelContainer(AppModelContainer.preview)
}
