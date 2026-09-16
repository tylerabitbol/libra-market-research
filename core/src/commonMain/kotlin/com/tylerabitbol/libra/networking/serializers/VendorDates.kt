package com.tylerabitbol.libra.networking.serializers

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Instant

/**
 * The four date shapes the vendors between them use.
 *
 * Swift reached for a `DateFormatter` or an `ISO8601FormatStyle` at each call
 * site. Collecting them here is the plan's instruction and also the safer
 * arrangement: a formatter configured slightly differently in one provider is
 * the kind of bug that shows up as a chart shifted by a day, months later.
 *
 * Every parse returns null rather than throwing. A single malformed row must
 * not discard a whole response; the callers drop the row instead.
 */
object VendorDate {

    /**
     * ISO-8601, with or without fractional seconds.
     *
     * Tiingo sends `2026-08-20T00:00:00.000Z`; Alpaca sends both
     * `2026-08-28T13:30:00Z` and `2026-08-28T13:35:00.000Z`, sometimes in the
     * same array. `Instant.parse` accepts both, so the Swift fallback chain is
     * one call here.
     */
    fun instant(text: String?): Instant? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { Instant.parse(trimmed) }.getOrNull() ?: day(trimmed)
    }

    /**
     * A bare `yyyy-MM-dd`, as FRED and the SEC send.
     *
     * Anchored to midnight UTC, matching Swift's UTC-locked `DateFormatter`.
     * These are calendar dates rather than instants — a filing is filed on a
     * day, not at a moment — and pinning them to one zone keeps two runs in
     * different places from disagreeing about which day a row belongs to.
     */
    fun day(text: String?, zone: TimeZone = TimeZone.UTC): Instant? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { LocalDate.parse(trimmed.take(10)).atStartOfDayIn(zone) }.getOrNull()
    }

    /** Finnhub timestamps every payload in epoch seconds. */
    fun epochSeconds(value: Long?): Instant? {
        if (value == null || value <= 0L) return null
        return Instant.fromEpochSeconds(value)
    }
}

/** ISO-8601 with or without fractional seconds; null when unparseable. */
object IsoInstantSerializer : KSerializer<Instant?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IsoInstant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant? {
        val input = decoder as? JsonDecoder ?: return VendorDate.instant(decoder.decodeString())
        val primitive = input.decodeJsonElement() as? JsonPrimitive ?: return null
        return VendorDate.instant(primitive.contentOrNull)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Instant?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toString())
    }
}

/** A bare `yyyy-MM-dd`, read as midnight UTC. */
object DayInstantSerializer : KSerializer<Instant?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DayInstant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant? {
        val input = decoder as? JsonDecoder ?: return VendorDate.day(decoder.decodeString())
        val primitive = input.decodeJsonElement() as? JsonPrimitive ?: return null
        return VendorDate.day(primitive.contentOrNull)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Instant?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toString())
    }
}
