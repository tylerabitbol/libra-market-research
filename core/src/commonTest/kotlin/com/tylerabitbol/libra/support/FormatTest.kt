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

    /**
     * No Swift counterpart. Foundation gave the Swift app a per-locale currency
     * symbol for free; this renders the code and a space instead. Nothing calls
     * either currency formatter with a non-USD code today, so this pins what
     * they do rather than asserting what they should do — a figure that quietly
     * changes shape is the failure mode worth catching here.
     */
    @Test
    fun a_non_usd_code_is_rendered_as_the_code_not_as_a_symbol() {
        assertEquals("EUR 1,234.50", Format.currency(1234.5, code = "EUR"))
        assertEquals("-EUR 1,234.50", Format.currency(-1234.5, code = "EUR"))
        assertEquals("JPY 1,234.50", Format.currency(1234.5, code = "JPY"))

        // compactCurrency drops the symbol entirely rather than substituting
        // the code, so the two formatters disagree. Recorded, not corrected.
        assertEquals("1.23B", Format.compactCurrency(1_234_000_000.0, code = "EUR"))
        assertEquals("\$1.23B", Format.compactCurrency(1_234_000_000.0))
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
    fun a_move_that_rounds_to_zero_has_no_direction() {
        // The S&P's 1D read a red "0.00%" off a -0.001% move: the colour
        // followed the raw value while the text followed the rounding.
        assertEquals("0.00%", Format.signedPercent(0.001))
        assertEquals("0.00%", Format.signedPercent(-0.001))
        assertEquals(0, Format.displayedSign(0.001))
        assertEquals(0, Format.displayedSign(-0.004))
        assertEquals(-1, Format.displayedSign(-0.006))
        assertEquals(1, Format.displayedSign(0.006))
        assertEquals(0, Format.displayedSign(null))
        assertEquals(0, Format.displayedSign(Double.NaN))
        // Grouped thousands must not read as unparseable, and so as zero.
        assertEquals(1, Format.displayedSign(1_234.567))
        assertEquals("+1,234.57", Format.signed(1_234.567))
        assertEquals(1, Format.displayedSign(0.06, precision = 1))
        assertEquals(0, Format.displayedSign(0.4, precision = 0))
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

    /**
     * Worked answers, not Swift parity. Each expected string is the decimal
     * value rounded half-to-even by hand, which is what ICU does and what the
     * Swift app printed.
     */
    @Test
    fun ties_round_to_even_on_the_decimal_value_not_the_binary_one() {
        // 2.675 is stored as 2.67499999…; ×100 in binary gave 267.4999… and
        // printed 2.67. As a decimal it is a tie, and 7 is odd: 2.68.
        assertEquals("2.68", Format.fixed(2.675, 2))
        // 1.015: a tie, 1 is odd, so up: 1.02 (binary scaling gave 1.01).
        assertEquals("1.02", Format.fixed(1.015, 2))
        // 1.005: a tie, 0 is even, so down: 1.00.
        assertEquals("1.00", Format.fixed(1.005, 2))
        // 0.125 is exact in binary: a tie, 2 is even, so 0.12.
        assertEquals("0.12", Format.fixed(0.125, 2))
        // Whole-number ties: 0.5 → 0, 1.5 → 2, 2.5 → 2.
        assertEquals("0", Format.fixed(0.5, 0))
        assertEquals("2", Format.fixed(1.5, 0))
        assertEquals("2", Format.fixed(2.5, 0))
        // Past the tie is not a tie: 2.6751 → 2.68, 2.6749 → 2.67.
        assertEquals("2.68", Format.fixed(2.6751, 2))
        assertEquals("2.67", Format.fixed(2.6749, 2))
    }

    @Test
    fun rounding_carries_through_every_digit_and_the_grouping() {
        assertEquals("1,000.00", Format.fixed(999.995, 2))
        assertEquals("10.0", Format.fixed(9.96, 1))
        assertEquals("-1,000.0", Format.fixed(-999.96, 1))
    }

    @Test
    fun values_that_round_to_zero_carry_no_sign() {
        assertEquals("0.00", Format.fixed(-0.004, 2))
        assertEquals("0.00", Format.fixed(0.0000001, 2))
        assertEquals("0.0", Format.fixed(-0.0, 1))
    }

    @Test
    fun very_small_and_very_large_values_come_through_scientific_notation() {
        // toString() renders these as 1.5E-5 and 1.0E20; the digits are
        // recovered from it rather than printed.
        assertEquals("0.00002", Format.fixed(0.000015, 5)) // tie, 1 odd → up
        assertEquals("100,000,000,000,000,000,000", Format.fixed(1e20, 0))
        assertEquals("12,345,678,901,234.00", Format.fixed(12_345_678_901_234.0, 2))
    }

    @Test
    fun a_compact_figure_that_rounds_up_to_a_thousand_takes_the_next_unit() {
        // 999.97B at 0 decimals would be "1,000B".
        assertEquals("\$1.00T", Format.compactCurrency(999_970_000_000.0))
        assertEquals("1.00M", Format.compact(999_600.0))
        // Just below the boundary keeps its unit: 999.4K.
        assertEquals("999K", Format.compact(999_400.0))
    }
}
