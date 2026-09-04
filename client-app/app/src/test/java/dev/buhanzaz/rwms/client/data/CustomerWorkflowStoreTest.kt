package dev.buhanzaz.rwms.client.data

import android.app.Application
import com.google.common.truth.Truth.assertThat
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

/** Verifies process-durable inquiry recovery without storing an authoritative cart projection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerWorkflowStoreTest {
    private val store = CustomerWorkflowStore(
        RuntimeEnvironment.getApplication(),
        Json { ignoreUnknownKeys = true },
    )

    /** Clears the singleton preference file before each isolated recovery assertion. */
    @Before
    fun clearBefore() = runTest { store.clear() }

    /** Leaves no recovery pointer for another test process. */
    @After
    fun clearAfter() = runTest { store.clear() }

    @Test
    fun `pending create key survives recreation and binds the returned inquiry`() = runTest {
        val warehouseId = UUID.randomUUID().toString()
        val inquiryId = UUID.randomUUID().toString()

        val pending = store.begin(warehouseId)
        val restoredPending = store.read()
        val bound = store.bind(
            requireNotNull(restoredPending),
            InquirySession(inquiryId, warehouseId, 0, "ACTIVE"),
        )

        assertThat(restoredPending.createIdempotencyKey).isEqualTo(pending.createIdempotencyKey)
        assertThat(bound.inquiryId).isEqualTo(inquiryId)
        assertThat(store.read()).isEqualTo(bound)
    }

    @Test
    fun `retry before bind reuses the pending create key for the same warehouse`() = runTest {
        val warehouseId = UUID.randomUUID().toString()

        val firstAttempt = store.begin(warehouseId)
        val retryAfterLostResponse = store.begin(warehouseId)

        assertThat(retryAfterLostResponse).isEqualTo(firstAttempt)
    }

    @Test
    fun `pending operation ledger is bounded without evicting unresolved keys`() = runTest {
        val first = store.beginIdempotentOperation("operation-0")
        repeat(MAX_PENDING_CUSTOMER_IDEMPOTENCY_OPERATIONS - 1) { index ->
            store.beginIdempotentOperation("operation-${index + 1}")
        }

        val rejected = runCatching {
            store.beginIdempotentOperation("operation-overflow")
        }.exceptionOrNull()

        assertThat(rejected).isInstanceOf(CustomerApiException::class.java)
        assertThat(store.beginIdempotentOperation("operation-0")).isEqualTo(first)
    }

    @Test
    fun `quote references survive recreation without persisting payment or application state`() = runTest {
        val first = CustomerBookingChangeReference("booking-a", "quote-a", "booking-cancel:booking-a:7:quote-a:0:true")
        val second = CustomerBookingChangeReference("booking-b", "quote-b")
        store.rememberBookingChange(first)
        store.rememberBookingChange(second)
        val recreated = CustomerWorkflowStore(RuntimeEnvironment.getApplication(), Json { ignoreUnknownKeys = true })

        assertThat(recreated.bookingChangeReferences()).containsExactly(first, second)
        recreated.forgetBookingChange(first.quoteId)
        assertThat(store.bookingChangeReferences()).containsExactly(second)
        store.clear()
        assertThat(recreated.bookingChangeReferences()).isEmpty()
    }

    @Test
    fun `terminal inquiry restart preserves warehouse preference and rotates create identity`() = runTest {
        val warehouseId = UUID.randomUUID().toString()
        val firstInquiryId = UUID.randomUUID().toString()
        val pending = store.begin(warehouseId, rememberWarehouse = true)
        val bound = store.bind(
            pending,
            InquirySession(firstInquiryId, warehouseId, 4, "BOOKED"),
        )

        val replacement = store.restart(bound)

        assertThat(replacement.warehouseId).isEqualTo(warehouseId)
        assertThat(replacement.rememberWarehouse).isTrue()
        assertThat(replacement.inquiryId).isNull()
        assertThat(replacement.createIdempotencyKey).isNotEqualTo(pending.createIdempotencyKey)
        assertThat(store.read()).isEqualTo(replacement)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inquiry from another warehouse cannot replace the pending intent`() = runTest {
        val pending = store.begin(UUID.randomUUID().toString())

        store.bind(
            pending,
            InquirySession(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0, "ACTIVE"),
        )
    }
}
