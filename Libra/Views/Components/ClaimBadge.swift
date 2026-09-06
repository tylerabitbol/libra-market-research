import SwiftUI

/// The FACT / CALCULATION / INTERPRETATION / HYPOTHESIS label from Section 24.
struct ClaimBadge: View {
    let kind: ClaimKind

    var body: some View {
        Text(kind.label)
            .font(.system(.caption2, design: .monospaced).weight(.semibold))
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(tint.opacity(0.15), in: .rect(cornerRadius: 4))
            .foregroundStyle(tint)
            .accessibilityLabel("\(kind.label). \(kind.definition)")
    }

    /// Deliberately not a red/green confidence gradient — these distinguish
    /// *kind* of statement, not good versus bad news.
    private var tint: Color {
        switch kind {
        case .fact: .primary
        case .calculation: .blue
        case .interpretation: .purple
        case .hypothesis: .orange
        }
    }
}

/// A claim rendered with its label, and its derivation available on tap.
///
/// Section 13 forbids a mysterious number: if the app computed something, the
/// user must be able to see exactly how.
struct ClaimRow: View {
    let claim: Claim
    @State private var isShowingDerivation = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                ClaimBadge(kind: claim.kind)
                Text(claim.text)
                    .font(.callout)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if claim.derivation != nil || !claim.sources.isEmpty {
                Button {
                    isShowingDerivation.toggle()
                } label: {
                    Label(
                        isShowingDerivation ? "Hide the arithmetic" : "Show the arithmetic",
                        systemImage: isShowingDerivation ? "chevron.up" : "chevron.down"
                    )
                    .font(.caption)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
            }

            if isShowingDerivation {
                derivationDetail
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var derivationDetail: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let derivation = claim.derivation {
                Text(derivation.formula)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
                ForEach(derivation.inputs) { input in
                    HStack {
                        Text(input.name).font(.caption)
                        Spacer()
                        Text(input.value)
                            .font(.system(.caption, design: .monospaced))
                    }
                }
                Divider()
                HStack {
                    Text("Result").font(.caption.weight(.semibold))
                    Spacer()
                    Text(derivation.result)
                        .font(.system(.caption, design: .monospaced).weight(.semibold))
                }
            }
            ForEach(claim.sources) { source in
                if let url = source.url {
                    Link(destination: url) {
                        Label("\(source.provider.displayName): \(source.detail)",
                              systemImage: "arrow.up.right.square")
                            .font(.caption)
                    }
                } else {
                    Text("\(source.provider.displayName): \(source.detail)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(10)
        .background(.quaternary.opacity(0.4), in: .rect(cornerRadius: 6))
    }
}

#Preview {
    VStack(alignment: .leading, spacing: 16) {
        ForEach(ClaimKind.allCases, id: \.self) { ClaimBadge(kind: $0) }
        Divider()
        ClaimRow(claim: Claim(
            kind: .calculation,
            text: "NVDA outperformed Information Technology by +7.0 pp over the last month.",
            derivation: Derivation(
                formula: "securityReturn - benchmarkReturn",
                inputs: [
                    .init(name: "NVDA", value: "+12.00%", source: nil),
                    .init(name: "Information Technology", value: "+5.00%", source: nil)
                ],
                result: "+7.0 pp"
            )
        ))
    }
    .padding()
}
