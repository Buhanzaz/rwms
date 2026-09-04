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
        val quote = quote(booking)

        val failure = runCatching {
            repository(
                lifecycleApi(
                    calls = calls,
                    bookings = { listOf(booking) },
                    cancel = { throw IOException("response lost") },
                    quote = { quote },
                ),
            ).cancelBooking(booking, quote, true)
        }.exceptionOrNull()
        val recovered = repository(
            lifecycleApi(calls = calls, cancel = { pending }, quote = {
                quote.copy(applicationState = BookingChangeApplicationState.APPLYING, testPaymentAvailable = false)
            }),
        ).cancelBooking(booking, quote, true)

        assertThat(failure).isInstanceOf(CustomerApiException::class.java)
        assertThat(recovered.booking).isEqualTo(pending)
        assertThat(recovered.quote.settlement).isEqualTo(BookingChangeSettlement.PAYMENT_REQUIRED)
        assertThat(recovered.quote.applicationState).isEqualTo(BookingChangeApplicationState.APPLYING)
        assertThat(calls).hasSize(2)
        assertThat(calls[0].key).isEqualTo(calls[1].key)
        assertThat(calls[0].expectedVersion).isEqualTo(7)
        assertThat(calls[1].expectedVersion).isEqualTo(7)
        assertThat(calls[1].quoteId).isEqualTo(quote.quoteId)
        assertThat(calls[1].testPaymentRequested).isTrue()
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
    fun `lost cancellation response is recovered only from exact applied quote not booking status`() = runTest {
        val booking = booking(7, "COMPLETED")
        val quote = quote(booking)
        val calls = mutableListOf<LifecycleCall>()
        val applied = quote.copy(
            version = 2,
            applicationState = BookingChangeApplicationState.APPLIED,
            settlement = BookingChangeSettlement.TEST_PAID,
            testPaymentAvailable = false,
        )
        val result = repository(lifecycleApi(
            calls = calls,
            cancel = { throw IOException("response lost") },
            quote = { applied },
        )).cancelBooking(booking, quote, true)

        assertThat(result.booking).isNull()
        assertThat(result.quote).isEqualTo(applied)
        assertThat(calls).hasSize(1)
    }

    @Test
    fun `successful booking response without exact quote does not prove payment and keeps retry key`() = runTest {
        val booking = booking(7, "COMPLETED")
        val quote = quote(booking)
        val cancelled = booking.copy(version = 8, status = "CANCELLED")
        val calls = mutableListOf<LifecycleCall>()
        val failed = runCatching {
            repository(lifecycleApi(calls, cancel = { cancelled }, quote = { throw IOException("GET unavailable") }))
                .cancelBooking(booking, quote, true)
        }
        assertThat(failed.isFailure).isTrue()
        val reference = store.bookingChangeReferences().single()
        assertThat(reference.quoteId).isEqualTo(quote.quoteId)
        assertThat(reference.commandFingerprint).isNotNull()
        repository(lifecycleApi(calls, cancel = { cancelled }, quote = {
            quote.copy(applicationState = BookingChangeApplicationState.APPLIED, settlement = BookingChangeSettlement.TEST_PAID)
        })).cancelBooking(booking, quote, true)
        assertThat(calls[0].key).isEqualTo(calls[1].key)
    }

    @Test
    fun `manager waived quote uses its new version without requesting a test charge`() = runTest {
        val booking = booking(7, "COMPLETED")
        val quote = quote(booking).copy(version = 3, settlement = BookingChangeSettlement.WAIVED, amountRubles = "0")
        val calls = mutableListOf<LifecycleCall>()
        val result = repository(lifecycleApi(calls, cancel = { request ->
            assertThat(request.changeQuoteVersion).isEqualTo(3)
            booking.copy(version = 8, status = "CANCELLED")
        }, quote = { quote.copy(applicationState = BookingChangeApplicationState.APPLIED) }))
            .cancelBooking(booking, quote, false)
        assertThat(calls.single().testPaymentRequested).isFalse()
        assertThat(result.quote.settlement).isEqualTo(BookingChangeSettlement.WAIVED)
    }

    @Test
    fun `recovered waived reschedule uses quoted target identity without reconstructing an offer`() = runTest {
        val booking = booking(7, "COMPLETED")
        val quoted = quote(booking, deliverySlot("slot-new", 5))
            .copy(version = 3, settlement = BookingChangeSettlement.WAIVED, amountRubles = "0")
        val calls = mutableListOf<LifecycleCall>()
        store.rememberBookingChange(CustomerBookingChangeReference(quoted.bookingId, quoted.quoteId))
        val api = lifecycleApi(calls, reschedule = { request ->
            assertThat(request.slotId).isEqualTo(quoted.slotId)
            assertThat(request.slotVersion).isEqualTo(quoted.slotVersion)
            booking.copy(version = 8, slotId = quoted.slotId)
        }, quote = { quoted.copy(applicationState = BookingChangeApplicationState.APPLIED) })

        val result = repository(api).rescheduleBooking(booking, quoted, false)

        assertThat(result.quote.settlement).isEqualTo(BookingChangeSettlement.WAIVED)
        assertThat(result.quote.targetDeliveryDate).isEqualTo("2026-09-02")
        assertThat(calls.single().testPaymentRequested).isFalse()
    }

    @Test
    fun `quote for a stale booking cannot reach the mutation transport`() = runTest {
        val booking = booking(7, "COMPLETED")
        val quote = quote(booking).copy(bookingVersion = 6)
        val calls = mutableListOf<LifecycleCall>()
        val failure = runCatching { repository(lifecycleApi(calls)).cancelBooking(booking, quote, true) }.exceptionOrNull()
        assertThat((failure as CustomerApiException).status).isEqualTo(409)
        assertThat(calls).isEmpty()
    }

    @Test
    fun `quote creation reuses its durable key after a lost response and stores only recovery identity`() = runTest {
        val booking = booking(7, "COMPLETED")
        val quote = quote(booking)
        val keys = mutableListOf<String>()
        var fail = true
        val api = proxyApi { method, args ->
            check(method == "createBookingChangeQuote")
            keys += args[1] as String
            assertThat(args[2]).isEqualTo(CreateBookingChangeQuoteRequest(7, BookingChangeOperation.CANCEL, null, null))
            if (fail) throw IOException("response lost")
            quote
        }
        assertThat(runCatching { repository(api).createBookingChangeQuote(booking, BookingChangeOperation.CANCEL) }.isFailure).isTrue()
        fail = false
        assertThat(repository(api).createBookingChangeQuote(booking, BookingChangeOperation.CANCEL)).isEqualTo(quote)
        assertThat(keys[0]).isEqualTo(keys[1])
        assertThat(store.bookingChangeReferences()).containsExactly(CustomerBookingChangeReference(quote.bookingId, quote.quoteId))
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
        val quote = quote(booking, offer)

        val failure = runCatching {
            repository(
                lifecycleApi(
                    calls = calls,
                    bookings = { listOf(booking) },
                    reschedule = { throw IOException("response lost") },
                    quote = { quote },
                ),
            ).rescheduleBooking(booking, quote, true)
        }.exceptionOrNull()
        val recovered = repository(
            lifecycleApi(calls = calls, reschedule = { changed }, quote = {
                quote.copy(applicationState = BookingChangeApplicationState.APPLIED, settlement = BookingChangeSettlement.TEST_PAID)
            }),
        ).rescheduleBooking(booking, quote, true)

        assertThat(failure).isInstanceOf(CustomerApiException::class.java)
        assertThat(booking.slotId).isEqualTo("slot-old")
        assertThat(recovered.booking).isEqualTo(changed)
        assertThat(recovered.quote.settlement).isEqualTo(BookingChangeSettlement.TEST_PAID)
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
        quote: () -> CustomerBookingChangeQuote = { error("Quote GET was not expected") },
    ): CustomerApi = proxyApi { method, arguments ->
        when (method) {
            "bookings" -> bookings()
            "bookingChangeQuote" -> quote()
            "cancelBooking" -> {
                val request = arguments[2] as CancelCustomerBookingRequest
                calls += LifecycleCall(
                    key = arguments[1] as String,
                    expectedVersion = request.expectedVersion,
                    quoteId = request.changeQuoteId,
                    testPaymentRequested = request.testPaymentRequested,
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
                    quoteId = request.changeQuoteId,
                    testPaymentRequested = request.testPaymentRequested,
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

    private fun quote(booking: CustomerBooking, slot: DeliverySlot? = null) = CustomerBookingChangeQuote(
        quoteId = UUID.randomUUID().toString(),
        version = 0,
        bookingId = requireNotNull(booking.bookingId),
        bookingVersion = booking.version,
        operation = if (slot == null) BookingChangeOperation.CANCEL else BookingChangeOperation.RESCHEDULE,
        oldSlotId = requireNotNull(booking.slotId),
        slotId = slot?.slotId,
        slotVersion = slot?.version,
        amountRubles = "12500",
        settlement = BookingChangeSettlement.PAYMENT_REQUIRED,
        applicationState = BookingChangeApplicationState.OFFERED,
        testPaymentAvailable = true,
        supportPhone = "+74951234567",
        expiresAt = "2026-09-01T15:00:00Z",
        noticeDays = 2,
        deliveryDate = "2026-09-01",
        warehouseTimeZone = "Europe/Moscow",
        targetDeliveryDate = slot?.date,
        targetWindowStart = slot?.start,
        targetWindowEnd = slot?.end,
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
        val quoteId: String? = null,
        val testPaymentRequested: Boolean = false,
    )
}
