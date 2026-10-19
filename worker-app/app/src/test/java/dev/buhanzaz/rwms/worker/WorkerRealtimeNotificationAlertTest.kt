package dev.buhanzaz.rwms.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkerRealtimeNotificationAlertTest {
    @Test
    fun `new urgent and cross group invalidations select a realtime notification`() {
        assertThat(shouldShowRealtimeNotification("NEW_TASK")).isTrue()
        assertThat(shouldShowRealtimeNotification("URGENT_TASK")).isTrue()
        assertThat(shouldShowRealtimeNotification("MANDATORY_TASK")).isTrue()
        assertThat(shouldShowRealtimeNotification("TASK_JOIN_AVAILABLE")).isTrue()
        assertThat(shouldShowRealtimeNotification("TASK_UPDATED")).isFalse()
        assertThat(shouldShowRealtimeNotification("SYNC")).isFalse()
        assertThat(WorkerNotifications.workerNotificationMessage("NEW_TASK"))
            .isEqualTo("Появилось новое задание")
        assertThat(WorkerNotifications.workerNotificationMessage("URGENT_TASK"))
            .isEqualTo("Появилось срочное задание")
        assertThat(WorkerNotifications.workerNotificationMessage("TASK_JOIN_AVAILABLE"))
            .contains("Смежной группе")
    }
}
