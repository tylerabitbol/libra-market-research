import Foundation

/// Verifies a stored credential by making the cheapest real request each
/// provider offers.
///
/// A key can only be confirmed by using it. Storing one and discovering hours
/// later that a chart is empty — because a provider answered 403 for a mistyped
/// token — is exactly the failure this removes.
enum ConnectionTest {
    enum Result: Sendable, Equatable {
        case success(detail: String)
        case failure(APIError)

        var isSuccess: Bool { if case .success = self { return true }; return false }

        var message: String {
            switch self {
            case .success(let detail): detail
            case .failure(let error):
                error.recoverySuggestion.map { "\(error.shortDescription) — \($0)" }
                    ?? error.shortDescription
            }
        }
    }

    static func run(
        for provider: DataProviderID,
        registry: ProviderRegistry
    ) async -> Result {
        do {
            switch provider {
            case .finnhub:
                let quote = try await registry.marketData.quote(symbol: "AAPL")
                return .success(detail: "Live quote received (AAPL \(Format.currency(quote.last))).")

            case .tiingo:
                let bars = try await registry.marketData.bars(
                    symbol: "AAPL", resolution: .daily,
                    from: Date.now.addingTimeInterval(-10 * 86_400), to: .now
                )
                guard !bars.isEmpty else {
                    return .failure(.noData(.tiingo, endpoint: "tiingo prices"))
                }
                return .success(detail: "\(bars.count) daily bars received.")

            case .alpaca:
                // Asked for over the last four days so the window spans a
                // weekend without the test reading as a failure on a Monday.
                let intraday = try await registry.marketData.bars(
                    symbol: "AAPL", resolution: .fifteenMinute,
                    from: Date.now.addingTimeInterval(-4 * 86_400), to: .now
                )
                guard !intraday.isEmpty else {
                    return .failure(.noData(.alpaca, endpoint: "alpaca bars"))
                }
                return .success(detail: "\(intraday.count) 15-minute IEX bars received.")

            case .fred:
                guard let macro = registry.macro else {
                    return .failure(.missingCredentials(.fred))
                }
                let observations = try await macro.observations(
                    seriesID: "SP500",
                    from: Date.now.addingTimeInterval(-10 * 86_400), to: .now
                )
                guard let latest = observations.last else {
                    return .failure(.noData(.fred, endpoint: "SP500"))
                }
                return .success(detail: "S&P 500 at \(Format.ratio(latest.value, precision: 2)).")

            case .sec:
                guard let sec = registry.sec else {
                    return .failure(.missingCredentials(.sec))
                }
                // Resolving a well-known ticker exercises the whole path: the
                // User-Agent EDGAR demands, the ticker map, and decoding.
                let cik = try await sec.resolveCIK(symbol: "AAPL")
                let filings = try await sec.filings(cik: cik, formTypes: [], limit: 5)
                return .success(
                    detail: "CIK \(cik) resolved, \(filings.count) recent filings."
                )

            case .computed, .sample:
                // Neither is a connection. `.sample` reaches nothing, and a
                // test that "succeeded" against invented data would report the
                // opposite of what it measured.
                return .failure(.noData(provider, endpoint: "connection test"))
            }
        } catch let error as APIError {
            return .failure(error)
        } catch {
            return .failure(.transport(provider, underlying: error.localizedDescription))
        }
    }
}
