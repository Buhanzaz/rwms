package dev.buhanzaz.rwms.driver

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.buhanzaz.rwms.driver.core.auth.EncryptedAuthStateStore
import dev.buhanzaz.rwms.driver.core.auth.DriverAuthRepository
import dev.buhanzaz.rwms.driver.core.database.DriverDatabase
import dev.buhanzaz.rwms.driver.core.database.DriverInvalidationEntity
import dev.buhanzaz.rwms.driver.core.network.GatewayProblemException
import dev.buhanzaz.rwms.driver.core.network.DriverDeviceRegistrationRequestDto
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayClient
import dev.buhanzaz.rwms.driver.core.network.toDriverUserMessage
import dev.buhanzaz.rwms.driver.core.sync.DriverSyncScheduler
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
import com.google.firebase.installations.FirebaseInstallations

/** Push is an optional invalidation transport. It never decides task state or connectivity. */
sealed interface DriverPushCapability {
    /** Registration has not yet been attempted for the authenticated context. */
    data object Unknown : DriverPushCapability
    /** This build or device has no usable Firebase configuration. */
    data object FirebaseUnavailable : DriverPushCapability
    /** The current Firebase Installation ID is registered with task-board. */
    data object Available : DriverPushCapability
    /** The deployed task-board does not expose optional device registration. */
    data class RegistrationEndpointUnavailable(val status: Int) : DriverPushCapability
    /** A retryable registration attempt failed without changing task authority. */
    data class TemporaryFailure(val message: String?) : DriverPushCapability
}

/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
data class DriverPushInvalidation(
    val eventId: String,
    val revision: Long,
    val type: String,
    val entryId: String?,
) {
    companion object {
        /** Reject incomplete data messages; no task text or authority comes from FCM. */
        fun from(data: Map<String, String>): DriverPushInvalidation? {
            val eventId = data["eventId"]?.takeIf(String::isNotBlank) ?: return null
            val revision = data["revision"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val type = data["type"]?.takeIf(String::isNotBlank) ?: return null
            if (type == "TASK_JOIN_AVAILABLE") return null
            return DriverPushInvalidation(eventId, revision, type, data["entryId"]?.takeIf(String::isNotBlank))
        }
    }
}

@Singleton
/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
class DriverPushCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authStateStore: EncryptedAuthStateStore,
    private val auth: DriverAuthRepository,
    private val gateway: DriverGatewayClient,
    private val database: DriverDatabase,
    private val scheduler: DriverSyncScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrationMutex = Mutex()
    private val mutableCapability = MutableStateFlow<DriverPushCapability>(DriverPushCapability.Unknown)
    val capability: StateFlow<DriverPushCapability> = mutableCapability.asStateFlow()

    /** Called only after /context proved an authenticated driver session. */
    fun registerAfterAuthenticatedContext(userId: String) {
        scope.launch { register(userId) }
    }

    /** Firebase calls this when its transport registration changes; renew the server-side FID binding. */
    fun onFirebaseRegistrationChanged() {
        scope.launch {
            mutableCapability.value = DriverPushCapability.Unknown
            val userId = auth.cachedDriverIdentity()
            if (userId != null) register(userId)
        }
    }

    /**
     * Persists only an invalidation record and asks the existing unique work to
     * fetch authoritative state. Duplicate FCM messages are ignored by event ID.
     */
    fun onInvalidation(invalidation: DriverPushInvalidation) {
        scope.launch {
            val userId = auth.cachedDriverIdentity() ?: return@launch
            val inserted = database.invalidationDao().insert(
                DriverInvalidationEntity(
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
                DriverNotifications.show(context, invalidation)
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
        if (auth.cachedDriverIdentity() != userId) return@withLock
        if (capability.value is DriverPushCapability.RegistrationEndpointUnavailable) return@withLock
        if (!DriverNotifications.isFirebaseConfigured(context)) {
            mutableCapability.value = DriverPushCapability.FirebaseUnavailable
            return@withLock
        }
        val firebaseInstallationId = requestFirebaseInstallationId()
        if (firebaseInstallationId.isNullOrBlank()) {
            mutableCapability.value = DriverPushCapability.FirebaseUnavailable
            return@withLock
        }
        val storedInstallationId = authStateStore.readInstallationId()
        val installationId = storedInstallationId ?: UUID.randomUUID().toString()
        if (storedInstallationId == null) authStateStore.writeInstallationId(installationId)
        val request = driverDeviceRegistrationRequest(
            firebaseInstallationId = firebaseInstallationId,
            appVersion = appVersion(),
            sdkInt = Build.VERSION.SDK_INT,
            locale = Locale.getDefault().toLanguageTag(),
        )
        runCatching { gateway.registerDevice(installationId, request) }
            .onSuccess { mutableCapability.value = DriverPushCapability.Available }
            .onFailure { error ->
                mutableCapability.value = when {
                    error is GatewayProblemException && error.problem.status in OPTIONAL_REGISTRATION_STATUSES ->
                        DriverPushCapability.RegistrationEndpointUnavailable(error.problem.status)
                    else -> DriverPushCapability.TemporaryFailure(
                        error.toDriverUserMessage(
                            "Не удалось включить уведомления. Обновите задания вручную.",
                        ),
                    )
                }
            }
    }

    private suspend fun requestFirebaseInstallationId(): String? = suspendCancellableCoroutine { continuation ->
        runCatching { FirebaseInstallations.getInstance().id }
            .onSuccess { task ->
                task.addOnCompleteListener { result ->
                    if (continuation.isActive) {
                        val installationId = if (result.isSuccessful) result.result else null
                        continuation.resume(installationId?.takeIf(String::isNotBlank))
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

/** Builds the FID registration payload without exposing a deprecated FCM registration token. */
internal fun driverDeviceRegistrationRequest(
    firebaseInstallationId: String,
    appVersion: String,
    sdkInt: Int,
    locale: String,
): DriverDeviceRegistrationRequestDto = DriverDeviceRegistrationRequestDto(
    targetKind = "FID",
    token = firebaseInstallationId,
    appVersion = appVersion,
    sdkInt = sdkInt,
    locale = locale,
)
