package dev.buhanzaz.rwms.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkerRealtimeNotificationAlertTest {
    @Test
    fun `only mandatory task invalidation selects a realtime notification`() {
        assertThat(shouldShowRealtimeNotification("MANDATORY_TASK")).isTrue()
        assertThat(shouldShowRealtimeNotification("TASK_UPDATED")).isFalse()
        assertThat(shouldShowRealtimeNotification("SYNC")).isFalse()
    }
}
