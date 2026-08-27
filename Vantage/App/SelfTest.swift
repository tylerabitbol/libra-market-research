import Foundation
import OSLog

/// A startup self-check, run only when launched with `-VantageSelfTest`.
///
/// The Keychain cannot be exercised by the unit test bundle: that bundle has no
/// host app, so it carries no entitlements of its own — the very condition that
/// caused the -34018 failure this check exists to catch. Running inside the real,
/// signed app is the only place the answer is meaningful.
enum SelfTest {
    static let launchArgument = "-VantageSelfTest"

    static var isRequested: Bool {
        CommandLine.arguments.contains(launchArgument)
    }

    private static let logger = Logger(subsystem: "com.tylerabitbol.vantage", category: "selftest")

    /// Exercises every configured provider with a real request and logs the
    /// outcome.
    ///
    /// Credentials are read from the Keychain and never appear in a command
    /// line, a log line, or this source file — which is what makes it safe to
    /// verify live API access without handling the keys directly.
    static func runConnectionTests(secrets: any SecretsStoring) async {
        let client = HTTPClient()
        let finnhub = FinnhubProvider(client: client, secrets: secrets)
        let tiingo = TiingoProvider(client: client, secrets: secrets)

        let registry = ProviderRegistry(
            marketData: CompositeMarketDataProvider(quotes: finnhub, history: tiingo),
            fundamentals: SECFundamentalsProvider(client: client, secrets: secrets),
            analyst: FinnhubAnalystProvider(provider: finnhub),
            metrics: FinnhubMetricsProvider(provider: finnhub),
            sec: SECProvider(client: client, secrets: secrets),
            macro: FREDProvider(client: client, secrets: secrets),
            news: FinnhubNewsProvider(provider: finnhub),
            isUsingSampleData: false
        )

        for provider in [DataProviderID.finnhub, .tiingo, .fred, .sec] {
            let result = await ConnectionTest.run(for: provider, registry: registry)
            let status = result.isSuccess ? "PASS" : "FAIL"
            logger.notice(
                "SELFTEST \(provider.rawValue, privacy: .public)=\(status, privacy: .public) \(result.message, privacy: .public)"
            )
        }

        await verifyValuationContext(registry: registry)
        await verifyFundamentals(registry: registry)
    }

    /// End-to-end check of the historical-valuation chain: fetch metrics, rank
    /// the current multiple against the company's own history, and confirm the
    /// two claims come back correctly labelled.
    private static func verifyValuationContext(registry: ProviderRegistry) async {
        guard let metricsProvider = registry.metrics else { return }
        do {
            let metrics = try await metricsProvider.metrics(symbol: "AAPL")
            for metric in ValuationMetric.all.prefix(3) {
                let history = metrics.history(metric.key)
                guard let current = metrics.currentValue(metric.key)
                        ?? history.last?.value,
                      let context = ValuationCalculator.historicalContext(
                        current: current, history: history)
                else {
                    logger.notice("SELFTEST valuation \(metric.displayName, privacy: .public)=SKIP insufficient history")
                    continue
                }
                logger.notice(
                    "SELFTEST valuation \(metric.displayName, privacy: .public)=\(Format.ratio(context.current, precision: 1), privacy: .public) percentile=\(context.percentile, privacy: .public) n=\(context.observationCount, privacy: .public) (\(context.descriptor, privacy: .public))"
                )
            }
        } catch {
            logger.error("SELFTEST valuation=FAIL \(String(describing: error), privacy: .public)")
        }
    }

    /// Confirms XBRL extraction produces discrete periods on live data.
    private static func verifyFundamentals(registry: ProviderRegistry) async {
        guard let fundamentals = registry.fundamentals else { return }
        do {
            let facts = try await fundamentals.facts(
                symbol: "AAPL", cik: "0000320193",
                concepts: [.revenue, .netIncome], since: nil
            )
            let quarters = facts.filter { $0.concept == .revenue && $0.periodKind == .quarter }
            let annual = facts.filter { $0.concept == .revenue && $0.periodKind == .annual }
            logger.notice(
                "SELFTEST fundamentals=PASS revenue quarters=\(quarters.count, privacy: .public) annual=\(annual.count, privacy: .public) latest=\(Format.compactCurrency(quarters.last?.value), privacy: .public)"
            )
        } catch {
            logger.error("SELFTEST fundamentals=FAIL \(String(describing: error), privacy: .public)")
        }
    }

    static let captureArgument = "-VantageCaptureFixtures"

    static var isCaptureRequested: Bool {
        #if DEBUG
        CommandLine.arguments.contains(captureArgument)
        #else
        false
        #endif
    }

    /// Saves a raw API response into the app container so it can be lifted out
    /// with `simctl get_app_container` and checked in as a test fixture.
    ///
    /// Exists so real payloads can be captured from EDGAR without the contact
    /// address its User-Agent requires ever appearing in a shell command. Debug
    /// builds only.
    static func captureFixtures(secrets: any SecretsStoring) async {
        #if DEBUG
        guard isCaptureRequested else { return }

        let client = HTTPClient()
        let fundamentals = SECFundamentalsProvider(client: client, secrets: secrets)
        do {
            // Apple: reports every concept we map, and restates, so it exercises
            // the deduplication path too.
            let response = try await fundamentals.companyFacts(cik: "0000320193")
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(TrimmedFacts(from: response))

            let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let url = directory.appending(path: "sec_companyfacts_AAPL.json")
            try data.write(to: url)
            logger.notice("CAPTURE wrote \(data.count) bytes to \(url.lastPathComponent, privacy: .public)")
        } catch {
            logger.error("CAPTURE failed: \(String(describing: error), privacy: .public)")
        }
        #endif
    }

    /// Reports secure-storage health to the unified log and returns whether it passed.
    @discardableResult
    static func run(secrets: any SecretsStoring) -> Bool {
        switch secrets.diagnose() {
        case .available:
            logger.notice("SELFTEST keychain=PASS round-trip succeeded")
            return true
        case .unavailable(let reason):
            logger.error("SELFTEST keychain=FAIL \(reason, privacy: .public)")
            return false
        }
    }
}


#if DEBUG
/// A companyfacts payload reduced to the concepts the app maps, with each
/// series truncated. The full response for a large issuer runs to tens of
/// megabytes, which is not something to check into a repository.
private struct TrimmedFacts: Encodable {
    let cik: Int?
    let entityName: String?
    let facts: [String: [String: Entry]]

    struct Entry: Encodable {
        let label: String?
        let units: [String: [Row]]
    }

    struct Row: Encodable {
        let start: String?
        let end: String
        let val: Double
        let accn: String?
        let fy: Int?
        let fp: String?
        let form: String?
        let filed: String?
    }

    init(from response: CompanyFactsResponse) {
        cik = response.cik
        entityName = response.entityName

        let wanted = Set(FinancialConcept.allCases.flatMap(\.candidateTags))
        var gaap: [String: Entry] = [:]
        for (tag, entry) in response.facts["us-gaap"] ?? [:] where wanted.contains(tag) {
            var units: [String: [Row]] = [:]
            for (unit, rows) in entry.units {
                // Keep the most recent entries; enough to cover several years
                // of annual and quarterly periods plus any restatements.
                units[unit] = rows.suffix(24).map {
                    Row(start: $0.start, end: $0.end, val: $0.val, accn: $0.accn,
                        fy: $0.fy, fp: $0.fp, form: $0.form, filed: $0.filed)
                }
            }
            gaap[tag] = Entry(label: entry.label, units: units)
        }
        facts = ["us-gaap": gaap]
    }
}
#endif
