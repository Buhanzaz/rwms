package dev.buhanzaz.rwms.rentalmanager.data

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.rentalmanager.network.CreateOrderRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateRentalClientRequest
import dev.buhanzaz.rwms.rentalmanager.network.CurrentUserDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPageDto
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
    fun `wrong role invalidates local session before data access`() = runTest {
        val api = FakeRentalManagerApi(
            user = managerUser(emptyList()).copy(globalRole = "WMS_MANAGER"),
            warehouseItems = emptyList(),
        )
        val invalidations = mutableListOf<String>()

        val failure = runCatching { repository(api, invalidations).loadSession() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RentalManagerAccessException::class.java)
        assertThat(invalidations).containsExactly("Приложение доступно только менеджерам аренды.")
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

    private fun repository(
        api: FakeRentalManagerApi,
        invalidations: MutableList<String>,
    ): RentalManagerRepository = RentalManagerRepository(
        api = api,
        invalidateSession = { invalidations += it },
        problemMessage = { "Ошибка запроса" },
    )
}

private class FakeRentalManagerApi(
    private val user: CurrentUserDto,
    private val warehouseItems: List<WarehouseDto>,
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
