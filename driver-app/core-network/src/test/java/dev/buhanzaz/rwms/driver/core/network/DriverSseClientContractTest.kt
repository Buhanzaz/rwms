package dev.buhanzaz.rwms.driver.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/** Verifies that driver reconnect requests cannot accidentally carry replay state. */
class DriverSseClientContractTest {
    @Test
    fun `fresh invalidation subscription has no replay cursor or header`() {
        val request = driverSseRequest("https://gateway.example.test/".toHttpUrl())

        assertThat(request.url.encodedPath).isEqualTo("/api/task-board/driver/v1/events")
        assertThat(request.header("Last-Event-ID")).isNull()
        assertThat(request.url.query).isNull()
    }
}
