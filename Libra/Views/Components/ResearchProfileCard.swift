import SwiftUI

/// The eleven dimensions, grouped by which way they point.
///
/// **Challenges come first.** Section 12 asks the app to actively search for
/// disconfirming evidence, and a section that lists the supporting case first
/// buries the half a reader is least likely to go looking for on their own.
///
/// There is no total. Section 13 asked for a 0–100 signal; the components are
/// here and the number deliberately is not, because summing a valuation
/// percentile, an insider count and a volatility rank produces a figure that
/// looks authoritative and means nothing. The footer says so on screen rather
/// than leaving its absence to be read as an oversight.
struct ResearchProfileCard: View {
    let profile: ResearchProfile
    @State private var isShowingUnavailable = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ClaimRow(claim: profile.shapeClaim)

            if !profile.challenging.isEmpty {
                group(title: "What challenges the picture", components: profile.challenging)
            }
            if !profile.supporting.isEmpty {
                group(title: "What supports it", components: profile.supporting)
            }
            let mixed = profile.components.filter { $0.direction == .neutral }
            if !mixed.isEmpty {
                group(title: "Neither way", components: mixed)
            }

            if !profile.unavailable.isEmpty {
                Button {
                    isShowingUnavailable.toggle()
                } label: {
                    Label(isShowingUnavailable
                          ? "Hide what could not be measured"
                          : "\(Format.count(profile.unavailable.count, "dimension")) could not be measured",
                          systemImage: isShowingUnavailable ? "chevron.up" : "chevron.down")
                        .font(.caption)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)

                if isShowingUnavailable {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(profile.unavailable) { component in
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Text(component.dimension.displayName)
                                    .font(.caption).foregroundStyle(.secondary)
                                Spacer(minLength: 8)
                                Text(component.summary)
                                    .font(.caption2).foregroundStyle(.tertiary)
                                    .multilineTextAlignment(.trailing)
                            }
                        }
                    }
                    // An absent dimension is not reassurance, and saying so is
                    // the point of keeping it visible at all.
                    Text("A dimension with no data is not the same as a dimension with "
                         + "nothing to report.")
                        .font(.caption2).foregroundStyle(.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            Divider()
            Text("No overall score is shown. Weighing these against one another is the "
                 + "judgement this tool leaves to you — a single number would only look "
                 + "like it had made it for you.")
                .font(.caption2).foregroundStyle(.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }

    private func group(title: String, components: [ResearchComponent]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title)
                    .font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Spacer()
                Text("\(components.count)")
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(.tertiary)
            }
            ForEach(components) { component in
                DimensionRow(component: component)
                if component.id != components.last?.id { Divider() }
            }
        }
    }
}

/// One dimension, expandable to the arithmetic and to what it was measured
/// against. Deliberately untinted: "challenges" is not "bad", and colouring
/// these red and green would turn a research tool into a verdict.
private struct DimensionRow: View {
    let component: ResearchComponent
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                isExpanded.toggle()
            } label: {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(component.dimension.displayName)
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Text(component.summary)
                        .font(.system(.subheadline, design: .rounded).weight(.medium))
                        .monospacedDigit()
                        .multilineTextAlignment(.trailing)
                    if component.claim != nil {
                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                            .font(.caption2).foregroundStyle(.tertiary)
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(component.dimension.displayName): \(component.summary). "
                + "\(component.direction.displayName) the current picture.")

            if isExpanded {
                if let claim = component.claim { ClaimRow(claim: claim) }
                Text(component.dimension.basis)
                    .font(.caption2).foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
