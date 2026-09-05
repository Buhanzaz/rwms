package dev.buhanzaz.rwms.rentalmanager.data

import dev.buhanzaz.rwms.rentalmanager.network.CreateOrderRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateRentalClientRequest
import dev.buhanzaz.rwms.rentalmanager.network.ConfirmOrderPaymentRequest
import dev.buhanzaz.rwms.rentalmanager.network.CurrentUserDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPageDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPaymentDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientPageDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerBackend
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerApi
import dev.buhanzaz.rwms.rentalmanager.network.UpdateOrderRequest
import dev.buhanzaz.rwms.rentalmanager.network.WarehouseDto
import java.util.UUID
import java.math.BigInteger
import java.time.Duration
import java.time.OffsetDateTime
import retrofit2.HttpException

data class RentalManagerSession(
    val user: CurrentUserDto,
    val warehouses: List<WarehouseDto>,
)

class RentalManagerAccessException(message: String) : IllegalStateException(message)

/** Uses only server-owned manager APIs; cached values in the UI are never authorization facts. */
class RentalManagerRepository(
    private val api: RentalManagerApi,
    private val invalidateSession: suspend (String) -> Unit,
    private val problemMessage: (HttpException) -> String,
) {
    constructor(backend: RentalManagerBackend) : this(
        api = backend.api,
        invalidateSession = backend.auth::invalidate,
        problemMessage = backend::problemMessage,
    )

    suspend fun loadSession(): RentalManagerSession {
        val user = api.currentUser()
        requireUuid(user.id, "user id")
        if (user.principalType != "USER" ||
            user.globalRole != "RENTAL_MANAGER" ||
            !user.rentalAccess
        ) {
            invalidateSession("Приложение доступно только менеджерам аренды.")
            throw RentalManagerAccessException("Приложение доступно только менеджерам аренды.")
        }
        val grantedWarehouseIds = user.warehouseAccesses
            .filter { it.level == "EDIT" || it.level == "MANAGE" }
            .map { requireUuid(it.warehouseId, "warehouse grant").toString() }
            .toSet()
        val warehouses = api.warehouses()
            .onEach {
                requireUuid(it.id, "warehouse id")
            }
            .filter { it.active && it.id in grantedWarehouseIds }
            .sortedWith(compareBy<WarehouseDto> { it.city }.thenBy { it.name })
        return RentalManagerSession(user, warehouses)
    }

    suspend fun clients(search: String, page: Int = 0): RentalClientPageDto =
        api.clients(search.trim().ifEmpty { null }, page = page)

    suspend fun client(clientId: String): RentalClientDto =
        api.client(requireUuid(clientId, "client id").toString())

    suspend fun createClient(
        idempotencyKey: UUID,
        request: CreateRentalClientRequest,
    ): RentalClientDto = api.createClient(idempotencyKey.toString(), request)

    suspend fun orders(search: String, page: Int = 0): OrderPageDto =
        api.orders(search.trim().ifEmpty { null }, page = page)

    suspend fun clientOrders(clientId: String, page: Int = 0): OrderPageDto =
        api.clientOrders(
            clientId = requireUuid(clientId, "client id").toString(),
            page = page,
        )

    suspend fun order(orderId: String): OrderDto =
        api.order(requireUuid(orderId, "order id").toString())

    suspend fun createOrder(
        idempotencyKey: UUID,
        request: CreateOrderRequest,
    ): OrderDto = api.createOrder(idempotencyKey.toString(), request)

    suspend fun updateOrder(
        order: OrderDto,
        idempotencyKey: UUID,
        contactPhone: String?,
        comment: String?,
    ): OrderDto = api.updateOrder(
        orderId = requireUuid(order.id, "order id").toString(),
        idempotencyKey = idempotencyKey.toString(),
        request = UpdateOrderRequest(
            expectedVersion = order.version,
            clientId = requireUuid(order.client.id, "client id").toString(),
            contactPhone = contactPhone?.trim()?.ifEmpty { null },
            comment = comment?.trim()?.ifEmpty { null },
        ),
    )

    suspend fun saveOrder(order: OrderDto, idempotencyKey: UUID): OrderDto =
        api.saveOrder(
            orderId = requireUuid(order.id, "order id").toString(),
            expectedVersion = order.version,
            idempotencyKey = idempotencyKey.toString(),
        )

    suspend fun cancelOrder(order: OrderDto, idempotencyKey: UUID): OrderDto =
        api.cancelOrder(
            orderId = requireUuid(order.id, "order id").toString(),
            expectedVersion = order.version,
            idempotencyKey = idempotencyKey.toString(),
        )

    /** Reads authoritative immutable bill and deadline evidence without starting or extending payment. */
    suspend fun orderPayment(orderId: String): OrderPaymentDto =
        api.orderPayment(requireUuid(orderId, "order id").toString())
            .also { validatePayment(it, orderId) }

    /** Records only the server-authorized manager acknowledgement under the payment projection fence. */
    suspend fun confirmOrderPayment(
        payment: OrderPaymentDto,
        idempotencyKey: UUID,
    ): OrderPaymentDto {
        validatePayment(payment, payment.orderId)
        require(payment.canConfirm && payment.state == "PENDING" && payment.receipt != null) {
            "Payment confirmation is not currently available"
        }
        val confirmed = api.confirmOrderPayment(
            orderId = requireUuid(payment.orderId, "payment order id").toString(),
            idempotencyKey = idempotencyKey.toString(),
            request = ConfirmOrderPaymentRequest(expectedVersion = payment.orderVersion),
        )
        validatePayment(confirmed, payment.orderId)
        return confirmed
    }

    fun userMessage(failure: Throwable): String = when (failure) {
        is RentalManagerAccessException -> failure.message ?: "Доступ запрещён."
        is HttpException -> problemMessage(failure)
        else -> "Не удалось загрузить данные. Проверьте подключение и повторите попытку."
    }

    private fun requireUuid(value: String, field: String): UUID =
        runCatching { UUID.fromString(value) }
            .getOrElse { throw IllegalStateException("Invalid $field") }

    /**
     * Rejects a malformed cross-boundary receipt before it reaches the payable UI or a command.
     * Money remains exact canonical whole-RUB text rather than a lossy numeric representation.
     */
    private fun validatePayment(payment: OrderPaymentDto, expectedOrderId: String) {
        val canonicalOrderId = requireUuid(expectedOrderId, "expected payment order id").toString()
        require(requireUuid(payment.orderId, "payment order id").toString() == canonicalOrderId) {
            "Payment belongs to another order"
        }
        require(payment.orderVersion >= 0) { "Payment version is invalid" }
        require(payment.orderStatus in setOf("DRAFT", "SAVED", "FULFILLED", "CLOSED", "CANCELLED"))
        require(payment.state in setOf(null, "PENDING", "CONFIRMED", "EXPIRING", "EXPIRED", "CANCELLED"))
        require(payment.source in setOf(null, "CUSTOMER_TEST", "PRESENTATION_TEST", "MANAGER_CONFIRMATION"))
        val now = OffsetDateTime.parse(payment.serverTime)
        val start = payment.startedAt?.let(OffsetDateTime::parse)
        val expiry = payment.expiresAt?.let(OffsetDateTime::parse)
        payment.resolvedAt?.let(OffsetDateTime::parse)
        if (payment.state != null) {
            require(start != null && expiry != null && payment.receipt != null)
            require(Duration.between(start, expiry) == Duration.ofMinutes(5))
        }
        if (payment.canConfirm) {
            require(payment.state == "PENDING" && payment.orderStatus == "SAVED" && expiry != null && now.isBefore(expiry))
        }
        if (payment.state == "CONFIRMED") require(payment.source != null && payment.resolvedAt != null)
        val receipt = payment.receipt ?: return
        require(requireUuid(receipt.orderId, "receipt order id").toString() == canonicalOrderId) {
            "Receipt belongs to another order"
        }
        require(receipt.schemaVersion == 1 && receipt.currency == "RUB") {
            "Receipt format is unsupported"
        }
        require(isCanonicalRubles(receipt.totalRubles)) { "Receipt total is invalid" }
        require(receipt.orderNumber.isNotBlank() && receipt.lines.isNotEmpty())
        OffsetDateTime.parse(receipt.issuedAt)
        require(receipt.lines.any { it.kind == "CABIN" })
        val deliveries = receipt.lines.count { it.kind == "DELIVERY" }
        require(deliveries <= 1 && receipt.deliveryIncluded == (deliveries == 1))
        var total = BigInteger.ZERO
        receipt.lines.forEach { line ->
            require(isCanonicalRubles(line.quantity) && isCanonicalRubles(line.unitPriceRubles) && isCanonicalRubles(line.amountRubles)) {
                "Receipt amount is invalid"
            }
            require(line.label.isNotBlank() && line.label.length <= 600)
            val quantity = line.quantity.toBigInteger()
            val unitPrice = line.unitPriceRubles.toBigInteger()
            val amount = line.amountRubles.toBigInteger()
            require(quantity > BigInteger.ZERO && quantity <= BigInteger.valueOf(Long.MAX_VALUE))
            require(unitPrice <= BigInteger.valueOf(Long.MAX_VALUE))
            val months = when (line.kind) {
                "CABIN", "FURNITURE" -> {
                    requireUuid(requireNotNull(line.rentalItemId), "receipt cabin id")
                    require(line.rentalMonths != null && line.rentalMonths in 1..120)
                    require(line.pricingVersion != null && line.pricingVersion >= 0)
                    if (line.kind == "CABIN") require(quantity == BigInteger.ONE && line.equipmentId == null)
                    else requireUuid(requireNotNull(line.equipmentId), "receipt equipment id")
                    BigInteger.valueOf(line.rentalMonths)
                }
                "DELIVERY" -> {
                    require(quantity == BigInteger.ONE && line.rentalMonths == null && line.pricingVersion == null)
                    require(line.rentalItemId == null && line.equipmentId == null)
                    BigInteger.ONE
                }
                else -> error("Unknown receipt line")
            }
            require(quantity * unitPrice * months == amount) { "Receipt line total is invalid" }
            total += amount
        }
        require(total == receipt.totalRubles.toBigInteger()) { "Receipt total is inconsistent" }
    }

    private fun isCanonicalRubles(value: String): Boolean =
        WHOLE_RUBLES.matches(value)

    private companion object {
        val WHOLE_RUBLES = Regex("^(0|[1-9][0-9]{0,79})$")
    }
}
