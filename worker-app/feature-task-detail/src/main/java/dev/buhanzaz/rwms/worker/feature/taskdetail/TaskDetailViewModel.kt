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
import dev.buhanzaz.rwms.worker.core.network.DriverTripDetailsDto
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.network.WorkerKpiPaletteDto
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskDetailDto
import dev.buhanzaz.rwms.worker.core.sync.WorkerProjectionWriter
import dev.buhanzaz.rwms.worker.core.sync.WorkerSyncScheduler
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private data class DetailKey(val userId: String, val entryId: String)

/** Ephemeral live logistics projection bound to one visible worker detail key. */
private data class TripUiSnapshot(
    val key: DetailKey? = null,
    val loading: Boolean = false,
    val complete: Boolean = false,
    val details: DriverTripDetailsDto? = null,
    val error: String? = null,
)

/** Result of one fenced task-board detail and optional logistics detail refresh. */
internal data class TaskDetailRefreshOutcome(
    val accepted: Boolean,
    val ordinaryError: String? = null,
    val logisticsRequested: Boolean = false,
    val tripDetails: DriverTripDetailsDto? = null,
    val tripError: String? = null,
)

/**
 * Refreshes task-board first, persists that authoritative detail, and only then follows a
 * logistics source reference. The caller-provided fence prevents a response for an obsolete
 * screen key or refresh generation from entering UI state.
 */
internal suspend fun loadTaskDetail(
    fetchDetail: suspend () -> WorkerTaskDetailDto,
    persistDetail: suspend (WorkerTaskDetailDto) -> Unit,
    fetchLogisticsTrip: suspend (String) -> DriverTripDetailsDto?,
    isCurrent: () -> Boolean,
    onLogisticsFetchStarted: () -> Unit = {},
): TaskDetailRefreshOutcome {
    val detail = try {
        fetchDetail()
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        return TaskDetailRefreshOutcome(
            accepted = isCurrent(),
            ordinaryError = "Не удалось обновить карточку. Повторим при синхронизации.",
        )
    }
    try {
        persistDetail(detail)
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        return TaskDetailRefreshOutcome(
            accepted = isCurrent(),
            ordinaryError = "Не удалось сохранить обновлённую карточку.",
        )
    }
    if (!isCurrent()) return TaskDetailRefreshOutcome(accepted = false)

    val source = detail.source
    if (source?.type != LOGISTICS_DRIVER_TASK_SOURCE_TYPE) {
        return TaskDetailRefreshOutcome(accepted = true)
    }

    onLogisticsFetchStarted()
    val tripDetails = try {
        fetchLogisticsTrip(source.sourceId)
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        return TaskDetailRefreshOutcome(
            accepted = isCurrent(),
            logisticsRequested = true,
            tripError = "Не удалось обновить данные ходки.",
        )
    }
    if (!isCurrent()) return TaskDetailRefreshOutcome(accepted = false)
    return TaskDetailRefreshOutcome(
        accepted = true,
        logisticsRequested = true,
        tripDetails = tripDetails,
    )
}

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
    val kpiPalette: WorkerKpiPaletteDto? = null,
    val tripDetails: DriverTripDetailsDto? = null,
    val tripRefreshInProgress: Boolean = false,
    val tripRefreshComplete: Boolean = false,
    val tripRefreshError: String? = null,
    val error: String? = null,
)

private data class SupportingState(
    val evidence: List<TaskEvidenceEntity>,
    val retryableEvidenceIds: Set<String>,
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
class TaskDetailViewModel @Inject constructor(
    private val localStore: WorkerLocalStore,
    private val gateway: WorkerGatewayClient,
    private val projections: WorkerProjectionWriter,
    private val scheduler: WorkerSyncScheduler,
    private val json: Json,
) : ViewModel() {
    private val key = MutableStateFlow<DetailKey?>(null)
    private val errors = MutableStateFlow<String?>(null)
    private val tripSnapshot = MutableStateFlow(TripUiSnapshot())
    private val refreshGeneration = AtomicLong()

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
                tripSnapshot,
            ) { tasks, detailRow, evidenceWithRetry, error, trip ->
                val task = tasks.firstOrNull { it.entryId == requested.entryId }
                val currentTrip = trip.takeIf { it.key == requested }
                TaskDetailUiState(
                    task = task,
                    detail = detailRow?.sanitizedDetailJson?.let { raw ->
                        runCatching { json.decodeFromString<WorkerTaskDetailDto>(raw) }.getOrNull()
                    },
                    queuePurpose = task?.categoryId?.let(evidenceWithRetry.categoryPurposes::get),
                    session = evidenceWithRetry.session,
                    assignments = evidenceWithRetry.assignments,
                    evidence = evidenceWithRetry.evidence.filter { it.entryId == requested.entryId },
                    retryableEvidenceIds = evidenceWithRetry.retryableEvidenceIds,
                    kpiPalette = evidenceWithRetry.kpiPalette,
                    tripDetails = currentTrip?.details,
                    tripRefreshInProgress = currentTrip?.loading == true,
                    tripRefreshComplete = currentTrip?.complete == true,
                    tripRefreshError = currentTrip?.error,
                    error = error,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState())

    fun bind(userId: String, entryId: String) {
        val next = DetailKey(userId, entryId)
        if (key.value == next) return
        refreshGeneration.incrementAndGet()
        key.value = next
        errors.value = null
        tripSnapshot.value = TripUiSnapshot(key = next)
        refresh()
    }

    fun refresh() {
        val current = key.value ?: return
        val generation = refreshGeneration.incrementAndGet()
        errors.value = null
        tripSnapshot.value = TripUiSnapshot(key = current)
        viewModelScope.launch {
            val isCurrent = {
                key.value == current && refreshGeneration.get() == generation
            }
            val outcome = loadTaskDetail(
                fetchDetail = { gateway.detail(current.entryId) },
                persistDetail = { projections.applyDetail(current.userId, it) },
                fetchLogisticsTrip = gateway::logisticsTripDetails,
                isCurrent = isCurrent,
                onLogisticsFetchStarted = {
                    if (isCurrent()) {
                        tripSnapshot.value = TripUiSnapshot(
                            key = current,
                            loading = true,
                        )
                    }
                },
            )
            if (!outcome.accepted || !isCurrent()) return@launch
            errors.value = outcome.ordinaryError
            tripSnapshot.value = TripUiSnapshot(
                key = current,
                complete = outcome.logisticsRequested,
                details = outcome.tripDetails,
                error = outcome.tripError,
            )
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

    fun perform(action: String, evidenceId: String? = null) {
        val current = key.value ?: return
        val state = uiState.value
        val task = state.task ?: return
        val requestedAction = WorkerTaskAction.entries.firstOrNull { it.wireValue == action } ?: return
        val presentation = taskActionPresentation(
            currentWorkerId = current.userId,
            taskStatus = if (task.locallyPending) state.detail?.status ?: task.status else task.status,
            availabilityMode = state.detail?.availabilityMode,
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
            runCatching {
                val lease = requireNotNull(localStore.leaseFor(current.userId)) { "Офлайн-доступ ещё не подготовлен" }
                require(lease.isLeaseActive(SystemClock.elapsedRealtime())) { "Срок офлайн-доступа истёк" }
                val payload = PendingWorkerAction(
                    operationId = UUID.randomUUID().toString(),
                    action = action,
                    expectedVersion = task.version,
                    workerGroupId = selectedGroupForAction(
                        requestedAction,
                        state.session?.currentGroupId,
                        state.queuePurpose,
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
                scheduler.request(current.userId)
            }.onFailure { errors.value = it.message ?: "Не удалось поставить действие в очередь" }
        }
    }
}

private const val LOGISTICS_DRIVER_TASK_SOURCE_TYPE = "LOGISTICS_DRIVER_TASK"

private fun String.statusAfterAction(): String = when (this) {
    "TAKE", "JOIN", "RESUME" -> "IN_PROGRESS"
    "PAUSE" -> "PAUSED"
    "COMPLETE" -> "DONE"
    else -> error("Unknown worker action")
}
