import SwiftUI

/// What a filing actually reported, shown beneath the card announcing it.
///
/// Section 9: "Do not simply dump filing links into the interface. Extract
/// useful metadata and summarize what changed." A card saying a 10-Q exists,
/// with a link, is the thing that instruction rules out.
///
/// Each figure carries its own epistemic status rather than the card carrying
/// one for all of them. A figure with a comparable period behind it is a
/// CALCULATION with arithmetic to show; a figure in a company's first year on
/// file is a FACT with nothing to compare against, and badging the two the
/// same would overstate the second.
struct FilingAnalysisCard: View {
    let analysis: FilingAnalysis.Result

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header

            if analysis.isAwaitingFacts {
                Text("EDGAR has not published this filing's structured figures yet. "
                     + "They usually follow the document itself within a day or two.")
                    .font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else if analysis.lines.isEmpty {
                Text("This filing reported no figures among those tracked.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(analysis.lines) { line in
                        FigureRow(line: line)
                        if line.id != analysis.lines.last?.id { Divider() }
                    }
                }
                Text("Each figure is compared with the same \(analysis.periodLabel) a year "
                     + "earlier, so seasonality is already removed.")
                    .font(.caption2).foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: "doc.text.magnifyingglass")
                .font(.caption).foregroundStyle(.secondary)
            Text("What this filing reported")
                .font(.caption.weight(.medium)).foregroundStyle(.secondary)
            Spacer()
            Text("PRIMARY SOURCE")
                .font(.system(.caption2, design: .monospaced).weight(.semibold))
                .padding(.horizontal, 5).padding(.vertical, 2)
                .background(.quaternary.opacity(0.6), in: .rect(cornerRadius: 4))
                .foregroundStyle(.secondary)
        }
    }
}

/// One figure, expandable to the arithmetic behind it.
private struct FigureRow: View {
    let line: FilingAnalysis.Line
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                isExpanded.toggle()
            } label: {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(line.label)
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Text(line.formatted)
                        .font(.system(.subheadline, design: .rounded).weight(.medium))
                        .monospacedDigit()
                    if let comparison = line.comparison {
                        // Deliberately not tinted. A margin narrowing is not
                        // bad news the way a price fall reads as bad news, and
                        // colouring it would make a judgement the figure does
                        // not support.
                        Text(comparison)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption2).foregroundStyle(.tertiary)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(line.label), \(line.formatted)"
                + (line.comparison.map { ", \($0)" } ?? ""))

            if isExpanded {
                ClaimRow(claim: line.claim)
            }
        }
    }
}

#Preview {
    let source = SourceReference(provider: .sec, detail: "10-Q 0000320193-26-000013",
                                 url: nil, retrievedAt: .now)
    return ScrollView {
        FilingAnalysisCard(analysis: FilingAnalysis.Result(
            accessionNumber: "0000320193-26-000013",
            formType: "10-Q",
            periodEnd: .now,
            lines: [
                .init(label: "Revenue", formatted: "$85.8B", comparison: "+14.2% YoY",
                      claim: Claim(kind: .calculation,
                                   text: "Revenue was $85.8B for the quarter ending Jun 27, "
                                       + "up 14.2% from the same quarter a year earlier.",
                                   sources: [source],
                                   derivation: Derivation(
                                    formula: "(current - prior) ÷ prior",
                                    inputs: [.init(name: "this quarter", value: "$85.8B", source: source),
                                             .init(name: "a year earlier", value: "$75.1B", source: nil)],
                                    result: "+14.2% YoY"))),
                .init(label: "Gross margin", formatted: "46.1%", comparison: "-1.8pp YoY",
                      claim: Claim(kind: .calculation,
                                   text: "Gross margin was 46.1% for the quarter ending Jun 27, "
                                       + "down 1.8pp from the same quarter a year earlier.",
                                   sources: [source]))
            ],
            isAwaitingFacts: false))
        .padding()
    }
    .background(Color(.systemGroupedBackground))
}
