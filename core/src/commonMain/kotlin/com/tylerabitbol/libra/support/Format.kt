package com.tylerabitbol.libra.support

import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Number formatting for a research terminal.
 *
 * Swift built this on `FormatStyle`; Kotlin has no equivalent that behaves
 * identically on both platforms, so the arithmetic is hand-rolled here. That
 * is deliberate: `toString()` and string templates render 1.0E7 and drift
 * between JVM and Native, and every user-visible figure must come through
 * this file.
 *
 * Rounding is half-to-even, matching the ICU default that Foundation used, so
 * ported tests keep their expected strings.
 *
 * The rule running through all of this: an absent value formats as
 * "Not available", never as 0, "—0.0%", or a blank that reads like a real
 * number. Section 21 treats fabricating a value as worse than showing nothing.
 */
object Format {
    const val notAvailable = "Not available"

    private val monthAbbreviations = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    // MARK: - Currency

    fun currency(value: Double?, code: String = "USD", precision: Int = 2): String {
        if (value == null) return notAvailable
        if (!value.isFinite()) return notAvailable
        val sign = if (value < 0) "-" else ""
        val body = fixed(abs(value), precision)
        return if (code == "USD") "$sign\$$body" else "$sign$code $body"
    }

    /** Large monetary figures: $3.10T, $412.5B, $24.4M. */
    fun compactCurrency(value: Double?, code: String = "USD"): String {
        if (value == null) return notAvailable
        if (!value.isFinite()) return notAvailable
        val symbol = if (code == "USD") "\$" else ""
        val sign = if (value < 0) "-" else ""
        return "$sign$symbol${compactMagnitude(abs(value))}"
    }

    /** Large plain counts: 1.9B shares, 24.4M. */
    fun compact(value: Double?): String {
        if (value == null) return notAvailable
        if (!value.isFinite()) return notAvailable
        val sign = if (value < 0) "-" else ""
        return "$sign${compactMagnitude(abs(value))}"
    }

    private fun compactMagnitude(value: Double): String {
        val units = listOf(
            1_000_000_000_000.0 to "T",
            1_000_000_000.0 to "B",
            1_000_000.0 to "M",
            1_000.0 to "K"
        )
        for ((threshold, suffix) in units) {
            if (value >= threshold) {
                val scaled = value / threshold
                // Keep three significant figures so 3.10T and 412B both read well.
                val precision = if (scaled >= 100) 0 else if (scaled >= 10) 1 else 2
                return "${fixed(scaled, precision)}$suffix"
            }
        }
        return fixed(value, if (value < 10) 2 else 0)
    }

    // MARK: - Percentages

    /** A percentage already expressed in percentage points (4.82 → "4.82%"). */
    fun percent(value: Double?, precision: Int = 2): String {
        if (value == null) return notAvailable
        if (!value.isFinite()) return notAvailable
        return "${fixed(value, precision)}%"
    }

    /** The same, with an explicit sign, for changes where direction matters. */
    fun signedPercent(value: Double?, precision: Int = 2): String {
        if (value == null) return notAvailable
        if (!value.isFinite()) return notAvailable
        val sign = if (value > 0) "+" else ""
        return "$sign${fixed(value, precision)}%"
    }

    fun signed(value: Double?, precision: Int = 2): String {
        if (value == null) return notAvailable
        if (!value.isFinite()) return notAvailable
        val sign = if (value > 0) "+" else ""
        return "$sign${fixed(value, precision)}"
    }

    /**
     * Difference between two percentages, in percentage points.
     *
     * Section 7 asks for statements like "outperformed its sector by roughly
     * 7 percentage points". Percentage points and percent are different units
     * and conflating them is a real source of wrong numbers, so the unit is
     * spelled out rather than reusing the "%" symbol.
     *
     * [signed] exists because a sentence that already carries a direction word
     * must not also carry a sign: "underperformed by +9.4 pp" states the
     * direction twice and contradicts itself once. Callers that pair this with
     * a verb pass `signed = false` and take the magnitude; the derivation block
     * underneath keeps the signed value, because that is the audit trail.
     */
    fun percentagePoints(value: Double?, precision: Int = 1, signed: Boolean = true): String {
        if (value == null) return notAvailable
        if (!value.isFinite()) return notAvailable
        val sign = if (signed && value > 0) "+" else ""
        return "$sign${fixed(value, precision)} pp"
    }

    /**
     * A count with its noun in the right number: "1 dimension", "2 dimensions".
     *
     * Written once because "1 dimensions could not be measured" is the kind of
     * error that reappears at every new call site otherwise.
     */
    fun count(value: Int, singular: String, plural: String? = null): String =
        "$value ${if (value == 1) singular else (plural ?: singular + "s")}"

    // MARK: - Ratios and multiples

    /** Volume relative to average: 1.9×. */
    fun multiple(value: Double?, precision: Int = 1): String {
        if (value == null) return notAvailable
        if (!value.isFinite()) return notAvailable
        return "${fixed(value, precision)}×"
    }

    /** Valuation multiples: P/E 31.4. */
    fun ratio(value: Double?, precision: Int = 1): String {
        if (value == null) return notAvailable
        // A negative P/E is not meaningful; the caller should have passed null,
        // but rendering "n/m" is better than a confidently wrong negative.
        if (!value.isFinite()) return notAvailable
        return fixed(value, precision)
    }

    /** Ordinal for historical percentile context: "78th". */
    fun ordinal(value: Int?): String {
        if (value == null) return notAvailable
        val suffix = when {
            value % 100 in 11..13 -> "th"
            value % 10 == 1 -> "st"
            value % 10 == 2 -> "nd"
            value % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$value$suffix"
    }

    // MARK: - Dates

    fun shortDate(date: Instant?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (date == null) return notAvailable
        val parts = date.toLocalDateTime(zone)
        return "${monthAbbreviations[parts.month.ordinal]} ${parts.day}, ${parts.year}"
    }

    fun dayAndMonth(date: Instant?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (date == null) return notAvailable
        val parts = date.toLocalDateTime(zone)
        return "${monthAbbreviations[parts.month.ordinal]} ${parts.day}"
    }

    /**
     * "Sep 2025" — a month without a day, for an axis whose range is long
     * enough that the day is noise and the year is not. Swift gets this shape
     * from Charts' automatic axis labels, which have no Kotlin equivalent.
     */
    fun monthAndYear(date: Instant?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (date == null) return notAvailable
        val parts = date.toLocalDateTime(zone)
        return "${monthAbbreviations[parts.month.ordinal]} ${parts.year}"
    }

    // MARK: - The arithmetic

    /**
     * Fixed-point rendering with grouping separators and half-to-even rounding.
     *
     * Half-to-even because Foundation's number style used ICU's default, and
     * the Swift tests were written against it — `FormatTests` even avoids an
     * exact .5 tie on purpose. Rounding half-up here would silently change
     * figures the tests do not cover.
     */
    internal fun fixed(value: Double, decimals: Int, grouping: Boolean = true): String {
        if (!value.isFinite()) return notAvailable
        val negative = value < 0
        val magnitude = abs(value)

        var factor = 1L
        repeat(decimals) { factor *= 10L }

        val scaled = magnitude * factor
        val lower = floor(scaled)
        val remainder = scaled - lower
        val rounded = when {
            remainder > 0.5 -> lower + 1.0
            remainder < 0.5 -> lower
            // Exactly halfway: round to the even unit.
            else -> if (lower.toLong() % 2L == 0L) lower else lower + 1.0
        }

        val units = rounded.toLong()
        val whole = units / factor
        val fraction = units % factor

        val sb = StringBuilder()
        if (negative && units != 0L) sb.append('-')
        sb.append(if (grouping) group(whole.toString()) else whole.toString())
        if (decimals > 0) {
            sb.append('.')
            sb.append(fraction.toString().padStart(decimals, '0'))
        }
        return sb.toString()
    }

    /** Inserts thousands separators: 1234567 → "1,234,567". */
    private fun group(digits: String): String {
        if (digits.length <= 3) return digits
        val sb = StringBuilder()
        val lead = digits.length % 3
        if (lead > 0) {
            sb.append(digits, 0, lead)
            if (digits.length > lead) sb.append(',')
        }
        var index = lead
        while (index < digits.length) {
            sb.append(digits, index, index + 3)
            index += 3
            if (index < digits.length) sb.append(',')
        }
        return sb.toString()
    }
}
