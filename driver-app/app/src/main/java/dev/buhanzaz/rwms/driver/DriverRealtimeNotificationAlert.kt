package dev.buhanzaz.rwms.driver

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.buhanzaz.rwms.driver.core.sync.DriverRealtimeInvalidation
import dev.buhanzaz.rwms.driver.core.sync.DriverRealtimeInvalidationAlert
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
class DriverRealtimeNotificationAlert @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DriverRealtimeInvalidationAlert {
    override fun onInserted(invalidation: DriverRealtimeInvalidation) {
        if (!shouldShowRealtimeNotification(invalidation.type)) return
        DriverNotifications.show(
            context,
            DriverPushInvalidation(
                eventId = invalidation.eventId,
                revision = invalidation.revision,
                type = invalidation.type,
                entryId = invalidation.entryId,
            ),
        )
    }
}

internal fun shouldShowRealtimeNotification(type: String): Boolean =
    type == "NEW_TASK" ||
        type == "URGENT_TASK" ||
        type == "MANDATORY_TASK"
