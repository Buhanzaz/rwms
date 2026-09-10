package dev.buhanzaz.rwms.worker.feature.taskdetail

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.database.OptimisticAction
import dev.buhanzaz.rwms.worker.core.database.PendingEvidenceReservation
import dev.buhanzaz.rwms.worker.core.database.PendingWorkerAction
import dev.buhanzaz.rwms.worker.core.database.ServerTimeAnchor
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerProblemReportStore
import dev.buhanzaz.rwms.worker.core.database.WorkerSessionEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.network.WorkerKpiPaletteDto
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskDetailDto
import dev.buhanzaz.rwms.worker.core.network.safeWorkerUserMessage
import dev.buhanzaz.rwms.worker.core.sync.WorkerProjectionWriter
import dev.buhanzaz.rwms.worker.core.sync.WorkerSyncScheduler
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private data class DetailKey(val userId: String, val entryId: String)

/** A newer committed feed projection must replace an older detail before the UI renders its fields. */
internal fun shouldRefreshDetailForCommittedFeed(
    taskVersion: Long?,
    detailVersion: Long?,
    taskHasProblem: Boolean?,
    detailHasProblem: Boolean?,
    taskIncomplete: Boolean?,
    detailIncomplete: Boolean?,
    taskCompletedWorkPercent: Double?,
    detailCompletedWorkPercent: Double?,
    locallyPending: Boolean,
): Boolean = !locallyPending && taskVersion != null && detailVersion != null && (
    taskVersion > detailVersion ||
        taskHasProblem != detailHasProblem ||
        taskIncomplete != detailIncomplete ||
        taskCompletedWorkPercent != detailCompletedWorkPercent
    )

/**
 * Defines worker feature UI state; server data and authorization remain authoritative.
 */
data class TaskDetailUiState(
    val task: WorkerTaskEntity? = null,
    val detail: WorkerTaskDetailDto? = null,
    val queuePurpose: String? = null,
    val session: WorkerSessionEntity? = null,
    val assignments: List<WorkerAssignmentEntity> = emptyList(),
    val evidence: List<TaskEvidenceEntity> = emptyList(),
    val retryableEvidenceIds: Set<String> = emptySet(),
    val hasPendingMissingItemReport: Boolean = false,
    val kpiPalette: WorkerKpiPaletteDto? = null,
    val error: String? = null,
)

private data class SupportingState(
    val evidence: List<TaskEvidenceEntity>,
    val retryableEvidenceIds: Set<String>,
    val hasPendingMissingItemReport: Boolean,
    val assignments: List<WorkerAssignmentEntity>,
    val session: WorkerSessionEntity?,
    val categoryPurposes: Map<String, String>,
    val kpiPalette: WorkerKpiPaletteDto?,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
/**
 * Defines worker feature UI state; server data and authorization remain authoritative.
 */
class TaskDetailViewModel internal constructor(
    private val localStore: WorkerLocalStore,
    private val gateway: WorkerGatewayClient,
    private val projections: WorkerProjectionWriter,
    private val json: Json,
    private val requestSync: (String) -> Unit,
) : ViewModel() {
    @Inject
    constructor(
        localStore: WorkerLocalStore,
        gateway: WorkerGatewayClient,
        projections: WorkerProjectionWriter,
        scheduler: WorkerSyncScheduler,
        json: Json,
    ) : this(
        localStore = localStore,
        gateway = gateway,
        projections = projections,
        json = json,
        requestSync = scheduler::requestAfterMutation,
    )

    private val key = MutableStateFlow<DetailKey?>(null)
    private val errors = MutableStateFlow<String?>(null)
    private val refreshGeneration = AtomicLong()
    private val lastFeedRefreshMarker = AtomicReference<String?>(null)

    val uiState: StateFlow<TaskDetailUiState> = key.flatMapLatest { requested ->
        if (requested == null) {
            flowOf(TaskDetailUiState())
        } else {
            val supportingState = combine(
                localStore.observeEvidence(requested.userId),
                localStore.observePendingOutbox(requested.userId),
                localStore.observeSession(requested.userId),
                localStore.observeAssignments(requested.userId, requested.entryId),
                localStore.observeCategories(requested.userId),
            ) { evidence, outbox, session, assignments, categories ->
                val activeLeaseId = session?.let {
                    val server = it.serverEpochMillis ?: return@let null
                    val elapsed = it.elapsedRealtimeAtSyncMillis ?: return@let null
                    val expires = it.leaseExpiresAtEpochMillis ?: return@let null
                    it.leaseId?.takeIf {
                        ServerTimeAnchor(server, elapsed, expires)
                            .isLeaseActive(SystemClock.elapsedRealtime())
                    }
                }
                val retryable = if (activeLeaseId == null) {
                    emptySet()
                } else {
                    outbox.asSequence()
                        .filter { it.kind == WorkerLocalStore.OUTBOX_EVIDENCE_RESERVATION }
                        .mapNotNull { operation ->
                            runCatching {
                                json.decodeFromString<PendingEvidenceReservation>(
                                    localStore.decryptOutboxPayload(operation),
                                )
                            }.getOrNull()?.let { payload -> operation.operationId to payload }
                        }
                        .filter { (operationId, payload) ->
                            operationId == payload.operationId &&
                                payload.offlineLeaseId == activeLeaseId &&
                                payload.operationId == payload.evidenceId
                        }
                        .mapTo(mutableSetOf()) { (_, payload) -> payload.evidenceId }
                }
                SupportingState(
                    evidence = evidence,
                    retryableEvidenceIds = retryable,
                    hasPendingMissingItemReport = outbox.any {
                        it.kind == WorkerProblemReportStore.OUTBOX_PROBLEM_REPORT && it.expectedVersion != null
                    },
                    assignments = assignments,
                    session = session,
                    categoryPurposes = categories.associate { it.queueId to it.queuePurpose },
                    kpiPalette = session?.kpiPaletteJson?.let { encoded ->
                        runCatching { json.decodeFromString<WorkerKpiPaletteDto>(encoded) }.getOrNull()
                    },
                )
            }.flowOn(Dispatchers.IO)
            combine(
                localStore.observeTasks(requested.userId),
                localStore.observeDetail(requested.userId, requested.entryId),
                supportingState,
                errors,
            ) { tasks, detailRow, evidenceWithRetry, error ->
                val task = tasks.firstOrNull { it.entryId == requested.entryId }
                TaskDetailUiState(
                    task = task,
                    detail = detailRow?.sanitizedDetailJson?.let { raw ->
                        runCatching { json.decodeFromString<WorkerTaskDetailDto>(raw) }.getOrNull()
                    },
                    queuePurpose = task?.categoryId?.let(evidenceWithRetry.categoryPurposes::get),
                    session = evidenceWithRetry.session,
                    assignments = evidenceWithRetry.assignments,
                    evidence = evidenceWithRetry.evidence.filter {
                        it.entryId == requested.entryId && it.problemReportId == null
                    },
                    retryableEvidenceIds = evidenceWithRetry.retryableEvidenceIds,
                    hasPendingMissingItemReport = evidenceWithRetry.hasPendingMissingItemReport,
                    kpiPalette = evidenceWithRetry.kpiPalette,
                    error = error,
                )
            }.onEach { state ->
                val task = state.task
                val detail = state.detail
                if (
                    shouldRefreshDetailForCommittedFeed(
                        taskVersion = task?.version,
                        detailVersion = detail?.version,
                        taskHasProblem = task?.hasProblem,
                        detailHasProblem = detail?.hasProblem,
                        taskIncomplete = task?.incomplete,
                        detailIncomplete = detail?.incomplete,
                        taskCompletedWorkPercent = task?.completedWorkPercent,
                        detailCompletedWorkPercent = detail?.completedWorkPercent,
                        locallyPending = task?.locallyPending == true,
                    ) &&
                    lastFeedRefreshMarker.getAndSet(
                        "${task!!.version}:${task.hasProblem}:${task.incomplete}:${task.completedWorkPercent}",
                    ) != "${task.version}:${task.hasProblem}:${task.incomplete}:${task.completedWorkPercent}"
                ) {
                    refresh()
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState())

    fun bind(userId: String, entryId: String) {
        val next = DetailKey(userId, entryId)
        if (key.value == next) return
        refreshGeneration.incrementAndGet()
        lastFeedRefreshMarker.set(null)
        key.value = next
        errors.value = null
        refresh()
    }

    fun refresh() {
        val current = key.value ?: return
        val generation = refreshGeneration.incrementAndGet()
        errors.value = null
        viewModelScope.launch {
            val isCurrent = {
                key.value == current && refreshGeneration.get() == generation
            }
            val detail = try {
                gateway.detail(current.entryId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (isCurrent()) {
                    errors.value = "Не удалось обновить карточку. Повторим при синхронизации."
                }
                return@launch
            }
            if (!isCurrent()) return@launch
            try {
                projections.applyDetail(current.userId, detail)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (isCurrent()) errors.value = "Не удалось сохранить обновлённую карточку."
            }
        }
    }

    fun retryEvidence(evidenceId: String) {
        val current = key.value ?: return
        val evidence = uiState.value.evidence.firstOrNull { it.evidenceId == evidenceId } ?: return
        if (evidence.state != "REVIEW_REQUIRED" || evidence.mediaId != null) return
        if (evidenceId !in uiState.value.retryableEvidenceIds) return
        requestSync(current.userId)
        errors.value = null
    }

    /** Enqueues a fenced server command; the synchronized detail/feed remains authoritative. */
    fun reportMissingItem(itemId: String, itemKind: String, itemName: String) {
        val current = key.value ?: return
        val state = uiState.value
        val detail = state.detail ?: return
        val task = state.task ?: return
        if (itemKind !in setOf("WORK", "MATERIAL") || task.locallyPending) return
        viewModelScope.launch {
            try {
                localStore.enqueueMissingItem(
                    userId = current.userId,
                    entryId = current.entryId,
                    routeIndex = detail.routeIndex,
                    expectedVersion = detail.version,
                    itemId = itemId,
                    itemKind = itemKind,
                    itemName = itemName,
                )
                errors.value = null
                requestSync(current.userId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                errors.value = exception.safeWorkerUserMessage(
                    "Не удалось сообщить об отсутствии. Обновите задание и повторите.",
                )
            }
        }
    }

    fun perform(action: String, evidenceId: String? = null) {
        val current = key.value ?: return
        val state = uiState.value
        val task = state.task ?: return
        val requestedAction = WorkerTaskAction.entries.firstOrNull { it.wireValue == action } ?: return
        val presentation = taskActionPresentation(
            currentWorkerId = current.userId,
            taskStatus = if (task.locallyPending) state.detail?.status ?: task.status else task.status,
            availabilityMode = state.detail?.availabilityMode,
            entryType = task.entryType,
            queuePurpose = state.queuePurpose,
            assignments = state.assignments,
            locallyPending = task.locallyPending,
            hasCurrentGroup = state.session?.currentGroupId != null,
            operationalAvailability = state.session?.operationalAvailability ?: "DISABLED",
        )
        if (!presentation.actionsEnabled || requestedAction !in presentation.actions) {
            errors.value = presentation.message ?: "Действие недоступно для текущего рабочего"
            return
        }
        val readyEvidenceIds = state.detail?.evidence.orEmpty()
            .asSequence()
            .filter { it.state == "READY" }
            .mapTo(mutableSetOf()) { it.evidenceId }
            .apply {
                addAll(
                    state.evidence.asSequence()
                        .filter { it.state == "READY" }
                        .map { it.evidenceId },
                )
            }
        val selectedEvidenceId = completionEvidenceId(
            queuePurpose = state.queuePurpose,
            readyEvidenceIds = readyEvidenceIds,
            selectedEvidenceId = evidenceId,
        )
        if (
            requestedAction == WorkerTaskAction.COMPLETE &&
            state.queuePurpose == LOGISTICS_DRIVER_QUEUE_PURPOSE &&
            selectedEvidenceId == null
        ) {
            errors.value = if (readyEvidenceIds.isEmpty()) {
                "Для завершения логистического задания добавьте фотографию"
            } else {
                "Выберите фотографию для завершения логистического задания"
            }
            return
        }
        viewModelScope.launch {
            try {
                val lease = requireNotNull(localStore.leaseFor(current.userId)) { "Офлайн-доступ ещё не подготовлен" }
                if (requestedAction != WorkerTaskAction.COMPLETE) {
                    require(lease.isLeaseActive(SystemClock.elapsedRealtime())) { "Срок офлайн-доступа истёк" }
                }
                val payload = PendingWorkerAction(
                    operationId = UUID.randomUUID().toString(),
                    action = action,
                    expectedVersion = task.version,
                    workerGroupId = selectedGroupForAction(
                        requestedAction,
                        state.session?.currentGroupId,
                    ),
                    evidenceId = selectedEvidenceId,
                    occurredAt = Instant.ofEpochMilli(
                        lease.estimatedServerNow(SystemClock.elapsedRealtime()),
                    ).toString(),
                    offlineLeaseId = requireNotNull(localStore.currentLeaseId(current.userId)),
                )
                localStore.applyOptimisticAction(
                    OptimisticAction(
                        userId = current.userId,
                        entryId = current.entryId,
                        statusAfterAction = action.statusAfterAction(),
                        payload = payload,
                    ),
                )
                requestSync(current.userId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                errors.value = workerTaskActionQueueErrorMessage(exception)
            }
        }
    }
}

/** Maps local queueing failures without exposing exception text to the worker. */
internal fun workerTaskActionQueueErrorMessage(failure: Throwable): String =
    failure.safeWorkerUserMessage(
        "Не удалось поставить действие в очередь. Синхронизируйте задание и повторите попытку.",
    )

private fun String.statusAfterAction(): String = when (this) {
    "TAKE", "JOIN", "RESUME" -> "IN_PROGRESS"
    "PAUSE" -> "PAUSED"
    "COMPLETE" -> "DONE"
    else -> error("Unknown worker action")
}
