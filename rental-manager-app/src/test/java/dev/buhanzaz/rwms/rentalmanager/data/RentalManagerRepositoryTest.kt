package dev.buhanzaz.rwms.rentalmanager.data

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.rentalmanager.network.CreateOrderRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateRentalClientRequest
import dev.buhanzaz.rwms.rentalmanager.network.CurrentUserDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPageDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPaymentDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPaymentReceiptDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPaymentReceiptLineDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientPageDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerApi
import dev.buhanzaz.rwms.rentalmanager.network.UpdateOrderRequest
import dev.buhanzaz.rwms.rentalmanager.network.WarehouseAccessDto
import dev.buhanzaz.rwms.rentalmanager.network.WarehouseDto
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RentalManagerRepositoryTest {
    @Test
    fun `session keeps active edit and manage warehouses in the single installation`() = runTest {
        val api = FakeRentalManagerApi(
            user = managerUser(
                accesses = listOf(
                    WarehouseAccessDto(WAREHOUSE_EDIT, "EDIT"),
                    WarehouseAccessDto(WAREHOUSE_VIEW, "VIEW"),
                    WarehouseAccessDto(WAREHOUSE_MANAGE, "MANAGE"),
                    WarehouseAccessDto(WAREHOUSE_INACTIVE, "MANAGE"),
                ),
            ),
            warehouseItems = listOf(
                warehouse(WAREHOUSE_EDIT, city = "Санкт-Петербург"),
                warehouse(WAREHOUSE_VIEW, city = "Москва"),
                warehouse(WAREHOUSE_MANAGE, city = "Казань"),
                warehouse(WAREHOUSE_INACTIVE, city = "Псков", active = false),
            ),
        )
        val invalidations = mutableListOf<String>()
        val repository = repository(api, invalidations)

        val session = repository.loadSession()

        assertThat(session.warehouses.map { it.id })
            .containsExactly(WAREHOUSE_EDIT, WAREHOUSE_MANAGE)
        assertThat(invalidations).isEmpty()
    }

    @Test
    fun `rental entitled system administrator can load the manager session`() = runTest {
        val api = FakeRentalManagerApi(
            user = managerUser(emptyList()).copy(globalRole = "SYSTEM_ADMIN"),
            warehouseItems = emptyList(),
        )
        val invalidations = mutableListOf<String>()

        val session = repository(api, invalidations).loadSession()

        assertThat(session.user.globalRole).isEqualTo("SYSTEM_ADMIN")
        assertThat(invalidations).isEmpty()
    }

    @Test
    fun `non staff role invalidates local session before data access`() = runTest {
        val api = FakeRentalManagerApi(
            user = managerUser(emptyList()).copy(globalRole = "CUSTOMER"),
            warehouseItems = emptyList(),
        )
        val invalidations = mutableListOf<String>()

        val failure = runCatching { repository(api, invalidations).loadSession() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RentalManagerAccessException::class.java)
        assertThat(invalidations)
            .containsExactly("Для приложения должен быть включён доступ к аренде.")
        assertThat(api.warehouseCalls).isEqualTo(0)
    }

    @Test
    fun `disabled rental access is rejected`() = runTest {
        val api = FakeRentalManagerApi(
            user = managerUser(emptyList()).copy(rentalAccess = false),
            warehouseItems = emptyList(),
        )

        val failure = runCatching {
            repository(api, mutableListOf()).loadSession()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RentalManagerAccessException::class.java)
    }

    @Test
    fun `malformed receipt total is rejected before it can become payable`() = runTest {
        val api = FakeRentalManagerApi(
            user = managerUser(emptyList()),
            warehouseItems = emptyList(),
            payment = payment(totalRubles = "01"),
        )

        val failure = runCatching {
            repository(api, mutableListOf()).orderPayment(PAYMENT_ORDER_ID)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("Receipt total")
    }

    @Test
    fun `receipt for another order is rejected before display or confirmation`() = runTest {
        val validPayment = payment(totalRubles = "200")
        val api = FakeRentalManagerApi(
            user = managerUser(emptyList()),
            warehouseItems = emptyList(),
            payment = validPayment.copy(
                receipt = requireNotNull(validPayment.receipt).copy(
                    orderId = "00000000-0000-0000-0000-000000000302",
                ),
            ),
        )

        val failure = runCatching {
            repository(api, mutableListOf()).orderPayment(PAYMENT_ORDER_ID)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("Receipt belongs to another order")
    }

    private fun repository(
        api: FakeRentalManagerApi,
        invalidations: MutableList<String>,
    ): RentalManagerRepository = RentalManagerRepository(
        api = api,
        invalidateSession = { invalidations += it },
        problemMessage = { "Ошибка запроса" },
    )

    @Test
    fun `valid frozen bill remains exact but wrong factors total and extended deadline fail closed`() = runTest {
        val valid = payment("200")
        val receipt = requireNotNull(valid.receipt)
        val invalid = listOf(
            valid.copy(receipt = receipt.copy(totalRubles = "201")),
            valid.copy(receipt = receipt.copy(lines = listOf(receipt.lines.single().copy(quantity = "2")))),
            valid.copy(receipt = receipt.copy(deliveryIncluded = true)),
            valid.copy(expiresAt = "2026-09-05T10:06:00Z"),
            valid.copy(state = "CONFIRMED", canConfirm = false, source = null),
        )
        assertThat(repository(FakeRentalManagerApi(managerUser(emptyList()), emptyList(), valid), mutableListOf())
            .orderPayment(PAYMENT_ORDER_ID)).isEqualTo(valid)
        invalid.forEach { candidate ->
            assertThat(runCatching {
                repository(FakeRentalManagerApi(managerUser(emptyList()), emptyList(), candidate), mutableListOf())
                    .orderPayment(PAYMENT_ORDER_ID)
            }.isFailure).isTrue()
        }
    }
}

private class FakeRentalManagerApi(
    private val user: CurrentUserDto,
    private val warehouseItems: List<WarehouseDto>,
    private val payment: OrderPaymentDto? = null,
) : RentalManagerApi {
    var warehouseCalls = 0

    override suspend fun currentUser(): CurrentUserDto = user

    override suspend fun warehouses(): List<WarehouseDto> {
        warehouseCalls += 1
        return warehouseItems
    }

    override suspend fun clients(search: String?, page: Int, size: Int): RentalClientPageDto =
        unsupported()

    override suspend fun client(clientId: String): RentalClientDto = unsupported()

    override suspend fun createClient(
        idempotencyKey: String,
        request: CreateRentalClientRequest,
    ): RentalClientDto = unsupported()

    override suspend fun clientOrders(
        clientId: String,
        page: Int,
        size: Int,
        sort: String,
        direction: String,
    ): OrderPageDto = unsupported()

    override suspend fun orders(
        search: String?,
        page: Int,
        size: Int,
        sort: String,
        direction: String,
    ): OrderPageDto = unsupported()

    override suspend fun order(orderId: String): OrderDto = unsupported()

    override suspend fun createOrder(
        idempotencyKey: String,
        request: CreateOrderRequest,
    ): OrderDto = unsupported()

    override suspend fun updateOrder(
        orderId: String,
        idempotencyKey: String,
        request: UpdateOrderRequest,
    ): OrderDto = unsupported()

    override suspend fun cancelOrder(
        orderId: String,
        expectedVersion: Long,
        idempotencyKey: String,
    ): OrderDto = unsupported()

    override suspend fun saveOrder(
        orderId: String,
        expectedVersion: Long,
        idempotencyKey: String,
    ): OrderDto = unsupported()

    override suspend fun orderPayment(orderId: String): OrderPaymentDto =
        requireNotNull(payment)

    override suspend fun confirmOrderPayment(
        orderId: String,
        idempotencyKey: String,
        request: dev.buhanzaz.rwms.rentalmanager.network.ConfirmOrderPaymentRequest,
    ): OrderPaymentDto = unsupported()

    override suspend fun clientPresentation(inquiryId: String):
        retrofit2.Response<dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationDto> = unsupported()

    override suspend fun publishClientPresentation(
        inquiryId: String,
        idempotencyKey: String,
        request: dev.buhanzaz.rwms.rentalmanager.network.PublishRentalPresentationRequest,
    ): dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationDto = unsupported()

    private fun <T> unsupported(): T = error("Unexpected API call")
}

private fun managerUser(accesses: List<WarehouseAccessDto>): CurrentUserDto = CurrentUserDto(
    id = USER_ID,
    username = "manager",
    displayName = "Менеджер",
    principalType = "USER",
    globalRole = "RENTAL_MANAGER",
    rentalAccess = true,
    warehouseAccessAll = false,
    warehouseAccesses = accesses,
)

private fun warehouse(
    id: String,
    city: String = "Санкт-Петербург",
    active: Boolean = true,
): WarehouseDto = WarehouseDto(
    id = id,
    version = 1,
    name = "Склад $city",
    city = city,
    address = null,
    timeZone = "Europe/Moscow",
    active = active,
    lifecycleState = if (active) "ACTIVE" else "DEACTIVATED",
    representative = false,
    production = true,
    mainWarehouse = false,
)

private const val USER_ID = "00000000-0000-0000-0000-000000000010"
private const val WAREHOUSE_EDIT = "00000000-0000-0000-0000-000000000101"
private const val WAREHOUSE_VIEW = "00000000-0000-0000-0000-000000000102"
private const val WAREHOUSE_MANAGE = "00000000-0000-0000-0000-000000000103"
private const val WAREHOUSE_INACTIVE = "00000000-0000-0000-0000-000000000104"
private const val PAYMENT_ORDER_ID = "00000000-0000-0000-0000-000000000301"

private fun payment(totalRubles: String): OrderPaymentDto = OrderPaymentDto(
    orderId = PAYMENT_ORDER_ID,
    orderVersion = 4,
    orderStatus = "SAVED",
    state = "PENDING",
    startedAt = "2026-09-05T10:00:00Z",
    expiresAt = "2026-09-05T10:05:00Z",
    resolvedAt = null,
    source = null,
    serverTime = "2026-09-05T10:01:00Z",
    canConfirm = true,
    receipt = OrderPaymentReceiptDto(
        schemaVersion = 1,
        orderId = PAYMENT_ORDER_ID,
        orderNumber = "A-100",
        issuedAt = "2026-09-05T10:00:00Z",
        currency = "RUB",
        deliveryIncluded = false,
        lines = listOf(
            OrderPaymentReceiptLineDto(
                kind = "CABIN",
                rentalItemId = "00000000-0000-0000-0000-000000000601",
                equipmentId = null,
                label = "Бытовка БК-101",
                quantity = "1",
                rentalMonths = 2,
                unitPriceRubles = "100",
                amountRubles = "200",
                pricingVersion = 4,
            ),
        ),
        totalRubles = totalRubles,
    ),
)
