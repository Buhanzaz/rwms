package dev.buhanzaz.rwms.client.data

import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verifies durable CustomerApp booking cancellation and rescheduling transport semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerBookingLifecycleRepositoryTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    private val store = CustomerWorkflowStore(context, json)

    /** Clears the process-shared mutation ledger before each recreation scenario. */
    @Before
    fun clearBefore() = runTest { store.clear() }

    /** Removes pending lifecycle command identities after every assertion. */
    @After
    fun clearAfter() = runTest { store.clear() }

    @Test
    fun `cancellation keeps exact version and idempotency key across a lost response`() = runTest {
        val booking = booking(version = 7, status = "COMPLETED")
        val pending = booking.copy(version = 8, status = "CANCELLATION_PENDING")
        val calls = mutableListOf<LifecycleCall>()

        val failure = runCatching {
            repository(
                lifecycleApi(
                    calls = calls,
                    bookings = { listOf(booking) },
                    cancel = { throw IOException("response lost") },
                ),
            ).cancelBooking(booking)
        }.exceptionOrNull()
        val recovered = repository(
            lifecycleApi(calls = calls, cancel = { pending }),
        ).cancelBooking(booking)

        assertThat(failure).isInstanceOf(CustomerApiException::class.java)
        assertThat(recovered).isEqualTo(pending)
        assertThat(calls).hasSize(2)
        assertThat(calls[0].key).isEqualTo(calls[1].key)
        assertThat(calls[0].expectedVersion).isEqualTo(7)
        assertThat(calls[1].expectedVersion).isEqualTo(7)
    }

    @Test
    fun `booking scoped slot search sends only the authoritative booking fence`() = runTest {
        val booking = booking(version = 12, status = "COMPLETED")
        var capturedBookingId: String? = null
        var capturedRequest: SearchCustomerBookingRescheduleRequest? = null
        val offer = deliverySlot(slotId = "slot-new", version = 4)
        val api = proxyApi { method, arguments ->
            when (method) {
                "searchBookingRescheduleSlots" -> {
                    capturedBookingId = arguments[0] as String
                    capturedRequest = arguments[1] as SearchCustomerBookingRescheduleRequest
                    listOf(offer)
                }
                else -> error("Unexpected CustomerApi call: $method")
            }
        }

        val result = repository(api).searchBookingRescheduleSlots(booking)

        assertThat(result).containsExactly(offer)
        assertThat(capturedBookingId).isEqualTo(booking.bookingId)
        assertThat(capturedRequest).isEqualTo(SearchCustomerBookingRescheduleRequest(expectedVersion = 12))
    }

    @Test
    fun `reschedule retries the exact atomic slot swap and never changes the old projection locally`() = runTest {
        val booking = booking(version = 18, status = "COMPLETED", slotId = "slot-old")
        val offer = deliverySlot(slotId = "slot-new", version = 5)
        val changed = booking.copy(
            version = 19,
            slotId = offer.slotId,
            deliveryDate = offer.date,
            windowStart = offer.start,
            windowEnd = offer.end,
        )
        val calls = mutableListOf<LifecycleCall>()

        val failure = runCatching {
            repository(
                lifecycleApi(
                    calls = calls,
                    bookings = { listOf(booking) },
                    reschedule = { throw IOException("response lost") },
                ),
            ).rescheduleBooking(booking, offer)
        }.exceptionOrNull()
        val recovered = repository(
            lifecycleApi(calls = calls, reschedule = { changed }),
        ).rescheduleBooking(booking, offer)

        assertThat(failure).isInstanceOf(CustomerApiException::class.java)
        assertThat(booking.slotId).isEqualTo("slot-old")
        assertThat(recovered).isEqualTo(changed)
        assertThat(calls).hasSize(2)
        assertThat(calls[0].key).isEqualTo(calls[1].key)
        assertThat(calls[1].expectedVersion).isEqualTo(18)
        assertThat(calls[1].slotId).isEqualTo("slot-new")
        assertThat(calls[1].slotVersion).isEqualTo(5)
    }

    private fun repository(api: CustomerApi): CustomerRepository = CustomerRepository(
        context = context,
        api = api,
        json = json,
        workflowStore = CustomerWorkflowStore(context, json),
    )

    @Suppress("UNCHECKED_CAST")
    private fun lifecycleApi(
        calls: MutableList<LifecycleCall>,
        bookings: () -> List<CustomerBooking> = { emptyList() },
        cancel: (CancelCustomerBookingRequest) -> CustomerBooking = {
            error("Cancellation was not expected")
        },
        reschedule: (RescheduleCustomerBookingRequest) -> CustomerBooking = {
            error("Rescheduling was not expected")
        },
    ): CustomerApi = proxyApi { method, arguments ->
        when (method) {
            "bookings" -> bookings()
            "cancelBooking" -> {
                val request = arguments[2] as CancelCustomerBookingRequest
                calls += LifecycleCall(
                    key = arguments[1] as String,
                    expectedVersion = request.expectedVersion,
                )
                cancel(request)
            }
            "rescheduleBooking" -> {
                val request = arguments[2] as RescheduleCustomerBookingRequest
                calls += LifecycleCall(
                    key = arguments[1] as String,
                    expectedVersion = request.expectedVersion,
                    slotId = request.slotId,
                    slotVersion = request.slotVersion,
                )
                reschedule(request)
            }
            else -> error("Unexpected CustomerApi call: $method")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun proxyApi(
        invocation: (String, Array<out Any?>) -> Any?,
    ): CustomerApi = Proxy.newProxyInstance(
        CustomerApi::class.java.classLoader,
        arrayOf(CustomerApi::class.java),
    ) { _, method, arguments ->
        invocation(method.name, arguments.orEmpty())
    } as CustomerApi

    private fun booking(
        version: Long,
        status: String,
        slotId: String = "slot-old",
    ): CustomerBooking = CustomerBooking(
        bookingId = UUID.randomUUID().toString(),
        version = version,
        orderId = UUID.randomUUID().toString(),
        status = status,
        inquiryId = UUID.randomUUID().toString(),
        slotId = slotId,
        warehouseId = UUID.randomUUID().toString(),
        deliveryAddress = "Невский проспект, 1",
        deliveryDate = "2026-09-01",
        windowStart = "09:00:00",
        windowEnd = "12:00:00",
    )

    private fun deliverySlot(slotId: String, version: Long): DeliverySlot = DeliverySlot(
        slotId = slotId,
        version = version,
        date = "2026-09-02",
        kind = DeliverySlotKind.FIXED_WINDOW,
        start = "12:00:00",
        end = "15:00:00",
        travelZoneHours = 1,
        capacityRemaining = 1,
        deliveryPriceRubles = 10_000,
        priceIsochroneMinutes = 60,
        siteCabinCapacity = 1,
        roadRouteConfirmed = true,
        privateSiteAccessConfirmed = false,
        failedTripChargeAcknowledged = false,
        routeProfile = CustomerRouteProfile(4.0, 2.5, 9.0, 12.0, 8.0, 3),
        expiresAt = "2026-08-31T18:00:00Z",
        state = "OFFERED",
    )

    /** Captured service-owned lifecycle command fence and replacement slot identity. */
    private data class LifecycleCall(
        val key: String,
        val expectedVersion: Long,
        val slotId: String? = null,
        val slotVersion: Long? = null,
    )
}
