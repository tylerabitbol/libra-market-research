package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.fixtures.Fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Swift: `FRED decoding` in `FREDDecodingTests.swift`. */
class FREDDecodingTest {

    @Test
    fun aRealObservationsPayloadDecodes() {
        val response: FREDObservationsResponse = Fixture.decode("fred_observations_SP500")
        assertEquals(12, response.observations.size)
    }

    @Test
    fun theMissingMarkerIsDroppedNeverCoercedToZero() {
        val response: FREDObservationsResponse = Fixture.decode("fred_observations_SP500")
        // This window spans Christmas and New Year, both market holidays.
        val markers = response.observations.filter { it.value == "." }
        assertEquals(2, markers.size, "Fixture should contain the holiday gaps")

        val parsed = response.observations.mapNotNull { FREDProvider.parseValue(it.value) }
        assertEquals(response.observations.size - 2, parsed.size)
        assertFalse(
            parsed.contains(0.0),
            "A holiday must not become a crash to zero on the chart",
        )
    }

    @Test
    fun parseValueRejectsTheMarkerAndBlanksAcceptsRealNumbers() {
        assertNull(FREDProvider.parseValue("."))
        assertNull(FREDProvider.parseValue(""))
        assertNull(FREDProvider.parseValue("   "))
        assertNull(FREDProvider.parseValue(null))
        assertEquals(6932.05, FREDProvider.parseValue("6932.05"))
        assertEquals(15.21, FREDProvider.parseValue(" 15.21 "))
    }

    @Test
    fun theVIXSeriesDecodesAsRealIndexLevels() {
        val response: FREDObservationsResponse = Fixture.decode("fred_observations_VIXCLS")
        val values = response.observations.mapNotNull { FREDProvider.parseValue(it.value) }
        assertTrue(values.isNotEmpty())
        // The VIX has never closed outside this band; a value beyond it means
        // we decoded the wrong field.
        assertTrue(values.all { it > 0 && it < 200 })
    }
}
