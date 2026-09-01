import SwiftUI

/// Section 14, scoped to what is true.
///
/// The spec implies screening a universe; free tiers make that impossible, and
/// a screen that silently examined a dozen stocks while looking like it
/// examined a market would be worse than none. This screens what the app holds
/// and says so plainly at the top, which is the difference between a small
/// honest tool and a misleading one.
struct ScreenerView: View {
    @Environment(AppEnvironment.self) private var app
    @State private var model = ScreenerViewModel()

    var body: some View {
        List {
            coverageSection
            rulesSection
            savedSection
            resultsSection
        }
        .navigationDestination(for: String.self) { SecurityDetailView(symbol: $0) }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Add rule", systemImage: "plus") { model.addRule() }
                    Button("Save this screen", systemImage: "tray.and.arrow.down") {
                        model.saveCurrentScreen()
                    }
                    .disabled(model.screen.rules.isEmpty)
                    Button("Start over", systemImage: "arrow.counterclockwise") {
                        model.newScreen()
                    }
                } label: {
                    Label("Screen", systemImage: "ellipsis.circle")
                }
            }
        }
        .refreshable { await model.load(snapshots: app.snapshots) }
        .task { await model.load(snapshots: app.snapshots) }
    }

    private var coverageSection: some View {
        Section {
            Text("Screening the \(model.subjects.count) "
                 + "\(model.subjects.count == 1 ? "security" : "securities") this app holds "
                 + "data for — your watchlist and anything you have opened. This is not a "
                 + "market-wide scan, and nothing here costs an API request.")
                .font(.caption).foregroundStyle(.secondary)
            if let failure = model.loadFailure {
                Text(failure).font(.caption).foregroundStyle(.orange)
            }
        }
    }

    private var rulesSection: some View {
        Section {
            if model.screen.rules.isEmpty {
                Button {
                    model.addRule()
                } label: {
                    Label("Add a rule", systemImage: "plus.circle")
                }
            } else {
                Picker("Combine", selection: $model.screen.combinator) {
                    ForEach(ScreenCombinator.allCases) { Text($0.displayName).tag($0) }
                }
                .pickerStyle(.segmented)

                ForEach($model.screen.rules) { $rule in
                    RuleEditor(rule: $rule)
                }
                .onDelete { model.removeRules(at: $0) }

                TextField("Name this screen", text: $model.screen.name)
                    .font(.caption)
            }
        } header: {
            Text("Rules")
        } footer: {
            if !model.screen.rules.isEmpty && model.untestableCount > 0 {
                // A screen that quietly drops what it could not test reports a
                // smaller universe than the user thinks they searched.
                Text("\(model.untestableCount) held "
                     + "\(model.untestableCount == 1 ? "security lacks" : "securities lack") a "
                     + "figure one of these rules needs, so they cannot match. Open them once "
                     + "to fill in what is missing.")
            }
        }
    }

    @ViewBuilder
    private var savedSection: some View {
        if !model.savedScreens.isEmpty {
            Section("Saved screens") {
                ForEach(model.savedScreens) { saved in
                    Button {
                        model.apply(saved)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(saved.name).font(.subheadline)
                            Text(saved.rules.map(\.summary).joined(separator: "  ·  "))
                                .font(.caption2).foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                    }
                    .buttonStyle(.plain)
                    .swipeActions {
                        Button("Delete", role: .destructive) { model.delete(saved) }
                    }
                }
            }
        }
    }

    private var resultsSection: some View {
        Section("\(model.results.count) of \(model.subjects.count) match") {
            if model.subjects.isEmpty {
                ContentUnavailableView {
                    Label("Nothing to screen yet", systemImage: "line.3.horizontal.decrease.circle")
                } description: {
                    Text("Add securities to your watchlist and open a few. The screener works "
                         + "over what the app has already collected, so it fills in as you use "
                         + "the rest of the app.")
                }
            } else if model.results.isEmpty {
                Text("No held security matches these rules.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                ForEach(model.results) { subject in
                    NavigationLink(value: subject.symbol) {
                        ResultRow(subject: subject, fields: model.screen.rules.map(\.field))
                    }
                }
            }
        }
    }
}

private struct RuleEditor: View {
    @Binding var rule: ScreenRule

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Picker("Field", selection: $rule.field) {
                ForEach(ScreenField.allCases) { Text($0.displayName).tag($0) }
            }
            HStack {
                Picker("Comparison", selection: $rule.comparison) {
                    ForEach(ScreenComparison.allCases) { Text($0.displayName).tag($0) }
                }
                .pickerStyle(.segmented)
                TextField("Value", value: $rule.threshold, format: .number)
                    .keyboardType(.numbersAndPunctuation)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 90)
                    .monospacedDigit()
            }
        }
    }
}

private struct ResultRow: View {
    let subject: ScreenSubject
    let fields: [ScreenField]

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(subject.symbol)
                    .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                Text(subject.name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            // Show the figures the screen actually tested, so a result can be
            // checked rather than taken on faith.
            HStack(spacing: 10) {
                ForEach(Array(Set(fields)).sorted(by: { $0.rawValue < $1.rawValue })) { field in
                    if let value = subject.value(for: field) {
                        Text("\(field.displayName): \(field.format(value))")
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundStyle(.tertiary)
                    }
                }
            }
        }
    }
}

#Preview {
    NavigationStack { ScreenerView() }
        .environment(AppEnvironment.preview())
}
