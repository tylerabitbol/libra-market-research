import SwiftUI

/// Settings, with Data Sources as the substantive part.
///
/// Section 19: keys never appear in source or in the UI beyond the field the
/// user types them into. Values go straight to the Keychain; this screen only
/// ever asks whether a key *exists*, never reads one back for display.
struct SettingsView: View {
    @Environment(AppEnvironment.self) private var app

    var body: some View {
        Form {
            if app.isUsingSampleData {
                Section {
                    SampleDataBanner()
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }

            if let reason = app.secretsHealth.reason {
                Section {
                    StorageUnavailableBanner(reason: reason)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }

            Section {
                ForEach(dataSources) { source in
                    VStack(alignment: .leading, spacing: 8) {
                        NavigationLink {
                            SecretEntryView(key: source.key, provider: source.provider)
                        } label: {
                            DataSourceRow(source: source,
                                          readiness: app.readiness(for: source.provider))
                        }
                        if app.readiness(for: source.provider).isReady {
                            ConnectionTestRow(provider: source.provider)
                        }
                    }
                    // Typing a key into a store that cannot retain it is worse
                    // than saying up front that it won't work.
                    .disabled(!app.secretsHealth.isAvailable)
                }
            } header: {
                Text("Data sources")
            } footer: {
                Text("Keys are stored in the iOS Keychain on this device. They are never written to the app's database, never included in logs, and never sent anywhere except the provider they belong to.")
            }

            Section {
                NavigationLink {
                    SecretEntryView(key: .anthropicAPIKey, provider: .computed)
                } label: {
                    HStack {
                        Text(SecretKey.anthropicAPIKey.displayName)
                        Spacer()
                        Text(app.hasKey(.anthropicAPIKey) ? "Set" : "Not set")
                            .foregroundStyle(.secondary)
                    }
                }
            } header: {
                Text("AI summaries")
            } footer: {
                Text("Optional. Every calculation in this app is deterministic Swift code and works without it. When enabled, AI is used only to summarise and organise data already fetched — it never supplies a figure of its own.")
            }

            Section("About") {
                LabeledContent("Research tool", value: "Not investment advice")
                    .foregroundStyle(.secondary)
                Text("This app analyses and organises published information. It does not make recommendations, predict prices, or tell you what to buy or sell.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var dataSources: [DataSourceDescriptor] {
        [
            .init(key: .finnhubAPIKey, provider: .finnhub,
                  purpose: "Live quotes, company profiles, ratings, news"),
            .init(key: .tiingoAPIKey, provider: .tiingo,
                  purpose: "Daily price history — charts, volatility, momentum"),
            .init(key: .secContactEmail, provider: .sec,
                  purpose: "Filings, financial statements, insider transactions"),
            .init(key: .fredAPIKey, provider: .fred,
                  purpose: "Index levels (S&P 500, Dow, Nasdaq, VIX) and macro series")
        ]
    }
}

/// Verifies a stored key by actually using it.
///
/// Without this, a mistyped key looks identical to a working one until some
/// unrelated screen renders empty much later.
private struct ConnectionTestRow: View {
    let provider: DataProviderID
    @Environment(AppEnvironment.self) private var app

    @State private var result: ConnectionTest.Result?
    @State private var isTesting = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button {
                Task { await test() }
            } label: {
                HStack(spacing: 6) {
                    if isTesting {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: "checkmark.shield")
                    }
                    Text(isTesting ? "Testing…" : "Test connection")
                }
                .font(.caption)
            }
            .buttonStyle(.borderless)
            .disabled(isTesting)

            if let result {
                Label(result.message, systemImage: result.isSuccess
                      ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .font(.caption2)
                    .foregroundStyle(result.isSuccess ? .green : .red)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func test() async {
        isTesting = true
        defer { isTesting = false }
        result = await ConnectionTest.run(for: provider, registry: app.registry)
    }
}

/// Shown when the Keychain itself is unusable, so the failure is attributed to
/// the build rather than to the user's key.
private struct StorageUnavailableBanner: View {
    let reason: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "lock.trianglebadge.exclamationmark.fill")
                .foregroundStyle(.red)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Secure storage unavailable")
                    .font(.footnote.weight(.semibold))
                Text(reason)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(.red.opacity(0.12), in: .rect(cornerRadius: 8))
        .accessibilityElement(children: .combine)
    }
}

struct DataSourceDescriptor: Identifiable, Sendable {
    var id: String { key.rawValue }
    let key: SecretKey
    let provider: DataProviderID
    let purpose: String
}

private struct DataSourceRow: View {
    let source: DataSourceDescriptor
    let readiness: SourceReadiness

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: readiness.isReady ? "checkmark.circle.fill" : "circle.dashed")
                .foregroundStyle(readiness.isReady ? .green : .secondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(source.provider.displayName)
                        .font(.body)
                    if source.provider.isPrimarySource {
                        Text("PRIMARY SOURCE")
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                }
                Text(source.purpose)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let message = readiness.message {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Entry screen for one credential.
///
/// The field is cleared on appear and the stored value is never loaded back
/// into it. Showing an existing key would put a secret on screen for no benefit
/// — the user already has it wherever they got it from.
struct SecretEntryView: View {
    let key: SecretKey
    let provider: DataProviderID

    @Environment(AppEnvironment.self) private var app
    @Environment(\.dismiss) private var dismiss

    @State private var draft = ""
    @State private var isRevealed = false
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section {
                if key.isSensitive && !isRevealed {
                    SecureField(key.displayName, text: $draft)
                        .textContentType(.password)
                } else {
                    TextField(key.displayName, text: $draft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(key == .secContactEmail ? .emailAddress : .asciiCapable)
                }

                if key.isSensitive {
                    Toggle("Show what I'm typing", isOn: $isRevealed)
                        .font(.callout)
                }
            } header: {
                Text(key.displayName)
            } footer: {
                Text(key.helpText)
            }

            if app.hasKey(key) {
                Section {
                    Text("A value is already saved. Typing here replaces it.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if let fingerprint = app.secrets.fingerprint(for: key) {
                        LabeledContent("Stored") {
                            Text(fingerprint)
                                .font(.system(.caption, design: .monospaced))
                        }
                        Text("Compare this with the key shown in your provider's dashboard. A different length means it was truncated or pasted incompletely.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Button("Remove saved value", role: .destructive) {
                        save(nil)
                    }
                }
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }

            Section {
                Button("Save") { save(draft) }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            } footer: {
                if key == .secContactEmail {
                    Text("The SEC asks every automated client to identify itself with a contact address. Requests to EDGAR stay disabled until this is set.")
                }
            }
        }
        .navigationTitle(provider.displayName)
        .navigationBarTitleDisplayMode(.inline)
        // Never prefill from storage.
        .onAppear { draft = "" }
    }

    private func save(_ value: String?) {
        do {
            let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines)
            try app.setSecret(trimmed?.isEmpty == true ? nil : trimmed, for: key)
            draft = ""
            errorMessage = nil
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

#Preview {
    NavigationStack {
        SettingsView()
            .navigationTitle("Settings")
    }
    .environment(AppEnvironment.preview())
}
