package dev.buhanzaz.rwms.client.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** Preserves the logistics whole-RUB string contract without numeric coercion or rounding. */
object RentalMonthlyPriceSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "dev.buhanzaz.rwms.client.RentalMonthlyPrice", PrimitiveKind.STRING,
    )

    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Rental price requires JSON")
        val value = jsonDecoder.decodeJsonElement() as? JsonPrimitive
        if (value == null || !value.isString || !value.content.matches(Regex("^(0|[1-9][0-9]{0,18})$"))) {
            throw SerializationException("Rental price must be a nonnegative whole-RUB string")
        }
        return value.content.toLongOrNull()
            ?: throw SerializationException("Rental price exceeds the supported whole-RUB range")
    }

    override fun serialize(encoder: Encoder, value: Long) {
        if (value < 0) throw SerializationException("Rental price must be nonnegative")
        encoder.encodeString(value.toString())
    }
}
