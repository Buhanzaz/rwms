package dev.buhanzaz.rwms.driver

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DriverRealtimeNotificationAlertTest {
    @Test
    fun `driver task invalidations select a realtime notification but slinger join does not`() {
        assertThat(shouldShowRealtimeNotification("NEW_TASK")).isTrue()
        assertThat(shouldShowRealtimeNotification("URGENT_TASK")).isTrue()
        assertThat(shouldShowRealtimeNotification("MANDATORY_TASK")).isTrue()
        assertThat(shouldShowRealtimeNotification("TASK_JOIN_AVAILABLE")).isFalse()
        assertThat(shouldShowRealtimeNotification("TASK_UPDATED")).isFalse()
        assertThat(shouldShowRealtimeNotification("SYNC")).isFalse()
        assertThat(DriverNotifications.driverNotificationMessage("NEW_TASK"))
            .isEqualTo("Появилось новое задание")
        assertThat(DriverNotifications.driverNotificationMessage("URGENT_TASK"))
            .isEqualTo("Появилось срочное задание")
    }
}
