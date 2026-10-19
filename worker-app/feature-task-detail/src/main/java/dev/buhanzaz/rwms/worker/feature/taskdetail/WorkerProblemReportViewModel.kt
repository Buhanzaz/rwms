package dev.buhanzaz.rwms.worker.feature.taskdetail

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.database.EncryptedEvidenceVariantPart
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerProblemReportDraftSnapshot
import dev.buhanzaz.rwms.worker.core.database.WorkerProblemReportStore
import dev.buhanzaz.rwms.worker.core.media.EncryptedEvidenceFileStore
import dev.buhanzaz.rwms.worker.core.network.safeWorkerUserMessage
import dev.buhanzaz.rwms.worker.core.sync.WorkerSyncScheduler
import dev.buhanzaz.rwms.worker.core.ui.decodeWorkerBitmap
import dev.buhanzaz.rwms.worker.core.ui.readWorkerImageBytes
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** A decrypted local preview is retained only while the report sheet is visible. */
sealed interface WorkerProblemReportPreview {
    data object Loading : WorkerProblemReportPreview
    data class Ready(val bitmap: Bitmap) : WorkerProblemReportPreview
    data class Failed(val message: String) : WorkerProblemReportPreview
}

/** State for one durable worker problem declaration. Server delivery remains asynchronous. */
data class WorkerProblemReportUiState(
    val loading: Boolean = true,
    val report: WorkerProblemReportDraftSnapshot? = null,
    val comment: String = "",
    val previews: Map<String, WorkerProblemReportPreview> = emptyMap(),
    val saving: Boolean = false,
    val error: String? = null,
    val dismissAfterSubmit: Boolean = false,
) {
    val isDraft: Boolean get() = report?.state == WorkerProblemReportStore.OUTBOX_DRAFT
    val canSubmit: Boolean get() = isDraft &&
        comment.trim().length in 1..WorkerProblemReportStore.MAX_COMMENT_LENGTH && !saving
    val remainingPhotos: Int get() =
        (WorkerProblemReportStore.MAX_ATTACHMENTS - report.orEmptyAttachments().size).coerceAtLeast(0)
}

private fun WorkerProblemReportDraftSnapshot?.orEmptyAttachments(): List<TaskEvidenceEntity> =
    this?.attachments.orEmpty()

/**
 * Persists report edits before network work is scheduled. It never marks an outbox report as
 * delivered: PENDING means only that its immutable declaration is queued locally.
 */
@HiltViewModel
class WorkerProblemReportViewModel internal constructor(
    private val problemReports: WorkerProblemReportStore,
    private val evidenceFileStore: EncryptedEvidenceFileStore,
    private val json: Json,
    private val requestSync: (String) -> Unit,
) : ViewModel() {
    @Inject
    constructor(
        problemReports: WorkerProblemReportStore,
        scheduler: WorkerSyncScheduler,
        evidenceFileStore: EncryptedEvidenceFileStore,
        json: Json,
    ) : this(
        problemReports = problemReports,
        evidenceFileStore = evidenceFileStore,
        json = json,
        requestSync = scheduler::requestAfterMutation,
    )

    private val mutableState = MutableStateFlow(WorkerProblemReportUiState())
    val uiState: StateFlow<WorkerProblemReportUiState> = mutableState.asStateFlow()
    private val durableWriteMutex = Mutex()
    private var commentSaveJob: Job? = null
    private var bindJob: Job? = null

    /** Opens the latest unresolved declaration, or a new draft after the previous one is reported. */
    fun bind(userId: String, entryId: String, routeIndex: Int) {
        bindJob?.cancel()
        mutableState.value = WorkerProblemReportUiState()
        bindJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val opened = try {
                withContext(Dispatchers.IO) { problemReports.openOrCreateDraft(userId, entryId, routeIndex) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = error.safeWorkerUserMessage("Не удалось открыть обращение. Обновите задание и повторите."),
                )
                return@launch
            }
            problemReports.observeEntryReports(userId, entryId).collectLatest { reports ->
                reports.firstOrNull { it.reportId == opened.reportId }?.let(::applyReport)
            }
        }
    }

    /** Each change is durably serialized; the final save is joined before a submission can freeze it. */
    fun updateComment(userId: String, comment: String) {
        val report = mutableState.value.report ?: return
        if (report.state != WorkerProblemReportStore.OUTBOX_DRAFT ||
            comment.length > WorkerProblemReportStore.MAX_COMMENT_LENGTH
        ) return
        mutableState.value = mutableState.value.copy(comment = comment, error = null)
        val previousSave = commentSaveJob
        commentSaveJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val failure = withContext(NonCancellable) {
                try {
                    previousSave?.join()
                    durableWriteMutex.withLock {
                        withContext(Dispatchers.IO) {
                            problemReports.updateDraftComment(userId, report.reportId, comment)
                        }
                    }
                    null
                } catch (error: Throwable) {
                    error
                }
            }
            if (failure != null) reportFailure(failure, "Не удалось сохранить текст обращения.")
        }
    }

    /** Releases only the sheet observer and transient previews; queued durable writes continue. */
    fun release() {
        bindJob?.cancel()
        bindJob = null
        mutableState.value.previews.values.filterIsInstance<WorkerProblemReportPreview.Ready>()
            .forEach { preview -> if (!preview.bitmap.isRecycled) preview.bitmap.recycle() }
        mutableState.value = WorkerProblemReportUiState()
    }

    /** Removes the Room association first, then deletes the corresponding encrypted files. */
    fun removePhoto(userId: String, evidenceId: String) {
        val report = mutableState.value.report ?: return
        if (!mutableState.value.isDraft || mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val failure = withContext(NonCancellable) {
                try {
                    durableWriteMutex.withLock {
                        val removed = withContext(Dispatchers.IO) {
                            problemReports.removeDraftPhoto(userId, report.reportId, evidenceId)
                        }
                        withContext(Dispatchers.IO) {
                            evidenceFileStore.deleteBundle(
                                removed.encryptedFilePath,
                                json.decodeFromString<List<EncryptedEvidenceVariantPart>>(removed.variantManifestJson),
                            )
                        }
                    }
                    null
                } catch (error: Throwable) {
                    error
                }
            }
            mutableState.value = mutableState.value.copy(saving = false)
            if (failure != null) reportFailure(failure, "Не удалось удалить фотографию.")
        }
    }

    /** Queues the durable immutable declaration, requests replay, and lets the sheet close. */
    fun submit(userId: String) {
        val report = mutableState.value.report ?: return
        if (!mutableState.value.canSubmit) return
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val failure = withContext(NonCancellable) {
                try {
                    commentSaveJob?.join()
                    durableWriteMutex.withLock {
                        withContext(Dispatchers.IO) {
                            problemReports.submitDraft(userId, report.reportId)
                        }
                    }
                    requestSync(userId)
                    null
                } catch (error: Throwable) {
                    error
                }
            }
            if (failure == null) {
                mutableState.value = mutableState.value.copy(saving = false, dismissAfterSubmit = true)
            } else {
                mutableState.value = mutableState.value.copy(saving = false)
                reportFailure(failure, "Не удалось поставить обращение в очередь.")
            }
        }
    }

    /** Retries only a server-rejected immutable command, preserving its original body and photos. */
    fun retry(userId: String) {
        val report = mutableState.value.report ?: return
        if (report.state !in RETRYABLE_STATES || mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val failure = withContext(NonCancellable) {
                try {
                    withContext(Dispatchers.IO) {
                        problemReports.retrySubmittedReport(userId, report.reportId)
                    }
                    requestSync(userId)
                    null
                } catch (error: Throwable) {
                    error
                }
            }
            mutableState.value = mutableState.value.copy(saving = false)
            if (failure != null) reportFailure(failure, "Не удалось повторить отправку обращения.")
        }
    }

    /** Decodes the encrypted SMALL WebP variant, never the original or transport-only larger files. */
    fun loadPreview(evidence: TaskEvidenceEntity) {
        if (mutableState.value.previews.containsKey(evidence.evidenceId)) return
        mutableState.value = mutableState.value.copy(
            previews = mutableState.value.previews + (evidence.evidenceId to WorkerProblemReportPreview.Loading),
        )
        viewModelScope.launch {
            val preview = try {
                val small = withContext(Dispatchers.IO) {
                    json.decodeFromString<List<EncryptedEvidenceVariantPart>>(evidence.variantManifestJson)
                        .firstOrNull { it.kind == SMALL_VARIANT }
                        ?: error("Миниатюра фотографии недоступна")
                }
                val bitmap = withContext(Dispatchers.IO) {
                    evidenceFileStore.openDecrypted(small.encryptedPath).use { input ->
                        decodeWorkerBitmap(input.readWorkerImageBytes(MAX_PREVIEW_BYTES), MAX_PREVIEW_PIXELS)
                    }
                }
                WorkerProblemReportPreview.Ready(bitmap)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                WorkerProblemReportPreview.Failed("Фото недоступно")
            }
            mutableState.value = mutableState.value.copy(
                previews = mutableState.value.previews + (evidence.evidenceId to preview),
            )
        }
    }

    override fun onCleared() {
        mutableState.value.previews.values.filterIsInstance<WorkerProblemReportPreview.Ready>()
            .forEach { preview -> if (!preview.bitmap.isRecycled) preview.bitmap.recycle() }
        super.onCleared()
    }

    private fun applyReport(report: WorkerProblemReportDraftSnapshot) {
        val current = mutableState.value
        val comment = if (current.report?.reportId == report.reportId && current.isDraft) {
            current.comment
        } else {
            report.comment
        }
        mutableState.value = current.copy(loading = false, report = report, comment = comment)
    }

    private fun reportFailure(error: Throwable, fallback: String) {
        mutableState.value = mutableState.value.copy(error = error.safeWorkerUserMessage(fallback))
    }

    private companion object {
        const val SMALL_VARIANT = "SMALL"
        const val MAX_PREVIEW_BYTES = 2 * 1024 * 1024
        const val MAX_PREVIEW_PIXELS = 256_000L
        val RETRYABLE_STATES = setOf(
            WorkerProblemReportStore.OUTBOX_REVIEW_REQUIRED,
            WorkerProblemReportStore.OUTBOX_CONFLICT,
        )
    }
}
