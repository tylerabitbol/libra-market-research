import SwiftUI

/// A labelled value that renders "Not available" honestly when the figure is
/// absent, rather than showing a zero or an empty cell that reads as one.
struct MetricCell: View {
    let label: String
    let value: String
    var secondary: String?
    var isAvailable: Bool = true
    var alignment: HorizontalAlignment = .leading

    var body: some View {
        VStack(alignment: alignment, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(.body, design: .rounded).weight(.medium))
                .foregroundStyle(isAvailable ? .primary : .secondary)
                .monospacedDigit()
            if let secondary {
                Text(secondary)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : .trailing)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label): \(value)")
    }
}

/// Direction-coloured variant for places where the dashboard genuinely benefits
/// from scanning colour, e.g. a dense benchmark grid.
struct DirectionalChangeText: View {
    let percent: Double?
    var precision: Int = 2
    var font: Font = .body

    var body: some View {
        Text(Format.signedPercent(percent, precision: precision))
            .font(font)
            .monospacedDigit()
            .foregroundStyle(color)
    }

    private var color: Color {
        guard let percent else { return .secondary }
        if percent > 0 { return .green }
        if percent < 0 { return .red }
        return .secondary
    }
}

/// The "Updated 4 minutes ago" line required by Section 20.
struct FreshnessLabel: View {
    let freshness: Freshness
    var font: Font = .caption2

    var body: some View {
        HStack(spacing: 4) {
            if case .refreshing = freshness {
                ProgressView().controlSize(.mini)
            } else if isProblem {
                Image(systemName: "exclamationmark.circle")
                    .accessibilityHidden(true)
            }
            Text(RelativeTimeText.status(for: freshness))
        }
        .font(font)
        .foregroundStyle(isProblem ? AnyShapeStyle(.orange) : AnyShapeStyle(.tertiary))
    }

    private var isProblem: Bool {
        switch freshness {
        case .stale, .failed, .missing: true
        case .fresh, .refreshing: false
        }
    }
}

#Preview {
    VStack(alignment: .leading, spacing: 20) {
        HStack {
            MetricCell(label: "Market cap", value: Format.compactCurrency(3_100_000_000_000))
            MetricCell(label: "Volume", value: Format.compact(48_200_000))
            MetricCell(label: "Beta", value: Format.notAvailable, isAvailable: false)
        }
        HStack(spacing: 16) {
            DirectionalChangeText(percent: 4.82)
            DirectionalChangeText(percent: -1.34)
            DirectionalChangeText(percent: nil)
        }
        VStack(alignment: .leading, spacing: 6) {
            FreshnessLabel(freshness: .fresh(asOf: .now.addingTimeInterval(-240)))
            FreshnessLabel(freshness: .stale(asOf: .now.addingTimeInterval(-7200)))
            FreshnessLabel(freshness: .failed(previous: .now.addingTimeInterval(-3600), reason: "rate limit"))
            FreshnessLabel(freshness: .missing)
        }
    }
    .padding()
}
