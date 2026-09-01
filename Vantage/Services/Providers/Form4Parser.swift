import Foundation

/// Parses an SEC ownership document — Form 3, 4 or 5 — into transactions.
///
/// Section 10 asks for insider activity, and the provider previously refused
/// rather than returning an empty array, which was the right failure: an empty
/// list reads as "no insider trades" when it means "not implemented".
///
/// Two things about this document deserve stating.
///
/// **The 10b5-1 flag is looked for in two places.** A sale made under a
/// pre-arranged plan carries no opinion about the company — it was scheduled
/// months earlier — and mixing it in with discretionary selling is the single
/// most misleading thing this feature could do. The SEC added an explicit
/// checkbox for it in 2023, but filings predating that indicate it only in a
/// footnote, and filing agents differ in how they tag it. So both are read: any
/// element whose name mentions 10b5, and any footnote text that does.
///
/// **Values are wrapped.** Most fields appear as
/// `<transactionShares><value>100</value></transactionShares>`, but some agents
/// emit the text directly on the outer element. Both are accepted.
enum Form4Parser {

    static func parse(
        _ data: Data,
        accessionNumber: String,
        filedAt: Date
    ) throws -> [InsiderTransactionDTO] {
        guard let root = XMLTree.parse(data) else {
            throw APIError.decoding(.sec, endpoint: "Form 4", underlying: "Malformed ownership XML")
        }

        let owner = root.first("reportingOwner")
        let name = owner?.first("reportingOwnerId")?.value("rptOwnerName") ?? "Unknown"
        let relationship = owner?.first("reportingOwnerRelationship")
        let isDirector = flag(relationship?.value("isDirector"))
        let isOfficer = flag(relationship?.value("isOfficer"))
        let isTenPercent = flag(relationship?.value("isTenPercentOwner"))
        let title = relationship?.value("officerTitle")

        // Footnote text lives at the end of the document and transactions
        // point at it by id. Applying a plan footnote to every line — rather
        // than to the lines that reference it — marks discretionary purchases
        // as scheduled and hides exactly the insider buying worth seeing.
        var planFootnoteIDs: Set<String> = []
        for footnote in root.descendants(named: "footnote")
        where footnote.text.localizedCaseInsensitiveContains("10b5-1") {
            if let id = footnote.attributes["id"] { planFootnoteIDs.insert(id) }
        }

        let transactions = root.descendants(named: "nonDerivativeTransaction")
            + root.descendants(named: "derivativeTransaction")

        return transactions.compactMap { node in
            let coding = node.first("transactionCoding")
            guard let code = coding?.value("transactionCode"), !code.isEmpty else { return nil }
            guard let date = node.first("transactionDate")?.valueDate() else { return nil }

            let amounts = node.first("transactionAmounts")
            let shares = amounts?.first("transactionShares")?.valueDouble()
            let price = amounts?.first("transactionPricePerShare")?.valueDouble()
            let owned = node.first("postTransactionAmounts")?
                .first("sharesOwnedFollowingTransaction")?.valueDouble()

            // Either the modern checkbox on this line, or a footnote this line
            // actually references.
            let hasPlanElement = node
                .descendants(where: { $0.name.localizedCaseInsensitiveContains("10b5") })
                .contains { flag($0.valueOrText()) }
            let referencesPlanFootnote = node.descendants(named: "footnoteId")
                .contains { reference in
                    reference.attributes["id"].map(planFootnoteIDs.contains) ?? false
                }

            return InsiderTransactionDTO(
                accessionNumber: accessionNumber,
                insiderName: name,
                insiderTitle: title,
                isDirector: isDirector,
                isOfficer: isOfficer,
                isTenPercentOwner: isTenPercent,
                transactionDate: date,
                filedAt: filedAt,
                transactionCode: code.trimmingCharacters(in: .whitespacesAndNewlines),
                isUnderTradingPlan: hasPlanElement || referencesPlanFootnote,
                shares: shares,
                pricePerShare: price,
                sharesOwnedAfter: owned)
        }
    }

    /// SEC booleans arrive as 0/1, and occasionally as true/false.
    private static func flag(_ raw: String?) -> Bool {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        else { return false }
        return raw == "1" || raw == "true"
    }
}

/// A minimal XML tree.
///
/// `XMLParser`'s delegate is a stateful callback API; building a tree once and
/// walking it keeps the extraction above readable and testable, which matters
/// more here than the allocation it costs on a document of a few kilobytes.
final class XMLTree {
    let name: String
    /// Kept because Form 4 links a transaction to its footnote by `id`, and
    /// dropping attributes loses that link entirely.
    let attributes: [String: String]
    private(set) var text: String = ""
    private(set) var children: [XMLTree] = []

    init(name: String, attributes: [String: String] = [:]) {
        self.name = name
        self.attributes = attributes
    }

    static func parse(_ data: Data) -> XMLTree? {
        let builder = Builder()
        let parser = XMLParser(data: data)
        parser.delegate = builder
        guard parser.parse() else { return nil }
        return builder.root
    }

    /// The first direct child with this name.
    func first(_ name: String) -> XMLTree? {
        children.first { $0.name == name }
    }

    /// The text of a named child, unwrapping the `<value>` element the SEC
    /// wraps most fields in.
    func value(_ name: String) -> String? {
        first(name)?.valueOrText()
    }

    /// This element's own text, or its `<value>` child's.
    func valueOrText() -> String? {
        if let wrapped = first("value") {
            let inner = wrapped.text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !inner.isEmpty { return inner }
        }
        let own = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return own.isEmpty ? nil : own
    }

    func valueDouble() -> Double? { valueOrText().flatMap(Double.init) }

    func valueDate() -> Date? {
        valueOrText().flatMap { SECProvider.dayFormatter.date(from: $0) }
    }

    func descendants(named name: String) -> [XMLTree] {
        descendants { $0.name == name }
    }

    func descendants(where matches: (XMLTree) -> Bool) -> [XMLTree] {
        var found: [XMLTree] = []
        if matches(self) { found.append(self) }
        for child in children { found += child.descendants(where: matches) }
        return found
    }

    private final class Builder: NSObject, XMLParserDelegate {
        var root: XMLTree?
        private var stack: [XMLTree] = []

        func parser(_ parser: XMLParser, didStartElement elementName: String,
                    namespaceURI: String?, qualifiedName: String?,
                    attributes: [String: String]) {
            let node = XMLTree(name: elementName, attributes: attributes)
            stack.last?.children.append(node)
            if root == nil { root = node }
            stack.append(node)
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            stack.last?.text += string
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String,
                    namespaceURI: String?, qualifiedName: String?) {
            stack.removeLast()
        }
    }
}
