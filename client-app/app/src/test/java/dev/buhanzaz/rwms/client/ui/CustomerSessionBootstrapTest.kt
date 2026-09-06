package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.auth.CustomerAuthConfiguration
import dev.buhanzaz.rwms.client.auth.CustomerAuthRepository
import dev.buhanzaz.rwms.client.auth.CustomerAuthState
import dev.buhanzaz.rwms.client.auth.EncryptedCustomerSessionStore
import dev.buhanzaz.rwms.client.data.CustomerApi
import dev.buhanzaz.rwms.client.data.CustomerApiException
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerCart
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CabinFacets
import dev.buhanzaz.rwms.client.data.CabinPage
import dev.buhanzaz.rwms.client.data.CheckoutRequest
import dev.buhanzaz.rwms.client.data.CustomerNotification
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerRepository
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import dev.buhanzaz.rwms.client.data.CustomerWorkflowStore
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.DeliverySlotKind
import dev.buhanzaz.rwms.client.data.HeldDeliverySlot
import dev.buhanzaz.rwms.client.data.InquirySession
import dev.buhanzaz.rwms.client.notifications.CustomerNotifications
import java.lang.reflect.Proxy
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises real native login around a deliberately non-cancellable old customer API read. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerSessionBootstrapTest {
    private val dispatcher = StandardTestDispatcher()
    private val context: Application = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    private val server = MockWebServer()
    private val viewModels = ViewModelStore()
    private val completedBootstraps = Channel<Unit>(Channel.UNLIMITED)
    private lateinit var auth: CustomerAuthRepository
    private lateinit var client: OkHttpClient
    private lateinit var workflowStore: CustomerWorkflowStore
    private var profileCalls = 0
    private var holdNextBookings = false
    private var heldBookings: Continuation<List<CustomerBooking>>? = null
    private var currentProfile = profile("first-customer")
    private var currentWarehouse = warehouse("first-warehouse")
    private var checkoutFixture: CheckoutFixture? = null
    private val checkoutCalls = mutableListOf<CheckoutCall>()
    private var checkoutCompleted = false

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        EncryptedCustomerSessionStore(context, json).clear()
        workflowStore = CustomerWorkflowStore(context, json)
        workflowStore.clear()
        checkoutFixture = null
        checkoutCalls.clear()
        checkoutCompleted = false
        val configuration = CustomerAuthConfiguration()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/auth/api/auth/csrf" -> jsonResponse("""{"parameterName":"_csrf","token":"test-csrf"}""")
                "/auth/login" -> MockResponse().setResponseCode(302)
                    .setHeader("Location", configuration.publicOrigin.toString())
                "/auth/oauth2/authorize" -> {
                    val state = requireNotNull(request.requestUrl?.queryParameter("state"))
                    assertThat(request.requestUrl?.queryParameter("code_challenge_method")).isEqualTo("S256")
                    MockResponse().setResponseCode(302)
                        .setHeader("Location", "${configuration.redirectUri}?code=test-code&state=$state")
                }
                "/auth/oauth2/token" -> jsonResponse("""{"access_token":"test-access-token","expires_in":3600}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
            val original = chain.request()
            // Keep production origin/redirect checks while routing every test exchange locally.
            val local = server.url("/").newBuilder()
                .encodedPath(original.url.encodedPath).encodedQuery(original.url.encodedQuery).build()
            chain.proceed(original.newBuilder().url(local).build()).newBuilder().request(original).build()
        }.build()
        auth = CustomerAuthRepository(context, configuration, client, json)
        auth.state.first { it is CustomerAuthState.SignedOut }
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        heldBookings?.resumeWith(Result.success(emptyList()))
        heldBookings = null
        viewModels.clear()
        auth.logout()
        workflowStore.clear()
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `new login bootstraps its own profile after old polling success releases the gate`() =
        exerciseOldPollingCompletion(failed = false)

    @Test
    fun `old polling failure cannot invalidate or finish the replacement session bootstrap`() =
        exerciseOldPollingCompletion(failed = true)

    @Test
    fun `checkout requires a held slot before it calls the repository`() = runTest(dispatcher) {
        checkoutFixture()
        val viewModel = signedInCheckoutViewModel()

        viewModel.checkout()
        runCurrent()

        assertThat(checkoutCalls).isEmpty()
        assertThat((viewModel.state.value as CustomerAppState.Ready).workflow.error)
            .contains("подтвердите выбранный слот")
    }

    @Test
    fun `checkout submits the exact held cart and slot versions then accepts the booking`() = runTest(dispatcher) {
        val fixture = checkoutFixture()
        val viewModel = signedInCheckoutViewModel()
        holdSlot(viewModel)

        viewModel.checkout()
        advanceUntilIdle()

        assertThat(checkoutCalls).containsExactly(
            CheckoutCall(fixture.inquiryId, fixture.cart.version, fixture.slot.slotId, fixture.slot.version),
        )
        val workflow = (viewModel.state.value as CustomerAppState.Ready).workflow
        assertThat(workflow.booking).isEqualTo(fixture.checkoutBooking)
        assertThat(workflow.bookings).containsExactly(fixture.checkoutBooking)
        assertThat(workflow.heldSlot).isNotNull()
        assertThat(workflow.error).isNull()
    }

    @Test
    fun `rejected checkout clears the local slot selection`() = runTest(dispatcher) {
        val fixture = checkoutFixture(status = "REJECTED")
        val viewModel = signedInCheckoutViewModel()
        holdSlot(viewModel)

        viewModel.checkout()
        advanceUntilIdle()

        val workflow = (viewModel.state.value as CustomerAppState.Ready).workflow
        assertThat(checkoutCalls).hasSize(1)
        assertThat(workflow.booking).isEqualTo(fixture.checkoutBooking)
        assertThat(workflow.heldSlot).isNull()
        assertThat(workflow.selectedSlotId).isNull()
        assertThat(workflow.slots).isEmpty()
        assertThat(workflow.slotSearchCompleted).isFalse()
    }

    @Test
    fun `checkout transport failure preserves the held selection and does not accept a booking`() = runTest(dispatcher) {
        checkoutFixture(failure = IOException("response lost"))
        val viewModel = signedInCheckoutViewModel()
        holdSlot(viewModel)

        viewModel.checkout()
        advanceUntilIdle()

        val workflow = (viewModel.state.value as CustomerAppState.Ready).workflow
        assertThat(checkoutCalls).hasSize(1)
        assertThat(workflow.booking).isNull()
        assertThat(workflow.heldSlot).isNotNull()
        assertThat(workflow.error).isNotNull()
    }

    private fun exerciseOldPollingCompletion(failed: Boolean) = runTest(dispatcher, timeout = 30.seconds) {
        val api = customerApi()
        val repository = CustomerRepository(context, api, json, workflowStore)
        val viewModel = CustomerAppViewModel(
            auth, repository, workflowStore, CustomerNotifications(context, auth, repository),
        )
        viewModels.put("customer", viewModel)
        auth.login("first-customer", "test-password", rememberMe = false)
        assertThat(auth.state.value).isEqualTo(CustomerAuthState.SignedIn)
        completedBootstraps.receive()
        runCurrent()
        assertThat((viewModel.state.value as CustomerAppState.Ready).workflow.profile?.id)
            .isEqualTo("first-customer")
        assertThat(profileCalls).isEqualTo(1)

        holdNextBookings = true
        viewModel.refreshCustomerUpdates()
        runCurrent()
        assertThat(heldBookings).isNotNull()
        val oldMarker = auth.notificationSession()
        auth.logout()
        runCurrent()
        val replacementUsername = if (failed) "replacement-customer" else "first-customer"
        currentProfile = profile(replacementUsername).copy(version = 2)
        currentWarehouse = warehouse("replacement-warehouse")
        auth.login(replacementUsername, "test-password", rememberMe = false)
        runCurrent()
        assertThat(auth.notificationSession()).isNotEqualTo(oldMarker)
        assertThat(auth.state.value).isEqualTo(CustomerAuthState.SignedIn)
        assertThat(profileCalls).isEqualTo(1)
        // A missing profile must not be shown as a new-account decision while cleanup is pending.
        assertThat(viewModel.state.value).isEqualTo(CustomerAppState.Loading)

        val previous = requireNotNull(heldBookings)
        heldBookings = null
        previous.resumeWith(
            if (failed) Result.failure(CustomerApiException(401, "Old session rejected"))
            else Result.success(listOf(CustomerBooking(
                bookingId = "old-booking", status = "PENDING", inquiryId = "old-inquiry", warehouseId = "old-warehouse",
            ))),
        )
        completedBootstraps.receive()
        runCurrent()
        val workflow = (viewModel.state.value as CustomerAppState.Ready).workflow
        assertThat(profileCalls).isEqualTo(2)
        assertThat(workflow.profile).isEqualTo(currentProfile)
        assertThat(workflow.warehouses).containsExactly(currentWarehouse)
        assertThat(workflow.bootstrapping).isFalse()
        assertThat(workflow.busy).isFalse()
        assertThat(workflow.error).isNull()
        assertThat(workflow.bookings).isEmpty()
        assertThat(auth.state.value).isEqualTo(CustomerAuthState.SignedIn)

        // Polling remains usable after both generations have completed their cleanup.
        viewModel.refreshCustomerUpdates()
        completedBootstraps.receive()
        runCurrent()
        assertThat((viewModel.state.value as CustomerAppState.Ready).workflow.profile).isEqualTo(currentProfile)
        viewModels.clear()
        runCurrent()
    }

    private fun customerApi(): CustomerApi = Proxy.newProxyInstance(
        CustomerApi::class.java.classLoader, arrayOf(CustomerApi::class.java),
    ) { _, method, args ->
        when (method.name) {
            "profile" -> currentProfile.also { profileCalls += 1 }
            "warehouses" -> listOf(currentWarehouse)
            "inquiry" -> checkoutFixture?.session ?: error("Unexpected inquiry read")
            "cart" -> checkoutFixture?.cart ?: error("Unexpected cart read")
            "facets" -> CabinFacets()
            "cabins" -> checkoutFixture?.let { CabinPage(content = listOf(it.cabin), totalElements = 1, totalPages = 1) }
                ?: error("Unexpected cabins read")
            "equipment" -> emptyList<Any>()
            "searchSlots" -> checkoutFixture?.let { listOf(it.slot) } ?: error("Unexpected slot search")
            "holdSlot" -> checkoutFixture?.let { HeldDeliverySlot(it.cart.version, it.slot) }
                ?: error("Unexpected slot hold")
            "checkout" -> {
                val fixture = requireNotNull(checkoutFixture)
                val request = requireNotNull(args)[2] as CheckoutRequest
                checkoutCalls += CheckoutCall(args[0] as String, request.expectedVersion, request.slotId, request.slotVersion)
                fixture.failure?.let { throw it }
                checkoutCompleted = true
                fixture.checkoutBooking
            }
            "bookings" -> if (holdNextBookings) {
                holdNextBookings = false
                @Suppress("UNCHECKED_CAST")
                val continuation = requireNotNull(args).last() as Continuation<List<CustomerBooking>>
                heldBookings = continuation
                COROUTINE_SUSPENDED
            } else checkoutFixture?.takeIf { checkoutCompleted }?.let { listOf(it.checkoutBooking) }
                ?: emptyList<CustomerBooking>()
            "notifications" -> emptyList<CustomerNotification>().also {
                completedBootstraps.trySend(Unit)
            }
            else -> error("Unexpected customer API operation: ${method.name}")
        }
    } as CustomerApi

    private fun jsonResponse(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun profile(id: String) = CustomerProfile(
        id = id, version = 1, entityType = CustomerEntityType.INDIVIDUAL,
        firstName = id, phone = "+79990000000",
    )

    private fun warehouse(id: String) = CustomerWarehouse(
        id = id, name = id, timezone = "Europe/Moscow", depotLatitude = 59.93, depotLongitude = 30.32,
    )

    private suspend fun TestScope.signedInCheckoutViewModel(): CustomerAppViewModel {
        val fixture = requireNotNull(checkoutFixture)
        currentProfile = profile("checkout-customer")
        currentWarehouse = warehouse(fixture.warehouseId)
        val reference = workflowStore.begin(fixture.warehouseId, rememberWarehouse = true)
        workflowStore.bind(reference, fixture.session)
        val repository = CustomerRepository(context, customerApi(), json, workflowStore)
        val viewModel = CustomerAppViewModel(auth, repository, workflowStore, CustomerNotifications(context, auth, repository))
        viewModels.put("checkout", viewModel)
        auth.login("checkout-customer", "test-password", rememberMe = false)
        completedBootstraps.receive()
        runCurrent()
        return viewModel
    }

    private fun holdSlot(viewModel: CustomerAppViewModel) {
        viewModel.confirmDeliveryLocation("Невский проспект, 1", 59.93, 30.31)
        viewModel.setPrivateSiteAccessConfirmed(true)
        viewModel.setFailedTripChargeAcknowledged(true)
        viewModel.searchSlots()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectSlot(requireNotNull(checkoutFixture).slot.slotId)
        viewModel.holdSelectedSlot()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun checkoutFixture(status: String = "PENDING", failure: Throwable? = null): CheckoutFixture {
        val warehouseId = UUID.randomUUID().toString()
        val inquiryId = UUID.randomUUID().toString()
        val cabin = CustomerCabin("unit", 4, "BT-1", 2, 12_000)
        val cart = CustomerCart(inquiryId, warehouseId, 11, "ACTIVE", cabins = listOf(cabin))
        val slot = DeliverySlot(
            slotId = "slot", version = 7, date = "2026-09-08", kind = DeliverySlotKind.FIXED_WINDOW,
            start = "10:00", end = "12:00", travelZoneHours = 1, capacityRemaining = 1,
            priceIsochroneMinutes = 30, siteCabinCapacity = 1, roadRouteConfirmed = true,
            privateSiteAccessConfirmed = true, failedTripChargeAcknowledged = true,
            routeProfile = dev.buhanzaz.rwms.client.data.CustomerRouteProfile(4.0, 2.5, 12.0, 12.0, 6.0, 4),
            expiresAt = "2026-09-07T10:00:00Z", state = "AVAILABLE",
        )
        return CheckoutFixture(
            warehouseId, inquiryId, InquirySession(inquiryId, warehouseId, cart.version, "ACTIVE"), cart, cabin, slot,
            CustomerBooking("booking", 3, null, status, inquiryId = inquiryId, slotId = slot.slotId, warehouseId = warehouseId),
            failure,
        ).also { checkoutFixture = it }
    }

    private data class CheckoutFixture(
        val warehouseId: String,
        val inquiryId: String,
        val session: InquirySession,
        val cart: CustomerCart,
        val cabin: CustomerCabin,
        val slot: DeliverySlot,
        val checkoutBooking: CustomerBooking,
        val failure: Throwable?,
    )

    private data class CheckoutCall(val inquiryId: String, val cartVersion: Long, val slotId: String, val slotVersion: Long)
}
