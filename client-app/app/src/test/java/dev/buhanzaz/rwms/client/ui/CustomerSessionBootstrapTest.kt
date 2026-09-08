package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.auth.CustomerAuthConfiguration
import dev.buhanzaz.rwms.client.auth.CustomerAuthRepository
import dev.buhanzaz.rwms.client.auth.CustomerSessionCipher
import dev.buhanzaz.rwms.client.auth.CustomerAuthState
import dev.buhanzaz.rwms.client.auth.EncryptedCustomerSessionStore
import dev.buhanzaz.rwms.client.auth.PendingCustomerRegistration
import dev.buhanzaz.rwms.client.data.BookingChangeApplicationState
import dev.buhanzaz.rwms.client.data.BookingChangeOperation
import dev.buhanzaz.rwms.client.data.BookingChangeSettlement
import dev.buhanzaz.rwms.client.data.CustomerApi
import dev.buhanzaz.rwms.client.data.CustomerApiException
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.CustomerBookingChangeQuote
import dev.buhanzaz.rwms.client.data.CustomerBookingChangeReference
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerCart
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CabinFacets
import dev.buhanzaz.rwms.client.data.CabinFilters
import dev.buhanzaz.rwms.client.data.CabinPage
import dev.buhanzaz.rwms.client.data.CheckoutRequest
import dev.buhanzaz.rwms.client.data.CustomerNotification
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerRepository
import dev.buhanzaz.rwms.client.data.PublicCustomerCatalogApi
import dev.buhanzaz.rwms.client.data.PublicCustomerCatalogRepository
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import dev.buhanzaz.rwms.client.data.CustomerWorkflowStore
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.DeliverySlotKind
import dev.buhanzaz.rwms.client.data.HeldDeliverySlot
import dev.buhanzaz.rwms.client.data.InquirySession
import dev.buhanzaz.rwms.client.notifications.CustomerNotifications
import java.lang.reflect.Proxy
import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

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
    private lateinit var sessionStore: EncryptedCustomerSessionStore
    private lateinit var client: OkHttpClient
    private lateinit var workflowStore: CustomerWorkflowStore
    private var profileCalls = 0
    private var holdNextBookings = false
    private var heldBookings: Continuation<List<CustomerBooking>>? = null
    private var holdNextCabins = false
    private var heldCabins: Continuation<CabinPage>? = null
    private val cabinTypeQueries = mutableListOf<Pair<String, String?>>()
    private var currentProfile = profile("first-customer")
    private var currentWarehouse = warehouse("first-warehouse")
    private var checkoutFixture: CheckoutFixture? = null
    private val checkoutCalls = mutableListOf<CheckoutCall>()
    private var checkoutCompleted = false
    private var restoredChange: CustomerBookingChangeQuote? = null
    private var bookingChangeReadFailure: CustomerApiException? = null
    private var bookingsReadFailure: CustomerApiException? = null
    private val changeReads = mutableListOf<Pair<String, String>>()
    private val publicReads = mutableListOf<String>()
    private var holdNextPublicCabins = false
    private var heldPublicCabins: Continuation<CabinPage>? = null
    private var publicCabinFailure: CustomerApiException? = null
    private var registrationRequest: RecordedRequest? = null
    private var registrationResponseSubjectId = CUSTOMER_SUBJECT_ID
    private var registrationResponseUsername = "client_01"
    private var tokenSubjectId = CUSTOMER_SUBJECT_ID
    private var tokenUsername = "client_01"
    private val profileReadResults = ArrayDeque<Result<CustomerProfile?>>()
    private val createdProfiles = mutableListOf<CustomerProfile>()
    private var createProfileFailure: Throwable? = null

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        sessionStore = EncryptedCustomerSessionStore(
            context,
            json,
            CustomerSessionCipher(SecretKeySpec(ByteArray(32) { 0x2A }, "AES")),
        )
        sessionStore.clear()
        workflowStore = CustomerWorkflowStore(context, json)
        workflowStore.clear()
        checkoutFixture = null
        checkoutCalls.clear()
        checkoutCompleted = false
        registrationRequest = null
        registrationResponseSubjectId = CUSTOMER_SUBJECT_ID
        registrationResponseUsername = "client_01"
        tokenSubjectId = CUSTOMER_SUBJECT_ID
        tokenUsername = "client_01"
        profileReadResults.clear()
        createdProfiles.clear()
        createProfileFailure = null
        val configuration = CustomerAuthConfiguration()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/auth/api/auth/csrf" -> jsonResponse("""{"parameterName":"_csrf","token":"test-csrf"}""")
                "/auth/api/customer/v1/registrations" -> {
                    registrationRequest = request
                    jsonResponse(
                        """{"subjectId":"$registrationResponseSubjectId","username":"$registrationResponseUsername"}""",
                    )
                        .setResponseCode(201)
                }
                "/auth/login" -> MockResponse().setResponseCode(302)
                    .setHeader("Location", configuration.publicOrigin.toString())
                "/auth/oauth2/authorize" -> {
                    val state = requireNotNull(request.requestUrl?.queryParameter("state"))
                    assertThat(request.requestUrl?.queryParameter("code_challenge_method")).isEqualTo("S256")
                    MockResponse().setResponseCode(302)
                        .setHeader("Location", "${configuration.redirectUri}?code=test-code&state=$state")
                }
                "/auth/oauth2/token" -> jsonResponse(
                    """{"access_token":"${customerJwt(tokenSubjectId, tokenUsername)}","expires_in":3600}""",
                )
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
        auth = CustomerAuthRepository(configuration, client, json, sessionStore)
        auth.state.first { it is CustomerAuthState.SignedOut }
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        heldPublicCabins?.resumeWith(Result.success(CabinPage()))
        heldPublicCabins = null
        heldBookings?.resumeWith(Result.success(emptyList()))
        heldBookings = null
        heldCabins?.resumeWith(Result.success(CabinPage()))
        heldCabins = null
        viewModels.clear()
        auth.pendingRegistration()?.let { pending -> sessionStore.forgetPendingRegistration(pending) }
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
    fun `registration sends the CSRF protected contract and continues through PKCE login`() = runBlocking {
        auth.register(
            username = "client_01",
            email = "client@example.test",
            password = "password-123",
            confirmation = "password-123",
            phone = "+79990000000",
            firstName = "Иван",
            lastName = "Петров",
        )

        val request = requireNotNull(registrationRequest)
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.getHeader("X-CSRF-TOKEN")).isEqualTo("test-csrf")
        assertThat(request.body.readUtf8()).isEqualTo(
            """{"username":"client_01","password":"password-123","passwordConfirmation":"password-123"}""",
        )
        assertThat(server.requestCount).isEqualTo(6)
        assertThat(auth.state.value).isEqualTo(CustomerAuthState.SignedIn)
        assertThat(auth.pendingRegistration()).isEqualTo(
            PendingCustomerRegistration(
                subjectId = CUSTOMER_SUBJECT_ID,
                username = "client_01",
                firstName = "Иван",
                lastName = "Петров",
                email = "client@example.test",
                phone = "+79990000000",
            ),
        )
    }

    @Test
    fun `confirmed registration creates its individual profile before the catalog opens`() = runTest(dispatcher) {
        profileReadResults.add(Result.success(null))
        val viewModel = registrationViewModel()
        runCurrent()

        registerCustomer()
        advanceUntilIdle()

        val expected = CustomerProfile(
            entityType = CustomerEntityType.INDIVIDUAL,
            firstName = "Иван",
            lastName = "Петров",
            phone = "+79990000000",
            email = "client@example.test",
        )
        val workflow = readyWorkflow(viewModel)
        assertThat(createdProfiles).containsExactly(expected)
        assertThat(workflow.profile).isEqualTo(expected.copy(id = "created-profile", version = 1))
        assertThat(workflow.registrationPending).isFalse()
        assertThat(workflow.registrationError).isNull()
        assertThat(auth.pendingRegistration()).isNull()
    }

    @Test
    fun `lost profile creation response reconciles its existing profile without a second create`() = runTest(dispatcher) {
        val reconciled = CustomerProfile(
            id = "reconciled-profile",
            version = 7,
            entityType = CustomerEntityType.INDIVIDUAL,
            firstName = "Иван",
            lastName = "Петров",
            phone = "+79990000000",
            email = "client@example.test",
        )
        profileReadResults.add(Result.success(null))
        profileReadResults.add(Result.success(reconciled))
        createProfileFailure = IOException("create response lost")
        val viewModel = registrationViewModel()
        runCurrent()

        registerCustomer()
        advanceUntilIdle()

        assertThat(createdProfiles).containsExactly(
            reconciled.copy(id = null, version = null),
        )
        assertThat(readyWorkflow(viewModel).profile).isEqualTo(reconciled)
        assertThat(auth.pendingRegistration()).isNull()
    }

    @Test
    fun `failed profile creation retries the confirmed draft without registering again`() = runTest(dispatcher) {
        profileReadResults.add(Result.success(null))
        profileReadResults.add(Result.success(null))
        createProfileFailure = CustomerApiException(503, "Профиль временно не сохранён")
        val viewModel = registrationViewModel()
        runCurrent()

        registerCustomer()
        advanceUntilIdle()

        val expected = CustomerProfile(
            entityType = CustomerEntityType.INDIVIDUAL,
            firstName = "Иван",
            lastName = "Петров",
            phone = "+79990000000",
            email = "client@example.test",
        )
        assertThat(readyWorkflow(viewModel).registrationPending).isTrue()
        assertThat(readyWorkflow(viewModel).registrationError).isEqualTo("Профиль временно не сохранён")
        assertThat(createdProfiles).containsExactly(expected)

        createProfileFailure = null
        profileReadResults.add(Result.success(null))
        viewModel.retryRegistrationProfile()
        advanceUntilIdle()

        assertThat(createdProfiles).containsExactly(expected, expected)
        assertThat(readyWorkflow(viewModel).profile).isEqualTo(expected.copy(id = "created-profile", version = 1))
        assertThat(readyWorkflow(viewModel).registrationPending).isFalse()
        assertThat(readyWorkflow(viewModel).registrationError).isNull()
        assertThat(auth.pendingRegistration()).isNull()
        assertThat(server.requestCount).isEqualTo(6)
    }

    @Test
    fun `encrypted confirmed draft survives restoration but a different JWT subject cannot provision it`() = runTest(dispatcher) {
        val pending = PendingCustomerRegistration(
            subjectId = CUSTOMER_SUBJECT_ID,
            username = "client_01",
            firstName = "Иван",
            lastName = "Петров",
            email = "client@example.test",
            phone = "+79990000000",
        )
        sessionStore.rememberPendingRegistration(pending)
        val restoredStore = EncryptedCustomerSessionStore(
            context,
            json,
            CustomerSessionCipher(SecretKeySpec(ByteArray(32) { 0x2A }, "AES")),
        )
        assertThat(restoredStore.pendingRegistration(" CLIENT_01 ")).isEqualTo(pending)
        auth.login("client_01", "test-password", rememberMe = true)
        auth.logout()
        assertThat(restoredStore.pendingRegistration("client_01")).isEqualTo(pending)
        tokenSubjectId = "22222222-2222-4222-8222-222222222222"
        profileReadResults.add(Result.success(null))
        val viewModel = registrationViewModel()
        runCurrent()

        try {
            auth.login("client_01", "test-password", rememberMe = true)
            advanceUntilIdle()

            assertThat(auth.state.value).isEqualTo(CustomerAuthState.SignedIn)
            assertThat(auth.pendingRegistration()).isNull()
            assertThat(createdProfiles).isEmpty()
            assertThat(readyWorkflow(viewModel).registrationError)
                .isEqualTo("Профиль клиента не найден. Обратитесь в поддержку.")
        } finally {
            sessionStore.forgetPendingRegistration(pending)
        }
    }

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

    @Test
    fun `latest filters wait behind polling and supersede an unfinished filter response`() = runTest(dispatcher) {
        val fixture = checkoutFixture()
        val viewModel = signedInCheckoutViewModel()
        cabinTypeQueries.clear()
        holdNextBookings = true
        viewModel.refreshCustomerUpdates()
        runCurrent()
        assertThat(heldBookings).isNotNull()

        viewModel.applyFilters(CabinFilters(cabinType = "БК-1"))
        viewModel.applyFilters(CabinFilters(cabinType = "БК-2"))
        assertThat(cabinTypeQueries).isEmpty()
        holdNextCabins = true
        val polling = requireNotNull(heldBookings)
        heldBookings = null
        polling.resumeWith(Result.success(emptyList()))
        runCurrent()
        assertThat(cabinTypeQueries).containsExactly(fixture.inquiryId to "БК-2")

        val latest = CabinFilters(cabinType = "БК-3")
        viewModel.applyFilters(latest)
        holdNextCabins = true
        val superseded = requireNotNull(heldCabins)
        heldCabins = null
        superseded.resumeWith(Result.success(CabinPage(content = listOf(fixture.cabin.copy(unitId = "obsolete")))))
        runCurrent()
        val waiting = (viewModel.state.value as CustomerAppState.Ready).workflow
        assertThat(waiting.filters).isEqualTo(CabinFilters())
        assertThat(waiting.cabins).containsExactly(fixture.cabin)
        assertThat(cabinTypeQueries).containsExactly(
            fixture.inquiryId to "БК-2",
            fixture.inquiryId to "БК-3",
        ).inOrder()

        val latestRead = requireNotNull(heldCabins)
        heldCabins = null
        val latestCabin = fixture.cabin.copy(unitId = "latest")
        latestRead.resumeWith(Result.success(CabinPage(content = listOf(latestCabin))))
        runCurrent()
        val completed = (viewModel.state.value as CustomerAppState.Ready).workflow
        assertThat(completed.filters).isEqualTo(latest)
        assertThat(completed.cabins).containsExactly(latestCabin)
        assertThat(completed.busy).isFalse()
    }

    @Test
    fun `bootstrap keeps expired offered terms instead of creating a replacement quote`() = runTest(dispatcher) {
        checkoutFixture()
        restoredChange = changeQuote().copy(expiresAt = "2020-01-01T00:00:00Z")
        val viewModel = signedInCheckoutViewModel()
        val workflow = (viewModel.state.value as CustomerAppState.Ready).workflow
        assertThat(workflow.bookingChangeQuote).isEqualTo(restoredChange)
        assertThat(workflow.bookingChangeUnavailableReason).contains("Срок действия условий истёк")
        assertThat(changeReads).containsExactly("booking" to "quote")
        assertThat(workflow.error).isNull()
    }

    @Test
    fun `closed pending change recovers through exact GET when the booking list fails`() = runTest(dispatcher) {
        checkoutFixture()
        restoredChange = changeQuote().copy(applicationState = BookingChangeApplicationState.APPLYING)
        val viewModel = signedInCheckoutViewModel()
        viewModel.dismissBookingChange()
        // DataStore uses real IO; draining virtual time alone does not release the command lane.
        viewModel.state.first { it is CustomerAppState.Ready && !it.workflow.busy }
        runCurrent()
        changeReads.clear()
        restoredChange = requireNotNull(restoredChange).copy(
            version = 2,
            applicationState = BookingChangeApplicationState.APPLIED,
            settlement = BookingChangeSettlement.TEST_PAID,
        )
        bookingsReadFailure = CustomerApiException(503, "Заказы временно недоступны")
        viewModel.resumeAutomaticUpdates()
        viewModel.refreshCustomerUpdates()
        viewModel.state.first { state ->
            state is CustomerAppState.Ready &&
                state.workflow.bookingChangeQuote?.applicationState == BookingChangeApplicationState.APPLIED
        }
        runCurrent()
        val workflow = (viewModel.state.value as CustomerAppState.Ready).workflow
        assertThat(changeReads).containsExactly("booking" to "quote")
        assertThat(workflow.bookingChangeDialogVisible).isFalse()
        assertThat(workflow.bookingChangeQuote).isEqualTo(restoredChange)
        assertThat(workflow.updatesError).isEqualTo("Заказы временно недоступны")
    }

    @Test
    fun `automatic reads recover a saved reference after the first exact GET fails`() = runTest(dispatcher) {
        checkoutFixture()
        restoredChange = changeQuote().copy(applicationState = BookingChangeApplicationState.APPLYING)
        bookingChangeReadFailure = CustomerApiException(503, "Статус временно недоступен")
        val viewModel = signedInCheckoutViewModel()
        val initial = (viewModel.state.value as CustomerAppState.Ready).workflow
        assertThat(initial.bookingChangeQuote).isNull()
        assertThat(initial.bookingChangeReferences).containsExactly("booking", "quote")
        changeReads.clear()
        bookingChangeReadFailure = null
        viewModel.resumeAutomaticUpdates()
        viewModel.refreshCustomerUpdates()
        viewModel.state.first { it is CustomerAppState.Ready && it.workflow.bookingChangeQuote != null }
        runCurrent()
        val workflow = (viewModel.state.value as CustomerAppState.Ready).workflow
        assertThat(changeReads).containsExactly("booking" to "quote")
        assertThat(workflow.bookingChangeQuote).isEqualTo(restoredChange)
        assertThat(workflow.bookingChangeDialogVisible).isFalse()
        assertThat(workflow.updatesError).isNull()
    }

    @Test
    fun `failed exact refresh preserves an already applied result without awaiting confirmation`() = runTest(dispatcher) {
        checkoutFixture()
        restoredChange = changeQuote().copy(
            applicationState = BookingChangeApplicationState.APPLIED,
            settlement = BookingChangeSettlement.TEST_PAID,
        )
        val viewModel = signedInCheckoutViewModel()
        changeReads.clear()
        bookingChangeReadFailure = CustomerApiException(503, "Статус временно недоступен")
        viewModel.refreshBookingChange("booking")
        viewModel.state.first { it is CustomerAppState.Ready && it.workflow.bookingChangeReadError != null }
        runCurrent()
        val workflow = (viewModel.state.value as CustomerAppState.Ready).workflow
        assertThat(changeReads).containsExactly("booking" to "quote")
        assertThat(workflow.bookingChangeQuote).isEqualTo(restoredChange)
        assertThat(workflow.bookingChangeNeedsRefresh).isFalse()
        assertThat(workflow.bookingChangeReadError).isEqualTo("Статус временно недоступен")
    }

    @Test
    fun `guest browses and paginates without customer identity inquiry or auth requests`() = runTest(dispatcher) {
        val viewModel = guestViewModel()
        viewModel.continueAsGuest()
        advanceUntilIdle()
        val city = guestState(viewModel).warehouses.first()
        viewModel.selectGuestWarehouse(city)
        advanceUntilIdle()
        assertThat(guestState(viewModel).cabins).hasSize(1)
        viewModel.loadMoreGuestCabins()
        advanceUntilIdle()
        assertThat(guestState(viewModel).cabins).hasSize(2)
        assertThat(guestState(viewModel).cabinPage).isEqualTo(1)
        viewModel.toggleCabin("first-warehouse--0")
        viewModel.checkout()
        runCurrent()
        assertThat(auth.state.value).isInstanceOf(CustomerAuthState.SignedOut::class.java)
        assertThat(workflowStore.read()).isNull()
        assertThat(profileCalls).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(publicReads).containsExactly(
            "warehouses", "facets:first-warehouse", "cabins:first-warehouse::0", "cabins:first-warehouse::1",
        ).inOrder()
    }

    @Test
    fun `late guest page cannot replace the newly selected city`() = runTest(dispatcher) {
        val viewModel = guestViewModel()
        viewModel.continueAsGuest()
        advanceUntilIdle()
        val cities = guestState(viewModel).warehouses
        holdNextPublicCabins = true
        viewModel.selectGuestWarehouse(cities.first())
        runCurrent()
        assertThat(heldPublicCabins).isNotNull()
        viewModel.selectGuestWarehouse(cities.last())
        runCurrent()
        completeHeldPublicPage()
        runCurrent()
        assertThat(guestState(viewModel).selectedWarehouse?.id).isEqualTo("second-warehouse")
        assertThat(guestState(viewModel).cabins.single().unitId).isEqualTo("second-warehouse--0")
    }

    @Test
    fun `late guest page cannot replace newer filters`() = runTest(dispatcher) {
        val viewModel = guestViewModel()
        viewModel.continueAsGuest()
        advanceUntilIdle()
        holdNextPublicCabins = true
        viewModel.selectGuestWarehouse(guestState(viewModel).warehouses.first())
        runCurrent()
        viewModel.applyGuestFilters(CabinFilters(cabinType = "Офисная"))
        runCurrent()
        completeHeldPublicPage()
        runCurrent()
        assertThat(guestState(viewModel).filters.cabinType).isEqualTo("Офисная")
        assertThat(guestState(viewModel).cabins.single().unitId).isEqualTo("first-warehouse-Офисная-0")
    }

    @Test
    fun `leaving the guest catalog fences an unfinished read`() = runTest(dispatcher) {
        val viewModel = guestViewModel()
        viewModel.continueAsGuest()
        advanceUntilIdle()
        holdNextPublicCabins = true
        viewModel.selectGuestWarehouse(guestState(viewModel).warehouses.first())
        runCurrent()
        viewModel.leaveGuestCatalog()
        completeHeldPublicPage()
        runCurrent()
        assertThat(viewModel.state.value).isEqualTo(CustomerAppState.SignedOut())
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `guest login opens login and old public completion cannot replace the customer session`() = runTest(dispatcher) {
        val viewModel = guestViewModel()
        viewModel.continueAsGuest()
        advanceUntilIdle()
        holdNextPublicCabins = true
        viewModel.selectGuestWarehouse(guestState(viewModel).warehouses.first())
        runCurrent()
        viewModel.requestGuestLogin()
        assertThat(viewModel.state.value).isEqualTo(CustomerAppState.SignedOut(showLogin = true))
        auth.login("first-customer", "test-password", rememberMe = false)
        completedBootstraps.receive()
        runCurrent()
        completeHeldPublicPage()
        runCurrent()
        assertThat((viewModel.state.value as CustomerAppState.Ready).workflow.profile?.id).isEqualTo("first-customer")
    }

    @Test
    fun `guest read errors stay visible and a foreground refresh can recover`() = runTest(dispatcher) {
        val viewModel = guestViewModel()
        viewModel.continueAsGuest()
        advanceUntilIdle()
        publicCabinFailure = CustomerApiException(503, "Каталог временно недоступен")
        viewModel.selectGuestWarehouse(guestState(viewModel).warehouses.first())
        runCurrent()
        assertThat(guestState(viewModel).error).isEqualTo("Каталог временно недоступен")
        assertThat(guestState(viewModel).busy).isFalse()
        publicCabinFailure = null
        viewModel.refreshGuestCatalog()
        runCurrent()
        assertThat(guestState(viewModel).error).isNull()
        assertThat(guestState(viewModel).cabins).hasSize(1)
        assertThat(server.requestCount).isEqualTo(0)
    }

    private suspend fun TestScope.guestViewModel(): CustomerAppViewModel {
        val repository = CustomerRepository(context, customerApi(), json, workflowStore)
        val viewModel = CustomerAppViewModel(auth, repository, workflowStore, CustomerNotifications(context, auth, repository), publicRepository())
        viewModels.put("guest", viewModel)
        // Enter at the first signed-out emission, including the protected workflow cleanup race.
        viewModel.state.first { it is CustomerAppState.SignedOut }
        return viewModel
    }

    private fun guestState(viewModel: CustomerAppViewModel): CustomerGuestCatalogState =
        (viewModel.state.value as CustomerAppState.GuestCatalog).catalog

    private fun completeHeldPublicPage() {
        requireNotNull(heldPublicCabins).resumeWith(Result.success(publicPage("obsolete", null, 0)))
        heldPublicCabins = null
    }

    private fun publicRepository(): PublicCustomerCatalogRepository {
        val api = Proxy.newProxyInstance(
            PublicCustomerCatalogApi::class.java.classLoader, arrayOf(PublicCustomerCatalogApi::class.java),
        ) { _, method, args ->
            when (method.name) {
                "warehouses" -> listOf(warehouse("first-warehouse"), warehouse("second-warehouse")).also { publicReads += "warehouses" }
                "facets" -> CabinFacets().also { publicReads += "facets:${requireNotNull(args)[0]}" }
                "cabins" -> {
                    val arguments = requireNotNull(args)
                    val warehouseId = arguments[0] as String
                    val type = arguments[1] as String?
                    val page = arguments[7] as Int
                    publicReads += "cabins:$warehouseId:${type.orEmpty()}:$page"
                    publicCabinFailure?.let { throw it }
                    if (holdNextPublicCabins) {
                        holdNextPublicCabins = false
                        @Suppress("UNCHECKED_CAST")
                        val continuation = arguments.last() as Continuation<CabinPage>
                        heldPublicCabins = continuation
                        COROUTINE_SUSPENDED
                    } else publicPage(warehouseId, type, page)
                }
                else -> error("Unexpected public operation: ${method.name}")
            }
        } as PublicCustomerCatalogApi
        return PublicCustomerCatalogRepository(api, json)
    }

    private fun publicPage(warehouseId: String, type: String?, page: Int) = CabinPage(
        content = listOf(CustomerCabin("$warehouseId-${type.orEmpty()}-$page", 1, "42", 1, 18_000)),
        page = page.toLong(), totalPages = 2,
    )

    private fun exerciseOldPollingCompletion(failed: Boolean) = runTest(dispatcher, timeout = 30.seconds) {
        val api = customerApi()
        val repository = CustomerRepository(context, api, json, workflowStore)
        val viewModel = CustomerAppViewModel(
            auth, repository, workflowStore, CustomerNotifications(context, auth, repository), publicRepository(),
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
            "profile" -> configuredProfile().also { profileCalls += 1 }
            "createProfile" -> {
                val requested = requireNotNull(args)[0] as CustomerProfile
                createdProfiles += requested
                createProfileFailure?.let { throw it }
                requested.copy(id = "created-profile", version = 1).also { currentProfile = it }
            }
            "warehouses" -> listOf(currentWarehouse)
            "inquiry" -> checkoutFixture?.session ?: error("Unexpected inquiry read")
            "cart" -> checkoutFixture?.cart ?: error("Unexpected cart read")
            "facets" -> CabinFacets()
            "cabins" -> {
                val parameters = requireNotNull(args)
                cabinTypeQueries += (parameters[0] as String) to (parameters[1] as String?)
                if (holdNextCabins) {
                    holdNextCabins = false
                    @Suppress("UNCHECKED_CAST")
                    val continuation = parameters.last() as Continuation<CabinPage>
                    heldCabins = continuation
                    COROUTINE_SUSPENDED
                } else checkoutFixture?.let { CabinPage(content = listOf(it.cabin), totalElements = 1, totalPages = 1) }
                    ?: error("Unexpected cabins read")
            }
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
            "bookingChangeQuote" -> {
                val parameters = requireNotNull(args)
                changeReads += (parameters[0] as String) to (parameters[1] as String)
                bookingChangeReadFailure?.let { throw it }
                requireNotNull(restoredChange)
            }
            "bookings" -> if (bookingsReadFailure != null) {
                throw requireNotNull(bookingsReadFailure)
            } else if (holdNextBookings) {
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

    private fun configuredProfile(): CustomerProfile {
        val result = profileReadResults.pollFirst() ?: return currentProfile
        return result.fold(
            onSuccess = { it ?: throw missingProfile() },
            onFailure = { throw it },
        )
    }

    private fun missingProfile(): Nothing = throw HttpException(
        Response.error<Unit>(404, "not found".toResponseBody("application/problem+json".toMediaType())),
    )

    private fun jsonResponse(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun customerJwt(subjectId: String, username: String): String = listOf(
        """{"alg":"none"}""",
        """{"sub":"$subjectId","preferred_username":"$username"}""",
        "signature",
    ).joinToString(".") { part -> Base64.getUrlEncoder().withoutPadding().encodeToString(part.encodeToByteArray()) }

    private fun profile(id: String) = CustomerProfile(
        id = id, version = 1, entityType = CustomerEntityType.INDIVIDUAL,
        firstName = id, phone = "+79990000000",
    )

    private fun warehouse(id: String) = CustomerWarehouse(
        id = id, name = id, timezone = "Europe/Moscow", depotLatitude = 59.93, depotLongitude = 30.32,
    )

    private fun registrationViewModel(): CustomerAppViewModel {
        val repository = CustomerRepository(context, customerApi(), json, workflowStore)
        return CustomerAppViewModel(
            auth,
            repository,
            workflowStore,
            CustomerNotifications(context, auth, repository),
            publicRepository(),
        ).also { viewModels.put("registration", it) }
    }

    private suspend fun registerCustomer() {
        auth.register(
            username = "client_01",
            email = "client@example.test",
            password = "password-123",
            confirmation = "password-123",
            phone = "+79990000000",
            firstName = "Иван",
            lastName = "Петров",
        )
    }

    private suspend fun readyWorkflow(viewModel: CustomerAppViewModel): CustomerWorkflowState {
        val ready = viewModel.state.first { state ->
            state is CustomerAppState.Ready && !state.workflow.bootstrapping
        }
        return (ready as CustomerAppState.Ready).workflow
    }

    private suspend fun TestScope.signedInCheckoutViewModel(): CustomerAppViewModel {
        val fixture = requireNotNull(checkoutFixture)
        currentProfile = profile("checkout-customer")
        currentWarehouse = warehouse(fixture.warehouseId)
        val reference = workflowStore.begin(fixture.warehouseId, rememberWarehouse = true)
        workflowStore.bind(reference, fixture.session)
        restoredChange?.let { workflowStore.rememberBookingChange(CustomerBookingChangeReference(it.bookingId, it.quoteId)) }
        val repository = CustomerRepository(context, customerApi(), json, workflowStore)
        val viewModel = CustomerAppViewModel(auth, repository, workflowStore, CustomerNotifications(context, auth, repository), publicRepository())
        viewModels.put("checkout", viewModel)
        auth.login("checkout-customer", "test-password", rememberMe = false)
        completedBootstraps.receive()
        if (restoredChange != null && bookingChangeReadFailure == null) {
            viewModel.state.first { it is CustomerAppState.Ready && it.workflow.bookingChangeQuote != null }
        } else if (bookingChangeReadFailure != null) {
            viewModel.state.first {
                it is CustomerAppState.Ready && it.workflow.updatesError == bookingChangeReadFailure?.message
            }
        }
        // A recovered quote is published before bootstrap has finished its storage work.
        viewModel.state.first { it is CustomerAppState.Ready && !it.workflow.bootstrapping && !it.workflow.busy }
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

    private fun changeQuote() = CustomerBookingChangeQuote(
        quoteId = "quote", version = 1, bookingId = "booking", bookingVersion = 3,
        operation = BookingChangeOperation.CANCEL, oldSlotId = "slot", slotId = null, slotVersion = null,
        amountRubles = "12500", settlement = BookingChangeSettlement.PAYMENT_REQUIRED,
        applicationState = BookingChangeApplicationState.OFFERED, testPaymentAvailable = true, supportPhone = null,
        expiresAt = "2099-01-01T00:00:00Z", noticeDays = 2, deliveryDate = "2026-09-08",
        warehouseTimeZone = "Europe/Moscow", targetDeliveryDate = null, targetWindowStart = null, targetWindowEnd = null,
    )

    private companion object {
        const val CUSTOMER_SUBJECT_ID = "11111111-1111-4111-8111-111111111111"
    }
}
