import SwiftUI

/// Section 16: the research journal, and what happened since each note.
///
/// The comparison is arithmetic and stops there. Whether a thesis was right is
/// a judgement about reasoning, and a tool that graded it would be marking its
/// own homework with the user's money. The app restates the falsification
/// condition the user wrote in advance and leaves the verdict to them.
struct JournalSection: View {
    let entries: [JournalEntryDTO]
    let comparison: (JournalEntryDTO) -> ThesisComparison
    let onNew: () -> Void
    let onEdit: (JournalEntryDTO) -> Void
    let onDelete: (JournalEntryDTO) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Research journal").font(.headline)
                Spacer()
                Button(action: onNew) {
                    Label("New note", systemImage: "square.and.pencil").font(.caption)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
            }

            if entries.isEmpty {
                Text("Record what you expect and what would prove you wrong. When you "
                     + "return, the app shows what actually happened — which is the only "
                     + "way to tell a good decision from a lucky one.")
                    .font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(Color(.secondarySystemGroupedBackground),
                                in: .rect(cornerRadius: 12))
            } else {
                ForEach(entries) { entry in
                    JournalEntryCard(entry: entry, comparison: comparison(entry),
                                     onEdit: { onEdit(entry) },
                                     onDelete: { onDelete(entry) })
                }
            }
        }
    }
}

private struct JournalEntryCard: View {
    let entry: JournalEntryDTO
    let comparison: ThesisComparison
    let onEdit: () -> Void
    let onDelete: () -> Void
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(entry.title.isEmpty ? "Untitled note" : entry.title)
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(Format.shortDate(entry.createdAt))
                    .font(.caption).foregroundStyle(.tertiary).monospacedDigit()
            }

            if let claim = comparison.claim {
                ClaimRow(claim: claim)
            } else {
                Text("No price was captured with this note, so there is nothing to "
                     + "compare it against.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if let prompt = comparison.falsificationPrompt {
                // Restated, never judged. Deciding whether the condition has
                // been met is the reason the field exists.
                Text(prompt)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.quaternary.opacity(0.4), in: .rect(cornerRadius: 8))
            }

            if !comparison.eventsSince.isEmpty {
                Text("\(comparison.eventsSince.count) recorded "
                     + "\(comparison.eventsSince.count == 1 ? "change" : "changes") since.")
                    .font(.caption2).foregroundStyle(.tertiary)
            }

            if isExpanded { fields }

            HStack(spacing: 16) {
                Button(isExpanded ? "Hide the note" : "Read the note") {
                    isExpanded.toggle()
                }
                Button("Edit", action: onEdit)
                Spacer()
                Button("Delete", role: .destructive, action: onDelete)
            }
            .font(.caption)
            .buttonStyle(.plain)
            .foregroundStyle(.tint)
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }

    @ViewBuilder
    private var fields: some View {
        VStack(alignment: .leading, spacing: 8) {
            field("Thesis", entry.thesis)
            field("What I expect", entry.expectation)
            field("What would prove me wrong", entry.falsification)
            field("Concerns", entry.concerns)
            field("Catalysts", entry.catalysts)
            field("Risks", entry.risks)
            field("Notes", entry.notes)
        }
    }

    @ViewBuilder
    private func field(_ label: String, _ value: String) -> some View {
        if !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.caption2).foregroundStyle(.tertiary)
                Text(value).font(.caption)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// The editor. Plain fields, in the order the spec lists them.
struct JournalEditor: View {
    @State var entry: JournalEntryDTO
    let onSave: (JournalEntryDTO) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Title") { TextField("Title", text: $entry.title) }
                Section("Thesis") {
                    TextField("What is the case?", text: $entry.thesis, axis: .vertical)
                        .lineLimit(3...8)
                }
                Section {
                    TextField("What do you expect to happen?",
                              text: $entry.expectation, axis: .vertical)
                        .lineLimit(2...6)
                    TextField("What would prove you wrong?",
                              text: $entry.falsification, axis: .vertical)
                        .lineLimit(2...6)
                } header: {
                    Text("Expectation")
                } footer: {
                    Text("Writing the disconfirming condition down before the outcome is "
                         + "known is what makes the later comparison honest rather than a "
                         + "memory exercise.")
                }
                Section("Concerns") {
                    TextField("Concerns", text: $entry.concerns, axis: .vertical)
                        .lineLimit(2...6)
                }
                Section("Catalysts") {
                    TextField("Catalysts", text: $entry.catalysts, axis: .vertical)
                        .lineLimit(2...6)
                }
                Section("Risks") {
                    TextField("Risks", text: $entry.risks, axis: .vertical)
                        .lineLimit(2...6)
                }
                Section("Notes") {
                    TextField("Anything else", text: $entry.notes, axis: .vertical)
                        .lineLimit(3...10)
                }
            }
            .navigationTitle("Research note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(entry)
                        dismiss()
                    }
                    .disabled(entry.isEmpty)
                }
            }
        }
    }
}
