package dev.buhanzaz.rwms.worker.feature.camera

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.database.PendingEvidenceReservation
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.media.EncryptedEvidenceFileStore
import dev.buhanzaz.rwms.worker.core.media.WorkerEvidenceBundlePreparer
import dev.buhanzaz.rwms.worker.core.media.WorkerGalleryJpegImporter
import dev.buhanzaz.rwms.worker.core.sync.WorkerSyncScheduler
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tracks capture/import progress, partial camera-batch persistence and a one-shot immutable list
 * of saved evidence identifiers.
 */
data class CameraUiState(
    val saving: Boolean = false,
    val savedEvidenceIds: List<String> = emptyList(),
    val persistedCapturePaths: Set<String> = emptySet(),
    val persistedCameraCaptureCount: Int = 0,
    val error: String? = null,
)

@HiltViewModel
/**
 * Owns CameraX capture and picker-import persistence while task completion remains task-owned.
 */
class CameraViewModel @Inject constructor(
    private val fileStore: EncryptedEvidenceFileStore,
    private val bundlePreparer: WorkerEvidenceBundlePreparer,
    private val galleryImporter: WorkerGalleryJpegImporter,
    private val localStore: WorkerLocalStore,
    private val scheduler: WorkerSyncScheduler,
    private val json: Json,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CameraUiState())
    private val accumulatedCameraEvidenceIds = mutableListOf<String>()
    private val accumulatedCameraCapturePaths = linkedSetOf<String>()
    val uiState: StateFlow<CameraUiState> = mutableState.asStateFlow()

    /** Saved captures are one-shot navigation results, never durable route state. */
    fun consumeSavedCaptures(evidenceIds: List<String>) {
        val current = mutableState.value
        val consumed = current.consumeSavedCaptures(evidenceIds)
        if (consumed !== current) {
            accumulatedCameraEvidenceIds.clear()
            accumulatedCameraCapturePaths.clear()
        }
        mutableState.value = consumed
    }

    /** Clears the acknowledgement after the camera removes already persisted transient files. */
    fun consumePersistedCaptures(filePaths: Set<String>) {
        mutableState.value = mutableState.value.consumePersistedCaptures(filePaths)
    }

    /**
     * Converts one confirmed CameraX batch into encrypted WebP evidence in capture order.
     *
     * Successful files are acknowledged once and excluded from a retry. If part of the batch
     * fails, already durable evidence IDs remain accumulated while only failed transient files
     * stay in the camera gallery. Completion flows defer their final scheduling until the matching
     * task action enters the outbox; ordinary capture requests sync after the batch.
     */
    fun confirmCaptures(
        userId: String,
        entryId: String,
        routeIndex: Int,
        temporaryFiles: List<File>,
        requestSyncAfterSave: Boolean = true,
    ) {
        if (mutableState.value.saving) return
        val selectedFiles = remainingWorkerCameraCaptures(
            files = temporaryFiles.distinctBy(File::getAbsolutePath),
            persistedPaths = accumulatedCameraCapturePaths,
        )
        if (selectedFiles.isEmpty()) {
            mutableState.value = if (accumulatedCameraEvidenceIds.isEmpty()) {
                CameraUiState(error = EMPTY_CAMERA_BATCH_ERROR)
            } else {
                CameraUiState(savedEvidenceIds = accumulatedCameraEvidenceIds.toList())
            }
            return
        }
        mutableState.value = CameraUiState(saving = true)
        viewModelScope.launch {
            val saved = mutableListOf<Pair<File, String>>()
            val failures = mutableListOf<Exception>()
            try {
                withContext(Dispatchers.IO) {
                    selectedFiles.forEach { temporaryFile ->
                        try {
                            val evidenceId = saveEvidence(
                                userId,
                                entryId,
                                routeIndex,
                                temporaryFile,
                            )
                            saved += temporaryFile to evidenceId
                            temporaryFile.delete()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failures += error
                        }
                    }
                }
            } catch (error: CancellationException) {
                if (saved.isNotEmpty()) runCatching { scheduler.request(userId) }
                throw error
            }

            accumulatedCameraEvidenceIds += saved.map { it.second }
            accumulatedCameraCapturePaths += saved.map { it.first.absolutePath }
            val syncFailure = if (
                accumulatedCameraEvidenceIds.isNotEmpty() &&
                (failures.isNotEmpty() || requestSyncAfterSave)
            ) {
                runCatching { scheduler.request(userId) }.exceptionOrNull()
            } else {
                null
            }
            if (failures.isEmpty()) {
                mutableState.value = CameraUiState(
                    savedEvidenceIds = accumulatedCameraEvidenceIds.toList(),
                    error = syncFailure?.let { failure ->
                        "Фотографии сохранены, но синхронизация не запущена: " +
                            (failure.message ?: "неизвестная ошибка")
                    },
                )
            } else {
                val batchFailure = cameraBatchFailureMessage(
                    savedCount = saved.size,
                    selectedCount = selectedFiles.size,
                    causeMessage = failures.firstNotNullOfOrNull { it.message },
                )
                mutableState.value = CameraUiState(
                    persistedCapturePaths = saved.mapTo(linkedSetOf()) { it.first.absolutePath },
                    persistedCameraCaptureCount = accumulatedCameraEvidenceIds.size,
                    error = if (syncFailure == null) {
                        batchFailure
                    } else {
                        "$batchFailure Синхронизация сохранённых фото не запущена: " +
                            (syncFailure.message ?: "неизвестная ошибка")
                    },
                )
            }
        }
    }

    /**
     * Imports every selected photo-picker URI sequentially through the encrypted evidence path.
     * One sync is requested after a complete batch when requested. A partial batch is also synced
     * so its already durable evidence cannot remain stranded, but it is reported as an error.
     */
    fun confirmGallery(
        userId: String,
        entryId: String,
        routeIndex: Int,
        uris: List<Uri>,
        requestSyncAfterSave: Boolean = true,
    ) {
        if (mutableState.value.saving) return
        val selectedUris = uris.toList()
        if (selectedUris.isEmpty()) {
            mutableState.value = CameraUiState(error = EMPTY_GALLERY_SELECTION_ERROR)
            return
        }
        mutableState.value = CameraUiState(saving = true)
        viewModelScope.launch {
            val savedEvidenceIds = mutableListOf<String>()
            val failures = mutableListOf<Exception>()
            try {
                withContext(Dispatchers.IO) {
                    selectedUris.forEach { uri ->
                        try {
                            val temporaryFile = galleryImporter.`import`(uri)
                            try {
                                savedEvidenceIds += saveEvidence(
                                    userId,
                                    entryId,
                                    routeIndex,
                                    temporaryFile,
                                )
                            } finally {
                                temporaryFile.delete()
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failures += error
                        }
                    }
                }
            } catch (error: CancellationException) {
                if (savedEvidenceIds.isNotEmpty()) runCatching { scheduler.request(userId) }
                throw error
            }

            val syncFailure = if (
                savedEvidenceIds.isNotEmpty() &&
                (failures.isNotEmpty() || requestSyncAfterSave)
            ) {
                runCatching { scheduler.request(userId) }.exceptionOrNull()
            } else {
                null
            }
            if (failures.isEmpty()) {
                mutableState.value = if (syncFailure == null) {
                    CameraUiState(savedEvidenceIds = savedEvidenceIds.toList())
                } else {
                    CameraUiState(
                        error = "Фотографии сохранены, но синхронизация не запущена: " +
                            (syncFailure.message ?: "неизвестная ошибка"),
                    )
                }
            } else {
                val importFailure = galleryBatchFailureMessage(
                    savedCount = savedEvidenceIds.size,
                    selectedCount = selectedUris.size,
                    causeMessage = failures.firstNotNullOfOrNull { it.message },
                )
                mutableState.value = CameraUiState(
                    error = if (syncFailure == null) {
                        importFailure
                    } else {
                        "$importFailure Синхронизация сохранённых фото не запущена: " +
                            (syncFailure.message ?: "неизвестная ошибка")
                    },
                )
            }
        }
    }

    private suspend fun saveEvidence(
        userId: String,
        entryId: String,
        routeIndex: Int,
        temporaryFile: File,
    ): String = withContext(Dispatchers.IO) {
        val lease = requireNotNull(localStore.leaseFor(userId)) { "Сначала синхронизируйте задание" }
        require(lease.isLeaseActive(SystemClock.elapsedRealtime())) { "Срок офлайн-доступа истёк" }
        val evidenceId = UUID.randomUUID().toString()
        val capturedAt = Instant.ofEpochMilli(
            lease.estimatedServerNow(SystemClock.elapsedRealtime()),
        ).toString()
        val persisted = bundlePreparer.prepareAndPersist(userId, evidenceId, temporaryFile)
        val reservation = PendingEvidenceReservation(
            operationId = evidenceId,
            evidenceId = evidenceId,
            routeIndex = routeIndex,
            capturedAt = capturedAt,
            offlineLeaseId = requireNotNull(localStore.currentLeaseId(userId)),
            contentType = "image/webp",
            sizeBytes = persisted.aggregateContentLength,
            sha256 = persisted.manifestSha256,
        )
        try {
            localStore.enqueueEvidenceReservation(
                userId = userId,
                entryId = entryId,
                evidenceId = evidenceId,
                encryptedFilePath = persisted.originalEncryptedPath,
                fileName = "$evidenceId.webp",
                routeIndex = routeIndex,
                capturedAt = capturedAt,
                sizeBytes = persisted.aggregateContentLength,
                sha256 = persisted.manifestSha256,
                variantManifestJson = json.encodeToString(persisted.variants),
                reservationPayload = json.encodeToString(reservation),
            )
        } catch (error: Throwable) {
            fileStore.deleteBundle(persisted.originalEncryptedPath, persisted.variants)
            throw error
        }
        evidenceId
    }
}

internal fun CameraUiState.consumeSavedCaptures(evidenceIds: List<String>): CameraUiState =
    if (savedEvidenceIds.isNotEmpty() && savedEvidenceIds == evidenceIds) CameraUiState() else this

/** Clears only the exact partial-batch acknowledgement consumed by the active camera screen. */
internal fun CameraUiState.consumePersistedCaptures(filePaths: Set<String>): CameraUiState =
    if (persistedCapturePaths.isNotEmpty() && persistedCapturePaths == filePaths) {
        copy(persistedCapturePaths = emptySet())
    } else {
        this
    }

/** Builds an honest user-facing result for a partially persisted CameraX batch. */
internal fun cameraBatchFailureMessage(
    savedCount: Int,
    selectedCount: Int,
    causeMessage: String?,
): String {
    val cause = causeMessage ?: "Не удалось сохранить фотографию"
    return if (savedCount > 0) {
        "Сохранено $savedCount из $selectedCount фото. Остальные не сохранены: $cause"
    } else {
        cause
    }
}

/** Builds an honest user-facing result for a failed multi-image import. */
internal fun galleryBatchFailureMessage(
    savedCount: Int,
    selectedCount: Int,
    causeMessage: String?,
): String {
    val cause = causeMessage ?: "Не удалось добавить фотографию"
    return if (savedCount > 0) {
        "Добавлено $savedCount из $selectedCount фото. Остальные не добавлены: $cause"
    } else {
        cause
    }
}

private const val EMPTY_GALLERY_SELECTION_ERROR = "Выберите хотя бы одну фотографию"
private const val EMPTY_CAMERA_BATCH_ERROR = "Сделайте хотя бы одну фотографию"
