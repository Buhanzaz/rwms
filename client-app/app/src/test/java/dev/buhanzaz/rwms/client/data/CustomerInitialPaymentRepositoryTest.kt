package dev.buhanzaz.rwms.client.data

import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Durable initial-payment identity and server-only reconciliation across process recreation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerInitialPaymentRepositoryTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    private val store = CustomerWorkflowStore(context, json)
    private val payment = initialPaymentFixture()
    private val booking = CustomerBooking(bookingId = "b1335c2b-6e0b-4a9c-b6bc-bf08a636e021",
        orderId = payment.orderId, status = "PENDING", inquiryId = "inquiry", warehouseId = "warehouse")

    @Before fun before() = runTest { store.clear() }
    @After fun after() = runTest { store.clear() }

    @Test
    fun `unknown response keeps exact key and version across repository recreation`() = runTest {
        val keys = mutableListOf<String>()
        val versions = mutableListOf<Long>()
        val api = api { name, args ->
            when (name) {
                "confirmTestPayment" -> {
                    assertThat(args[0]).isEqualTo(booking.bookingId)
                    keys += args[1] as String
                    versions += (args[2] as ConfirmCustomerPaymentRequest).expectedVersion
                    throw IOException("response lost")
                }
                "payment" -> payment
                else -> error(name)
            }
        }
        repeat(2) { assertThat(runCatching { repository(api).confirmTestPayment(booking, payment) }.isFailure).isTrue() }
        assertThat(keys).hasSize(2)
        assertThat(keys[0]).isEqualTo(keys[1])
        assertThat(versions).containsExactly(3L, 3L)
    }

    @Test
    fun `lost response reconciles only confirmed same bill and exact order`() = runTest {
        val confirmed = payment.copy(orderVersion = 4, state = "CONFIRMED", canConfirm = false,
            source = "CUSTOMER_TEST", resolvedAt = "2026-09-05T12:01:00Z")
        val api = api { name, _ -> if (name == "payment") confirmed else throw IOException("response lost") }
        assertThat(repository(api).confirmTestPayment(booking, payment)).isEqualTo(confirmed)
        val wrong = api { name, _ -> if (name == "payment") confirmed.copy(orderId = "foreign") else throw IOException() }
        assertThat(runCatching { repository(wrong).confirmTestPayment(booking, payment) }.isFailure).isTrue()
    }

    @Test
    fun `expired or unissued bill cannot issue a test payment`() = runTest {
        var called = false
        val api = api { _, _ -> called = true; error("not expected") }
        val unavailable = payment.copy(state = "EXPIRED", canConfirm = false)
        assertThat(runCatching { repository(api).confirmTestPayment(booking, unavailable) }.isFailure).isTrue()
        assertThat(called).isFalse()
    }

    @Test
    fun `loading inbox does not acknowledge it and reading calls exact notification id`() = runTest {
        val calls = mutableListOf<String>()
        val message = CustomerNotification("note-id", payment.orderId, booking.bookingId, "PAYMENT_EXPIRED",
            "Резерв снят", "2026-09-05T12:05:00Z", null)
        val api = api { name, args ->
            calls += name
            when (name) {
                "notifications" -> listOf(message)
                "readNotification" -> {
                    assertThat(args[0]).isEqualTo(message.id)
                    message.copy(readAt = "2026-09-05T12:06:00Z")
                }
                else -> error(name)
            }
        }
        val repository = repository(api)
        assertThat(repository.notifications()).containsExactly(message)
        assertThat(calls).containsExactly("notifications")
        assertThat(repository.readNotification(message.id).readAt).isNotNull()
        assertThat(calls).containsExactly("notifications", "readNotification").inOrder()
    }

    private fun repository(api: CustomerApi) = CustomerRepository(context, api, json, CustomerWorkflowStore(context, json))

    private fun api(block: (String, Array<out Any?>) -> Any?): CustomerApi = Proxy.newProxyInstance(
        CustomerApi::class.java.classLoader, arrayOf(CustomerApi::class.java),
    ) { _, method, args -> block(method.name, args.orEmpty()) } as CustomerApi
}
