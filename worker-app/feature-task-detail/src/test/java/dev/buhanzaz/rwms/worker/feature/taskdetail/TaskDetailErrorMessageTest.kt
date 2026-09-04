package dev.buhanzaz.rwms.worker.feature.taskdetail

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Test

/** Verifies that task queue failures remain actionable without leaking diagnostics. */
class TaskDetailErrorMessageTest {
    @Test
    fun `queue failure hides arbitrary exception message`() {
        val message = workerTaskActionQueueErrorMessage(
            IllegalStateException("SQL constraint at /srv/rwms/worker.db"),
        )

        assertThat(message).contains("Синхронизируйте задание")
        assertThat(message).doesNotContain("SQL")
        assertThat(message).doesNotContain("/srv/rwms")
    }

    @Test
    fun `queue transport failure shows safe connectivity action`() {
        val message = workerTaskActionQueueErrorMessage(
            IOException("Failed to connect to internal-gateway:8080"),
        )

        assertThat(message).contains("Проверьте сеть")
        assertThat(message).doesNotContain("internal-gateway")
    }

    @Test
    fun `queue mapping propagates cancellation`() {
        val cancellation = CancellationException("screen closed")

        try {
            workerTaskActionQueueErrorMessage(cancellation)
            throw AssertionError("CancellationException expected")
        } catch (actual: CancellationException) {
            assertThat(actual).isSameInstanceAs(cancellation)
        }
    }
}
