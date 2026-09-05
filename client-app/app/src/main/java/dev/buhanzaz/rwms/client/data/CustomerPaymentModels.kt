package dev.buhanzaz.rwms.client.data

import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** Server-owned initial payment; null state is explicitly not payment evidence. */
@Serializable
data class CustomerOrderPayment(
    val orderId: String,
    val orderVersion: Long,
    val orderStatus: String,
    val state: String?,
    val startedAt: String?,
    val expiresAt: String?,
    val resolvedAt: String?,
    val source: String?,
    val serverTime: String,
    val canConfirm: Boolean,
    val receipt: CustomerPaymentReceipt?,
) {
    /** Validates exact order binding and frozen bill arithmetic before offering payment. */
    fun validated(expectedOrderId: String): CustomerOrderPayment = apply {
        require(orderId == expectedOrderId && orderVersion >= 0)
        require(orderStatus in setOf("DRAFT", "SAVED", "FULFILLED", "CLOSED", "CANCELLED"))
        require(state in setOf(null, "PENDING", "CONFIRMED", "EXPIRING", "EXPIRED", "CANCELLED"))
        require(source in setOf(null, "CUSTOMER_TEST", "PRESENTATION_TEST", "MANAGER_CONFIRMATION"))
        val now = Instant.parse(serverTime)
        val start = startedAt?.let(Instant::parse)
        val expiry = expiresAt?.let(Instant::parse)
        resolvedAt?.let(Instant::parse)
        if (state != null) {
            require(receipt != null && start != null && expiry != null)
            require(Duration.between(start, expiry) == Duration.ofMinutes(5))
        }
        if (canConfirm) require(state == "PENDING" && orderStatus == "SAVED" && now.isBefore(expiry))
        if (state == "CONFIRMED") require(source != null && resolvedAt != null)
        receipt?.validated(orderId)
    }
}

/** Immutable non-fiscal RUB bill; amount strings intentionally exceed int64. */
@Serializable
data class CustomerPaymentReceipt(
    val schemaVersion: Int,
    val orderId: String,
    val orderNumber: String,
    val issuedAt: String,
    val currency: String,
    val deliveryIncluded: Boolean,
    val lines: List<CustomerPaymentReceiptLine>,
    @Serializable(with = ReceiptIntegerStringSerializer::class) val totalRubles: String,
) {
    /** Rejects truncated, incorrectly bound, or arithmetically inconsistent receipts. */
    fun validated(expectedOrderId: String): CustomerPaymentReceipt = apply {
        require(schemaVersion == 1 && currency == "RUB" && orderId == expectedOrderId)
        require(orderNumber.isNotBlank() && lines.isNotEmpty())
        Instant.parse(issuedAt)
        require(lines.any { it.kind == "CABIN" })
        val deliveries = lines.count { it.kind == "DELIVERY" }
        require(deliveries <= 1 && deliveryIncluded == (deliveries == 1))
        val amounts = lines.map { it.validatedAmount() }
        require(amounts.fold(BigInteger.ZERO, BigInteger::add) == exactReceiptInteger(totalRubles))
    }
}

/** A cabin, per-unit/month furniture rental, or one accepted delivery charge. */
@Serializable
data class CustomerPaymentReceiptLine(
    val kind: String,
    val rentalItemId: String?,
    val equipmentId: String?,
    val label: String,
    @Serializable(with = ReceiptIntegerStringSerializer::class) val quantity: String,
    val rentalMonths: Long?,
    @Serializable(with = ReceiptIntegerStringSerializer::class) val unitPriceRubles: String,
    @Serializable(with = ReceiptIntegerStringSerializer::class) val amountRubles: String,
    val pricingVersion: Long?,
) {
    internal fun validatedAmount(): BigInteger {
        require(label.isNotBlank() && label.length <= 600)
        val count = exactReceiptInteger(quantity)
        val price = exactReceiptInteger(unitPriceRubles)
        val amount = exactReceiptInteger(amountRubles)
        require(count.signum() > 0 && count <= BigInteger.valueOf(Long.MAX_VALUE))
        require(price <= BigInteger.valueOf(Long.MAX_VALUE))
        val months = when (kind) {
            "CABIN", "FURNITURE" -> {
                require(rentalItemId != null && rentalMonths != null && rentalMonths in 1..120)
                require(pricingVersion != null && pricingVersion >= 0)
                if (kind == "CABIN") require(count == BigInteger.ONE && equipmentId == null)
                else require(equipmentId != null)
                BigInteger.valueOf(rentalMonths)
            }
            "DELIVERY" -> {
                require(count == BigInteger.ONE && rentalMonths == null && pricingVersion == null)
                require(rentalItemId == null && equipmentId == null)
                BigInteger.ONE
            }
            else -> error("Unknown receipt line")
        }
        require(count * price * months == amount)
        return amount
    }
}

/** Fences explicit simulated payment to the exact displayed order version. */
@Serializable
data class ConfirmCustomerPaymentRequest(val expectedVersion: Long)

/** Durable server inbox item; reading it is a separate explicit command. */
@Serializable
data class CustomerNotification(
    val id: String,
    val orderId: String,
    val bookingId: String?,
    val kind: String,
    val message: String,
    val createdAt: String,
    val readAt: String?,
)

/** Parses a canonical nonnegative whole-RUB string without floating point or int64 truncation. */
fun exactReceiptInteger(value: String): BigInteger {
    require(Regex("^(0|[1-9][0-9]{0,79})$").matches(value))
    return value.toBigInteger()
}

/** Enforces the canonical JSON string wire type, not a lossy numeric token coercion. */
object ReceiptIntegerStringSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("ReceiptIntegerString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val value = if (decoder is JsonDecoder) {
            val primitive = decoder.decodeJsonElement() as? JsonPrimitive
                ?: throw SerializationException("Expected integer string")
            if (!primitive.isString) throw SerializationException("Expected integer string")
            primitive.content
        } else decoder.decodeString()
        if (!Regex("^(0|[1-9][0-9]{0,79})$").matches(value)) throw SerializationException("Invalid integer string")
        return value
    }

    override fun serialize(encoder: Encoder, value: String) {
        exactReceiptInteger(value)
        encoder.encodeString(value)
    }
}
