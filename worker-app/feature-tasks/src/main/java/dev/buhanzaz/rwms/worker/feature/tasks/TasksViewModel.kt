package dev.buhanzaz.rwms.worker.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerConflictEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerSessionEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerSyncProgressEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.network.AuthenticatedGatewayMonitor
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

data class TasksUiState(
    val session: WorkerSessionEntity? = null,
    val categories: List<WorkerCategoryEntity> = emptyList(),
    val tasks: List<WorkerTaskEntity> = emptyList(),
    val evidence: List<TaskEvidenceEntity> = emptyList(),
    val conflicts: List<WorkerConflictEntity> = emptyList(),
    val progress: WorkerSyncProgressEntity? = null,
    val online: Boolean = false,
)

private data class VisibleTaskProjection(
    val session: WorkerSessionEntity?,
    val categories: List<WorkerCategoryEntity>,
    val tasks: List<WorkerTaskEntity>,
    val evidence: List<TaskEvidenceEntity>,
    val conflicts: List<WorkerConflictEntity>,
)

private data class TaskProjection(
    val visible: VisibleTaskProjection,
    val progress: WorkerSyncProgressEntity?,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel @Inject constructor(
    private val localStore: WorkerLocalStore,
    private val scheduler: WorkerSyncScheduler,
    private val gatewayMonitor: AuthenticatedGatewayMonitor,
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
            VisibleTaskProjection(session, categories, tasks, evidence, conflicts)
        }.combine(localStore.observeProgress(id)) { visible, progress ->
            TaskProjection(visible, progress)
        }.combine(gatewayMonitor.state) { projection, gateway ->
            TasksUiState(
                session = projection.visible.session,
                categories = projection.visible.categories,
                tasks = projection.visible.tasks,
                evidence = projection.visible.evidence,
                conflicts = projection.visible.conflicts,
                progress = projection.progress,
                online = gateway.online,
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
