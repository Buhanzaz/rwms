package dev.buhanzaz.rwms.driver.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

class DriverGatewayPathTest {
    @Test
    fun `same origin media path accepts only public media routes`() {
        assertThat(requireSameOriginApiPath("/api/media/v1/upload-sessions/123e4567-e89b-12d3-a456-426614174000/content"))
            .isEqualTo("/api/media/v1/upload-sessions/123e4567-e89b-12d3-a456-426614174000/content")
    }

    @Test
    fun `source and thumbnail photos use authenticated same origin media reads`() {
        val ownerQuery =
            "ownerType=TASK_BOARD_ENTRY&ownerId=223e4567-e89b-12d3-a456-426614174000" +
                "&warehouseId=323e4567-e89b-12d3-a456-426614174000&context=WORK_RESULT&generation=4"
        val original =
            "/api/media/v1/assets/123e4567-e89b-12d3-a456-426614174000/original?$ownerQuery"
        val thumbnail =
            "/api/media/v1/assets/123e4567-e89b-12d3-a456-426614174000/variants/SMALL/content?$ownerQuery"

        assertThat(requireSameOriginMediaReadPath(original)).isEqualTo(original)
        assertThat(requireSameOriginMediaReadPath(thumbnail)).isEqualTo(thumbnail)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `source photo read rejects a foreign origin`() {
        requireSameOriginMediaReadPath(
            "https://minio.internal/api/media/v1/assets/123e4567-e89b-12d3-a456-426614174000/original",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `same origin media path rejects storage url`() {
        requireSameOriginApiPath("https://minio.internal/object")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `same origin media path rejects traversal and query`() {
        requireSameOriginApiPath("/api/media/v1/upload-sessions/123e4567-e89b-12d3-a456-426614174000/content?next=../secret")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `same origin media path rejects encoded traversal`() {
        requireSameOriginApiPath("/api/media/v1/upload-sessions/%2e%2e/content")
    }

    @Test
    fun `driver task and evidence routes use the public task board prefix`() {
        assertThat(route<GET>("driverContext")).isEqualTo("$DRIVER_PREFIX/context")
        assertThat(route<GET>("driverFeed")).isEqualTo("$DRIVER_PREFIX/feed")
        assertThat(route<GET>("driverTaskDetail")).isEqualTo("$DRIVER_PREFIX/entries/{entryId}")
        assertThat(route<GET>("logisticsDriverTask"))
            .isEqualTo("/api/logistics/v1/driver-tasks/{taskId}")
        assertThat(route<POST>("applyAction")).isEqualTo("$DRIVER_PREFIX/entries/{entryId}/actions")
        assertThat(route<POST>("reserveEvidence"))
            .isEqualTo("$DRIVER_PREFIX/entries/{entryId}/evidence-reservations")
        assertThat(route<PUT>("registerDevice")).isEqualTo("$DRIVER_PREFIX/devices/{installationId}")
        assertThat(route<DELETE>("unregisterDevice")).isEqualTo("$DRIVER_PREFIX/devices/{installationId}")
        assertThat(route<POST>("createUploadSession")).isEqualTo("/api/media/v1/upload-sessions")
        assertThat(route<POST>("finalizeUploadSession"))
            .isEqualTo("/api/media/v1/upload-sessions/{uploadSessionId}/complete")
    }

    private inline fun <reified T : Annotation> route(methodName: String): String {
        val method = DriverGatewayApi::class.java.declaredMethods.single { it.name == methodName }
        val annotation = method.getAnnotation(T::class.java)
        return when (annotation) {
            is GET -> annotation.value
            is POST -> annotation.value
            is PUT -> annotation.value
            is DELETE -> annotation.value
            else -> error("Unsupported route annotation on $methodName")
        }
    }

    private companion object {
        const val DRIVER_PREFIX = "/api/task-board/driver/v1"
    }
}
