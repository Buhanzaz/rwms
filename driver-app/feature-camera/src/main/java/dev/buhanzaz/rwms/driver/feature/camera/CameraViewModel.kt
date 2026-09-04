package dev.buhanzaz.rwms.driver.feature.camera

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.driver.core.database.PendingEvidenceReservation
import dev.buhanzaz.rwms.driver.core.database.PendingShiftCommand
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.media.EncryptedEvidenceFileStore
import dev.buhanzaz.rwms.driver.core.sync.DriverSyncScheduler
import dev.buhanzaz.rwms.driver.core.network.DriverShiftPhotoDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import dev.buhanzaz.rwms.driver.core.network.toDriverUserMessage
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Defines driver feature UI state; server data and authorization remain authoritative.
 */
data class CameraUiState(
    val saving: Boolean = false,
    val savedEvidenceId: String? = null,
    val error: String? = null,
)

@HiltViewModel
/**
 * Defines driver feature UI state; server data and authorization remain authoritative.
 */
class CameraViewModel @Inject constructor(
    private val fileStore: EncryptedEvidenceFileStore,
    private val localStore: DriverLocalStore,
    private val scheduler: DriverSyncScheduler,
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
                mutableState.value = CameraUiState(
                    error = error.toDriverUserMessage(
                        "Не удалось сохранить фотографию. Сделайте снимок ещё раз.",
                    ),
                )
            }
        }
    }

    /** Saves Driver Shift evidence through the existing encrypted outbox and media pipeline. */
    fun confirmShiftCapture(userId: String, target: DriverCameraTarget.Shift, temporaryFile: File) {
        if (mutableState.value.saving) return
        mutableState.value = CameraUiState(saving = true)
        viewModelScope.launch {
            runCatching {
                val lease = requireNotNull(localStore.leaseFor(userId)) { "Сначала синхронизируйте смену" }
                require(lease.isLeaseActive(SystemClock.elapsedRealtime())) { "Срок офлайн-доступа истёк" }
                val snapshot = requireNotNull(localStore.cachedShiftSnapshot(userId)) { "Смена не загружена" }
                val today = json.decodeFromString<TodayDriverShiftDto>(snapshot.serializedTodayShift)
                val shift = requireNotNull(today.shift) { "Смена недоступна" }
                require(shift.id == target.shiftId) { "Состояние смены изменилось" }
                require(shift.version >= target.expectedVersion) { "Обновите состояние смены" }
                val evidenceId = UUID.randomUUID().toString()
                val capturedAt = Instant.ofEpochMilli(
                    lease.estimatedServerNow(SystemClock.elapsedRealtime()),
                ).toString()
                val persisted = fileStore.persistJpeg(userId, evidenceId, temporaryFile)
                val localPhoto = DriverShiftPhotoDto(
                    id = evidenceId,
                    clientReferenceId = evidenceId,
                    evidenceId = evidenceId,
                    role = target.role,
                    defectId = target.defectId,
                    inspectionItemId = target.inspectionItemId,
                    state = "PENDING_SYNC",
                    capturedAt = capturedAt,
                    contentType = "image/jpeg",
                    sizeBytes = persisted.plainSizeBytes,
                    sha256 = persisted.sha256,
                )
                val optimisticInspection = today.inspection?.let { inspection ->
                    inspection.copy(
                        items = inspection.items.map { item ->
                            val defect = item.defect
                            if (item.id == target.inspectionItemId && defect != null && defect.id == target.defectId) {
                                item.copy(defect = defect.copy(photoIds = defect.photoIds + evidenceId))
                            } else {
                                item
                            }
                        },
                    )
                }
                val optimistic = today.copy(
                    shift = shift.copy(version = shift.version + 1),
                    inspection = optimisticInspection,
                    photos = today.photos + localPhoto,
                )
                val optimisticEntity = snapshot.copy(
                    shiftId = shift.id,
                    workDate = shift.workDate,
                    nextRequiredAction = optimistic.nextRequiredAction,
                    serializedTodayShift = json.encodeToString(optimistic),
                    serverTime = optimistic.serverTime,
                )
                val command = PendingShiftCommand(
                    operationId = evidenceId,
                    shiftId = shift.id,
                    action = "PHOTO_RESERVATION",
                    expectedVersion = shift.version,
                    clientReferenceId = evidenceId,
                    evidenceId = evidenceId,
                    photoRole = target.role,
                    defectId = target.defectId,
                    inspectionItemId = target.inspectionItemId,
                    capturedAt = capturedAt,
                    contentType = "image/jpeg",
                    sizeBytes = persisted.plainSizeBytes,
                    sha256 = persisted.sha256,
                )
                try {
                    localStore.enqueueShiftPhotoReservation(
                        userId = userId,
                        command = command,
                        optimisticSnapshot = optimisticEntity,
                        encryptedFilePath = persisted.encryptedPath,
                        fileName = "$evidenceId.jpg",
                    )
                } catch (error: Throwable) {
                    fileStore.delete(persisted.encryptedPath)
                    throw error
                }
                scheduler.request(userId)
                evidenceId
            }.onSuccess { evidenceId ->
                mutableState.value = CameraUiState(savedEvidenceId = evidenceId)
            }.onFailure { error ->
                temporaryFile.delete()
                mutableState.value = CameraUiState(
                    error = error.toDriverUserMessage(
                        "Не удалось сохранить фотографию. Сделайте снимок ещё раз.",
                    ),
                )
            }
        }
    }
}

internal fun CameraUiState.consumeSavedCapture(evidenceId: String): CameraUiState =
    if (savedEvidenceId == evidenceId) CameraUiState() else this
