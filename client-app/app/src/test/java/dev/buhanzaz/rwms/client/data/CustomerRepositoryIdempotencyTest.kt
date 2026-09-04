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

/** Verifies durable command identity and authoritative acceptance reconciliation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerRepositoryIdempotencyTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    private val store = CustomerWorkflowStore(context, json)

    /** Clears the process-shared DataStore before each repository recreation scenario. */
    @Before
    fun clearBefore() = runTest { store.clear() }

    /** Removes pending command identities after every assertion. */
    @After
    fun clearAfter() = runTest { store.clear() }

    @Test
    fun `acceptance key survives recreation and clears after success or reconciliation`() = runTest {
        val bookingId = UUID.randomUUID().toString()
        val cabinId = UUID.randomUUID().toString()
        val keys = mutableListOf<String>()
        val acceptance = CustomerCabinAcceptance(
            acceptanceId = UUID.randomUUID().toString(),
            version = 1,
            acceptedAt = "2026-08-31T12:00:00Z",
            signaturePointCount = 1,
        )
        val strokes = listOf(
            CustomerSignatureStroke(listOf(CustomerSignaturePoint(0.5f, 0.5f, 0))),
        )

        val firstFailure = runCatching {
            repository(
                api = acceptanceApi(
                    keys = keys,
                    accept = { throw IOException("response lost") },
                ),
            ).acceptCabin(bookingId, cabinId, strokes)
        }.exceptionOrNull()
        val recovered = repository(
            api = acceptanceApi(keys = keys, accept = { acceptance }),
        ).acceptCabin(bookingId, cabinId, strokes)

        assertThat(firstFailure).isInstanceOf(CustomerApiException::class.java)
        assertThat(recovered).isEqualTo(acceptance)
        assertThat(keys[1]).isEqualTo(keys[0])

        val authoritativeBooking = booking(bookingId, cabinId, acceptance)
        val reconciled = repository(
            api = acceptanceApi(
                keys = keys,
                accept = { throw IOException("response lost again") },
                bookings = { listOf(authoritativeBooking) },
            ),
        ).acceptCabin(bookingId, cabinId, strokes)
        val reconciledAgain = repository(
            api = acceptanceApi(
                keys = keys,
                accept = { throw IOException("response lost once more") },
                bookings = { listOf(authoritativeBooking) },
            ),
        ).acceptCabin(bookingId, cabinId, strokes)

        assertThat(reconciled).isEqualTo(acceptance)
        assertThat(reconciledAgain).isEqualTo(acceptance)
        assertThat(keys[2]).isNotEqualTo(keys[1])
        assertThat(keys[3]).isNotEqualTo(keys[2])
    }

    @Test
    fun `problem report key survives repository recreation until authoritative success`() = runTest {
        val bookingId = UUID.randomUUID().toString()
        val cabinId = UUID.randomUUID().toString()
        val warehouseId = UUID.randomUUID().toString()
        val keys = mutableListOf<String>()
        val problem = CustomerCabinProblem(
            problemId = UUID.randomUUID().toString(),
            category = "OTHER",
            phase = "BEFORE_ACCEPTANCE",
            description = "Повреждение двери",
            reportedAt = "2026-08-31T12:00:00Z",
        )
        val owner = CustomerShipmentMediaOwner(
            ownerType = "LOGISTICS_SHIPMENT",
            documentId = UUID.randomUUID().toString(),
            lineId = UUID.randomUUID().toString(),
            warehouseId = warehouseId,
            context = "SHIPMENT",
        )

        val firstFailure = runCatching {
            repository(
                api = problemApi(keys) { throw IOException("response lost") },
            ).reportProblem(
                bookingId,
                cabinId,
                owner,
                category = "OTHER",
                description = "  Повреждение двери  ",
                evidence = emptyList(),
            )
        }.exceptionOrNull()
        val recovered = repository(
            api = problemApi(keys) { problem },
        ).reportProblem(
            bookingId,
            cabinId,
            owner,
            category = "OTHER",
            description = "  Повреждение двери  ",
            evidence = emptyList(),
        )

        assertThat(firstFailure).isInstanceOf(CustomerApiException::class.java)
        assertThat(recovered).isEqualTo(problem)
        assertThat(keys[1]).isEqualTo(keys[0])
    }

    private fun repository(api: CustomerApi): CustomerRepository = CustomerRepository(
        context = context,
        api = api,
        json = json,
        workflowStore = CustomerWorkflowStore(context, json),
    )

    @Suppress("UNCHECKED_CAST")
    private fun acceptanceApi(
        keys: MutableList<String>,
        accept: (String) -> CustomerCabinAcceptance,
        bookings: () -> List<CustomerBooking> = { emptyList() },
    ): CustomerApi = Proxy.newProxyInstance(
        CustomerApi::class.java.classLoader,
        arrayOf(CustomerApi::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "acceptCabin" -> (arguments?.get(2) as String).let { key ->
                keys += key
                accept(key)
            }
            "bookings" -> bookings()
            else -> error("Unexpected CustomerApi call: ${method.name}")
        }
    } as CustomerApi

    @Suppress("UNCHECKED_CAST")
    private fun problemApi(
        keys: MutableList<String>,
        report: (String) -> CustomerCabinProblem,
    ): CustomerApi = Proxy.newProxyInstance(
        CustomerApi::class.java.classLoader,
        arrayOf(CustomerApi::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "reportProblem" -> (arguments?.get(2) as String).let { key ->
                keys += key
                report(key)
            }
            "bookings" -> emptyList<CustomerBooking>()
            else -> error("Unexpected CustomerApi call: ${method.name}")
        }
    } as CustomerApi

    private fun booking(
        bookingId: String,
        cabinId: String,
        acceptance: CustomerCabinAcceptance,
    ): CustomerBooking = CustomerBooking(
        bookingId = bookingId,
        status = "COMPLETED",
        inquiryId = UUID.randomUUID().toString(),
        warehouseId = UUID.randomUUID().toString(),
        cabins = listOf(
            CustomerBookingCabin(
                cabinUnitId = cabinId,
                accountingNo = "CABIN-1",
                rentalMonths = 1,
                deliveryState = "ARRIVED",
                arrivalEligible = true,
                acceptance = acceptance,
            ),
        ),
    )
}
