package com.tylerabitbol.libra.networking.serializers

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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads a number that a vendor may have sent as a string, or not at all.
 *
 * Swift's `Decodable` absorbed this quietly because `Double?` decoding tried
 * several representations. kotlinx.serialization does not, and the mismatch is
 * not hypothetical: FRED writes a missing observation as the string `"."`,
 * Tiingo and Alpaca mix bare numbers with quoted ones in the same array, and
 * Finnhub sends `null` for a metric it has no value for.
 *
 * Anything that is not a finite number reads as null. A parse failure here
 * would otherwise take down a whole response over one unusable row, and the
 * callers already treat null as "not reported" — which is what it is.
 */
object LenientDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientDouble", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Double? {
        val input = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        val primitive = input.decodeJsonElement() as? JsonPrimitive ?: return null
        val value = primitive.doubleOrNull ?: primitive.contentOrNull?.trim()?.toDoubleOrNull()
        return value?.takeIf { it.isFinite() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

/** The same leniency for whole numbers: share counts, volumes, timestamps. */
object LenientLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long? {
        val input = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val primitive = input.decodeJsonElement() as? JsonPrimitive ?: return null
        val text = primitive.contentOrNull?.trim() ?: return null
        return text.toLongOrNull() ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}

/**
 * Reads a string that a vendor may have sent as a number.
 *
 * SEC CIKs arrive both ways — `320193` in one payload and `"0000320193"` in
 * another — and Finnhub quotes some identifiers and not others.
 */
object LenientStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString()
        return (input.decodeJsonElement() as? JsonPrimitive)?.contentOrNull
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/** Reads the primitive out of an element that may be a number or a string. */
internal fun JsonPrimitive.asDoubleOrNull(): Double? =
    doubleOrNull ?: contentOrNull?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun kotlinx.serialization.json.JsonElement.asDoubleOrNull(): Double? =
    (this as? JsonPrimitive)?.asDoubleOrNull() ?: runCatching { jsonPrimitive.asDoubleOrNull() }
        .getOrNull()
