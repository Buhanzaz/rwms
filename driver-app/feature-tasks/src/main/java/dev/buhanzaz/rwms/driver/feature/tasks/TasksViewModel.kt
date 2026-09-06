package dev.buhanzaz.rwms.driver.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.driver.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.driver.core.database.DriverAssignmentEntity
import dev.buhanzaz.rwms.driver.core.database.DriverCategoryEntity
import dev.buhanzaz.rwms.driver.core.database.DriverConflictEntity
import dev.buhanzaz.rwms.driver.core.database.DriverGroupEntity
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.database.DriverSessionEntity
import dev.buhanzaz.rwms.driver.core.database.DriverSyncProgressEntity
import dev.buhanzaz.rwms.driver.core.database.DriverTaskEntity
import dev.buhanzaz.rwms.driver.core.network.AuthenticatedGatewayMonitor
import dev.buhanzaz.rwms.driver.core.network.DriverKpiPaletteDto
import dev.buhanzaz.rwms.driver.core.sync.DriverSyncScheduler
import dev.buhanzaz.rwms.driver.core.sync.DriverWarehouseClock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Defines driver feature UI state; server data and authorization remain authoritative.
 */
data class TasksUiState(
    val session: DriverSessionEntity? = null,
    val groups: List<DriverGroupEntity> = emptyList(),
    val categories: List<DriverCategoryEntity> = emptyList(),
    val tasks: List<DriverTaskEntity> = emptyList(),
    val assignments: List<DriverAssignmentEntity> = emptyList(),
    val evidence: List<TaskEvidenceEntity> = emptyList(),
    val conflicts: List<DriverConflictEntity> = emptyList(),
    val progress: DriverSyncProgressEntity? = null,
    val online: Boolean = false,
    val kpiPalette: DriverKpiPaletteDto? = null,
    /** Server-anchored date in the assigned warehouse timezone; null keeps dated work closed. */
    val warehouseDate: LocalDate? = null,
)

/** Groups the authorization-filtered Room streams used by the board projection. */
private data class VisibleTaskProjection(
    val session: DriverSessionEntity?,
    val groups: List<DriverGroupEntity>,
    val categories: List<DriverCategoryEntity>,
    val tasks: List<DriverTaskEntity>,
    val assignments: List<DriverAssignmentEntity>,
    val evidence: List<TaskEvidenceEntity>,
    val conflicts: List<DriverConflictEntity>,
)

/** Carries group and assignment ownership separately from task list updates. */
private data class BoardOwnershipProjection(
    val groups: List<DriverGroupEntity>,
    val assignments: List<DriverAssignmentEntity>,
)

/** Couples visible board data with the current durable synchronization progress. */
private data class TaskProjection(
    val visible: VisibleTaskProjection,
    val progress: DriverSyncProgressEntity?,
    val warehouseDate: LocalDate? = null,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
/**
 * Defines driver feature UI state; server data and authorization remain authoritative.
 */
class TasksViewModel @Inject constructor(
    private val localStore: DriverLocalStore,
    private val scheduler: DriverSyncScheduler,
    private val warehouseClock: DriverWarehouseClock,
    private val gatewayMonitor: AuthenticatedGatewayMonitor,
    private val json: Json,
) : ViewModel() {
    private val userId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TasksUiState> = userId.flatMapLatest { id ->
        if (id == null) flowOf(TasksUiState()) else combine(
            localStore.observeSession(id),
            localStore.observeCategories(id),
            localStore.observeTasks(id),
            localStore.observeEvidence(id),
            localStore.observeConflicts(id),
        ) { session, categories, tasks, evidence, conflicts ->
            VisibleTaskProjection(
                session = session,
                groups = emptyList(),
                categories = categories,
                tasks = tasks,
                assignments = emptyList(),
                evidence = evidence,
                conflicts = conflicts,
            )
        }.combine(
            combine(
                localStore.observeGroups(id),
                localStore.observeAssignments(id),
            ) { groups, assignments -> BoardOwnershipProjection(groups, assignments) },
        ) { visible, ownership ->
            visible.copy(groups = ownership.groups, assignments = ownership.assignments)
        }.combine(localStore.observeProgress(id)) { visible, progress ->
            TaskProjection(visible, progress)
        }.combine(warehouseClock.observeDate(id)) { projection, warehouseDate ->
            projection.copy(warehouseDate = warehouseDate)
        }.combine(gatewayMonitor.state) { projection, gateway ->
            TasksUiState(
                session = projection.visible.session,
                groups = projection.visible.groups,
                categories = projection.visible.categories,
                tasks = projection.visible.tasks,
                assignments = projection.visible.assignments,
                evidence = projection.visible.evidence,
                conflicts = projection.visible.conflicts,
                progress = projection.progress,
                online = gateway.online,
                kpiPalette = projection.visible.session?.kpiPaletteJson?.let { encoded ->
                    runCatching { json.decodeFromString<DriverKpiPaletteDto>(encoded) }.getOrNull()
                },
                warehouseDate = projection.warehouseDate,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    fun bind(userId: String) {
        if (this.userId.value == userId) return
        this.userId.value = userId
        scheduler.request(userId)
    }

    fun syncNow() {
        userId.value?.let(scheduler::request)
    }
}
