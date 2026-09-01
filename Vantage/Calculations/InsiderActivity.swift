import Foundation

/// Section 10's summaries, rather than a raw list of Form 4 lines.
///
/// The spec is explicit: "Do not simply display a raw list." It is also
/// explicit that the app must not imply insider activity predicts returns, and
/// the claim below says so in as many words.
///
/// Everything here turns on one distinction. A sale under a 10b5-1 plan was
/// scheduled months before it executed and carries no opinion about the
/// company; a grant is compensation; shares withheld for tax are an
/// administrative consequence of vesting. Counting those alongside a decision
/// to buy or sell on the open market produces a number that looks like insider
/// sentiment and is mostly payroll.
enum InsiderActivity {

    struct Summary: Sendable, Hashable {
        let purchases: [InsiderTransactionDTO]
        let sales: [InsiderTransactionDTO]
        /// Filed but excluded from the counts, and reported separately so the
        /// exclusion is visible rather than silent.
        let scheduledCount: Int
        let routineCount: Int
        let windowStart: Date
        let windowEnd: Date

        var purchaseCount: Int { purchases.count }
        var saleCount: Int { sales.count }
        var purchaseValue: Double? { total(purchases) }
        var saleValue: Double? { total(sales) }
        var hasDiscretionaryActivity: Bool { purchaseCount + saleCount > 0 }

        private func total(_ transactions: [InsiderTransactionDTO]) -> Double? {
            let values = transactions.compactMap(\.approximateValue)
            return values.isEmpty ? nil : values.reduce(0, +)
        }

        /// Approximate because Form 4 reports a price per transaction line, and
        /// a single decision is often filed as several lines at several prices.
        var claim: Claim {
            Claim(
                kind: .calculation,
                text: "\(purchaseCount) discretionary open-market purchase"
                    + "\(purchaseCount == 1 ? "" : "s") and \(saleCount) sale"
                    + "\(saleCount == 1 ? "" : "s") between "
                    + "\(Format.shortDate(windowStart)) and \(Format.shortDate(windowEnd)). "
                    + "\(scheduledCount) scheduled-plan and \(routineCount) routine "
                    + "transactions are excluded because they carry no decision. "
                    + "Insider activity does not predict returns.",
                sources: [SourceReference(provider: .sec, detail: "Form 4 filings",
                                          retrievedAt: windowEnd)],
                derivation: Derivation(
                    formula: "count of transactions where nature is discretionary",
                    inputs: [
                        .init(name: "purchases", value: "\(purchaseCount)", source: nil),
                        .init(name: "sales", value: "\(saleCount)", source: nil),
                        .init(name: "approximate purchase value",
                              value: Format.compactCurrency(purchaseValue), source: nil),
                        .init(name: "approximate sale value",
                              value: Format.compactCurrency(saleValue), source: nil),
                        .init(name: "excluded as scheduled", value: "\(scheduledCount)", source: nil),
                        .init(name: "excluded as routine", value: "\(routineCount)", source: nil)
                    ],
                    result: "\(purchaseCount) bought, \(saleCount) sold"))
        }
    }

    static func summarize(_ transactions: [InsiderTransactionDTO]) -> Summary? {
        guard let earliest = transactions.map(\.transactionDate).min(),
              let latest = transactions.map(\.transactionDate).max()
        else { return nil }

        var purchases: [InsiderTransactionDTO] = []
        var sales: [InsiderTransactionDTO] = []
        var scheduled = 0
        var routine = 0

        for transaction in transactions {
            switch transaction.nature {
            case .openMarketPurchase: purchases.append(transaction)
            case .openMarketSale: sales.append(transaction)
            case .scheduledPlan: scheduled += 1
            default: routine += 1
            }
        }

        return Summary(purchases: purchases, sales: sales,
                       scheduledCount: scheduled, routineCount: routine,
                       windowStart: earliest, windowEnd: latest)
    }

    /// Events for transactions that reflect a decision, filed since the user
    /// last looked.
    ///
    /// Scheduled sales are deliberately not events. A plan executing on
    /// schedule is not news, and a feed that reported every one of them would
    /// bury the purchases that are.
    static func events(
        _ transactions: [InsiderTransactionDTO],
        since: Date?,
        limit: Int = 5
    ) -> [DetectedEventDTO] {
        guard let since else { return [] }
        return transactions
            .filter { $0.nature.isDiscretionary && $0.filedAt > since }
            .sorted { $0.transactionDate > $1.transactionDate }
            .prefix(limit)
            .map { transaction in
                let verb = transaction.nature == .openMarketPurchase ? "bought" : "sold"
                var lines = ["\(transaction.nature.displayName)"]
                if let shares = transaction.shares {
                    lines.append("Shares: \(Format.compact(shares))")
                }
                if let value = transaction.approximateValue {
                    lines.append("Approximate value: \(Format.compactCurrency(value))")
                }
                if let owned = transaction.sharesOwnedAfter {
                    lines.append("Held afterwards: \(Format.compact(owned))")
                }
                return DetectedEventDTO(
                    kind: .insiderTransaction,
                    occurredAt: transaction.filedAt,
                    headline: "\(transaction.insiderName) \(verb) on "
                        + Format.shortDate(transaction.transactionDate),
                    detailLines: lines,
                    context: "An officer or director dealing in their own company's shares "
                        + "outside a pre-arranged plan. This is a fact about one person's "
                        + "decision, not a signal about the company — insider transactions "
                        + "do not predict returns, and a single sale has many innocent "
                        + "explanations.",
                    // A transaction either happened or did not; there is no
                    // sample to rank it against.
                    unusualness: 0,
                    sourceDetails: ["Form 4 — \(transaction.accessionNumber)"])
            }
    }
}
