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

/** Tracks capture/import progress and a one-shot immutable list of saved evidence identifiers. */
data class CameraUiState(
    val saving: Boolean = false,
    val savedEvidenceIds: List<String> = emptyList(),
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
    val uiState: StateFlow<CameraUiState> = mutableState.asStateFlow()

    /** Saved captures are one-shot navigation results, never durable route state. */
    fun consumeSavedCaptures(evidenceIds: List<String>) {
        mutableState.value = mutableState.value.consumeSavedCaptures(evidenceIds)
    }

    /**
     * Converts a confirmed CameraX result into encrypted WebP evidence. Completion flows defer
     * scheduling until their action is in the outbox; ordinary evidence capture schedules immediately.
     */
    fun confirmCapture(
        userId: String,
        entryId: String,
        routeIndex: Int,
        temporaryFile: File,
        requestSyncAfterSave: Boolean = true,
    ) {
        if (mutableState.value.saving) return
        mutableState.value = CameraUiState(saving = true)
        viewModelScope.launch {
            runCatching {
                saveEvidence(userId, entryId, routeIndex, temporaryFile).also {
                    if (requestSyncAfterSave) scheduler.request(userId)
                }
            }.onSuccess { evidenceId ->
                mutableState.value = CameraUiState(savedEvidenceIds = listOf(evidenceId))
            }.onFailure { error ->
                temporaryFile.delete()
                mutableState.value = CameraUiState(error = error.message ?: "Не удалось сохранить фотографию")
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
