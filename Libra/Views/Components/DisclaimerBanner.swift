import SwiftUI

/// The standing "this is not advice" notice, on the first screen rather than
/// buried in Settings.
///
/// It lived only in Settings → About, which meant a reader could use every
/// screen in the app without ever seeing it. Deliberately quieter than
/// `SampleDataBanner`: that one warns that the numbers on screen are invented,
/// which must stay the loudest thing in the app, and two orange banners
/// competing would blunt it.
struct DisclaimerBanner: View {
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "info.circle")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Research tool — not investment advice")
                    .font(.footnote.weight(.semibold))
                Text("Libra analyses published information. It makes no recommendations, and data may be delayed or inaccurate.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 8))
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    DisclaimerBanner().padding()
}
