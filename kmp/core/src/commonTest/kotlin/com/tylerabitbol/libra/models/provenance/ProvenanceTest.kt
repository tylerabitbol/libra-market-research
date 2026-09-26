package com.tylerabitbol.libra.models.provenance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Ported from LibraTests/ProvenanceTests.swift, suite "Claim provenance". */
class ProvenanceTest {

    @Test
    fun claim_kinds_order_from_most_to_least_certain() {
        assertTrue(ClaimKind.Fact < ClaimKind.Calculation)
        assertTrue(ClaimKind.Calculation < ClaimKind.Interpretation)
        assertTrue(ClaimKind.Interpretation < ClaimKind.Hypothesis)
    }

    @Test
    fun a_fact_with_a_source_reference_is_traceable() {
        val claim = Claim(
            kind = ClaimKind.Fact,
            text = "Revenue was \$30.0B in Q2 FY2026.",
            sources = listOf(
                SourceReference(
                    provider = DataProviderID.SEC,
                    detail = "10-Q filed 2026-07-28",
                    url = "https://www.sec.gov/",
                    retrievedAt = Clock.System.now()
                )
            )
        )
        assertTrue(claim.isTraceable)
    }

    @Test
    fun a_calculation_is_traceable_through_its_derivation_alone() {
        val claim = Claim(
            kind = ClaimKind.Calculation,
            text = "Revenue grew 14.2% year-over-year.",
            derivation = Derivation(
                formula = "(revenue - revenuePriorYear) / revenuePriorYear",
                inputs = listOf(
                    Derivation.Input(name = "revenue", value = "30,040,000,000"),
                    Derivation.Input(name = "revenuePriorYear", value = "26,300,000,000")
                ),
                result = "14.2%"
            )
        )
        assertTrue(claim.isTraceable)
    }

    @Test
    fun a_bare_claim_with_no_source_and_no_derivation_is_not_traceable() {
        val claim = Claim(kind = ClaimKind.Hypothesis, text = "This may indicate improving demand.")
        assertFalse(claim.isTraceable, "Untraceable claims must be detectable before display")
    }

    @Test
    fun sec_is_the_only_provider_marked_as_a_primary_source() {
        val primaries = DataProviderID.entries.filter { it.isPrimarySource }
        assertEquals(listOf(DataProviderID.SEC), primaries)
    }
}
