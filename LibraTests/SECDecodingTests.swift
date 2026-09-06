import Testing
import Foundation
@testable import Libra

/// EDGAR decoding.
///
/// Unlike the Finnhub, Tiingo and FRED fixtures, these two are hand-authored
/// rather than captured, because fetching from EDGAR requires putting a real
/// contact address in the User-Agent. They mirror the documented shape, and
/// the live path is verified separately by the in-app self-test, which
/// resolved Apple's CIK and parsed five real filings.
@Suite("SEC submissions decoding")
struct SECDecodingTests {
    @Test("Parallel arrays are zipped by index into whole filings")
    func parallelArraysZipCorrectly() throws {
        let response = try Fixture.decode(
            SubmissionsResponse.self, from: "sec_submissions_synthetic"
        )
        let filings = response.filings.recent.filings(cik: "0000320193")

        #expect(filings.count == 3)
        // form[0] must describe the same filing as accessionNumber[0]; getting
        // this wrong silently attributes every filing to the wrong document.
        #expect(filings[0].accessionNumber == "0000320193-26-000013")
        #expect(filings[0].formType == "10-Q")
        #expect(filings[2].formType == "8-K")
    }

    @Test("Ragged arrays are bounded by the shortest, never zipped past the end")
    func raggedArraysAreBounded() throws {
        let response = try Fixture.decode(
            SubmissionsResponse.self, from: "sec_submissions_ragged"
        )
        // filingDate has only two entries; a naive zip would crash or misalign.
        let filings = response.filings.recent.filings(cik: "0000320193")
        #expect(filings.count == 2)
        #expect(filings.allSatisfy { !$0.accessionNumber.isEmpty })
    }

    @Test("Filing dates parse and period-of-report is carried when present")
    func datesParse() throws {
        let response = try Fixture.decode(
            SubmissionsResponse.self, from: "sec_submissions_synthetic"
        )
        let filings = response.filings.recent.filings(cik: "0000320193")
        let first = try #require(filings.first)
        #expect(SECProvider.dayFormatter.string(from: first.filedAt) == "2026-07-28")
        let period = try #require(first.periodOfReport)
        #expect(SECProvider.dayFormatter.string(from: period) == "2026-06-27")
    }

    @Test("Every filing keeps a link back to the original document")
    func documentLinksPreserved() throws {
        let response = try Fixture.decode(
            SubmissionsResponse.self, from: "sec_submissions_synthetic"
        )
        let first = try #require(response.filings.recent.filings(cik: "0000320193").first)

        // The spec treats SEC as a primary source: the app summarises filings,
        // it never replaces them, so the original must always be reachable.
        let document = try #require(first.primaryDocumentURL)
        #expect(document.absoluteString.contains("sec.gov/Archives/edgar/data/320193"))
        #expect(document.absoluteString.hasSuffix("aapl-20260627.htm"))
        #expect(first.filingIndexURL != nil)
    }

    @Test("CIK is zero-padded to the ten digits EDGAR paths require")
    func cikPadding() {
        #expect(SECProvider.padCIK(320193) == "0000320193")
        #expect(SECProvider.padCIK(19617) == "0000019617")
        #expect(SECProvider.padCIK(1045810) == "0001045810")
    }

    @Test("Filings can be filtered to the form types a caller asked for")
    func formTypeFiltering() throws {
        let response = try Fixture.decode(
            SubmissionsResponse.self, from: "sec_submissions_synthetic"
        )
        let all = response.filings.recent.filings(cik: "0000320193")
        #expect(all.filter { $0.formType == "10-Q" }.count == 2)
    }

    @Test("Periodic reports and current reports are distinguished")
    func filingClassification() {
        let tenQ = FilingRecord(accessionNumber: "a", formType: "10-Q", filedAt: .now)
        let eightK = FilingRecord(accessionNumber: "b", formType: "8-K", filedAt: .now)
        let formFour = FilingRecord(accessionNumber: "c", formType: "4", filedAt: .now)

        #expect(tenQ.isPeriodicReport)
        #expect(eightK.isCurrentReport)
        #expect(formFour.isInsiderForm)
        #expect(!tenQ.isInsiderForm)
    }
}

@Suite("SEC identification")
struct SECIdentificationTests {
    @Test("No contact email means no request is attempted")
    func missingEmailBlocksRequests() async {
        let provider = SECProvider(client: HTTPClient(session: StubURLProtocol.makeSession()),
                                   secrets: InMemorySecretsStore())
        StubURLProtocol.reset()
        await #expect(throws: APIError.missingCredentials(.sec)) {
            _ = try await provider.resolveCIK(symbol: "AAPL")
        }
        #expect(StubURLProtocol.requests.isEmpty,
                "EDGAR refuses unidentified clients; we must not send one")
    }

    @Test("The organisation name is optional and not treated as a secret")
    func organizationNameIsNotSecret() {
        #expect(!SecretKey.secOrganizationName.isSensitive)
        #expect(!SecretKey.secContactEmail.isSensitive)
        #expect(SecretKey.tiingoAPIKey.isSensitive)
    }
}
