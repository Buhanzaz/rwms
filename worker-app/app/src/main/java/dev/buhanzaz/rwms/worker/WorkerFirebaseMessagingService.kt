package dev.buhanzaz.rwms.worker

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** FCM contains only a revision/event identifier; it is never a task data source. */
@AndroidEntryPoint
class WorkerFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var push: WorkerPushCoordinator

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        if (token.isNotBlank()) push.onMessagingTokenChanged()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        WorkerPushInvalidation.from(message.data)?.let(push::onInvalidation)
    }
}
