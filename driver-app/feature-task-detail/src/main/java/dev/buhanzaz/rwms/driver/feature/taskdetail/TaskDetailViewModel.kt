package dev.buhanzaz.rwms.driver.feature.taskdetail

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.driver.core.database.OptimisticAction
import dev.buhanzaz.rwms.driver.core.database.PendingEvidenceReservation
import dev.buhanzaz.rwms.driver.core.database.PendingDriverAction
import dev.buhanzaz.rwms.driver.core.database.ServerTimeAnchor
import dev.buhanzaz.rwms.driver.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.driver.core.database.DriverAssignmentEntity
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.database.DriverSessionEntity
import dev.buhanzaz.rwms.driver.core.database.DriverTaskEntity
import dev.buhanzaz.rwms.driver.core.network.DriverTripDetailsDto
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayClient
import dev.buhanzaz.rwms.driver.core.network.DriverKpiPaletteDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskDetailDto
import dev.buhanzaz.rwms.driver.core.network.GatewayFailureDisposition
import dev.buhanzaz.rwms.driver.core.network.GatewayProblemException
import dev.buhanzaz.rwms.driver.core.sync.DriverProjectionWriter
import dev.buhanzaz.rwms.driver.core.sync.DriverSyncScheduler
import java.time.Instant
import java.time.LocalDate
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Fences asynchronous detail results to one authenticated driver and entry. */
private data class DetailKey(val userId: String, val entryId: String)

/** Ephemeral live logistics projection bound to one visible driver detail key. */
private data class TripUiSnapshot(
    val key: DetailKey? = null,
    val loading: Boolean = false,
    val complete: Boolean = false,
    val details: DriverTripDetailsDto? = null,
    val error: String? = null,
    val claimInProgress: Boolean = false,
    val claimComplete: Boolean = false,
    val claimError: String? = null,
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
 * logistics source reference when the task targets one exact assigned driver or an eligible
 * future shared preview. The caller-provided fence prevents a response for an obsolete screen key
 * or refresh generation from entering UI state.
 */
internal suspend fun loadTaskDetail(
    driverAudienceMode: String?,
    today: LocalDate,
    fetchDetail: suspend () -> DriverTaskDetailDto,
    persistDetail: suspend (DriverTaskDetailDto) -> Unit,
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
    if (
        !canReadRichLogisticsDetails(
            sourceType = source?.type,
            driverAudienceMode = driverAudienceMode,
            scheduledDate = detail.scheduledDate,
            today = today,
        )
    ) {
        return TaskDetailRefreshOutcome(accepted = true)
    }
    val logisticsSourceId = requireNotNull(source).sourceId

    onLogisticsFetchStarted()
    val tripDetails = try {
        fetchLogisticsTrip(logisticsSourceId)
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
 * Defines driver feature UI state; server data and authorization remain authoritative.
 */
data class TaskDetailUiState(
    val task: DriverTaskEntity? = null,
    val detail: DriverTaskDetailDto? = null,
    val queuePurpose: String? = null,
    val session: DriverSessionEntity? = null,
    val assignments: List<DriverAssignmentEntity> = emptyList(),
    val hasPendingTake: Boolean = false,
    val evidence: List<TaskEvidenceEntity> = emptyList(),
    val retryableEvidenceIds: Set<String> = emptySet(),
    val kpiPalette: DriverKpiPaletteDto? = null,
    val tripDetails: DriverTripDetailsDto? = null,
    val tripRefreshInProgress: Boolean = false,
    val tripRefreshComplete: Boolean = false,
    val tripRefreshError: String? = null,
    val extraTaskClaimInProgress: Boolean = false,
    val extraTaskClaimed: Boolean = false,
    val extraTaskClaimError: String? = null,
    val error: String? = null,
)

/** Combines Room projections required to render and authorize local detail actions. */
private data class SupportingState(
    val evidence: List<TaskEvidenceEntity>,
    val retryableEvidenceIds: Set<String>,
    val assignments: List<DriverAssignmentEntity>,
    val pendingTakeEntryIds: Set<String>,
    val session: DriverSessionEntity?,
    val categoryPurposes: Map<String, String>,
    val kpiPalette: DriverKpiPaletteDto?,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
/**
 * Defines driver feature UI state; server data and authorization remain authoritative.
 */
class TaskDetailViewModel @Inject constructor(
    private val localStore: DriverLocalStore,
    private val gateway: DriverGatewayClient,
    private val projections: DriverProjectionWriter,
    private val scheduler: DriverSyncScheduler,
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
                        .filter { it.kind == DriverLocalStore.OUTBOX_EVIDENCE_RESERVATION }
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
                val pendingTakeEntryIds = outbox.asSequence()
                    .filter { it.kind == DriverLocalStore.OUTBOX_ACTION }
                    .mapNotNull { operation ->
                        runCatching {
                            json.decodeFromString<PendingDriverAction>(
                                localStore.decryptOutboxPayload(operation),
                            )
                        }.getOrNull()
                            ?.takeIf { it.action == DriverTaskAction.TAKE.wireValue }
                            ?.let { operation.entryId }
                    }
                    .toSet()
                SupportingState(
                    evidence = evidence,
                    retryableEvidenceIds = retryable,
                    assignments = assignments,
                    pendingTakeEntryIds = pendingTakeEntryIds,
                    session = session,
                    categoryPurposes = categories.associate { it.queueId to it.queuePurpose },
                    kpiPalette = session?.kpiPaletteJson?.let { encoded ->
                        runCatching { json.decodeFromString<DriverKpiPaletteDto>(encoded) }.getOrNull()
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
                        runCatching { json.decodeFromString<DriverTaskDetailDto>(raw) }.getOrNull()
                    },
                    queuePurpose = task?.categoryId?.let(evidenceWithRetry.categoryPurposes::get),
                    session = evidenceWithRetry.session,
                    assignments = evidenceWithRetry.assignments,
                    hasPendingTake = requested.entryId in evidenceWithRetry.pendingTakeEntryIds,
                    evidence = evidenceWithRetry.evidence.filter { it.entryId == requested.entryId },
                    retryableEvidenceIds = evidenceWithRetry.retryableEvidenceIds,
                    kpiPalette = evidenceWithRetry.kpiPalette,
                    tripDetails = currentTrip?.details,
                    tripRefreshInProgress = currentTrip?.loading == true,
                    tripRefreshComplete = currentTrip?.complete == true,
                    tripRefreshError = currentTrip?.error,
                    extraTaskClaimInProgress = currentTrip?.claimInProgress == true,
                    extraTaskClaimed = currentTrip?.claimComplete == true,
                    extraTaskClaimError = currentTrip?.claimError,
                    error = error,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState())

    /** Waits for the selected task row before deciding whether rich logistics read is allowed. */
    private suspend fun currentDriverAudienceMode(requested: DetailKey): String? =
        localStore.observeTasks(requested.userId)
            .first { tasks -> tasks.any { task -> task.entryId == requested.entryId } }
            .first { task -> task.entryId == requested.entryId }
            .driverAudienceMode

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
        val previousSnapshot = tripSnapshot.value.takeIf { it.key == current }
        tripSnapshot.value = TripUiSnapshot(
            key = current,
            claimInProgress = previousSnapshot?.claimInProgress == true,
            claimComplete = previousSnapshot?.claimComplete == true,
            claimError = previousSnapshot?.claimError,
        )
        viewModelScope.launch {
            val isCurrent = {
                key.value == current && refreshGeneration.get() == generation
            }
            val driverAudienceMode = currentDriverAudienceMode(current)
            if (!isCurrent()) return@launch
            val outcome = loadTaskDetail(
                driverAudienceMode = driverAudienceMode,
                today = LocalDate.now(),
                fetchDetail = { gateway.detail(current.entryId) },
                persistDetail = { projections.applyDetail(current.userId, it) },
                fetchLogisticsTrip = gateway::logisticsTripDetails,
                isCurrent = isCurrent,
                onLogisticsFetchStarted = {
                    if (isCurrent()) {
                        tripSnapshot.value = tripSnapshot.value.copy(key = current, loading = true)
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
                claimInProgress = tripSnapshot.value.claimInProgress,
                claimComplete = tripSnapshot.value.claimComplete,
                claimError = tripSnapshot.value.claimError,
            )
        }
    }

    /** Claims one future shared logistics trip online without issuing task-board TAKE. */
    fun claimExtraTask() {
        val current = key.value ?: return
        val state = uiState.value
        val detail = state.detail ?: return
        val source = detail.source
        val claimState = tripSnapshot.value.takeIf { it.key == current }
        if (
            claimState?.claimInProgress == true || claimState?.claimComplete == true ||
            !canClaimFutureLogisticsTask(
                sourceType = source?.type,
                driverAudienceMode = state.task?.driverAudienceMode,
                scheduledDate = detail.scheduledDate,
                today = LocalDate.now(),
            )
        ) {
            errors.value = "Дополнительную ходку можно взять только на будущую дату"
            return
        }
        val sourceId = requireNotNull(source).sourceId
        tripSnapshot.value = tripSnapshot.value.copy(
            key = current,
            claimInProgress = true,
            claimError = null,
        )
        errors.value = null
        viewModelScope.launch {
            val claimedTrip = try {
                gateway.claimFutureLogisticsTask(sourceId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (key.value != current) return@launch
                tripSnapshot.value = tripSnapshot.value.copy(
                    key = current,
                    claimInProgress = false,
                    claimError = extraTaskClaimErrorMessage(exception),
                )
                return@launch
            }
            if (key.value != current) return@launch
            val currentSnapshot = tripSnapshot.value
            tripSnapshot.value = currentSnapshot.copy(
                key = current,
                claimInProgress = false,
                claimComplete = true,
                claimError = null,
                details = claimedTrip ?: currentSnapshot.details,
            )
            runCatching { scheduler.request(current.userId) }
            refresh()
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
        val requestedAction = DriverTaskAction.entries.firstOrNull { it.wireValue == action } ?: return
        val presentation = taskActionPresentation(
            currentDriverId = current.userId,
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
            requestedAction == DriverTaskAction.COMPLETE &&
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
                val payload = PendingDriverAction(
                    operationId = UUID.randomUUID().toString(),
                    action = action,
                    expectedVersion = task.version,
                    driverGroupId = selectedGroupForAction(
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

/** Converts typed gateway outcomes into a recovery-oriented driver message. */
internal fun extraTaskClaimErrorMessage(failure: Throwable): String = when (
    (failure as? GatewayProblemException)?.disposition
) {
    GatewayFailureDisposition.CONFLICT ->
        "Эту ходку уже взял другой водитель. Обновите логистику."
    GatewayFailureDisposition.AUTHENTICATION_REQUIRED ->
        "Сеанс истёк. Войдите снова, чтобы взять ходку."
    GatewayFailureDisposition.USER_ACTION_REQUIRED ->
        "Эта ходка больше недоступна вам. Обновите логистику."
    GatewayFailureDisposition.RETRYABLE ->
        "Сервис временно недоступен. Повторите позже."
    else -> "Не удалось взять дополнительное задание."
}

private fun String.statusAfterAction(): String = when (this) {
    "TAKE", "RESUME" -> "IN_PROGRESS"
    "PAUSE" -> "PAUSED"
    "COMPLETE" -> "DONE"
    else -> error("Unknown driver action")
}
