package dev.buhanzaz.rwms.driver.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Keeps the required-nullable `customerDeliveryPurpose` key strict at the public boundary.
 *
 * DriverApp normally uses `explicitNulls = false`, which would otherwise silently turn a
 * missing nullable key into `null`. The rest of the response remains forward-compatible with
 * newer read-only fields through the configured JSON decoder.
 */
object DriverTripDetailsDtoSerializer : KSerializer<DriverTripDetailsDto> {
    override val descriptor: SerialDescriptor = DriverTripDetailsDtoWire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: DriverTripDetailsDto) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("DriverTripDetailsDto supports JSON only")
        val encoded = jsonEncoder.json.encodeToJsonElement(
            DriverTripDetailsDtoWire.serializer(),
            value.toWire(),
        ) as JsonObject
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                encoded.forEach { (name, element) -> put(name, element) }
                put(
                    "customerDeliveryPurpose",
                    value.customerDeliveryPurpose?.let(::JsonPrimitive) ?: JsonNull,
                )
            },
        )
    }

    override fun deserialize(decoder: Decoder): DriverTripDetailsDto {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("DriverTripDetailsDto supports JSON only")
        val payload = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("DriverTripDetailsDto must be a JSON object")
        payload.requireNullableCustomerDeliveryPurpose()
        return jsonDecoder.json.decodeFromJsonElement(
            DriverTripDetailsDtoWire.serializer(),
            payload,
        ).toDto()
    }
}

@Serializable
private data class DriverTripDetailsDtoWire(
    val taskNumber: String,
    val tripNumber: Int,
    val operationType: String,
    val customerDeliveryPurpose: String?,
    val clientName: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val primaryContactName: String?,
    val primaryContactPhone: String?,
    val additionalContacts: List<DriverTripAdditionalContactDto>,
    val comment: String?,
    val desiredDeliveryWindows: List<DriverTripDesiredDeliveryWindowDto>,
    val scheduledDate: String,
    val cabins: List<DriverTripCabinDto>,
)

private fun DriverTripDetailsDto.toWire() = DriverTripDetailsDtoWire(
    taskNumber = taskNumber,
    tripNumber = tripNumber,
    operationType = operationType,
    customerDeliveryPurpose = customerDeliveryPurpose,
    clientName = clientName,
    address = address,
    latitude = latitude,
    longitude = longitude,
    primaryContactName = primaryContactName,
    primaryContactPhone = primaryContactPhone,
    additionalContacts = additionalContacts,
    comment = comment,
    desiredDeliveryWindows = desiredDeliveryWindows,
    scheduledDate = scheduledDate,
    cabins = cabins,
)

private fun DriverTripDetailsDtoWire.toDto() = DriverTripDetailsDto(
    taskNumber = taskNumber,
    tripNumber = tripNumber,
    operationType = operationType,
    customerDeliveryPurpose = customerDeliveryPurpose,
    clientName = clientName,
    address = address,
    latitude = latitude,
    longitude = longitude,
    primaryContactName = primaryContactName,
    primaryContactPhone = primaryContactPhone,
    additionalContacts = additionalContacts,
    comment = comment,
    desiredDeliveryWindows = desiredDeliveryWindows,
    scheduledDate = scheduledDate,
    cabins = cabins,
)

private fun JsonObject.requireNullableCustomerDeliveryPurpose() {
    val value = this["customerDeliveryPurpose"]
        ?: throw SerializationException("DriverTripDetailsDto.customerDeliveryPurpose is required")
    if (value === JsonNull) return
    val primitive = value as? JsonPrimitive
        ?: throw SerializationException(
            "DriverTripDetailsDto.customerDeliveryPurpose must be a string or null",
        )
    if (!primitive.isString || primitive.contentOrNull == null) {
        throw SerializationException(
            "DriverTripDetailsDto.customerDeliveryPurpose must be a string or null",
        )
    }
}
