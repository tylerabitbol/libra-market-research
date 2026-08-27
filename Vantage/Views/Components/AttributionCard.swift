import SwiftUI

/// How much of a move the market accounts for — the deterministic half of
/// "why did it change".
///
/// A stock down 6% on a day the market fell 5% is a completely different
/// situation from the same 6% on a flat day, and an ordinary stock app renders
/// them identically. This card is the difference.
struct AttributionCard: View {
    let attribution: MoveAttribution

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "arrow.triangle.branch")
                    .font(.caption).foregroundStyle(.secondary)
                Text("How much was the market?")
                    .font(.caption.weight(.medium)).foregroundStyle(.secondary)
                Spacer()
                Text(attribution.leaning.displayName)
                    .font(.system(.caption2, design: .monospaced).weight(.semibold))
                    .padding(.horizontal, 5).padding(.vertical, 2)
                    .background(.quaternary.opacity(0.6), in: .rect(cornerRadius: 4))
                    .foregroundStyle(.secondary)
            }

            split

            VStack(alignment: .leading, spacing: 3) {
                ForEach(Array(attribution.detailLines.enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            if attribution.isMarketProxy {
                Text("The S&P 500 index publishes only at the close, so an ETF stands "
                     + "in for the open session. It tracks the index closely but not exactly.")
                    .font(.caption2).foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Divider()
            ClaimRow(claim: attribution.claim)

            if let beta = attribution.beta {
                ClaimRow(claim: beta.claim)
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }

    /// Two bars on a shared scale. Deliberately not a pie or a percentage
    /// split: the two parts can point in opposite directions — a stock can
    /// rise on a falling market — and any "share of the move" framing breaks
    /// down entirely when they do.
    private var split: some View {
        let magnitude = max(abs(attribution.explainedByMarket),
                            abs(attribution.residual), 0.0001)
        return VStack(alignment: .leading, spacing: 6) {
            bar(label: "Market accounts for",
                value: attribution.explainedByMarket, magnitude: magnitude)
            bar(label: "Unexplained",
                value: attribution.residual, magnitude: magnitude)
        }
    }

    private func bar(label: String, value: Double, magnitude: Double) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(label).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Text(Format.percentagePoints(value))
                    .font(.system(.caption, design: .monospaced))
                    .monospacedDigit()
            }
            GeometryReader { proxy in
                let width = proxy.size.width * min(abs(value) / magnitude, 1)
                ZStack(alignment: value < 0 ? .trailing : .leading) {
                    Capsule().fill(.quaternary)
                    Capsule().fill(.secondary).frame(width: max(2, width))
                }
            }
            .frame(height: 4)
        }
    }
}

#Preview {
    AttributionCard(attribution: MoveAttribution(
        securityMove: 8.74, marketMove: 0.31, marketName: "S&P 500 (SPY)",
        beta: Beta(value: 2.24, observationCount: 250,
                   earliest: .now.addingTimeInterval(-365 * 86_400), latest: .now),
        explainedByMarket: 0.69, residual: 8.05, isMarketProxy: true
    ))
    .padding()
    .background(Color(.systemGroupedBackground))
}
