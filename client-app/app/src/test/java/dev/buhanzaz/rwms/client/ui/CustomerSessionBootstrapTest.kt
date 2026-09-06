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
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerNotification
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerRepository
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import dev.buhanzaz.rwms.client.data.CustomerWorkflowStore
import dev.buhanzaz.rwms.client.notifications.CustomerNotifications
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        EncryptedCustomerSessionStore(context, json).clear()
        workflowStore = CustomerWorkflowStore(context, json)
        workflowStore.clear()
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
            "bookings" -> if (holdNextBookings) {
                holdNextBookings = false
                @Suppress("UNCHECKED_CAST")
                val continuation = requireNotNull(args).last() as Continuation<List<CustomerBooking>>
                heldBookings = continuation
                COROUTINE_SUSPENDED
            } else emptyList<CustomerBooking>()
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
}
