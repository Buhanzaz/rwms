package dev.buhanzaz.rwms.client.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Exact requested owner operation; CustomerApp never sends a company-fault exemption. */
@Serializable
enum class BookingChangeOperation { CANCEL, RESCHEDULE }

/** Authoritative fee settlement, including the explicitly non-financial test marker. */
@Serializable
enum class BookingChangeSettlement { POLICY_UNCONFIGURED, PAYMENT_REQUIRED, NOT_REQUIRED, TEST_PAID, WAIVED }

/** Whether the owner has only quoted, accepted, or applied the requested booking change. */
@Serializable
enum class BookingChangeApplicationState { OFFERED, APPLYING, APPLIED }

/** Booking/slot fences for a quote; cancellation serializes both slot properties as explicit null. */
@Serializable(with = CreateBookingChangeQuoteSerializer::class)
data class CreateBookingChangeQuoteRequest(
    val expectedVersion: Long,
    val operation: BookingChangeOperation,
    val slotId: String?,
    val slotVersion: Long?,
)

/**
 * Preserves this endpoint's required nullable fields without changing the shared API Json policy.
 * Other CustomerApp requests deliberately continue to omit optional null properties.
 */
object CreateBookingChangeQuoteSerializer : KSerializer<CreateBookingChangeQuoteRequest> {
    override val descriptor = buildClassSerialDescriptor("CreateBookingChangeQuoteRequest") {
        element<Long>("expectedVersion")
        element<BookingChangeOperation>("operation")
        element<String?>("slotId")
        element<Long?>("slotVersion")
    }

    override fun serialize(encoder: Encoder, value: CreateBookingChangeQuoteRequest) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("JSON is required")
        output.encodeJsonElement(
            buildJsonObject {
                put("expectedVersion", value.expectedVersion)
                put("operation", value.operation.name)
                put("slotId", value.slotId?.let(::JsonPrimitive) ?: JsonNull)
                put("slotVersion", value.slotVersion?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
    }

    override fun deserialize(decoder: Decoder): CreateBookingChangeQuoteRequest {
        val input = decoder as? JsonDecoder ?: throw SerializationException("JSON is required")
        val value = input.decodeJsonElement().jsonObject
        return CreateBookingChangeQuoteRequest(
            expectedVersion = value.getValue("expectedVersion").jsonPrimitive.long,
            operation = BookingChangeOperation.valueOf(value.getValue("operation").jsonPrimitive.content),
            slotId = value.getValue("slotId").jsonPrimitive.contentOrNull,
            slotVersion = value.getValue("slotVersion").takeUnless { it == JsonNull }?.jsonPrimitive?.long,
        )
    }
}

/** Exact service quote; amounts remain decimal strings in transport to preserve the Long range. */
@Serializable
data class CustomerBookingChangeQuote(
    val quoteId: String,
    val version: Long,
    val bookingId: String,
    val bookingVersion: Long,
    val operation: BookingChangeOperation,
    val oldSlotId: String,
    val slotId: String?,
    val slotVersion: Long?,
    @Serializable(with = ExactWholeRublesTextSerializer::class) val amountRubles: String?,
    val settlement: BookingChangeSettlement,
    val applicationState: BookingChangeApplicationState,
    val testPaymentAvailable: Boolean,
    val supportPhone: String?,
    val expiresAt: String,
    val noticeDays: Int,
    val deliveryDate: String,
    val warehouseTimeZone: String,
    val targetDeliveryDate: String?,
    val targetWindowStart: String?,
    val targetWindowEnd: String?,
) {
    /** Converts an already validated service integer without calculating or rounding any fee. */
    fun amountAsLongOrNull(): Long? = amountRubles?.toLongOrNull()
}

/** Requires the canonical string-or-null money token, never a coercible JSON number. */
object ExactWholeRublesTextSerializer : KSerializer<String?> {
    override val descriptor = String.serializer().nullable.descriptor

    override fun serialize(encoder: Encoder, value: String?) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("JSON is required")
        output.encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
    }

    override fun deserialize(decoder: Decoder): String? {
        val input = decoder as? JsonDecoder ?: throw SerializationException("JSON is required")
        val value = input.decodeJsonElement()
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive
        if (primitive?.isString != true) throw SerializationException("Whole rubles must be a string")
        return primitive.content
    }
}

/** A lost POST response can be recovered from the exact quote without inventing a booking result. */
data class CustomerBookingChangeResult(
    val booking: CustomerBooking?,
    val quote: CustomerBookingChangeQuote,
)

/** Rejects unusable money and contradictory quote identities before exposing actionable UI. */
internal fun CustomerBookingChangeQuote.validated(): CustomerBookingChangeQuote {
    val configuredAmount = amountRubles?.let { amount ->
        amount.matches(WHOLE_RUBLES_PATTERN) && amount.toLongOrNull() != null
    } ?: (settlement == BookingChangeSettlement.POLICY_UNCONFIGURED)
    val targetValid = when (operation) {
        BookingChangeOperation.CANCEL -> slotId == null && slotVersion == null &&
            targetDeliveryDate == null && targetWindowStart == null && targetWindowEnd == null
        BookingChangeOperation.RESCHEDULE -> !slotId.isNullOrBlank() && slotVersion != null && slotVersion >= 0 &&
            runCatching {
                LocalDate.parse(requireNotNull(targetDeliveryDate))
                LocalTime.parse(requireNotNull(targetWindowStart))
                LocalTime.parse(requireNotNull(targetWindowEnd))
            }.isSuccess
    }
    if (quoteId.isBlank() || bookingId.isBlank() || oldSlotId.isBlank() || version < 0 ||
        bookingVersion <= 0 || noticeDays < 0 || !configuredAmount || !targetValid ||
        (settlement == BookingChangeSettlement.TEST_PAID && applicationState != BookingChangeApplicationState.APPLIED) ||
        runCatching { Instant.parse(expiresAt) }.isFailure
    ) {
        throw CustomerApiException(502, "Сервис вернул некорректные условия изменения. Обновите заказ.")
    }
    return this
}

private val WHOLE_RUBLES_PATTERN = Regex("0|[1-9][0-9]*")
