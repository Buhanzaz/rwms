package dev.buhanzaz.rwms.driver.feature.shift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yandex.mapkit.map.MapWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.database.DriverOutboxEntity
import dev.buhanzaz.rwms.driver.core.database.DriverShiftDraftEntity
import dev.buhanzaz.rwms.driver.core.database.DriverShiftSnapshotEntity
import dev.buhanzaz.rwms.driver.core.database.PendingShiftCommand
import dev.buhanzaz.rwms.driver.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.driver.core.network.AuthenticatedGatewayMonitor
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayClient
import dev.buhanzaz.rwms.driver.core.network.DriverMedicalCheckDto
import dev.buhanzaz.rwms.driver.core.network.DriverShiftPhotoDto
import dev.buhanzaz.rwms.driver.core.network.DriverVehicleDefectDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import dev.buhanzaz.rwms.driver.core.network.toDriverUserMessage
import dev.buhanzaz.rwms.driver.core.sync.DriverProjectionWriter
import dev.buhanzaz.rwms.driver.core.sync.DriverSyncScheduler
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Complete process-resumable state rendered by the Driver Up shift feature. */
data class DriverShiftUiState(
    val loading: Boolean = true,
    val today: TodayDriverShiftDto? = null,
    val draft: DriverShiftDraftEntity? = null,
    val evidence: List<TaskEvidenceEntity> = emptyList(),
    val pendingActions: Set<String> = emptySet(),
    val pendingCount: Int = 0,
    val online: Boolean = false,
    val syncStage: String? = null,
    val syncMessage: String? = null,
    val submitting: Boolean = false,
    val requiresOdometerConfirmation: Boolean = false,
    val traffic: TrafficBriefing? = null,
    val trafficLoading: Boolean = false,
    val error: String? = null,
)

/** Camera target metadata needed to reserve a photo against the existing Driver Shift owner. */
data class ShiftPhotoCaptureRequest(
    val shiftId: String,
    val expectedVersion: Long,
    val role: String,
    val defectId: String? = null,
    val inspectionItemId: String? = null,
)

/**
 * Coordinates local encrypted outbox writes and server-driven shift projection refreshes. It never
 * decides a server transition: optimistic state is limited to inspection progress and photo rows.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DriverShiftViewModel @Inject constructor(
    private val localStore: DriverLocalStore,
    private val gateway: DriverGatewayClient,
    private val projections: DriverProjectionWriter,
    private val scheduler: DriverSyncScheduler,
    private val gatewayMonitor: AuthenticatedGatewayMonitor,
    private val trafficProvider: TrafficBriefingProvider,
    private val json: Json,
) : ViewModel() {
    private val boundUserId = MutableStateFlow<String?>(null)
    private val transientError = MutableStateFlow<String?>(null)
    private val initialLoadComplete = MutableStateFlow(false)
    private val submitting = MutableStateFlow(false)
    private val requiresOdometerConfirmation = MutableStateFlow(false)
    private val traffic = MutableStateFlow<TrafficBriefing?>(null)
    private val trafficLoading = MutableStateFlow(false)
    private val commandMutex = Mutex()
    private var loadedTrafficCoordinates: Pair<Double, Double>? = null

    val state: StateFlow<DriverShiftUiState> = boundUserId
        .flatMapLatest { userId ->
            if (userId == null) flowOf(DriverShiftUiState()) else observeUserState(userId)
        }
        .combine(submitting) { current, isSubmitting -> current.copy(submitting = isSubmitting) }
        .combine(requiresOdometerConfirmation) { current, requiresConfirmation ->
            current.copy(requiresOdometerConfirmation = requiresConfirmation)
        }
        .combine(traffic) { current, briefing -> current.copy(traffic = briefing) }
        .combine(trafficLoading) { current, loading -> current.copy(trafficLoading = loading) }
        .combine(transientError) { current, error -> current.copy(error = error ?: current.error) }
        .combine(initialLoadComplete) { current, complete ->
            current.copy(loading = current.today == null && !complete)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DriverShiftUiState())

    /** Binds this entry-scoped ViewModel to one authenticated Driver Up account. */
    fun bind(userId: String) {
        if (boundUserId.value == userId) return
        initialLoadComplete.value = false
        transientError.value = null
        traffic.value = null
        loadedTrafficCoordinates = null
        boundUserId.value = userId
        viewModelScope.launch {
            try {
                if (localStore.cachedShiftSnapshot(userId) == null) {
                    projections.applyTodayShift(userId, gateway.todayDriverShift())
                }
            } catch (error: Throwable) {
                transientError.value = error.toDriverUserMessage(
                    "Не удалось получить состояние смены. Проверьте соединение и повторите.",
                )
            } finally {
                initialLoadComplete.value = true
            }
        }
    }

    /** Fetches the startup aggregate immediately without replacing durable pending operations. */
    fun refresh() {
        val userId = boundUserId.value ?: return
        viewModelScope.launch {
            runCatching { gateway.todayDriverShift() }
                .onSuccess { today ->
                    projections.applyTodayShift(userId, today)
                    transientError.value = null
                }
                .onFailure { error ->
                    transientError.value = error.toDriverUserMessage(
                        "Не удалось обновить смену. Проверьте соединение и повторите.",
                    )
                }
        }
    }

    /** Loads MapKit traffic once for the current briefing coordinates and fails open. */
    fun loadTraffic(latitude: Double?, longitude: Double?) {
        if (latitude == null || longitude == null) {
            traffic.value = TrafficBriefing(level = null, available = false)
            return
        }
        val coordinates = latitude to longitude
        if (loadedTrafficCoordinates == coordinates || trafficLoading.value) return
        loadedTrafficCoordinates = coordinates
        trafficLoading.value = true
        viewModelScope.launch {
            traffic.value = trafficProvider.load(latitude, longitude)
            trafficLoading.value = false
        }
    }

    /** Connects the visible briefing map to the replaceable traffic provider. */
    fun attachTrafficMap(mapWindow: MapWindow) {
        trafficProvider.attach(mapWindow)
    }

    /** Releases the exact briefing map that left composition. */
    fun detachTrafficMap(mapWindow: MapWindow) {
        trafficProvider.detach(mapWindow)
    }

    /** Persists one-time briefing acknowledgement and waits for server transition confirmation. */
    fun confirmBriefing() = enqueueSimpleCommand(ACTION_BRIEFING_SEEN)

    /** Persists test self-confirmation with an explicitly non-authoritative client timestamp. */
    fun confirmMedicalCheck() = enqueueSimpleCommand(
        action = ACTION_MEDICAL_CHECK,
        enrich = { command -> command.copy(clientCompletedAt = Instant.now().toString()) },
    )

    /** Saves one OK/DEFECT item result and its progress locally before connected replay. */
    fun updateInspectionItem(
        itemId: String,
        result: String,
        defectDescription: String?,
        requestedDefectId: String? = null,
    ) {
        val userId = boundUserId.value ?: return
        viewModelScope.launch {
            mutate(userId) { entity, today ->
                val shift = requireNotNull(today.shift)
                val inspection = requireNotNull(today.inspection)
                val item = inspection.items.firstOrNull { it.id == itemId }
                    ?: error("Пункт осмотра больше недоступен")
                require(result == "OK" || result == "DEFECT") { "Неизвестный результат осмотра" }
                val description = defectDescription?.trim()
                if (result == "DEFECT") require(!description.isNullOrBlank()) { "Опишите неисправность" }
                val defectId = if (result == "DEFECT") {
                    requestedDefectId ?: item.defect?.id ?: UUID.randomUUID().toString()
                } else {
                    null
                }
                val now = Instant.now().toString()
                val optimisticItem = item.copy(
                    version = item.version + 1,
                    state = result,
                    defect = if (defectId == null) null else DriverVehicleDefectDto(
                        id = defectId,
                        shiftId = shift.id,
                        vehicleId = requireNotNull(today.vehicle).id,
                        inspectionItemId = item.id,
                        description = requireNotNull(description),
                        severity = "BLOCKING",
                        status = "OPEN",
                        photoIds = today.photos
                            .filter { it.defectId == defectId }
                            .map { it.id },
                        createdAt = item.defect?.createdAt ?: now,
                    ),
                )
                val items = inspection.items.map { current -> if (current.id == itemId) optimisticItem else current }
                val optimistic = today.copy(
                    shift = shift.copy(version = shift.version + 1),
                    inspection = inspection.copy(
                        version = inspection.version + 1,
                        checkedRequired = items.count { it.required && it.state != "NOT_CHECKED" },
                        blockingDefectCount = items.count { candidate ->
                            candidate.defect?.let { defect ->
                                defect.severity == "BLOCKING" && defect.status == "OPEN"
                            } == true
                        },
                        items = items,
                    ),
                )
                val operationId = UUID.randomUUID().toString()
                val command = PendingShiftCommand(
                    operationId = operationId,
                    shiftId = shift.id,
                    action = ACTION_INSPECTION_ITEM,
                    expectedVersion = shift.version,
                    itemId = item.id,
                    expectedItemVersion = item.version,
                    result = result,
                    defectId = defectId,
                    defectDescription = description,
                )
                localStore.enqueueShiftCommand(userId, command, entity.withToday(userId, optimistic, json))
                scheduler.request(userId)
            }
        }
    }

    /** Requests server completion only after every required item has a result and no blocker exists. */
    fun completeInspection() {
        val today = state.value.today ?: return
        val inspection = today.inspection ?: return
        if (inspection.checkedRequired != inspection.totalRequired) {
            transientError.value = "Завершите все обязательные проверки"
            return
        }
        if (inspection.blockingDefectCount > 0) {
            transientError.value = "Автомобиль не готов к смене"
            return
        }
        enqueueSimpleCommand(ACTION_INSPECTION_COMPLETE)
    }

    /** Starts the shift only through a connected, version-fenced server command. */
    fun startShift() = enqueueSimpleCommand(ACTION_START)

    /** Opens closing only after task-board declared all mandatory work complete. */
    fun startClosing() = enqueueSimpleCommand(ACTION_CLOSING_START)

    /** Saves a manual warehouse-return attestation and waits for server confirmation. */
    fun confirmWarehouseReturn() = enqueueSimpleCommand(
        action = ACTION_WAREHOUSE_RETURN,
        enrich = { command -> command.copy(confirmationType = "MANUAL") },
    )

    /** Creates a process-death-safe default closing draft when the server enters closing-report state. */
    fun ensureClosingDraft() {
        val userId = boundUserId.value ?: return
        val shiftId = state.value.today?.shift?.id ?: return
        viewModelScope.launch {
            localStore.ensureShiftDraft(defaultDraft(userId, shiftId))
        }
    }

    /** Advances or revises one locally persisted closing wizard input. */
    fun updateClosingDraft(transform: (DriverShiftDraftEntity) -> DriverShiftDraftEntity) {
        val userId = boundUserId.value ?: return
        val shiftId = state.value.today?.shift?.id ?: return
        viewModelScope.launch {
            commandMutex.withLock {
                val current = localStore.shiftDraft(userId, shiftId) ?: defaultDraft(userId, shiftId)
                localStore.saveShiftDraft(
                    transform(current).copy(updatedAtEpochMillis = System.currentTimeMillis()),
                )
                requiresOdometerConfirmation.value = false
            }
        }
    }

    /** Validates and queues the complete closing report; backend repeats every business invariant. */
    fun submitClosingReport(forceSuspiciousOdometer: Boolean = false) {
        val userId = boundUserId.value ?: return
        viewModelScope.launch {
            mutate(userId) { entity, today ->
                val shift = requireNotNull(today.shift)
                val vehicle = requireNotNull(today.vehicle)
                val draft = localStore.shiftDraft(userId, shift.id) ?: error("Заполните отчёт о смене")
                val queued = localStore.pendingOutbox(userId).shiftCommands(shift.id, localStore, json)
                require(queued.all { it.action == ACTION_PHOTO_RESERVATION }) {
                    "Предыдущее действие ожидает синхронизации"
                }
                val condition = requireNotNull(draft.vehicleCondition) { "Укажите состояние автомобиля" }
                val endOdometer = draft.endOdometerText.filter(Char::isDigit).toLongOrNull()
                    ?: error("Введите текущий пробег")
                require(endOdometer >= 0) { "Пробег не может быть отрицательным" }
                vehicle.startOdometer?.let { start -> require(endOdometer >= start) { "Пробег не может уменьшиться" } }
                val fuel = requireNotNull(draft.fuelLevelPercent) { "Укажите уровень топлива" }
                require(fuel in 0..100) { "Уровень топлива должен быть от 0 до 100%" }
                val description = draft.defectDescription.trim()
                if (condition == "DEFECT_REPORTED") {
                    require(description.isNotBlank()) { "Опишите новую неисправность" }
                    requireNotNull(draft.defectId)
                }
                val suspicious = vehicle.startOdometer?.let { start ->
                    endOdometer - start > today.suspiciousOdometerJumpKm
                } == true
                if (suspicious && !forceSuspiciousOdometer && !draft.confirmSuspiciousOdometer) {
                    requiresOdometerConfirmation.value = true
                    return@mutate
                }
                val operationId = UUID.randomUUID().toString()
                val command = PendingShiftCommand(
                    operationId = operationId,
                    shiftId = shift.id,
                    action = ACTION_CLOSING_REPORT,
                    expectedVersion = shift.version,
                    vehicleCondition = condition,
                    endOdometer = endOdometer,
                    fuelLevelPercent = fuel,
                    confirmSuspiciousOdometer = suspicious || forceSuspiciousOdometer || draft.confirmSuspiciousOdometer,
                    defectId = draft.defectId.takeIf { condition == "DEFECT_REPORTED" },
                    defectDescription = description.takeIf { condition == "DEFECT_REPORTED" },
                )
                localStore.enqueueShiftCommand(userId, command, entity)
                scheduler.request(userId)
            }
        }
    }

    /** Queues final close but deliberately keeps SHIFT_CLOSED hidden until server confirmation. */
    fun closeShift() = enqueueSimpleCommand(ACTION_CLOSE)

    /** Clears only transient presentation feedback; durable conflicts remain in the recovery screen. */
    fun clearError() {
        transientError.value = null
    }

    private fun enqueueSimpleCommand(
        action: String,
        enrich: (PendingShiftCommand) -> PendingShiftCommand = { it },
    ) {
        val userId = boundUserId.value ?: return
        viewModelScope.launch {
            mutate(userId) { entity, today ->
                val shift = requireNotNull(today.shift) { "Смена недоступна" }
                val queued = localStore.pendingOutbox(userId).shiftCommands(shift.id, localStore, json)
                val allowedPredecessors = when (action) {
                    ACTION_INSPECTION_COMPLETE -> setOf(ACTION_INSPECTION_ITEM, ACTION_PHOTO_RESERVATION)
                    else -> emptySet()
                }
                require(queued.all { it.action in allowedPredecessors }) {
                    "Предыдущее действие ожидает синхронизации"
                }
                val operationId = UUID.randomUUID().toString()
                val command = enrich(
                    PendingShiftCommand(
                        operationId = operationId,
                        shiftId = shift.id,
                        action = action,
                        expectedVersion = shift.version,
                    ),
                )
                localStore.enqueueShiftCommand(userId, command, entity)
                scheduler.request(userId)
            }
        }
    }

    private suspend fun mutate(
        userId: String,
        block: suspend (DriverShiftSnapshotEntity, TodayDriverShiftDto) -> Unit,
    ) {
        commandMutex.withLock {
            submitting.value = true
            transientError.value = null
            try {
                val entity = requireNotNull(localStore.cachedShiftSnapshot(userId)) { "Сначала обновите смену" }
                val today = json.decodeFromString<TodayDriverShiftDto>(entity.serializedTodayShift)
                block(entity, today)
            } catch (error: Throwable) {
                transientError.value = error.toDriverUserMessage(
                    "Не удалось сохранить действие. Обновите смену и повторите.",
                )
            } finally {
                submitting.value = false
            }
        }
    }

    private fun observeUserState(userId: String): Flow<DriverShiftUiState> =
        localStore.observeShiftSnapshot(userId).flatMapLatest { snapshot ->
            val shiftId = snapshot?.shiftId
            val draftFlow = if (shiftId == null) flowOf(null) else localStore.observeShiftDraft(userId, shiftId)
            val evidenceFlow = if (shiftId == null) flowOf(emptyList()) else localStore.observeShiftEvidence(userId, shiftId)
            combine(
                localStore.observePendingOutbox(userId),
                draftFlow,
                evidenceFlow,
                gatewayMonitor.state,
                localStore.observeProgress(userId),
            ) { outbox, draft, evidence, network, progress ->
                val today = snapshot?.serializedTodayShift?.let { encoded ->
                    runCatching { json.decodeFromString<TodayDriverShiftDto>(encoded) }.getOrNull()
                }
                val pending = outbox.shiftCommands(shiftId, localStore, json)
                DriverShiftUiState(
                    loading = snapshot == null,
                    today = today,
                    draft = draft,
                    evidence = evidence,
                    pendingActions = pending.mapTo(linkedSetOf()) { it.action },
                    pendingCount = pending.size,
                    online = network.online,
                    syncStage = progress?.stage,
                    syncMessage = progress?.message,
                    error = if (snapshot != null && today == null) "Локальное состояние смены повреждено" else null,
                )
            }
        }

    private fun defaultDraft(userId: String, shiftId: String) = DriverShiftDraftEntity(
        localId = "$userId:$shiftId",
        userId = userId,
        shiftId = shiftId,
        step = "VEHICLE",
        vehicleCondition = null,
        endOdometerText = "",
        fuelLevelPercent = null,
        defectId = null,
        defectDescription = "",
        confirmSuspiciousOdometer = false,
        updatedAtEpochMillis = System.currentTimeMillis(),
    )
}

private fun List<DriverOutboxEntity>.shiftCommands(
    shiftId: String?,
    localStore: DriverLocalStore,
    json: Json,
): List<PendingShiftCommand> = asSequence()
    .filter { it.kind == DriverLocalStore.OUTBOX_SHIFT_COMMAND && (shiftId == null || it.entryId == shiftId) }
    .mapNotNull { operation ->
        runCatching {
            json.decodeFromString<PendingShiftCommand>(localStore.decryptOutboxPayload(operation))
        }.getOrNull()
    }
    .toList()

private fun DriverShiftSnapshotEntity.withToday(
    userId: String,
    today: TodayDriverShiftDto,
    json: Json,
): DriverShiftSnapshotEntity = copy(
    userId = userId,
    shiftId = today.shift?.id,
    workDate = today.shift?.workDate,
    enabled = today.enabled,
    nextRequiredAction = today.nextRequiredAction,
    serializedTodayShift = json.encodeToString(today),
    serverTime = today.serverTime,
)

internal const val ACTION_BRIEFING_SEEN = "BRIEFING_SEEN"
internal const val ACTION_MEDICAL_CHECK = "MEDICAL_CHECK"
internal const val ACTION_INSPECTION_ITEM = "INSPECTION_ITEM"
internal const val ACTION_INSPECTION_COMPLETE = "INSPECTION_COMPLETE"
internal const val ACTION_START = "START"
internal const val ACTION_CLOSING_START = "CLOSING_START"
internal const val ACTION_WAREHOUSE_RETURN = "WAREHOUSE_RETURN"
internal const val ACTION_CLOSING_REPORT = "CLOSING_REPORT"
internal const val ACTION_PHOTO_RESERVATION = "PHOTO_RESERVATION"
internal const val ACTION_CLOSE = "CLOSE"
