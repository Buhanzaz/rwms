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
import dev.buhanzaz.rwms.worker.core.database.WorkerSessionEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskDetailDto
import dev.buhanzaz.rwms.worker.core.sync.WorkerProjectionWriter
import dev.buhanzaz.rwms.worker.core.sync.WorkerSyncScheduler
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private data class DetailKey(val userId: String, val entryId: String)

data class TaskDetailUiState(
    val task: WorkerTaskEntity? = null,
    val detail: WorkerTaskDetailDto? = null,
    val session: WorkerSessionEntity? = null,
    val assignments: List<WorkerAssignmentEntity> = emptyList(),
    val evidence: List<TaskEvidenceEntity> = emptyList(),
    val retryableEvidenceIds: Set<String> = emptySet(),
    val error: String? = null,
)

private data class SupportingState(
    val evidence: List<TaskEvidenceEntity>,
    val retryableEvidenceIds: Set<String>,
    val assignments: List<WorkerAssignmentEntity>,
    val session: WorkerSessionEntity?,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModel @Inject constructor(
    private val localStore: WorkerLocalStore,
    private val gateway: WorkerGatewayClient,
    private val projections: WorkerProjectionWriter,
    private val scheduler: WorkerSyncScheduler,
    private val json: Json,
) : ViewModel() {
    private val key = MutableStateFlow<DetailKey?>(null)
    private val errors = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TaskDetailUiState> = key.flatMapLatest { requested ->
        if (requested == null) {
            flowOf(TaskDetailUiState())
        } else {
            val supportingState = combine(
                localStore.observeEvidence(requested.userId),
                localStore.observePendingOutbox(requested.userId),
                localStore.observeSession(requested.userId),
                localStore.observeAssignments(requested.userId, requested.entryId),
            ) { evidence, outbox, session, assignments ->
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
                SupportingState(evidence, retryable, assignments, session)
            }.flowOn(Dispatchers.IO)
            combine(
                localStore.observeTasks(requested.userId),
                localStore.observeDetail(requested.userId, requested.entryId),
                supportingState,
                errors,
            ) { tasks, detailRow, evidenceWithRetry, error ->
                TaskDetailUiState(
                    task = tasks.firstOrNull { it.entryId == requested.entryId },
                    detail = detailRow?.sanitizedDetailJson?.let { raw ->
                        runCatching { json.decodeFromString<WorkerTaskDetailDto>(raw) }.getOrNull()
                    },
                    session = evidenceWithRetry.session,
                    assignments = evidenceWithRetry.assignments,
                    evidence = evidenceWithRetry.evidence.filter { it.entryId == requested.entryId },
                    retryableEvidenceIds = evidenceWithRetry.retryableEvidenceIds,
                    error = error,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState())

    fun bind(userId: String, entryId: String) {
        val next = DetailKey(userId, entryId)
        if (key.value == next) return
        key.value = next
        refresh()
    }

    fun refresh() {
        val current = key.value ?: return
        viewModelScope.launch {
            runCatching { gateway.detail(current.entryId) }
                .onSuccess {
                    projections.applyDetail(current.userId, it)
                    errors.value = null
                }
                .onFailure { errors.value = "Не удалось обновить карточку. Повторим при синхронизации." }
        }
    }

    fun retryEvidence(evidenceId: String) {
        val current = key.value ?: return
        val evidence = uiState.value.evidence.firstOrNull { it.evidenceId == evidenceId } ?: return
        if (evidence.state != "REVIEW_REQUIRED" || evidence.mediaId != null) return
        if (evidenceId !in uiState.value.retryableEvidenceIds) return
        scheduler.request(current.userId)
        errors.value = null
    }

    fun perform(action: String) {
        val current = key.value ?: return
        val state = uiState.value
        val task = state.task ?: return
        val requestedAction = WorkerTaskAction.entries.firstOrNull { it.wireValue == action } ?: return
        val presentation = taskActionPresentation(
            currentWorkerId = current.userId,
            taskStatus = if (task.locallyPending) state.detail?.status ?: task.status else task.status,
            availabilityMode = state.detail?.availabilityMode,
            assignments = state.assignments,
            locallyPending = task.locallyPending,
            hasCurrentGroup = state.session?.currentGroupId != null,
            operationalAvailability = state.session?.operationalAvailability ?: "DISABLED",
        )
        if (!presentation.actionsEnabled || requestedAction !in presentation.actions) {
            errors.value = presentation.message ?: "Действие недоступно для текущего рабочего"
            return
        }
        viewModelScope.launch {
            runCatching {
                val lease = requireNotNull(localStore.leaseFor(current.userId)) { "Офлайн-доступ ещё не подготовлен" }
                require(lease.isLeaseActive(SystemClock.elapsedRealtime())) { "Срок офлайн-доступа истёк" }
                val payload = PendingWorkerAction(
                    operationId = UUID.randomUUID().toString(),
                    action = action,
                    expectedVersion = task.version,
                    workerGroupId = managerSelectedGroupForAction(
                        requestedAction,
                        state.session?.currentGroupId,
                    ),
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
                scheduler.request(current.userId)
            }.onFailure { errors.value = it.message ?: "Не удалось поставить действие в очередь" }
        }
    }
}

private fun String.statusAfterAction(): String = when (this) {
    "TAKE", "RESUME" -> "IN_PROGRESS"
    "PAUSE" -> "PAUSED"
    "COMPLETE" -> "DONE"
    else -> error("Unknown worker action")
}
