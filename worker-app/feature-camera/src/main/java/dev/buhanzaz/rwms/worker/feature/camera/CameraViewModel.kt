package dev.buhanzaz.rwms.worker.feature.camera

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.database.PendingEvidenceReservation
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.media.EncryptedEvidenceFileStore
import dev.buhanzaz.rwms.worker.core.sync.WorkerSyncScheduler
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Defines worker feature UI state; server data and authorization remain authoritative.
 */
data class CameraUiState(
    val saving: Boolean = false,
    val savedEvidenceId: String? = null,
    val error: String? = null,
)

@HiltViewModel
/**
 * Defines worker feature UI state; server data and authorization remain authoritative.
 */
class CameraViewModel @Inject constructor(
    private val fileStore: EncryptedEvidenceFileStore,
    private val localStore: WorkerLocalStore,
    private val scheduler: WorkerSyncScheduler,
    private val json: Json,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = mutableState.asStateFlow()

    /** A saved capture is a one-shot navigation result, never route state. */
    fun consumeSavedCapture(evidenceId: String) {
        mutableState.value = mutableState.value.consumeSavedCapture(evidenceId)
    }

    fun confirmCapture(userId: String, entryId: String, routeIndex: Int, temporaryFile: File) {
        if (mutableState.value.saving) return
        mutableState.value = CameraUiState(saving = true)
        viewModelScope.launch {
            runCatching {
                val lease = requireNotNull(localStore.leaseFor(userId)) { "Сначала синхронизируйте задание" }
                require(lease.isLeaseActive(SystemClock.elapsedRealtime())) { "Срок офлайн-доступа истёк" }
                val evidenceId = UUID.randomUUID().toString()
                val capturedAt = Instant.ofEpochMilli(
                    lease.estimatedServerNow(SystemClock.elapsedRealtime()),
                ).toString()
                val persisted = fileStore.persistJpeg(userId, evidenceId, temporaryFile)
                val reservation = PendingEvidenceReservation(
                    operationId = evidenceId,
                    evidenceId = evidenceId,
                    routeIndex = routeIndex,
                    capturedAt = capturedAt,
                    offlineLeaseId = requireNotNull(localStore.currentLeaseId(userId)),
                    contentType = "image/jpeg",
                    sizeBytes = persisted.plainSizeBytes,
                    sha256 = persisted.sha256,
                )
                try {
                    localStore.enqueueEvidenceReservation(
                        userId = userId,
                        entryId = entryId,
                        evidenceId = evidenceId,
                        encryptedFilePath = persisted.encryptedPath,
                        fileName = "$evidenceId.jpg",
                        routeIndex = routeIndex,
                        capturedAt = capturedAt,
                        sizeBytes = persisted.plainSizeBytes,
                        sha256 = persisted.sha256,
                        reservationPayload = json.encodeToString(reservation),
                    )
                } catch (error: Throwable) {
                    // Encryption succeeded but the atomic Room transaction did
                    // not. Do not leave an orphaned encrypted JPEG behind.
                    fileStore.delete(persisted.encryptedPath)
                    throw error
                }
                scheduler.request(userId)
                evidenceId
            }.onSuccess { evidenceId ->
                mutableState.value = CameraUiState(savedEvidenceId = evidenceId)
            }.onFailure { error ->
                temporaryFile.delete()
                mutableState.value = CameraUiState(error = error.message ?: "Не удалось сохранить фотографию")
            }
        }
    }
}

internal fun CameraUiState.consumeSavedCapture(evidenceId: String): CameraUiState =
    if (savedEvidenceId == evidenceId) CameraUiState() else this
