package dev.buhanzaz.rwms.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkerRealtimeNotificationAlertTest {
    @Test
    fun `mandatory and optional join invalidations select a realtime notification`() {
        assertThat(shouldShowRealtimeNotification("MANDATORY_TASK")).isTrue()
        assertThat(shouldShowRealtimeNotification("TASK_JOIN_AVAILABLE")).isTrue()
        assertThat(shouldShowRealtimeNotification("TASK_UPDATED")).isFalse()
        assertThat(shouldShowRealtimeNotification("SYNC")).isFalse()
    }
}
