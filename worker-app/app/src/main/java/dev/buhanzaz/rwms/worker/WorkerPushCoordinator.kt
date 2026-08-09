package dev.buhanzaz.rwms.worker

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.buhanzaz.rwms.worker.core.auth.EncryptedAuthStateStore
import dev.buhanzaz.rwms.worker.core.auth.WorkerAuthRepository
import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.database.WorkerInvalidationEntity
import dev.buhanzaz.rwms.worker.core.network.GatewayProblemException
import dev.buhanzaz.rwms.worker.core.network.WorkerDeviceRegistrationRequestDto
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.sync.WorkerSyncScheduler
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.firebase.messaging.FirebaseMessaging

/** Push is an optional invalidation transport. It never decides task state or connectivity. */
sealed interface WorkerPushCapability {
    data object Unknown : WorkerPushCapability
    data object FirebaseUnavailable : WorkerPushCapability
    data object Available : WorkerPushCapability
    data class RegistrationEndpointUnavailable(val status: Int) : WorkerPushCapability
    data class TemporaryFailure(val message: String?) : WorkerPushCapability
}

/**
 * Defines worker application UI or lifecycle state; it does not decide a server task transition.
 */
data class WorkerPushInvalidation(
    val eventId: String,
    val revision: Long,
    val type: String,
    val entryId: String?,
) {
    companion object {
        /** Reject incomplete data messages; no task text or authority comes from FCM. */
        fun from(data: Map<String, String>): WorkerPushInvalidation? {
            val eventId = data["eventId"]?.takeIf(String::isNotBlank) ?: return null
            val revision = data["revision"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val type = data["type"]?.takeIf(String::isNotBlank) ?: return null
            return WorkerPushInvalidation(eventId, revision, type, data["entryId"]?.takeIf(String::isNotBlank))
        }
    }
}

@Singleton
/**
 * Defines worker application UI or lifecycle state; it does not decide a server task transition.
 */
class WorkerPushCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authStateStore: EncryptedAuthStateStore,
    private val auth: WorkerAuthRepository,
    private val gateway: WorkerGatewayClient,
    private val database: WorkerDatabase,
    private val scheduler: WorkerSyncScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrationMutex = Mutex()
    private val mutableCapability = MutableStateFlow<WorkerPushCapability>(WorkerPushCapability.Unknown)
    val capability: StateFlow<WorkerPushCapability> = mutableCapability.asStateFlow()

    /** Called only after /context proved an authenticated worker session. */
    fun registerAfterAuthenticatedContext(userId: String) {
        scope.launch { register(userId) }
    }

    /** Firebase calls this even when no activity exists. Store first, then renew if possible. */
    fun onNewToken(token: String) {
        if (token.isBlank()) return
        scope.launch {
            authStateStore.writeFcmToken(token)
            mutableCapability.value = WorkerPushCapability.Unknown
            val userId = auth.cachedWorkerIdentity()
            if (userId != null) register(userId)
        }
    }

    /**
     * Persists only an invalidation record and asks the existing unique work to
     * fetch authoritative state. Duplicate FCM messages are ignored by event ID.
     */
    fun onInvalidation(invalidation: WorkerPushInvalidation) {
        scope.launch {
            val userId = auth.cachedWorkerIdentity() ?: return@launch
            val inserted = database.invalidationDao().insert(
                WorkerInvalidationEntity(
                    eventId = invalidation.eventId,
                    userId = userId,
                    revision = invalidation.revision,
                    type = invalidation.type,
                    entryId = invalidation.entryId,
                    occurredAt = Instant.now().toString(),
                ),
            )
            if (inserted != -1L) {
                scheduler.request(userId)
                WorkerNotifications.show(context, invalidation)
            }
        }
    }

    /** Best effort while the bearer session is still valid; logout must never wait on it. */
    suspend fun unregisterBeforeLogout() {
        val installationId = authStateStore.readInstallationId() ?: return
        withTimeoutOrNull(3_000L) {
            runCatching { gateway.unregisterDevice(installationId) }
        }
    }

    private suspend fun register(userId: String): Unit = registrationMutex.withLock {
        if (auth.cachedWorkerIdentity() != userId) return@withLock
        if (capability.value is WorkerPushCapability.RegistrationEndpointUnavailable) return@withLock
        if (!WorkerNotifications.isFirebaseConfigured(context)) {
            mutableCapability.value = WorkerPushCapability.FirebaseUnavailable
            return@withLock
        }
        val storedToken = authStateStore.readFcmToken()
        val token = storedToken ?: requestFcmToken()
        if (token.isNullOrBlank()) {
            mutableCapability.value = WorkerPushCapability.FirebaseUnavailable
            return@withLock
        }
        if (storedToken == null) authStateStore.writeFcmToken(token)
        val storedInstallationId = authStateStore.readInstallationId()
        val installationId = storedInstallationId ?: UUID.randomUUID().toString()
        if (storedInstallationId == null) authStateStore.writeInstallationId(installationId)
        val request = WorkerDeviceRegistrationRequestDto(
            token = token,
            appVersion = appVersion(),
            sdkInt = Build.VERSION.SDK_INT,
            locale = Locale.getDefault().toLanguageTag(),
        )
        runCatching { gateway.registerDevice(installationId, request) }
            .onSuccess { mutableCapability.value = WorkerPushCapability.Available }
            .onFailure { error ->
                mutableCapability.value = when {
                    error is GatewayProblemException && error.problem.status in OPTIONAL_REGISTRATION_STATUSES ->
                        WorkerPushCapability.RegistrationEndpointUnavailable(error.problem.status)
                    else -> WorkerPushCapability.TemporaryFailure(error.message)
                }
            }
    }

    @Suppress("DEPRECATION")
    private suspend fun requestFcmToken(): String? = suspendCancellableCoroutine { continuation ->
        runCatching { FirebaseMessaging.getInstance().token }
            .onSuccess { task ->
                task.addOnCompleteListener { result ->
                    if (continuation.isActive) {
                        val token = if (result.isSuccessful) result.result else null
                        continuation.resume(token?.takeIf(String::isNotBlank))
                    }
                }
            }
            .onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }

    private companion object {
        val OPTIONAL_REGISTRATION_STATUSES = setOf(404, 501)
    }
}
