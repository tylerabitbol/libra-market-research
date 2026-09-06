import SwiftUI

/// Shown whenever the view behind it is rendering synthetic data.
///
/// This is not decoration. Sample prices are plausible-looking random walks,
/// and a research tool that displays them without saying so is actively
/// misleading — the one failure mode worse than showing nothing at all.
struct SampleDataBanner: View {
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Sample data")
                    .font(.footnote.weight(.semibold))
                Text("These numbers are randomly generated, not real market data. Add your API keys in Settings.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(.orange.opacity(0.12), in: .rect(cornerRadius: 8))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Warning: sample data. These numbers are randomly generated, not real market data.")
    }
}

#Preview {
    SampleDataBanner().padding()
}
