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
