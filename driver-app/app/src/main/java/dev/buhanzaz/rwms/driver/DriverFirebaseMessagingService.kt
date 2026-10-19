package dev.buhanzaz.rwms.driver

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** FCM contains only a revision/event identifier; it is never a task data source. */
@AndroidEntryPoint
class DriverFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var push: DriverPushCoordinator

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        push.onFirebaseRegistrationChanged()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        DriverPushInvalidation.from(message.data)?.let(push::onInvalidation)
    }
}
