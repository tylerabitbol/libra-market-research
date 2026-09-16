package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.fixtures.Fixture
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.networking.MockHttp
import com.tylerabitbol.libra.persistence.FilingRecord
import com.tylerabitbol.libra.services.secrets.InMemorySecretsStore
import com.tylerabitbol.libra.services.secrets.SecretKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * EDGAR decoding.
 *
 * Unlike the Finnhub, Tiingo and FRED fixtures, these two are hand-authored
 * rather than captured, because fetching from EDGAR requires putting a real
 * contact address in the User-Agent. They mirror the documented shape, and the
 * live path is verified separately by the in-app self-test, which resolved
 * Apple's CIK and parsed five real filings.
 *
 * Swift: `SEC submissions decoding` in `SECDecodingTests.swift`.
 */
class SECDecodingTest {

    private fun filings(fixture: String = "sec_submissions_synthetic", cik: String = "0000320193") =
        Fixture.decode<SubmissionsResponse>(fixture).filings.recent.filings(cik)

    @Test
    fun parallelArraysAreZippedByIndexIntoWholeFilings() {
        val filings = filings()

        assertEquals(3, filings.size)
        // form[0] must describe the same filing as accessionNumber[0]; getting
        // this wrong silently attributes every filing to the wrong document.
        assertEquals("0000320193-26-000013", filings[0].accessionNumber)
        assertEquals("10-Q", filings[0].formType)
        assertEquals("8-K", filings[2].formType)
    }

    @Test
    fun raggedArraysAreBoundedByTheShortestNeverZippedPastTheEnd() {
        // filingDate has only two entries; a naive zip would crash or misalign.
        val filings = filings("sec_submissions_ragged")
        assertEquals(2, filings.size)
        assertTrue(filings.all { it.accessionNumber.isNotEmpty() })
    }

    @Test
    fun filingDatesParseAndPeriodOfReportIsCarriedWhenPresent() {
        val first = assertNotNull(filings().firstOrNull())
        assertEquals(Instant.parse("2026-07-28T00:00:00Z"), first.filedAt)
        assertEquals(Instant.parse("2026-06-27T00:00:00Z"), assertNotNull(first.periodOfReport))
    }

    @Test
    fun everyFilingKeepsALinkBackToTheOriginalDocument() {
        val first = assertNotNull(filings().firstOrNull())

        // The spec treats SEC as a primary source: the app summarises filings,
        // it never replaces them, so the original must always be reachable.
        val document = assertNotNull(first.primaryDocumentURL)
        assertTrue(document.contains("sec.gov/Archives/edgar/data/320193"))
        assertTrue(document.endsWith("aapl-20260627.htm"))
        assertNotNull(first.filingIndexURL)
    }

    @Test
    fun cikIsZeroPaddedToTheTenDigitsEdgarPathsRequire() {
        assertEquals("0000320193", SECProvider.padCIK(320193))
        assertEquals("0000019617", SECProvider.padCIK(19617))
        assertEquals("0001045810", SECProvider.padCIK(1045810))
    }

    @Test
    fun filingsCanBeFilteredToTheFormTypesACallerAskedFor() {
        assertEquals(2, filings().count { it.formType == "10-Q" })
    }

    @Test
    fun periodicReportsAndCurrentReportsAreDistinguished() {
        val now = Instant.fromEpochSeconds(1_756_000_000)
        fun record(form: String) =
            FilingRecord(accessionNumber = form, symbol = "AAPL", formType = form, filedAt = now)

        assertTrue(record("10-Q").isPeriodicReport)
        assertTrue(record("8-K").isCurrentReport)
        assertTrue(record("4").isInsiderForm)
        assertFalse(record("10-Q").isInsiderForm)
    }
}

/** Swift: `SEC identification` in `SECDecodingTests.swift`. */
class SECIdentificationTest {

    @Test
    fun noContactEmailMeansNoRequestIsAttempted() = runTest {
        val router = MockHttp.router()
        val provider = SECProvider(testClient(router), InMemorySecretsStore())

        val error = assertFailsWith<APIError.MissingCredentials> { provider.resolveCIK("AAPL") }
        assertEquals(DataProviderID.SEC, error.providerID)
        assertTrue(
            router.requests.isEmpty(),
            "EDGAR refuses unidentified clients; we must not send one",
        )
    }

    @Test
    fun theOrganisationNameIsOptionalAndNotTreatedAsASecret() {
        assertFalse(SecretKey.SecOrganizationName.isSensitive)
        assertFalse(SecretKey.SecContactEmail.isSensitive)
        assertTrue(SecretKey.TiingoAPIKey.isSensitive)
    }

    @Test
    fun theUserAgentIdentifiesTheClientAndFallsBackToTheAppName() = runTest {
        val router = MockHttp.router().stub(
            "/submissions/CIK0000320193.json",
            Fixture.text("sec_submissions_synthetic"),
        )
        val secrets = InMemorySecretsStore(
            mapOf(SecretKey.SecContactEmail to "tests@example.com"),
        )
        SECProvider(testClient(router), secrets).filings("320193", emptyList(), 10)

        val request = assertNotNull(router.requests.firstOrNull())
        // EDGAR's documented format is "Company Name contact@domain.com", and
        // the app name satisfies the policy on its own.
        assertEquals("Libra tests@example.com", request.headers["User-Agent"])

        secrets.set("Acme Research", SecretKey.SecOrganizationName)
        SECProvider(testClient(router), secrets).filings("320193", emptyList(), 10)
        assertEquals("Acme Research tests@example.com", router.requests.last().headers["User-Agent"])
    }
}

/** Swift: `SEC identifier handling` in `CorrectnessRegressionTests.swift`. */
class CIKTest {

    @Test
    fun aValidCIKIsZeroPaddedToTenDigits() {
        assertEquals("0000320193", SECProvider.normalizedCIK("320193"))
        assertEquals("0000320193", SECProvider.normalizedCIK("0000320193"))
    }

    @Test
    fun anUnparseableCIKThrowsInsteadOfSilentlyBecomingZero() {
        // A `?: 0` fallback builds a well-formed request for CIK 0000000000 —
        // a silent wrong question rather than a visible failure.
        assertFailsWith<APIError.NotFound> { SECProvider.normalizedCIK("not-a-cik") }
        assertFailsWith<APIError.NotFound> { SECProvider.normalizedCIK("") }
        assertFailsWith<APIError.NotFound> { SECProvider.normalizedCIK("0") }
    }

    @Test
    fun aCIKThatCannotBuildAnArchiveURLYieldsNoFilingsRatherThanWrongLinks() {
        val response: SubmissionsResponse = Fixture.decode("sec_submissions_synthetic")
        assertTrue(
            response.filings.recent.filings("not-a-cik").isEmpty(),
            "A link to the wrong filer is worse than no link",
        )
    }
}
