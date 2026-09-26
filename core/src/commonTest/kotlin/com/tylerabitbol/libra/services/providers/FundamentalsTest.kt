package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.fixtures.Fixture
import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.networking.HTTPClient
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Extraction from a real `companyfacts` payload, captured from EDGAR through
 * the app itself so no contact address passed through a shell command.
 *
 * Swift: `XBRL fundamentals extraction` in `FundamentalsTests.swift`.
 */
class FundamentalsTest {

    private fun facts(): CompanyFactsResponse = Fixture.decode("sec_companyfacts_AAPL")

    private fun extract(concept: FinancialConcept, since: Instant? = null) =
        SECFundamentalsProvider.extract(concept, facts(), since)

    @Test
    fun aRealCompanyfactsPayloadDecodes() {
        val response = facts()
        assertEquals("Apple Inc.", response.entityName)
        assertEquals(320193, response.cik)
        assertTrue(response.facts["us-gaap"]?.isNotEmpty() == true)
    }

    @Test
    fun revenueResolvesThroughTheCandidateTagList() {
        val extracted = extract(FinancialConcept.Revenue)
        assertTrue(extracted.isNotEmpty())
        // Apple reports revenue in the hundreds of billions annually; a value
        // outside this range means the wrong tag or unit was chosen.
        val latest = extracted.filter { it.isAnnual }.lastOrNull()
        if (latest != null) {
            assertTrue(latest.value > 100_000_000_000.0)
            assertEquals("USD", latest.unit)
        }
    }

    @Test
    fun epsIsReadFromUSDPerSharesNotUSD() {
        val extracted = extract(FinancialConcept.EarningsPerShareDiluted)
        assertTrue(extracted.isNotEmpty())
        assertTrue(extracted.all { it.unit == "USD/shares" })
        // A per-share figure in single digits; picking USD would give billions.
        assertTrue(extracted.all { abs(it.value) < 100 })
    }

    @Test
    fun shareCountsAreReadFromTheSharesUnit() {
        val extracted = extract(FinancialConcept.SharesOutstandingDiluted)
        if (extracted.isNotEmpty()) {
            assertTrue(extracted.all { it.unit == "shares" })
            assertTrue(extracted.all { it.value > 1_000_000_000.0 })
        }
    }

    @Test
    fun everyExtractedFactCarriesTheTagItCameFromForTraceability() {
        val extracted = extract(FinancialConcept.NetIncome)
        assertTrue(extracted.isNotEmpty())
        assertTrue(extracted.all { it.rawTag?.startsWith("us-gaap:") == true })
        assertTrue(extracted.all { it.accessionNumber != null })
    }

    @Test
    fun periodsAreClassifiedByDurationNotByTheFpLabelAlone() {
        for (fact in extract(FinancialConcept.Revenue)) {
            val start = fact.periodStart
            if (start == null) {
                assertEquals(FiscalPeriodKind.Instant, fact.periodKind)
                continue
            }
            val days = (fact.periodEnd - start).inWholeSeconds.toDouble() / 86_400.0
            when (fact.periodKind) {
                FiscalPeriodKind.Quarter -> assertTrue(days < 140)
                FiscalPeriodKind.Annual -> assertTrue(days > 310)
                else -> throw AssertionError(
                    "Cumulative period ${fact.periodKind} should be filtered out",
                )
            }
        }
    }

    @Test
    fun cumulativeYearToDateRowsAreExcludedFromExtraction() {
        // A Q3 10-Q files both a three-month and a nine-month revenue figure
        // under one tag. Treating the nine-month total as a quarter would make
        // Q3 look roughly three times Q2.
        val extracted = extract(FinancialConcept.Revenue)

        assertTrue(extracted.isNotEmpty())
        assertTrue(extracted.all { it.periodKind.isDiscrete })
        assertTrue(extracted.none { it.periodKind == FiscalPeriodKind.NineMonth })
        assertTrue(extracted.none { it.periodKind == FiscalPeriodKind.HalfYear })
    }

    @Test
    fun quarterlyRevenueStaysWithinAPlausibleBandOfItsNeighbours() {
        val quarters = extract(FinancialConcept.Revenue)
            .filter { it.periodKind == FiscalPeriodKind.Quarter }
            .sortedBy { it.periodEnd }

        if (quarters.size < 4) return
        // A cumulative row leaking in shows up as a quarter several times its
        // neighbour. Apple's quarters vary seasonally but never by 2.5x.
        for ((previous, current) in quarters.zipWithNext()) {
            val ratio = current.value / previous.value
            assertTrue(
                ratio < 2.5 && ratio > 0.4,
                "Quarter-over-quarter ratio $ratio suggests a cumulative row leaked in",
            )
        }
    }

    @Test
    fun durationWindowsTolerate52And53WeekFiscalCalendars() {
        assertEquals(FiscalPeriodKind.Quarter, FiscalPeriodKind.classify(91.0))
        assertEquals(FiscalPeriodKind.Quarter, FiscalPeriodKind.classify(98.0)) // 14-week quarter
        assertEquals(FiscalPeriodKind.HalfYear, FiscalPeriodKind.classify(182.0))
        // The row that caught this.
        assertEquals(FiscalPeriodKind.NineMonth, FiscalPeriodKind.classify(272.0))
        assertEquals(FiscalPeriodKind.Annual, FiscalPeriodKind.classify(364.0))
        assertEquals(FiscalPeriodKind.Annual, FiscalPeriodKind.classify(371.0)) // 53-week year
        assertEquals(FiscalPeriodKind.Instant, FiscalPeriodKind.classify(null))
    }

    @Test
    fun restatementsCollapseToTheMostRecentlyFiledFigurePerPeriod() {
        val periodEnd = Instant.fromEpochSeconds(1_700_000_000)
        fun fact(value: Double, filed: Long, accession: String) = FinancialFactDTO(
            concept = FinancialConcept.Revenue,
            rawTag = "us-gaap:Revenues",
            periodEnd = periodEnd,
            fiscalYear = 2025,
            fiscalQuarter = 2,
            isAnnual = false,
            periodKind = FiscalPeriodKind.Quarter,
            value = value,
            unit = "USD",
            filedAt = Instant.fromEpochSeconds(filed),
            accessionNumber = accession,
        )

        val result = FactPeriods.deduplicated(
            listOf(fact(100.0, 1_700_100_000, "old"), fact(110.0, 1_800_000_000, "new")),
        )
        assertEquals(1, result.size)
        assertEquals(110.0, result.first().value, "The corrected figure must win")
        assertEquals("new", result.first().accessionNumber)
    }

    @Test
    fun distinctPeriodsAreNeverCollapsedIntoOneAnother() {
        fun fact(end: Long, quarter: Int, value: Double) = FinancialFactDTO(
            concept = FinancialConcept.Revenue,
            periodEnd = Instant.fromEpochSeconds(end),
            fiscalYear = 2025,
            fiscalQuarter = quarter,
            isAnnual = false,
            periodKind = FiscalPeriodKind.Quarter,
            value = value,
            unit = "USD",
        )
        assertEquals(
            2,
            FactPeriods.deduplicated(
                listOf(fact(1_700_000_000, 1, 100.0), fact(1_708_000_000, 2, 120.0)),
            ).size,
        )
    }

    @Test
    fun aConceptTheIssuerDoesNotReportYieldsNothingNeverAZero() {
        val response = facts()
        for (concept in FinancialConcept.entries) {
            val extracted = SECFundamentalsProvider.extract(concept, response, since = null)
            // The guarantee is that nothing is synthesised: a concept either
            // produces facts traceable to a real tag, or produces none at all.
            if (extracted.isEmpty()) continue
            assertTrue(extracted.all { it.rawTag != null })
            assertTrue(extracted.all { it.accessionNumber != null })
        }
    }

    @Test
    fun theSinceFilterExcludesOlderPeriods() {
        val cutoff = Instant.fromEpochSeconds(1_750_000_000)
        assertTrue(extract(FinancialConcept.Revenue, cutoff).all { it.periodEnd >= cutoff })
    }

    @Test
    fun unitPreferenceMapsEachConceptToTheRightXBRLUnitKey() {
        assertEquals(
            "USD/shares",
            FinancialConcept.EarningsPerShareDiluted.preferredUnit(listOf("USD", "USD/shares")),
        )
        assertEquals(
            "shares",
            FinancialConcept.SharesOutstandingDiluted.preferredUnit(listOf("shares")),
        )
        assertEquals("USD", FinancialConcept.Revenue.preferredUnit(listOf("USD")))
        assertNull(FinancialConcept.Revenue.preferredUnit(emptyList()))
    }

    @Test
    fun everyMappedConceptHasAtLeastOneCandidateTag() {
        for (concept in FinancialConcept.entries) {
            assertTrue(concept.candidateTags.isNotEmpty(), "$concept has no tag mapping")
        }
    }
}

/**
 * Tag migration. Issuers change the XBRL tag they report a concept under, and
 * the naive "first candidate tag that returns anything" rule silently
 * truncates the history at the changeover — a chart that simply starts late,
 * which looks like a data limitation rather than a bug.
 *
 * Swift: `Candidate tag merging` in `FundamentalsTests.swift`.
 */
class CandidateTagMergingTest {

    /**
     * Old years under `Revenues`, recent years under the newer tag — the shape
     * NVIDIA's own filings take.
     */
    private val payload = """
        {
          "cik": 1045810,
          "entityName": "NVIDIA CORP",
          "facts": {
            "us-gaap": {
              "RevenueFromContractWithCustomerExcludingAssessedTax": {
                "units": {
                  "USD": [
                    {"start": "2024-01-29", "end": "2025-01-26", "val": 130497000000,
                     "fy": 2025, "fp": "FY", "form": "10-K", "filed": "2025-02-26",
                     "accn": "0001045810-25-000023"},
                    {"start": "2025-01-27", "end": "2026-01-25", "val": 213000000000,
                     "fy": 2026, "fp": "FY", "form": "10-K", "filed": "2026-02-25",
                     "accn": "0001045810-26-000010"}
                  ]
                }
              },
              "Revenues": {
                "units": {
                  "USD": [
                    {"start": "2021-02-01", "end": "2022-01-30", "val": 26914000000,
                     "fy": 2022, "fp": "FY", "form": "10-K", "filed": "2022-03-18",
                     "accn": "0001045810-22-000036"},
                    {"start": "2022-01-31", "end": "2023-01-29", "val": 26974000000,
                     "fy": 2023, "fp": "FY", "form": "10-K", "filed": "2023-02-24",
                     "accn": "0001045810-23-000017"}
                  ]
                }
              }
            }
          }
        }
    """.trimIndent()

    private fun response(json: String = payload): CompanyFactsResponse =
        HTTPClient.libraJson.decodeFromString(json)

    @Test
    fun historySpanningATagChangeIsCompleteNotTruncated() {
        val facts = SECFundamentalsProvider.extract(
            FinancialConcept.Revenue,
            response(),
            since = null,
        )

        // First-tag-wins returned only the two years under the newer tag.
        assertEquals(4, facts.size)
        val years = facts.map { it.periodEnd.toLocalDateTime(TimeZone.UTC).year }
        assertEquals(setOf(2022, 2023, 2025, 2026), years.toSet())
    }

    @Test
    fun resultsAreOrderedOldestFirstRegardlessOfWhichTagSuppliedThem() {
        val ends = SECFundamentalsProvider.extract(
            FinancialConcept.Revenue,
            response(),
            since = null,
        ).map { it.periodEnd }
        assertEquals(ends.sorted(), ends, "A merged series must not interleave by tag")
    }

    @Test
    fun aPeriodReportedUnderTwoTagsContributesOneFigure() {
        val json = """
            {
              "cik": 1, "entityName": "T",
              "facts": { "us-gaap": {
                "RevenueFromContractWithCustomerExcludingAssessedTax": { "units": { "USD": [
                  {"start": "2024-01-01", "end": "2024-12-31", "val": 100,
                   "fy": 2024, "fp": "FY", "form": "10-K", "filed": "2025-01-15", "accn": "a"}
                ]}},
                "Revenues": { "units": { "USD": [
                  {"start": "2024-01-01", "end": "2024-12-31", "val": 115,
                   "fy": 2024, "fp": "FY", "form": "10-K", "filed": "2025-01-15", "accn": "b"}
                ]}}
              }}
            }
        """.trimIndent()
        val facts = SECFundamentalsProvider.extract(
            FinancialConcept.Revenue,
            response(json),
            since = null,
        )

        // Concatenating would report 215 of revenue for one year.
        assertEquals(1, facts.size)
        // The higher-priority tag wins: the two tags differ by assessed tax.
        assertEquals(100.0, assertNotNull(facts.firstOrNull()).value)
    }
}
