import SwiftUI
import SwiftData

/// The watchlist (Section 15).
///
/// Membership lives in SwiftData so the list renders instantly from disk and
/// stays usable offline; prices arrive afterwards and fill in.
struct WatchlistView: View {
    @Environment(AppEnvironment.self) private var app
    @Environment(\.modelContext) private var context

    @Query(sort: \WatchlistEntry.addedAt, order: .reverse)
    private var entries: [WatchlistEntry]

    @State private var model = WatchlistViewModel()
    @State private var isAddingSymbol = false
    /// Debug-only deep link; nil in release builds.
    @State private var debugSymbol: String? = DeveloperOptions.debugSymbol
    /// A failed write used to vanish silently, leaving the user believing a
    /// security had been added when it had not.
    @State private var saveError: String?

    var body: some View {
        Group {
            if entries.isEmpty {
                emptyState
            } else {
                list
            }
        }
        .navigationDestination(item: $debugSymbol) { symbol in
            SecurityDetailView(symbol: symbol)
        }
        .navigationTitle("Watchlist")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { isAddingSymbol = true } label: {
                    Label("Add security", systemImage: "plus")
                }
            }
            if !entries.isEmpty {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Picker("Sort", selection: Binding(
                            get: { model.sort }, set: { model.setSort($0) }
                        )) {
                            ForEach(WatchlistViewModel.SortOrder.allCases) { order in
                                Text(order.displayName).tag(order)
                            }
                        }
                    } label: {
                        Label("Sort", systemImage: "arrow.up.arrow.down")
                    }
                }
            }
        }
        .sheet(isPresented: $isAddingSymbol) {
            AddSymbolSheet { profile in add(profile) }
        }
        .alert("Couldn't save", isPresented: .constant(saveError != nil)) {
            Button("OK") { saveError = nil }
        } message: {
            Text(saveError ?? "")
        }
        .refreshable { model.load(entries: entries, registry: app.registry, snapshots: app.snapshots, force: true) }
        .task(id: entries.count) {
            model.load(entries: entries, registry: app.registry, snapshots: app.snapshots)
        }
    }

    private var list: some View {
        List {
            if app.isUsingSampleData {
                SampleDataBanner()
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }

            Section {
                ForEach(model.sortedRows) { row in
                    NavigationLink {
                        SecurityDetailView(symbol: row.symbol)
                    } label: {
                        WatchlistRowView(row: row)
                    }
                }
                .onDelete(perform: delete)
            } footer: {
                FreshnessLabel(freshness: model.freshness, font: .caption2)
            }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No securities yet", systemImage: "list.bullet.rectangle")
        } description: {
            Text("Add a company to start tracking what changes underneath it.")
        } actions: {
            Button("Add a security") { isAddingSymbol = true }
                .buttonStyle(.borderedProminent)
        }
    }

    private func add(_ profile: CompanyProfileDTO) {
        // Reuse an existing Security rather than creating a duplicate; symbol
        // is unique in the schema and a second insert would fail the save.
        let symbol = profile.symbol.uppercased()
        let descriptor = FetchDescriptor<Security>(
            predicate: #Predicate { $0.symbol == symbol }
        )
        let existing = try? context.fetch(descriptor).first

        let security: Security
        if let existing {
            security = existing
        } else {
            security = Security(symbol: symbol, name: profile.name,
                                exchange: profile.exchange, sector: profile.sector,
                                industry: profile.industry, cik: profile.cik,
                                currency: profile.currency)
            context.insert(security)
        }

        guard security.watchlistEntry == nil else { return }
        let entry = WatchlistEntry(security: security, priority: entries.count)
        context.insert(entry)
        save()
    }

    private func save() {
        do {
            try context.save()
            saveError = nil
        } catch {
            saveError = error.localizedDescription
        }
    }

    private func delete(at offsets: IndexSet) {
        let sorted = model.sortedRows
        for index in offsets {
            guard index < sorted.count else { continue }
            let symbol = sorted[index].symbol
            // Remove the watchlist membership only. The Security and its
            // recorded history stay: Section 17 depends on not discarding
            // history, and re-adding later should find it intact.
            if let entry = entries.first(where: { $0.security?.symbol == symbol }) {
                context.delete(entry)
            }
        }
        save()
    }
}

private struct WatchlistRowView: View {
    let row: WatchlistRow

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.symbol)
                    .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                Text(row.name)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                if !row.hasValue, let error = row.error {
                    Text(error.shortDescription)
                        .font(.caption2).foregroundStyle(.orange)
                } else {
                    // A price from disk beats an error message. The refresh
                    // failing does not make the last known price untrue — it
                    // makes it old, which is what the timestamp below says.
                    Text(Format.currency(row.last))
                        .font(.system(.subheadline, design: .rounded).weight(.medium))
                        .monospacedDigit()
                        .foregroundStyle(row.isStoredCopy ? .secondary : .primary)
                    DirectionalChangeText(percent: row.changePercent, font: .caption)
                    if let asOf = row.asOf {
                        Text(RelativeTimeText.string(for: asOf))
                            .font(.caption2).foregroundStyle(.tertiary)
                    }
                }
            }
        }
        .padding(.vertical, 2)
    }
}

/// Search-and-add sheet.
private struct AddSymbolSheet: View {
    @Environment(AppEnvironment.self) private var app
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var model = SymbolSearchViewModel()
    let onSelect: (CompanyProfileDTO) -> Void

    var body: some View {
        NavigationStack {
            List {
                if model.isSearching {
                    HStack { ProgressView(); Text("Searching…").font(.caption) }
                } else if let error = model.error {
                    Text(error.recoverySuggestion ?? error.shortDescription)
                        .font(.caption).foregroundStyle(.orange)
                } else if !query.isEmpty && model.results.isEmpty {
                    Text("No matches.").font(.caption).foregroundStyle(.secondary)
                }

                ForEach(model.results, id: \.symbol) { profile in
                    Button {
                        onSelect(profile)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(profile.symbol)
                                .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                            Text(profile.name)
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .navigationTitle("Add security")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "Ticker or company name")
            .onChange(of: query) { _, new in
                model.search(new, registry: app.registry)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

#Preview {
    NavigationStack { WatchlistView() }
        .environment(AppEnvironment.preview())
        .modelContainer(AppModelContainer.preview)
}
