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
