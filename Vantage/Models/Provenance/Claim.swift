import Foundation

/// The epistemic status of a statement the app puts on screen.
///
/// Section 24 of the product spec is a hard requirement: the app must never
/// present a hypothesis as a fact. Encoding that as a type rather than a UI
/// convention means an unlabelled statement cannot reach the user — there is
/// no way to construct a `Claim` without declaring what kind of claim it is.
enum ClaimKind: String, Codable, Sendable, CaseIterable, Comparable {
    /// Reported directly by a primary source. "Revenue was $30.0B in Q2 FY2026."
    case fact
    /// Derived deterministically from facts. "Revenue grew 14.2% year-over-year."
    case calculation
    /// A judgement about what the calculations show. "Revenue growth has accelerated."
    case interpretation
    /// A possible explanation, explicitly unproven. "This may indicate improving demand."
    case hypothesis

    /// Ordered from most to least certain, so UI can sort or filter by confidence.
    private var rank: Int {
        switch self {
        case .fact: 0
        case .calculation: 1
        case .interpretation: 2
        case .hypothesis: 3
        }
    }

    static func < (lhs: ClaimKind, rhs: ClaimKind) -> Bool { lhs.rank < rhs.rank }

    var label: String {
        switch self {
        case .fact: "FACT"
        case .calculation: "CALCULATION"
        case .interpretation: "INTERPRETATION"
        case .hypothesis: "HYPOTHESIS"
        }
    }

    /// Shown in the UI when the user asks what a label means.
    var definition: String {
        switch self {
        case .fact: "Reported directly by a primary source."
        case .calculation: "Computed from reported figures using a formula you can inspect."
        case .interpretation: "A judgement about what the figures show. Reasonable people may disagree."
        case .hypothesis: "A possible explanation. Not established, and not a prediction."
        }
    }
}

/// A pointer back to where a piece of information came from.
///
/// Every `Claim` must carry at least one of these for facts, so the user can
/// always get from a sentence on screen to the underlying record.
struct SourceReference: Codable, Sendable, Hashable, Identifiable {
    var id: String { "\(provider.rawValue):\(detail):\(retrievedAt.timeIntervalSince1970)" }

    let provider: DataProviderID
    /// Human-readable description of the specific record, e.g. "10-Q filed 2026-07-28".
    let detail: String
    /// Link to the original document where one exists. SEC filings always have one.
    let url: URL?
    let retrievedAt: Date

    init(provider: DataProviderID, detail: String, url: URL? = nil, retrievedAt: Date) {
        self.provider = provider
        self.detail = detail
        self.url = url
        self.retrievedAt = retrievedAt
    }
}

/// Identifies which upstream service a piece of data came from.
enum DataProviderID: String, Codable, Sendable, CaseIterable {
    case sec
    case finnhub
    case fred
    case computed
    case userJournal

    var displayName: String {
        switch self {
        case .sec: "SEC EDGAR"
        case .finnhub: "Finnhub"
        case .fred: "FRED"
        case .computed: "Calculated locally"
        case .userJournal: "Your notes"
        }
    }

    /// Primary sources are filings from the issuer itself. The spec treats SEC
    /// as authoritative where it and a vendor disagree.
    var isPrimarySource: Bool { self == .sec }
}

/// A single statement the app is prepared to show the user, carrying its own
/// epistemic status and its trail back to the source data.
struct Claim: Sendable, Hashable, Identifiable {
    let id: UUID
    let kind: ClaimKind
    let text: String
    let sources: [SourceReference]
    /// For calculations: the formula and inputs, so the user can verify the
    /// number rather than trust it. Section 13 forbids black-box values.
    let derivation: Derivation?

    init(
        id: UUID = UUID(),
        kind: ClaimKind,
        text: String,
        sources: [SourceReference] = [],
        derivation: Derivation? = nil
    ) {
        self.id = id
        self.kind = kind
        self.text = text
        self.sources = sources
        self.derivation = derivation
    }

    /// True when the claim can be traced to something the user can open and read.
    /// Interpretations and hypotheses inherit traceability from the claims they rest on.
    var isTraceable: Bool { !sources.isEmpty || derivation != nil }
}

/// The arithmetic behind a `.calculation` claim, in a form the UI can display.
struct Derivation: Sendable, Hashable {
    /// e.g. "(revenue - revenuePriorYear) / revenuePriorYear"
    let formula: String
    /// Named inputs with their values, e.g. ("revenue", "30,040,000,000").
    let inputs: [Input]
    let result: String

    struct Input: Sendable, Hashable, Identifiable {
        var id: String { name }
        let name: String
        let value: String
        let source: SourceReference?
    }
}
