package dev.buhanzaz.rwms.rentalmanager.data

import dev.buhanzaz.rwms.rentalmanager.network.CreateOrderRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateRentalClientRequest
import dev.buhanzaz.rwms.rentalmanager.network.CurrentUserDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPageDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientPageDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerBackend
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerApi
import dev.buhanzaz.rwms.rentalmanager.network.UpdateOrderRequest
import dev.buhanzaz.rwms.rentalmanager.network.WarehouseDto
import java.util.UUID
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

    fun userMessage(failure: Throwable): String = when (failure) {
        is RentalManagerAccessException -> failure.message ?: "Доступ запрещён."
        is HttpException -> problemMessage(failure)
        else -> "Не удалось загрузить данные. Проверьте подключение и повторите попытку."
    }

    private fun requireUuid(value: String, field: String): UUID =
        runCatching { UUID.fromString(value) }
            .getOrElse { throw IllegalStateException("Invalid $field") }
}
