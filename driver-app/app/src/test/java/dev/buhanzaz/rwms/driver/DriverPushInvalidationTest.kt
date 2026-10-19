package dev.buhanzaz.rwms.driver

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DriverPushInvalidationTest {
    @Test
    fun `rejects the slinger only join notification`() {
        assertThat(
            DriverPushInvalidation.from(
                mapOf(
                    "eventId" to "event-42",
                    "revision" to "42",
                    "type" to "TASK_JOIN_AVAILABLE",
                    "entryId" to "entry-8",
                ),
            ),
        ).isNull()
    }

    @Test
    fun `registers a Firebase installation id instead of a registration token`() {
        val request = driverDeviceRegistrationRequest(
            firebaseInstallationId = "fid-42",
            appVersion = "1.0",
            sdkInt = 36,
            locale = "ru-RU",
        )

        assertThat(request.provider).isEqualTo("FCM")
        assertThat(request.targetKind).isEqualTo("FID")
        assertThat(request.token).isEqualTo("fid-42")
    }

    @Test
    fun `rejects incomplete or malformed push data`() {
        assertThat(DriverPushInvalidation.from(emptyMap())).isNull()
        assertThat(
            DriverPushInvalidation.from(
                mapOf("eventId" to "e", "revision" to "-1", "type" to "UPDATE"),
            ),
        ).isNull()
    }
}
