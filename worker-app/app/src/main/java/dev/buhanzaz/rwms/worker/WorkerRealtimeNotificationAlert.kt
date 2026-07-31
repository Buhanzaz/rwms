package dev.buhanzaz.rwms.worker

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.buhanzaz.rwms.worker.core.sync.WorkerRealtimeInvalidation
import dev.buhanzaz.rwms.worker.core.sync.WorkerRealtimeInvalidationAlert
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerRealtimeNotificationAlert @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WorkerRealtimeInvalidationAlert {
    override fun onInserted(invalidation: WorkerRealtimeInvalidation) {
        if (!shouldShowRealtimeNotification(invalidation.type)) return
        WorkerNotifications.show(
            context,
            WorkerPushInvalidation(
                eventId = invalidation.eventId,
                revision = invalidation.revision,
                type = invalidation.type,
                entryId = invalidation.entryId,
            ),
        )
    }
}

internal fun shouldShowRealtimeNotification(type: String): Boolean =
    type == "MANDATORY_TASK" || type == "TASK_JOIN_AVAILABLE"
