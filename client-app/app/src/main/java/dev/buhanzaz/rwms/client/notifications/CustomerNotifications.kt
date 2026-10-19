package dev.buhanzaz.rwms.client.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.buhanzaz.rwms.client.MainActivity
import dev.buhanzaz.rwms.client.R
import dev.buhanzaz.rwms.client.auth.CustomerAuthRepository
import dev.buhanzaz.rwms.client.auth.CustomerAuthState
import dev.buhanzaz.rwms.client.data.CustomerApiException
import dev.buhanzaz.rwms.client.data.CustomerNotification
import dev.buhanzaz.rwms.client.data.CustomerRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Delivers server inbox messages, never inventing expiry from a device timer or marking them read. */
@Singleton
class CustomerNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: CustomerAuthRepository,
    private val repository: CustomerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val delivered = context.getSharedPreferences("customer_notification_delivery", Context.MODE_PRIVATE)
    private val work by lazy { WorkManager.getInstance(context) }
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Starts process-level login observation; only encrypted remembered sessions survive restarts. */
    fun start() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Заказы и резерв", NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch {
            auth.state.collectLatest { state ->
                when (state) {
                    CustomerAuthState.SignedIn -> {
                        work.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP,
                            PeriodicWorkRequestBuilder<CustomerInboxWorker>(15, TimeUnit.MINUTES)
                                .setConstraints(connected).addTag(TAG).build())
                        schedule("signin", 0)
                    }
                    is CustomerAuthState.SignedOut -> clear()
                    else -> Unit
                }
            }
        }
    }

    /** Best-effort background check after the server deadline; Android may defer its execution. */
    fun schedule(orderId: String, remainingMillis: Long) {
        if (auth.notificationSession() == null) return
        work.enqueueUniqueWork("$TAG:$orderId", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CustomerInboxWorker>()
                .setConstraints(connected).setInitialDelay(remainingMillis.coerceAtLeast(0) + 5_000, TimeUnit.MILLISECONDS)
                .addTag(TAG).build())
    }

    /** Worker and foreground reads use the same API and account fence. */
    suspend fun fetchAndPublish(): List<CustomerNotification> {
        val marker = auth.notificationSession() ?: return emptyList()
        val items = repository.notifications()
        if (auth.notificationSession() != marker) throw CancellationException("Customer session changed")
        publish(items, marker)
        return items
    }

    @Synchronized
    private fun publish(items: List<CustomerNotification>, marker: String) {
        if (auth.notificationSession() != marker || !manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val seen = delivered.getStringSet("ids", emptySet()).orEmpty().toMutableSet()
        items.filter { it.readAt == null && it.kind == "PAYMENT_EXPIRED" && it.id !in seen }.forEach { item ->
            if (auth.notificationSession() != marker) return
            manager.notify(item.id, 1, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Срок оплаты истёк")
                .setContentText("Резерв снят. Подробности — в разделе «Мои заказы».")
                .setContentIntent(customerNotificationIntent(context))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setOnlyAlertOnce(true).build())
            seen += item.id
        }
        // Delivery receipts are UI preferences, not domain state or proof that the user read a message.
        delivered.edit().putStringSet("ids", seen.toList().takeLast(1_000).toSet()).apply()
    }

    /** Removes the OS card only after a successful explicit server acknowledgement. */
    fun acknowledged(id: String) { manager.cancel(id, 1) }

    @Synchronized
    private fun clear() {
        work.cancelAllWorkByTag(TAG)
        manager.cancelAll()
        delivered.edit().clear().apply()
    }

    private companion object {
        const val CHANNEL = "customer_orders"
        const val TAG = "customer_inbox"
    }
}

/** Explicit immutable navigation with no token, URI, order payload, or caller-supplied target. */
internal fun customerNotificationIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

/** Access to existing singleton graph without introducing a second HTTP/authentication stack. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CustomerInboxDependencies {
    fun auth(): CustomerAuthRepository
    fun notifications(): CustomerNotifications
}

/** Bounded network catch-up; OS delivery timing does not control the server reservation. */
class CustomerInboxWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, CustomerInboxDependencies::class.java)
        return try {
            val auth = withTimeout(15_000) { dependencies.auth().state.first { it != CustomerAuthState.Loading } }
            if (auth != CustomerAuthState.SignedIn) return Result.success()
            dependencies.notifications().fetchAndPublish()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CustomerApiException) {
            if (failure.status in 400..499 || runAttemptCount >= 7) Result.failure() else Result.retry()
        } catch (_: Exception) {
            if (runAttemptCount >= 7) Result.failure() else Result.retry()
        }
    }
}
