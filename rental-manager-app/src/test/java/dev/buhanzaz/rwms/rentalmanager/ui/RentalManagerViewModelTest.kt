package dev.buhanzaz.rwms.rentalmanager.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.rentalmanager.auth.RentalManagerAuthState
import dev.buhanzaz.rwms.rentalmanager.data.RentalManagerRepository
import dev.buhanzaz.rwms.rentalmanager.network.ConfirmOrderPaymentRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateOrderRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateRentalClientRequest
import dev.buhanzaz.rwms.rentalmanager.network.CurrentUserDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPageDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPaymentDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPaymentReceiptDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPaymentReceiptLineDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPermissionsDto
import dev.buhanzaz.rwms.rentalmanager.network.PublishRentalPresentationRequest
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientPageDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerApi
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationDto
import dev.buhanzaz.rwms.rentalmanager.network.UpdateOrderRequest
import dev.buhanzaz.rwms.rentalmanager.network.WarehouseDto
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RentalManagerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<RentalManagerViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    @Test
    fun `late update patches A in lists while B remains selected`() = lateResultKeepsB { vm ->
        vm.updateOrder(null, "updated")
    }

    @Test
    fun `late save cannot replace B or load A payment`() = lateResultKeepsB { vm ->
        vm.saveOrder()
    }

    @Test
    fun `late cancellation cannot replace B or navigate away`() = lateResultKeepsB { vm ->
        vm.cancelOrder()
    }

    @Test
    fun `late payment confirmation cannot replace payment for B`() = runTest {
        val fixture = fixture()
        fixture.api.payments[ORDER_A.id] = PENDING_PAYMENT
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        val response = CompletableDeferred<OrderPaymentDto>()
        fixture.api.confirmPayment = { response.await() }
        fixture.viewModel.confirmOrderPayment()
        runCurrent()
        fixture.viewModel.closeOrder(ORDER_A.id)
        fixture.viewModel.openOrder(ORDER_B.id)
        advanceUntilIdle()

        response.complete(
            PENDING_PAYMENT.copy(
                orderVersion = 2,
                state = "CONFIRMED",
                canConfirm = false,
                source = "MANAGER_CONFIRMATION",
                resolvedAt = NOW,
            ),
        )
        advanceUntilIdle()

        assertThat(fixture.api.confirmationCalls).isEqualTo(1)
        assertThat(fixture.viewModel.state.value.selectedOrder).isEqualTo(ORDER_B)
        assertThat(fixture.viewModel.state.value.selectedOrderPayment?.payment?.orderId).isEqualTo(ORDER_B.id)
        assertThat(fixture.viewModel.state.value.notice).isNull()
        assertThat(fixture.viewModel.state.value.commandRunning).isFalse()
        assertThat(fixture.events).isEmpty()
    }

    private fun lateResultKeepsB(command: (RentalManagerViewModel) -> Unit) = runTest {
        val fixture = fixture()
        fixture.viewModel.openClient(CLIENT.id)
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        val response = fixture.api.delayMutation()
        command(fixture.viewModel)
        runCurrent()
        fixture.viewModel.closeOrder(ORDER_A.id)
        fixture.viewModel.openOrder(ORDER_B.id)
        advanceUntilIdle()
        val paymentReads = fixture.api.paymentReads.toList()
        val updated = ORDER_A.copy(version = 2, comment = "updated")

        response.complete(updated)
        advanceUntilIdle()

        assertThat(fixture.viewModel.state.value.selectedOrder).isEqualTo(ORDER_B)
        assertThat(fixture.viewModel.state.value.selectedOrderId).isEqualTo(ORDER_B.id)
        assertThat(fixture.viewModel.state.value.orders).containsExactly(updated, ORDER_B)
        assertThat(fixture.viewModel.state.value.selectedClientOrders).containsExactly(updated, ORDER_B)
        assertThat(fixture.viewModel.state.value.selectedOrderPayment?.payment?.orderId).isEqualTo(ORDER_B.id)
        assertThat(fixture.viewModel.state.value.notice).isNull()
        assertThat(fixture.viewModel.state.value.commandRunning).isFalse()
        assertThat(fixture.api.paymentReads).containsExactlyElementsIn(paymentReads).inOrder()
        assertThat(fixture.events).isEmpty()
    }

    @Test
    fun `cancellation finishing after leaving its route does not navigate`() = runTest {
        val fixture = fixture()
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        val response = fixture.api.delayMutation()
        fixture.viewModel.cancelOrder()
        runCurrent()
        fixture.viewModel.closeOrder(ORDER_A.id)

        response.complete(ORDER_A.copy(version = 2, status = "CANCELLED"))
        advanceUntilIdle()

        assertThat(fixture.events).isEmpty()
        assertThat(fixture.viewModel.state.value.selectedOrder).isNull()
        assertThat(fixture.viewModel.state.value.orders.first().status).isEqualTo("CANCELLED")
        assertThat(fixture.viewModel.state.value.notice).isNull()
    }

    @Test
    fun `late response cannot replace a newly reopened copy of the same order`() = runTest {
        val fixture = fixture()
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        val response = fixture.api.delayMutation()
        fixture.viewModel.updateOrder(null, "old command")
        runCurrent()
        fixture.viewModel.closeOrder(ORDER_A.id)
        val newer = ORDER_A.copy(version = 3, comment = "newer server state")
        fixture.api.orderItems[ORDER_A.id] = newer
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()

        response.complete(ORDER_A.copy(version = 2, comment = "old command"))
        advanceUntilIdle()

        assertThat(fixture.viewModel.state.value.selectedOrder).isEqualTo(newer)
        assertThat(fixture.viewModel.state.value.orders.first()).isEqualTo(newer)
        assertThat(fixture.viewModel.state.value.notice).isNull()
        assertThat(fixture.events).isEmpty()
    }

    @Test
    fun `current order cancellation still updates state and emits navigation`() = runTest {
        val fixture = fixture()
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        fixture.api.mutation = { _, _ -> ORDER_A.copy(version = 2, status = "CANCELLED") }

        fixture.viewModel.cancelOrder()
        advanceUntilIdle()

        assertThat(fixture.viewModel.state.value.selectedOrder?.status).isEqualTo("CANCELLED")
        assertThat(fixture.events).containsExactly(RentalManagerNavigationEvent.OpenOrders(ORDER_A.id))
        assertThat(fixture.viewModel.state.value.notice?.isError).isFalse()
        assertThat(fixture.viewModel.state.value.commandRunning).isFalse()
    }

    @Test
    fun `immediate duplicate submission starts only one command`() = runTest {
        val fixture = fixture()
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        val response = fixture.api.delayMutation()

        fixture.viewModel.updateOrder(null, "same")
        fixture.viewModel.updateOrder(null, "same")
        runCurrent()

        assertThat(fixture.api.keys).hasSize(1)
        response.complete(ORDER_A.copy(version = 2))
        advanceUntilIdle()
        assertThat(fixture.viewModel.state.value.selectedOrder?.version).isEqualTo(2)
        assertThat(fixture.viewModel.state.value.commandRunning).isFalse()
    }

    @Test
    fun `failed command preserves its idempotency key for retry`() = runTest {
        val fixture = fixture()
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        fixture.api.mutation = { _, _ -> throw IOException("lost response") }
        fixture.viewModel.updateOrder(null, "same")
        advanceUntilIdle()
        assertThat(fixture.viewModel.state.value.notice?.isError).isTrue()
        fixture.api.mutation = { _, _ -> ORDER_A.copy(version = 2) }

        fixture.viewModel.updateOrder(null, "same")
        advanceUntilIdle()

        assertThat(fixture.api.keys).hasSize(2)
        assertThat(fixture.api.keys.distinct()).hasSize(1)
        assertThat(fixture.savedState.get<String>("updateOrder.key")).isNull()
    }

    @Test
    fun `recreated view model retries failed command with persisted idempotency key`() = runTest {
        val fixture = fixture()
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        fixture.api.mutation = { _, _ -> throw IOException("lost response") }

        fixture.viewModel.updateOrder(null, "same")
        advanceUntilIdle()
        val failedKey = fixture.api.keys.single()

        fixture.api.mutation = { _, _ -> ORDER_A.copy(version = 2) }
        val recreated = RentalManagerViewModel(
            fixture.repository,
            fixture.auth,
            { _, _ -> },
            {},
            { fixture.invalidations += it },
            fixture.savedState,
        )
        viewModels += recreated
        advanceUntilIdle()
        recreated.openOrder(ORDER_A.id)
        advanceUntilIdle()

        recreated.updateOrder(null, "same")
        advanceUntilIdle()

        assertThat(fixture.api.keys).containsExactly(failedKey, failedKey).inOrder()
        assertThat(fixture.savedState.get<String>("updateOrder.key")).isNull()
        assertThat(recreated.state.value.selectedOrder?.version).isEqualTo(2)
    }

    @Test
    fun `old session success cannot clear a new command key or running state`() = runTest {
        val fixture = fixture()
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        val oldResponse = fixture.api.delayMutation()
        fixture.viewModel.updateOrder(null, "old")
        runCurrent()
        val oldJob = requireNotNull(fixture.api.mutationJob)

        fixture.auth.value = RentalManagerAuthState.SignedOut
        runCurrent()
        assertThat(oldJob.isCancelled).isTrue()
        fixture.auth.value = RentalManagerAuthState.SignedIn
        advanceUntilIdle()
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        val newResponse = fixture.api.delayMutation()
        fixture.viewModel.updateOrder(null, "new")
        runCurrent()
        val newKey = fixture.savedState.get<String>("updateOrder.key")
        val before = fixture.viewModel.state.value

        oldResponse.complete(ORDER_A.copy(version = 2, comment = "old"))
        runCurrent()

        assertThat(fixture.viewModel.state.value).isEqualTo(before)
        assertThat(fixture.viewModel.state.value.commandRunning).isTrue()
        assertThat(fixture.savedState.get<String>("updateOrder.key")).isEqualTo(newKey)
        assertThat(fixture.events).isEmpty()
        newResponse.complete(ORDER_A.copy(version = 3, comment = "new"))
        advanceUntilIdle()
        assertThat(fixture.viewModel.state.value.selectedOrder?.comment).isEqualTo("new")
    }

    @Test
    fun `late unauthorized response cannot invalidate the replacement session`() = runTest {
        val fixture = fixture()
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        val response = fixture.api.delayMutation()
        fixture.viewModel.updateOrder(null, "old")
        runCurrent()
        fixture.auth.value = RentalManagerAuthState.Failure("expired")
        runCurrent()
        fixture.api.user = USER.copy(id = ACTOR_B)
        fixture.auth.value = RentalManagerAuthState.SignedIn
        advanceUntilIdle()
        val before = fixture.viewModel.state.value

        response.completeExceptionally(HttpException(Response.error<Any>(401, "{}".toResponseBody())))
        advanceUntilIdle()

        assertThat(fixture.viewModel.state.value).isEqualTo(before)
        assertThat(fixture.viewModel.state.value.session?.user?.id).isEqualTo(ACTOR_B)
        assertThat(fixture.invalidations).isEmpty()
    }

    @Test
    fun `logout closes the command job before remote logout completes`() = runTest {
        val fixture = fixture()
        fixture.viewModel.openOrder(ORDER_A.id)
        advanceUntilIdle()
        val response = fixture.api.delayMutation()
        fixture.viewModel.saveOrder()
        runCurrent()
        val job = requireNotNull(fixture.api.mutationJob)

        fixture.viewModel.logout()
        assertThat(job.isCancelled).isTrue()
        response.complete(ORDER_A.copy(version = 2, status = "SAVED"))
        advanceUntilIdle()

        assertThat(fixture.viewModel.state.value.phase).isEqualTo(RentalManagerPhase.CONNECTING)
        assertThat(fixture.viewModel.state.value.selectedOrder).isNull()
        assertThat(fixture.viewModel.state.value.orders).isEmpty()
        assertThat(fixture.viewModel.state.value.commandRunning).isFalse()
        assertThat(fixture.events).isEmpty()
    }

    private suspend fun TestScope.fixture(): Fixture {
        val api = FakeApi()
        val auth = MutableStateFlow<RentalManagerAuthState>(RentalManagerAuthState.SignedIn)
        val invalidations = mutableListOf<String>()
        val savedState = SavedStateHandle()
        val repository = RentalManagerRepository(api, { invalidations += it }, { "Request failed" })
        val vm = RentalManagerViewModel(
            repository, auth, { _, _ -> }, {}, { invalidations += it }, savedState,
        )
        viewModels += vm
        val events = mutableListOf<RentalManagerNavigationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.collect { events += it }
        }
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(RentalManagerPhase.READY)
        return Fixture(api, auth, repository, vm, savedState, invalidations, events)
    }

    private data class Fixture(
        val api: FakeApi,
        val auth: MutableStateFlow<RentalManagerAuthState>,
        val repository: RentalManagerRepository,
        val viewModel: RentalManagerViewModel,
        val savedState: SavedStateHandle,
        val invalidations: MutableList<String>,
        val events: MutableList<RentalManagerNavigationEvent>,
    )

    private class FakeApi : RentalManagerApi {
        var user = USER
        val orderItems = linkedMapOf(ORDER_A.id to ORDER_A, ORDER_B.id to ORDER_B)
        val paymentReads = mutableListOf<String>()
        val payments = mutableMapOf<String, OrderPaymentDto>()
        var confirmationCalls = 0
        var confirmPayment: suspend () -> OrderPaymentDto = { error("Unexpected confirmation") }
        val keys = mutableListOf<String>()
        var mutationJob: Job? = null
        var mutation: suspend (String, String) -> OrderDto = { _, _ -> error("Unexpected command") }

        fun delayMutation(): CompletableDeferred<OrderDto> {
            val response = CompletableDeferred<OrderDto>()
            mutation = { _, _ ->
                mutationJob = currentCoroutineContext().job
                withContext(NonCancellable) { response.await() }
            }
            return response
        }

        override suspend fun currentUser(): CurrentUserDto = user
        override suspend fun warehouses(): List<WarehouseDto> = emptyList()
        override suspend fun clients(search: String?, page: Int, size: Int) =
            RentalClientPageDto(listOf(CLIENT), 0, 25, 1, 1)
        override suspend fun client(clientId: String): RentalClientDto = CLIENT
        override suspend fun createClient(
            idempotencyKey: String,
            request: CreateRentalClientRequest,
        ): RentalClientDto = error("Unexpected create")
        override suspend fun clientOrders(
            clientId: String, page: Int, size: Int, sort: String, direction: String,
        ) = orderPage()
        override suspend fun orders(
            search: String?, page: Int, size: Int, sort: String, direction: String,
        ) = orderPage()
        override suspend fun order(orderId: String): OrderDto = orderItems.getValue(orderId)
        override suspend fun createOrder(
            idempotencyKey: String, request: CreateOrderRequest,
        ): OrderDto = error("Unexpected create")
        override suspend fun updateOrder(
            orderId: String, idempotencyKey: String, request: UpdateOrderRequest,
        ): OrderDto = mutate(orderId, idempotencyKey)
        override suspend fun cancelOrder(
            orderId: String, expectedVersion: Long, idempotencyKey: String,
        ): OrderDto = mutate(orderId, idempotencyKey)
        override suspend fun saveOrder(
            orderId: String, expectedVersion: Long, idempotencyKey: String,
        ): OrderDto = mutate(orderId, idempotencyKey)
        override suspend fun orderPayment(orderId: String): OrderPaymentDto {
            paymentReads += orderId
            return payments[orderId]
                ?: OrderPaymentDto(orderId, 1, "DRAFT", null, null, null, null, null, NOW, false, null)
        }
        override suspend fun confirmOrderPayment(
            orderId: String, idempotencyKey: String, request: ConfirmOrderPaymentRequest,
        ): OrderPaymentDto {
            confirmationCalls += 1
            return confirmPayment()
        }
        override suspend fun clientPresentation(
            inquiryId: String,
        ): Response<RentalPresentationDto> = error("Unexpected presentation")
        override suspend fun publishClientPresentation(
            inquiryId: String, idempotencyKey: String, request: PublishRentalPresentationRequest,
        ): RentalPresentationDto = error("Unexpected presentation")

        private suspend fun mutate(orderId: String, key: String): OrderDto {
            keys += key
            return mutation(orderId, key)
        }

        private fun orderPage() = OrderPageDto(orderItems.values.toList(), 0, 25, 2, 1)
    }

    private companion object {
        const val ACTOR_A = "00000000-0000-0000-0000-000000000001"
        const val ACTOR_B = "00000000-0000-0000-0000-000000000002"
        const val NOW = "2026-09-06T12:00:00Z"
        val USER = CurrentUserDto(ACTOR_A, "manager", "Manager", "USER", "RENTAL_MANAGER", true, false)
        val CLIENT = RentalClientDto(
            "00000000-0000-0000-0000-000000000010", 1, "INDIVIDUAL", "Client",
            responsibleManagerId = ACTOR_A,
            updatedAt = NOW,
        )
        val ORDER_A = OrderDto(
            "00000000-0000-0000-0000-000000000020", 1, "A", "DRAFT", CLIENT, ACTOR_A, "Manager",
            unitCount = 1,
            updatedAt = NOW,
            permissions = OrderPermissionsDto(true, false, false, false),
        )
        val ORDER_B = ORDER_A.copy(id = "00000000-0000-0000-0000-000000000021", number = "B")
        val PENDING_PAYMENT = OrderPaymentDto(
            ORDER_A.id, 1, "SAVED", "PENDING", NOW, "2026-09-06T12:05:00Z", null, null, NOW, true,
            OrderPaymentReceiptDto(
                1, ORDER_A.id, ORDER_A.number, NOW, "RUB", false,
                listOf(
                    OrderPaymentReceiptLineDto(
                        "CABIN", "00000000-0000-0000-0000-000000000030", null, "Cabin",
                        "1", 1, "100", "100", 1,
                    ),
                ),
                "100",
            ),
        )
    }
}
