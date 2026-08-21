package dev.buhanzaz.rwms.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerOutboxEntity
import dev.buhanzaz.rwms.worker.core.sync.WorkerSyncScheduler
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Defines worker application UI or lifecycle state; it does not decide a server task transition.
 */
data class WorkerDownloadItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val percent: Int?,
    val error: String?,
    val canRetry: Boolean,
)

/**
 * Defines worker application UI or lifecycle state; it does not decide a server task transition.
 */
data class WorkerDownloadsUiState(
    val items: List<WorkerDownloadItem> = emptyList(),
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
/**
 * Defines worker application UI or lifecycle state; it does not decide a server task transition.
 */
class WorkerDownloadsViewModel @Inject constructor(
    private val localStore: WorkerLocalStore,
    private val scheduler: WorkerSyncScheduler,
) : ViewModel() {
    private val userId = MutableStateFlow<String?>(null)

    val state: StateFlow<WorkerDownloadsUiState> = userId.flatMapLatest { id ->
        if (id == null) {
            flowOf(WorkerDownloadsUiState())
        } else {
            combine(
                localStore.observePendingOutbox(id),
                localStore.observeEvidence(id),
            ) { outbox, evidence ->
                WorkerDownloadsUiState(workerDownloadItems(outbox, evidence))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkerDownloadsUiState())

    fun bind(userId: String) {
        if (this.userId.value == userId) return
        this.userId.value = userId
    }

    fun retry() {
        userId.value?.let(scheduler::request)
    }
}

/**
 * Builds a deliberately sanitized UI projection. In particular the encrypted
 * outbox payload and encrypted evidence path never cross this boundary.
 */
internal fun workerDownloadItems(
    outbox: List<WorkerOutboxEntity>,
    evidence: List<TaskEvidenceEntity>,
): List<WorkerDownloadItem> {
    val evidenceReservationIds = evidence.mapTo(mutableSetOf()) { it.reservationOperationId }
    val evidenceItems = evidence.asSequence()
        .filterNot { it.state == "READY" }
        .filter {
            it.state in ACTIVE_EVIDENCE_STATES ||
                it.state in ERROR_EVIDENCE_STATES ||
                !it.lastError.isNullOrBlank()
        }
        .map { item ->
            val failed = item.state in ERROR_EVIDENCE_STATES || !item.lastError.isNullOrBlank()
            WorkerDownloadItem(
                id = "evidence:${item.evidenceId}",
                title = "Фото задания",
                subtitle = "Задание ${item.entryId}",
                status = evidenceStatus(item),
                percent = item.uploadPercent.takeIf { item.state == "UPLOADING" },
                error = item.lastError?.takeIf(String::isNotBlank)
                    ?: item.reviewReason?.takeIf { failed && it.isNotBlank() },
                canRetry = !item.lastError.isNullOrBlank() ||
                    (item.state == "REVIEW_REQUIRED" && item.mediaId == null),
            )
        }
    val outboxItems = outbox.asSequence()
        .filterNot {
            it.kind == WorkerLocalStore.OUTBOX_EVIDENCE_RESERVATION &&
                it.operationId in evidenceReservationIds
        }
        .map { operation ->
            WorkerDownloadItem(
                id = "outbox:${operation.operationId}",
                title = if (operation.kind == WorkerLocalStore.OUTBOX_ACTION) {
                    "Действие по заданию"
                } else {
                    "Подготовка фото"
                },
                subtitle = "Задание ${operation.entryId}",
                status = if (operation.state == WorkerLocalStore.OUTBOX_RETRY) "Ошибка" else "В очереди",
                percent = null,
                error = operation.lastError?.takeIf(String::isNotBlank),
                canRetry = operation.state == WorkerLocalStore.OUTBOX_RETRY,
            )
        }
    return (outboxItems + evidenceItems).sortedBy(WorkerDownloadItem::id).toList()
}

@Composable
fun WorkerDownloadsScreen(
    userId: String,
    onMenu: () -> Unit,
    profileMonogram: String,
    onProfile: () -> Unit,
    viewModel: WorkerDownloadsViewModel = hiltViewModel(),
) {
    LaunchedEffect(userId) { viewModel.bind(userId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkerScreenScaffold(
        title = "Загрузки",
        onMenu = onMenu,
        profileMonogram = profileMonogram,
        onProfile = onProfile,
    ) { padding ->
        if (state.items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Нет активных загрузок", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Успешно отправленные действия и фотографии исчезают отсюда автоматически.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = WorkerDownloadItem::id) { item ->
                    WorkerDownloadCard(item = item, onRetry = viewModel::retry)
                }
            }
        }
    }
}

@Composable
private fun WorkerDownloadCard(item: WorkerDownloadItem, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    item.status,
                    color = if (item.error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }
            item.percent?.let { percent ->
                LinearProgressIndicator(
                    progress = { percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Загружено: ${percent.coerceIn(0, 100)}%")
            }
            item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (item.canRetry) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("Повторить")
                }
            }
        }
    }
}

private fun evidenceStatus(evidence: TaskEvidenceEntity): String = when (evidence.state) {
    "CAPTURED" -> "Ожидает отправки"
    "RESERVED" -> "Подготовка"
    "UPLOADING" -> "Загружается"
    "PROCESSING" -> "Обрабатывается"
    "REVIEW_REQUIRED" -> "Требуется внимание"
    "REJECTED" -> "Отклонено"
    else -> "Синхронизация"
}

private val ACTIVE_EVIDENCE_STATES = setOf("CAPTURED", "RESERVED", "UPLOADING", "PROCESSING")
private val ERROR_EVIDENCE_STATES = setOf("REVIEW_REQUIRED", "REJECTED")
