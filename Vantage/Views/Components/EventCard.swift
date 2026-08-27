import SwiftUI

/// One detected change, rendered with its epistemic labels intact.
///
/// The headline is a CALCULATION — measured arithmetic, with the derivation one
/// tap away. The "why this matters" text is an INTERPRETATION and is badged
/// differently, because it is a judgement about what the figures show rather
/// than a measurement. Neither ever becomes a suggestion to buy or sell.
struct EventCard: View {
    let event: DetectedEventDTO
    /// Shown when the card appears in a feed mixing several securities.
    var symbol: String?
    var isNew: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header

            ClaimRow(claim: event.headlineClaim)

            if !event.detailLines.isEmpty {
                VStack(alignment: .leading, spacing: 3) {
                    ForEach(Array(event.detailLines.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }

            if event.unusualness > 0 {
                UnusualnessMeter(value: event.unusualness)
            }

            if let context = event.contextClaim {
                Divider()
                ClaimRow(claim: context)
            }

            if !event.sourceURLs.isEmpty {
                ForEach(event.sourceURLs, id: \.self) { url in
                    Link(destination: url) {
                        Label("Open the source document", systemImage: "arrow.up.right.square")
                            .font(.caption)
                    }
                }
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: event.kind.systemImage)
                .font(.caption)
                .foregroundStyle(.secondary)
            if let symbol {
                Text(symbol)
                    .font(.system(.caption, design: .monospaced).weight(.semibold))
            }
            Text(event.kind.displayName)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
            if isNew {
                Text("NEW")
                    .font(.system(.caption2, design: .monospaced).weight(.semibold))
                    .padding(.horizontal, 5).padding(.vertical, 2)
                    .background(.tint.opacity(0.15), in: .rect(cornerRadius: 4))
                    .foregroundStyle(.tint)
            }
            if event.isProvisional {
                Text("SESSION OPEN")
                    .font(.system(.caption2, design: .monospaced).weight(.semibold))
                    .padding(.horizontal, 5).padding(.vertical, 2)
                    .background(.orange.opacity(0.15), in: .rect(cornerRadius: 4))
                    .foregroundStyle(.orange)
                    .accessibilityLabel("Session still open. This reading can "
                        + "change before the close and is not yet recorded.")
            }
            Spacer()
            Text(Format.dayAndMonth(event.occurredAt))
                .font(.caption)
                .foregroundStyle(.tertiary)
                .monospacedDigit()
        }
    }
}

/// How unusual a reading is relative to the security's own recent history.
///
/// Labelled in words as well as drawn, and deliberately not tinted red or
/// green: unusual is not the same as bad, and this figure carries no direction.
/// It is a position within an observed sample, never a probability — a bar that
/// implied "2% chance" would be a fabricated statistic.
struct UnusualnessMeter: View {
    /// 0–1.
    let value: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Unusual for this security")
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                Text(descriptor)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
            }
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(.quaternary)
                    Capsule()
                        .fill(.secondary)
                        .frame(width: max(2, proxy.size.width * min(max(value, 0), 1)))
                }
            }
            .frame(height: 4)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Unusual for this security: \(descriptor). "
            + "Measured against its own recent history. Not a measure of "
            + "importance, direction, or likelihood.")
    }

    private var descriptor: String {
        switch value {
        case ..<0.5: "Within its usual range"
        case ..<0.9: "Above its usual range"
        case ..<0.99: "Rare for this security"
        default: "Among the most extreme in the sample"
        }
    }
}

#Preview {
    ScrollView {
        VStack(spacing: 12) {
            EventCard(
                event: DetectedEventDTO(
                    kind: .unusualVolume,
                    occurredAt: .now,
                    headline: "Volume 2.4× the 60-session median on Aug 26",
                    detailLines: [
                        "Volume: 214.9M shares",
                        "Median of prior 60 sessions: 89.5M shares",
                        "Higher than 60 of those 60 sessions"
                    ],
                    context: "Elevated volume means more shares changed hands than usual, "
                        + "which indicates unusual attention. It carries no direction on its own.",
                    unusualness: 1.0,
                    sourceDetails: ["Daily bars"]
                ),
                symbol: "NVDA",
                isNew: true
            )
        }
        .padding()
    }
    .background(Color(.systemGroupedBackground))
}
