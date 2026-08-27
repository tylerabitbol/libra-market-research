import Foundation

/// Reported financials from SEC EDGAR's XBRL `companyfacts` API.
///
/// This is the primary source: figures as the issuer filed them, free, with a
/// decade or more of history. It replaces the vendor fundamentals that
/// Finnhub's free tier withholds, and it is authoritative where the two differ.
///
/// Three properties of XBRL shape everything below:
///
/// 1. **Companies use different tags for the same idea.** "Revenue" may be
///    `Revenues`, `RevenueFromContractWithCustomerExcludingAssessedTax`, or
///    `SalesRevenueNet`. Each concept therefore has an ordered candidate list.
/// 2. **Issuers restate.** The same period appears more than once with
///    different `accn` and `filed` values. Both are kept; the most recently
///    filed wins for display, and the originals remain for "what did this look
///    like at the time".
/// 3. **A missing concept is genuinely missing.** No substitution, no zero.
struct SECFundamentalsProvider: FundamentalsProvider {
    let id: DataProviderID = .sec
    private let client: HTTPClient
    private let secrets: any SecretsStoring
    private let sec: SECProvider

    private static let dataHost = URL(string: "https://data.sec.gov")!

    init(client: HTTPClient, secrets: any SecretsStoring) {
        self.client = client
        self.secrets = secrets
        self.sec = SECProvider(client: client, secrets: secrets)
    }

    func isConfigured() async -> Bool { secrets.hasValue(for: .secContactEmail) }

    func facts(
        symbol: String,
        cik: String?,
        concepts: [FinancialConcept],
        since: Date?
    ) async throws -> [FinancialFactDTO] {
        // `??` takes an autoclosure on the right, which cannot contain an await.
        let resolved: String
        if let cik { resolved = cik } else { resolved = try await sec.resolveCIK(symbol: symbol) }
        let response = try await companyFacts(cik: resolved)
        let wanted = concepts.isEmpty ? FinancialConcept.allCases : concepts

        return wanted.flatMap { concept in
            Self.extract(concept: concept, from: response, since: since)
        }
    }

    /// The raw payload, exposed so a debug build can capture it as a fixture.
    func companyFacts(cik: String) async throws -> CompanyFactsResponse {
        let padded = try SECProvider.normalizedCIK(cik)
        let email = try secrets.require(.secContactEmail, for: .sec)
        let organization = secrets.value(for: .secOrganizationName)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let name = (organization?.isEmpty == false) ? organization! : "Vantage"

        let endpoint = Endpoint(
            provider: .sec,
            baseURL: Self.dataHost,
            path: "/api/xbrl/companyfacts/CIK\(padded).json",
            headers: ["User-Agent": "\(name) \(email)",
                      "Accept-Encoding": "gzip, deflate",
                      "Host": "data.sec.gov"],
            label: "companyfacts"
        )
        return try await client.get(endpoint, as: CompanyFactsResponse.self)
    }

    /// Picks the first candidate tag the issuer actually reports, then maps its
    /// entries. Trying every candidate and merging would double-count a company
    /// that reports two of them.
    static func extract(
        concept: FinancialConcept,
        from response: CompanyFactsResponse,
        since: Date?
    ) -> [FinancialFactDTO] {
        guard let gaap = response.facts["us-gaap"] else { return [] }

        for tag in concept.candidateTags {
            guard let entry = gaap[tag] else { continue }
            guard let unit = concept.preferredUnit(from: Array(entry.units.keys)),
                  let rows = entry.units[unit]
            else { continue }

            let facts = rows.compactMap { row -> FinancialFactDTO? in
                row.asDTO(concept: concept, tag: tag, unit: unit, since: since)
            }
            // Cumulative half-year and nine-month rows share a tag with the
            // discrete quarters; keeping both would double-count.
            .filter(\.periodKind.isDiscrete)
            if !facts.isEmpty { return Self.deduplicated(facts) }
        }
        return []
    }

    /// Collapses restatements to the most recently filed figure per period,
    /// while keeping periods distinct. An issuer filing a correction should not
    /// produce two conflicting rows on one chart.
    /// Year and month of a period end, tolerant of fiscal calendars that shift
    /// the closing date by a few days between years.
    static func periodKey(_ date: Date) -> String {
        let components = Calendar(identifier: .iso8601).dateComponents([.year, .month], from: date)
        return "\(components.year ?? 0)-\(components.month ?? 0)"
    }

    static func deduplicated(_ facts: [FinancialFactDTO]) -> [FinancialFactDTO] {
        var latest: [String: FinancialFactDTO] = [:]
        for fact in facts {
            // Keyed on the period the figure actually describes. XBRL's `fy`
            // is the filing's fiscal context, not the fact's, so two different
            // periods can share it — keying on `fy` silently drops one.
            let key = "\(Self.periodKey(fact.periodEnd))-\(fact.periodKind.rawValue)"
            if let existing = latest[key] {
                let existingFiled = existing.filedAt ?? .distantPast
                let candidateFiled = fact.filedAt ?? .distantPast
                if candidateFiled > existingFiled { latest[key] = fact }
            } else {
                latest[key] = fact
            }
        }
        return latest.values.sorted { $0.periodEnd < $1.periodEnd }
    }
}

// MARK: - Concept mapping

extension FinancialConcept {
    /// Ordered us-gaap tags to try. Order matters: the most specific and most
    /// commonly used modern tag comes first, older or broader ones after.
    var candidateTags: [String] {
        switch self {
        case .revenue:
            ["RevenueFromContractWithCustomerExcludingAssessedTax",
             "Revenues",
             "SalesRevenueNet",
             "RevenueFromContractWithCustomerIncludingAssessedTax"]
        case .costOfRevenue:
            ["CostOfGoodsAndServicesSold", "CostOfRevenue", "CostOfGoodsSold"]
        case .grossProfit:
            ["GrossProfit"]
        case .operatingIncome:
            ["OperatingIncomeLoss"]
        case .netIncome:
            ["NetIncomeLoss", "ProfitLoss"]
        case .earningsPerShareDiluted:
            ["EarningsPerShareDiluted", "IncomeLossFromContinuingOperationsPerDilutedShare"]
        case .sharesOutstandingDiluted:
            ["WeightedAverageNumberOfDilutedSharesOutstanding"]
        case .operatingCashFlow:
            ["NetCashProvidedByUsedInOperatingActivities",
             "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations"]
        case .capitalExpenditures:
            ["PaymentsToAcquirePropertyPlantAndEquipment",
             "PaymentsToAcquireProductiveAssets"]
        case .cashAndEquivalents:
            ["CashAndCashEquivalentsAtCarryingValue", "CashCashEquivalentsAndShortTermInvestments"]
        case .shortTermInvestments:
            ["ShortTermInvestments", "MarketableSecuritiesCurrent"]
        case .totalDebt:
            ["LongTermDebtNoncurrent", "LongTermDebt", "DebtLongtermAndShorttermCombinedAmount"]
        case .totalAssets:
            ["Assets"]
        case .totalLiabilities:
            ["Liabilities"]
        case .stockholdersEquity:
            ["StockholdersEquity",
             "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"]
        }
    }

    /// XBRL reports each concept under one or more unit keys. Picking the wrong
    /// one silently changes the meaning — EPS lives under "USD/shares", share
    /// counts under "shares", and everything monetary under "USD".
    func preferredUnit(from available: [String]) -> String? {
        let preference: [String]
        switch self {
        case .earningsPerShareDiluted: preference = ["USD/shares"]
        case .sharesOutstandingDiluted: preference = ["shares"]
        default: preference = ["USD"]
        }
        return preference.first(where: available.contains) ?? available.first
    }
}

// MARK: - Wire format

struct CompanyFactsResponse: Decodable, Sendable {
    let cik: Int?
    let entityName: String?
    /// Taxonomy ("us-gaap", "dei") → tag → entry.
    let facts: [String: [String: FactEntry]]

    struct FactEntry: Decodable, Sendable {
        let label: String?
        /// Unit key ("USD", "USD/shares", "shares") → observations.
        let units: [String: [FactRow]]
    }

    struct FactRow: Decodable, Sendable {
        /// Absent for instantaneous facts such as balance-sheet items.
        let start: String?
        let end: String
        let val: Double
        let accn: String?
        let fy: Int?
        /// "FY", "Q1"…"Q4".
        let fp: String?
        let form: String?
        let filed: String?
        let frame: String?

        func asDTO(
            concept: FinancialConcept,
            tag: String,
            unit: String,
            since: Date?
        ) -> FinancialFactDTO? {
            guard let periodEnd = SECProvider.dayFormatter.date(from: end) else { return nil }
            if let since, periodEnd < since { return nil }

            let periodStart = start.flatMap { SECProvider.dayFormatter.date(from: $0) }
            // Duration is the reliable signal; `fp` alone mislabels both
            // full-year rows inside a 10-K and cumulative year-to-date rows.
            let days = periodStart.map { periodEnd.timeIntervalSince($0) / 86_400 }
            let periodKind = FiscalPeriodKind.classify(days: days)
            let isAnnual = periodKind == .annual

            let quarter: Int? = switch fp {
            case "Q1": 1
            case "Q2": 2
            case "Q3": 3
            case "Q4": 4
            default: nil
            }

            return FinancialFactDTO(
                concept: concept,
                rawTag: "us-gaap:\(tag)",
                periodStart: periodStart,
                periodEnd: periodEnd,
                fiscalYear: fy ?? Calendar(identifier: .iso8601)
                    .component(.year, from: periodEnd),
                fiscalQuarter: quarter,
                isAnnual: isAnnual,
                periodKind: periodKind,
                value: val,
                unit: unit,
                filedAt: filed.flatMap { SECProvider.dayFormatter.date(from: $0) },
                accessionNumber: accn
            )
        }
    }
}
