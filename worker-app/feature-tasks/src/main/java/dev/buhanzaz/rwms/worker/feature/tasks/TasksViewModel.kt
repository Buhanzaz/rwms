package dev.buhanzaz.rwms.worker.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerConflictEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerGroupEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerSessionEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerSyncProgressEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.network.AuthenticatedGatewayMonitor
import dev.buhanzaz.rwms.worker.core.network.WorkerKpiPaletteDto
import dev.buhanzaz.rwms.worker.core.sync.WorkerSyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Defines worker feature UI state; server data and authorization remain authoritative.
 */
data class TasksUiState(
    val session: WorkerSessionEntity? = null,
    val groups: List<WorkerGroupEntity> = emptyList(),
    val categories: List<WorkerCategoryEntity> = emptyList(),
    val tasks: List<WorkerTaskEntity> = emptyList(),
    val assignments: List<WorkerAssignmentEntity> = emptyList(),
    val evidence: List<TaskEvidenceEntity> = emptyList(),
    val conflicts: List<WorkerConflictEntity> = emptyList(),
    val progress: WorkerSyncProgressEntity? = null,
    val online: Boolean = false,
    val kpiPalette: WorkerKpiPaletteDto? = null,
)

private data class VisibleTaskProjection(
    val session: WorkerSessionEntity?,
    val groups: List<WorkerGroupEntity>,
    val categories: List<WorkerCategoryEntity>,
    val tasks: List<WorkerTaskEntity>,
    val assignments: List<WorkerAssignmentEntity>,
    val evidence: List<TaskEvidenceEntity>,
    val conflicts: List<WorkerConflictEntity>,
)

private data class BoardOwnershipProjection(
    val groups: List<WorkerGroupEntity>,
    val assignments: List<WorkerAssignmentEntity>,
)

private data class TaskProjection(
    val visible: VisibleTaskProjection,
    val progress: WorkerSyncProgressEntity?,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
/**
 * Defines worker feature UI state; server data and authorization remain authoritative.
 */
class TasksViewModel @Inject constructor(
    private val localStore: WorkerLocalStore,
    private val scheduler: WorkerSyncScheduler,
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
                    runCatching { json.decodeFromString<WorkerKpiPaletteDto>(encoded) }.getOrNull()
                },
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

    /** Accepts the persisted server snapshot explicitly, then asks for a fresh authoritative feed. */
    fun acknowledgeConflicts() {
        val id = userId.value ?: return
        viewModelScope.launch {
            localStore.acknowledgeOpenConflicts(id)
            scheduler.request(id)
        }
    }
}
