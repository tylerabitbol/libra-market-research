package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.fixtures.Fixture
import com.tylerabitbol.libra.models.core.InsiderTransactionNature
import com.tylerabitbol.libra.networking.APIError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Form 4 parsing, and the distinction the whole feature turns on.
 *
 * A sale made under a pre-arranged plan carries no opinion about the company —
 * it was scheduled months earlier — and mixing it in with discretionary
 * selling is the single most misleading thing this feature could do. Half
 * these tests are about that one classification.
 *
 * Swift: `Form 4 parsing` in `Form4ParsingTests.swift`.
 */
class Form4ParsingTest {

    private val filedAt = Instant.fromEpochSeconds(1_756_000_000)

    private fun parsed(): List<InsiderTransactionDTO> = Form4Parser.parse(
        Fixture.text("sec_form4_synthetic", extension = "xml"),
        accessionNumber = "0001214156-26-000042",
        filedAt = filedAt,
    )

    private fun code(code: String) = assertNotNull(
        parsed().firstOrNull { it.transactionCode == code },
        "No $code line in the fixture",
    )

    @Test
    fun everyTransactionLineInTheDocumentIsRead() {
        val transactions = parsed()
        assertEquals(5, transactions.size, "Four non-derivative lines and one derivative")
        assertTrue(transactions.all { it.accessionNumber == "0001214156-26-000042" })
        assertTrue(transactions.all { it.insiderName == "DOE JANE" })
    }

    @Test
    fun theReportingPersonsRelationshipIsCarriedThrough() {
        val transaction = assertNotNull(parsed().firstOrNull())
        assertTrue(transaction.isOfficer)
        assertFalse(transaction.isDirector)
        assertFalse(transaction.isTenPercentOwner)
        assertEquals("Chief Financial Officer", transaction.insiderTitle)
    }

    @Test
    fun aSaleFlaggedOnlyByFootnoteIsRecognisedAsScheduled() {
        val sale = code("S")
        // Filings predating the 2023 checkbox indicate the plan in a footnote
        // and nowhere else. Missing it would present a scheduled sale as a
        // discretionary one, which is the reading this feature must not produce.
        assertTrue(sale.isUnderTradingPlan)
        assertEquals(InsiderTransactionNature.ScheduledPlan, sale.nature)
        assertFalse(sale.nature.isDiscretionary)
    }

    @Test
    fun aSaleFlaggedByTheModernCheckboxIsRecognisedToo() {
        assertTrue(code("M").isUnderTradingPlan, "The aff10b5One element marks this one")
    }

    @Test
    fun anOpenMarketPurchaseIsDiscretionary() {
        val purchase = code("P")
        assertFalse(purchase.isUnderTradingPlan)
        assertEquals(InsiderTransactionNature.OpenMarketPurchase, purchase.nature)
        assertTrue(purchase.nature.isDiscretionary)
        assertEquals(4_000.0, purchase.shares)
        assertEquals(205.00, purchase.pricePerShare)
        assertEquals(820_000.0, purchase.approximateValue)
    }

    @Test
    fun taxWithholdingAndAwardsAreNotMarketDecisions() {
        val withholding = code("F")
        val award = code("A")

        assertEquals(InsiderTransactionNature.TaxWithholding, withholding.nature)
        assertFalse(withholding.nature.isDiscretionary)
        assertEquals(InsiderTransactionNature.Grant, award.nature)
        assertFalse(award.nature.isDiscretionary)
    }

    @Test
    fun aValueGivenDirectlyOnTheElementIsReadNotOnlyAWrappedOne() {
        // Filing agents differ: most emit <transactionShares><value>9000</value>,
        // some put the text on the outer element.
        assertEquals(9_000.0, code("A").shares)
    }

    @Test
    fun sharesOwnedAfterwardsSurvivesWhenReportedAndIsAbsentWhenNot() {
        assertEquals(180_000.0, code("S").sharesOwnedAfter)
        // The withholding line reports no post-transaction total; inventing one
        // would be worse than leaving it out.
        assertNull(code("F").sharesOwnedAfter)
    }

    @Test
    fun datesAreTheTransactionsOwnNotTheFilings() {
        val purchase = code("P")
        assertEquals(Instant.parse("2026-08-22T00:00:00Z"), purchase.transactionDate)
        assertEquals(filedAt, purchase.filedAt)
    }

    @Test
    fun malformedXMLRefusesRatherThanReturningAnEmptyList() {
        // An empty array reads as "this insider made no trades". The provider
        // refused to be implemented at all rather than say that, and the parser
        // keeps the same discipline.
        assertFailsWith<APIError.Decoding> {
            Form4Parser.parse("<not-xml", accessionNumber = "x", filedAt = filedAt)
        }
    }

    @Test
    fun anUnclosedElementIsMalformedNotHalfADocument() {
        // No Swift counterpart: `XMLParser` reported this itself. The
        // hand-rolled walk has to notice, or a truncated download would read
        // as a filing with fewer transactions than it has.
        assertFailsWith<APIError.Decoding> {
            Form4Parser.parse(
                "<ownershipDocument><reportingOwner>",
                accessionNumber = "x",
                filedAt = filedAt,
            )
        }
    }

    @Test
    fun theStyledDocumentPathResolvesToTheMachineReadableXML() {
        val styled = FilingDTO(
            accessionNumber = "0001214156-26-000042",
            formType = "4",
            filedAt = filedAt,
            primaryDocumentURL = "https://www.sec.gov/Archives/edgar/data/320193/" +
                "000121415626000042/xslF345X05/form4.xml",
        )

        // The xsl directory serves HTML; the parseable document is the same
        // filename one level up.
        assertEquals(
            "https://www.sec.gov/Archives/edgar/data/320193/000121415626000042/form4.xml",
            SECProvider.ownershipXMLURL(styled),
        )
    }

    @Test
    fun aDocumentThatIsAlreadyRawXMLIsLeftAlone() {
        val raw = FilingDTO(
            accessionNumber = "x",
            formType = "4",
            filedAt = filedAt,
            primaryDocumentURL = "https://www.sec.gov/Archives/edgar/data/320193/" +
                "000121415626000042/form4.xml",
        )
        val resolved = assertNotNull(SECProvider.ownershipXMLURL(raw))
        assertTrue(resolved.endsWith("/000121415626000042/form4.xml"))
    }
}
