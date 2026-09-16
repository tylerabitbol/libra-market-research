package com.tylerabitbol.libra.support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from LibraTests/FormatTests.swift, suite "Formatting". */
class FormatTest {

    @Test
    fun every_formatter_renders_a_missing_value_as_not_available_never_as_zero() {
        assertEquals(Format.notAvailable, Format.currency(null))
        assertEquals(Format.notAvailable, Format.compactCurrency(null))
        assertEquals(Format.notAvailable, Format.compact(null))
        assertEquals(Format.notAvailable, Format.percent(null))
        assertEquals(Format.notAvailable, Format.signedPercent(null))
        assertEquals(Format.notAvailable, Format.percentagePoints(null))
        assertEquals(Format.notAvailable, Format.multiple(null))
        assertEquals(Format.notAvailable, Format.ratio(null))
        assertEquals(Format.notAvailable, Format.ordinal(null))
        assertEquals(Format.notAvailable, Format.shortDate(null))
    }

    @Test
    fun large_figures_use_compact_magnitudes() {
        assertEquals("\$3.10T", Format.compactCurrency(3_100_000_000_000.0))
        // Avoids an exact .5 tie, where half-to-even rounding makes the
        // expected value a coin flip rather than a property of the formatter.
        assertEquals("\$413B", Format.compactCurrency(412_700_000_000.0))
        assertEquals("24.4M", Format.compact(24_400_000.0))
        assertEquals("1.90B", Format.compact(1_900_000_000.0))
    }

    @Test
    fun negative_magnitudes_keep_their_sign() {
        assertEquals("-\$2.50B", Format.compactCurrency(-2_500_000_000.0))
    }

    @Test
    fun signed_percentages_always_show_direction() {
        assertEquals("+4.82%", Format.signedPercent(4.82))
        assertEquals("-1.34%", Format.signedPercent(-1.34))
        assertEquals("0.00%", Format.signedPercent(0.0))
    }

    @Test
    fun percentage_points_are_labelled_pp_not_percent() {
        val text = Format.percentagePoints(7.0)
        assertTrue(text.contains("pp"))
        assertFalse(text.contains("%"), "Percent and percentage points are different units")
    }

    @Test
    fun a_non_finite_ratio_is_not_available_rather_than_inf() {
        assertEquals(Format.notAvailable, Format.ratio(Double.POSITIVE_INFINITY))
        assertEquals(Format.notAvailable, Format.ratio(Double.NaN))
    }

    @Test
    fun ordinals_handle_the_teens_correctly() {
        assertEquals("1st", Format.ordinal(1))
        assertEquals("2nd", Format.ordinal(2))
        assertEquals("3rd", Format.ordinal(3))
        assertEquals("4th", Format.ordinal(4))
        assertEquals("11th", Format.ordinal(11))
        assertEquals("12th", Format.ordinal(12))
        assertEquals("13th", Format.ordinal(13))
        assertEquals("21st", Format.ordinal(21))
        assertEquals("78th", Format.ordinal(78))
        assertEquals("101st", Format.ordinal(101))
    }

    @Test
    fun volume_multiples_read_as_one_point_nine() {
        assertEquals("1.9×", Format.multiple(1.9))
    }

    @Test
    fun a_count_agrees_in_number_with_its_noun() {
        assertEquals("1 dimension", Format.count(1, "dimension"))
        assertEquals("2 dimensions", Format.count(2, "dimension"))
        assertEquals("0 sessions", Format.count(0, "session"))
        assertEquals("1 company", Format.count(1, "company", plural = "companies"))
        assertEquals("3 companies", Format.count(3, "company", plural = "companies"))
    }

    @Test
    fun percentage_points_can_be_rendered_without_a_sign_for_verb_paired_text() {
        assertEquals("+9.4 pp", Format.percentagePoints(9.4))
        assertEquals("9.4 pp", Format.percentagePoints(9.4, signed = false))
        assertEquals(
            "-9.4 pp",
            Format.percentagePoints(-9.4, signed = false),
            "Suppressing the plus must not suppress a minus"
        )
        assertEquals(Format.notAvailable, Format.percentagePoints(null, signed = false))
    }

    @Test
    fun grouped_thousands_never_render_in_scientific_notation() {
        // Not in the Swift suite: Kotlin's toString() would produce 1.2345678E7
        // here, which is the failure this formatter exists to prevent.
        assertEquals("12,345,678.00", Format.fixed(12_345_678.0, 2))
        assertEquals("1,000", Format.fixed(1_000.0, 0))
        assertEquals("999", Format.fixed(999.0, 0))
    }
}
