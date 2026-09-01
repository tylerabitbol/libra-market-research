import Foundation

/// The eleven dimensions of Section 13, and Section 12's search for evidence
/// that argues the other way.
///
/// **There is no score.** Section 13 asked for "Research Signal: 82/100" with a
/// breakdown behind it. The breakdown is here; the number is not, and its
/// absence is the design rather than an omission. Summing a valuation
/// percentile, an insider count and a volatility rank into one integer produces
/// exactly the authoritative-looking, meaningless figure this codebase refuses
/// in four other places — a P/E ranked against a company's own history means
/// something, and averaged with a momentum rank it means nothing at all. The
/// components are what a reader can act on; the total would only look like it.
///
/// Two rules make the disconfirming half real:
///
/// - **Direction is computed per dimension from the same arithmetic that
///   produces the figure**, not chosen afterwards. A system that classifies
///   evidence only once it knows the conclusion is a bull-case finder wearing
///   a skeptic's label.
/// - **A dimension with no data is `.unavailable`, never neutral.** Treating an
///   absent figure as "nothing to worry about" is the fabrication Section 21
///   forbids, applied to judgement rather than to numbers.
enum ResearchDimension: String, Sendable, CaseIterable, Identifiable {
    case momentum
    case relativeStrength
    case revenueTrend
    case earningsTrend
    case profitability
    case valuation
    case balanceSheet
    case analystPosture
    case insiderActivity
    case sectorStrength
    case volatility

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .momentum: "Momentum"
        case .relativeStrength: "Relative strength"
        case .revenueTrend: "Revenue trend"
        case .earningsTrend: "Earnings trend"
        case .profitability: "Profitability"
        case .valuation: "Valuation"
        case .balanceSheet: "Balance sheet"
        case .analystPosture: "Analyst posture"
        case .insiderActivity: "Insider activity"
        case .sectorStrength: "Sector strength"
        case .volatility: "Volatility"
        }
    }

    /// What this dimension is measured against, shown so the reader knows the
    /// yardstick without leaving the screen.
    var basis: String {
        switch self {
        case .momentum: "Trailing return over the selected range."
        case .relativeStrength: "Return against the S&P 500 over the same window."
        case .revenueTrend: "Year-over-year revenue growth, against its own history."
        case .earningsTrend: "Year-over-year net income growth, against its own history."
        case .profitability: "Operating margin, against this company's own range."
        case .valuation: "Multiples ranked against this company's own past, not against peers."
        case .balanceSheet: "Total debt against cash and equivalents, as filed."
        case .analystPosture: "Published ratings and reported earnings surprises."
        case .insiderActivity: "Discretionary open-market transactions, excluding scheduled plans."
        case .sectorStrength: "The sector's return against the S&P 500."
        case .volatility: "Realised volatility, recent window against the prior one."
        }
    }
}

/// Which way a piece of evidence points.
///
/// Never a recommendation, and never aggregated. "Challenging" means the figure
/// argues against the picture the other figures paint — it does not mean sell,
/// and a company can be worth holding with half its evidence challenging.
enum EvidenceDirection: String, Sendable, Hashable, CaseIterable {
    case supportive
    case challenging
    case neutral
    /// No data. Kept distinct from neutral on purpose: an absent figure is not
    /// reassurance.
    case unavailable

    var displayName: String {
        switch self {
        case .supportive: "Supports"
        case .challenging: "Challenges"
        case .neutral: "Mixed"
        case .unavailable: "Not available"
        }
    }
}

struct ResearchComponent: Sendable, Hashable, Identifiable {
    var id: String { dimension.rawValue }
    let dimension: ResearchDimension
    /// The figure itself, in a few words.
    let summary: String
    let direction: EvidenceDirection
    /// The arithmetic, so the reader can check rather than trust.
    let claim: Claim?

    static func unavailable(_ dimension: ResearchDimension, reason: String) -> ResearchComponent {
        ResearchComponent(dimension: dimension, summary: reason,
                          direction: .unavailable, claim: nil)
    }
}

/// Every dimension the app can currently speak to, with the disconfirming ones
/// separated out because Section 12 asks for them by name.
struct ResearchProfile: Sendable, Hashable {
    let components: [ResearchComponent]

    var supporting: [ResearchComponent] { components.filter { $0.direction == .supportive } }
    var challenging: [ResearchComponent] { components.filter { $0.direction == .challenging } }
    var unavailable: [ResearchComponent] { components.filter { $0.direction == .unavailable } }
    var measured: [ResearchComponent] { components.filter { $0.direction != .unavailable } }

    /// True when the evidence points both ways at once — the case worth saying
    /// out loud, because a reader scanning a list of green rows will not see it.
    var isConflicted: Bool { !supporting.isEmpty && !challenging.isEmpty }

    /// An INTERPRETATION about the shape of the evidence, never about the
    /// security. It counts dimensions; it does not weigh them, because the
    /// weighting is exactly the judgement the app refuses to make for you.
    var shapeClaim: Claim {
        let text: String
        if measured.isEmpty {
            text = "Not enough data to characterise the evidence either way."
        } else if isConflicted {
            text = "The evidence points both ways: \(supporting.count) "
                + "\(supporting.count == 1 ? "dimension supports" : "dimensions support") "
                + "the current picture and \(challenging.count) "
                + "\(challenging.count == 1 ? "challenges" : "challenge") it. "
                + "Which matters more is a judgement about this business, not a count."
        } else if challenging.isEmpty {
            text = "No dimension the app can measure currently argues against the picture. "
                + "That is a statement about what is measured here, not an absence of risk."
        } else {
            text = "No dimension the app can measure currently supports the picture."
        }
        return Claim(kind: .interpretation, text: text)
    }
}
