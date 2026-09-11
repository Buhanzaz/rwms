package dev.buhanzaz.rwms.worker.feature.taskdetail

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerMediaReferenceDto
import dev.buhanzaz.rwms.worker.core.network.WorkerWorkDto
import dev.buhanzaz.rwms.worker.core.ui.TaskStatusChip
import dev.buhanzaz.rwms.worker.core.ui.WorkerButton as Button
import dev.buhanzaz.rwms.worker.core.ui.WorkerKpiColorRange
import dev.buhanzaz.rwms.worker.core.ui.WorkerOutlinedButton as OutlinedButton
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import dev.buhanzaz.rwms.worker.core.ui.cabinNumberForDisplay
import dev.buhanzaz.rwms.worker.core.ui.workerRepairComplexityLabel
import dev.buhanzaz.rwms.worker.core.ui.workerKpiTimeColor
import dev.buhanzaz.rwms.worker.core.ui.workerTaskStageOrdinal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Renders one task, its evidence and the actions allowed by the synchronized server state. */
@Composable
fun TaskDetailScreen(
    userId: String,
    entryId: String,
    onBack: (() -> Unit)? = null,
    onCamera: (routeIndex: Int, completeAfterSave: Boolean) -> Unit,
    onMedia: (
        title: String,
        previewPaths: List<String>,
        readPaths: List<String>,
        initialIndex: Int,
    ) -> Unit,
    onGallery: (routeIndex: Int) -> Unit = {},
    onReportCapture: (reportId: String, routeIndex: Int, remainingPhotos: Int, fromGallery: Boolean) -> Unit = { _, _, _, _ -> },
    onMenu: (() -> Unit)? = null,
    profileMonogram: String? = null,
    profileAvatar: Bitmap? = null,
    onProfile: (() -> Unit)? = null,
    onCompletionQueued: () -> Unit = {},
    takeSlingerOnOpen: Boolean = false,
    autoTakeOnOpen: Boolean = false,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(userId, entryId) { viewModel.bind(userId, entryId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val detail = state.detail
    val task = state.task
    val session = state.session
    val cabinNumber = cabinNumberForDisplay(task?.unitNumber, detail?.taskObject?.label)
    val displayedStatus = when {
        task?.locallyPending == true -> task.status
        detail != null -> detail.status
        else -> task?.status
    }
    val photoCapture = photoCapturePresentation(
        hasLoadedDetail = detail != null,
        taskStatus = displayedStatus,
        currentWorkerId = userId,
        queuePurpose = state.queuePurpose,
        assignments = state.assignments,
        locallyPending = task?.locallyPending == true,
    )
    val readyServerEvidence = detail?.evidence.orEmpty()
        .filter { evidence -> evidence.state == "READY" && !evidence.readPath.isNullOrBlank() }
    val readyEvidenceIds = readyServerEvidence.mapTo(mutableSetOf()) { it.evidenceId }.apply {
        addAll(detail?.evidence.orEmpty().filter { it.state == "READY" }.map { it.evidenceId })
        addAll(state.evidence.filter { it.state == "READY" }.map { it.evidenceId })
    }
    val readyEvidenceCount = readyEvidenceIds.size
    var showCompletionDialog by rememberSaveable(entryId) { mutableStateOf(false) }
    var showProblemReport by rememberSaveable(entryId) { mutableStateOf(false) }
    val localEvidenceWithoutServerPhoto = state.evidence
        .filterNot { local -> local.state == "READY" }
        .filterNot { local ->
            readyServerEvidence.any { remote -> remote.evidenceId == local.evidenceId }
        }
    val plannedDurationMinutes = detail?.plannedDurationMinutes ?: task?.plannedDurationMinutes
    val timerSnapshot = detail?.timerSnapshot ?: task?.serverTimerSnapshotOrNull()
    var elapsedSinceSnapshotSeconds by remember(timerSnapshot) { mutableStateOf(0L) }
    LaunchedEffect(timerSnapshot) {
        while (timerSnapshot?.timerState == "WORKING") {
            delay(1_000)
            elapsedSinceSnapshotSeconds += 1
        }
    }
    val timing = taskTimingPresentation(
        snapshot = timerSnapshot,
        plannedDurationMinutes = plannedDurationMinutes,
        elapsedSinceSnapshotSeconds = elapsedSinceSnapshotSeconds,
    )
    val kpiTimeColor = workerKpiTimeColor(
        remainingPercent = timerSnapshot?.projectedAfter(elapsedSinceSnapshotSeconds)?.remainingPercent,
        ranges = state.kpiPalette?.ranges.orEmpty().map {
            WorkerKpiColorRange(it.fromPercent, it.toPercent, it.color)
        },
        overdueColor = state.kpiPalette?.overdueColor,
    )
    val sourceMediaPresentation = taskSourceMediaPresentation(
        works = detail?.works.orEmpty(),
        sourceMedia = detail?.sourceMedia.orEmpty(),
    )
    val transfer = detail?.let(::transferTaskPresentation)
    val generalSourceMedia = sourceMediaPresentation.general
    val stageOrdinal = taskDetailStageOrdinal(
        detailRouteStepIndex = detail?.routeStepIndex,
        detailRouteStepCount = detail?.routeStepCount,
        cachedRouteStepIndex = task?.routeStepIndex,
        cachedRouteStepCount = task?.routeStepCount,
    )
    val repairComplexity = workerRepairComplexityLabel(detail?.title ?: task?.title)
    val showRepairComplexity = detail?.source?.type == "MAINTENANCE_REPAIR" || repairComplexity != null
    LaunchedEffect(timerSnapshot?.nextTransitionAt, timerSnapshot?.serverTime) {
        val snapshot = timerSnapshot ?: return@LaunchedEffect
        val nextTransitionAt = snapshot.nextTransitionAt ?: return@LaunchedEffect
        val transitionDelay = runCatching {
            java.time.Duration.between(
                java.time.Instant.parse(snapshot.serverTime),
                java.time.Instant.parse(nextTransitionAt),
            ).toMillis()
        }.getOrNull() ?: return@LaunchedEffect
        if (transitionDelay > 0) delay(transitionDelay)
        viewModel.refresh()
    }
    val actionPresentation = taskActionPresentation(
        currentWorkerId = userId,
        taskStatus = displayedStatus,
        availabilityMode = detail?.availabilityMode,
        entryType = task?.entryType ?: "SHADOW",
        queuePurpose = state.queuePurpose,
        assignments = state.assignments,
        locallyPending = task?.locallyPending == true,
        hasCurrentGroup = session?.currentGroupId != null,
        operationalAvailability = session?.operationalAvailability ?: "DISABLED",
    )
    val canReportProblem = WorkerTaskAction.COMPLETE in actionPresentation.actions &&
        actionPresentation.actionsEnabled &&
        photoCapture.enabled
    val canReportMissing = canReportProblem &&
        !state.hasPendingMissingItemReport
    var automaticJoinRequested by remember(entryId, takeSlingerOnOpen) { mutableStateOf(false) }
    var automaticTakeRequested by remember(entryId, autoTakeOnOpen) { mutableStateOf(false) }
    val footerActions = actionPresentation.actions.filter {
        it == WorkerTaskAction.TAKE ||
            it == WorkerTaskAction.JOIN ||
            it == WorkerTaskAction.RESUME
    }
    LaunchedEffect(
        takeSlingerOnOpen,
        actionPresentation.actions,
        actionPresentation.actionsEnabled,
    ) {
        if (shouldAutomaticallyJoinSlingerTask(
                takeSlingerOnOpen = takeSlingerOnOpen,
                alreadyRequested = automaticJoinRequested,
                presentation = actionPresentation,
            )
        ) {
            automaticJoinRequested = true
            viewModel.perform(WorkerTaskAction.JOIN.wireValue)
        }
    }
    LaunchedEffect(
        autoTakeOnOpen,
        actionPresentation.actions,
        actionPresentation.actionsEnabled,
    ) {
        if (shouldAutomaticallyTakeTask(
                autoTakeOnOpen = autoTakeOnOpen,
                alreadyRequested = automaticTakeRequested,
                presentation = actionPresentation,
            )
        ) {
            automaticTakeRequested = true
            viewModel.perform(WorkerTaskAction.TAKE.wireValue)
        }
    }
    LaunchedEffect(readyEvidenceCount) { viewModel.refresh() }
    val mediaTitle = cabinNumber ?: "Задание"
    LaunchedEffect(detail?.status, task?.locallyPending) {
        if (task?.locallyPending == true && task.status == "DONE") {
            // A successful gallery import completed the local capture flow. Keep the dialog for
            // picker cancellation, but close it once the local task is queued as done.
            showCompletionDialog = false
        }
        if (shouldCloseAfterAuthoritativeCompletion(detail?.status, task?.locallyPending == true)) {
            onCompletionQueued()
        }
    }
    WorkerScreenScaffold(
        title = if (transfer == null) cabinNumber ?: "Задание" else "Межскладской рейс",
        onBack = onBack,
        onMenu = onMenu,
        profileMonogram = profileMonogram,
        profileAvatar = profileAvatar,
        onProfile = onProfile,
        bottomBar = {
            if (footerActions.isNotEmpty()) {
                TaskEntryActionFooter(
                    actions = footerActions,
                    presentation = actionPresentation,
                    onAction = { viewModel.perform(it.wireValue) },
                )
            }
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        // The scaffold padding ends below the 8dp outer margin and 60dp header surface.
        // Moving back 38dp places the mask transition at that surface's vertical midpoint.
        val headerFadeEnd = (padding.calculateTopPadding() - 38.dp).coerceAtLeast(1.dp)
        LazyColumn(
            // Let the list viewport reach beneath the shell surfaces. Applying Scaffold's
            // padding to the modifier shrinks the viewport and leaves the scroll content ending
            // in mid-air; contentPadding keeps the first/last rows safe while preserving clipping.
            modifier = Modifier.fillMaxSize().fadeIntoTaskHeader(headerFadeEnd),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection),
                top = padding.calculateTopPadding() + 12.dp,
                end = padding.calculateEndPadding(layoutDirection),
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
        ) {
            item {
                TaskMediaPager(
                    thumbnailPaths = generalSourceMedia.map { it.thumbnailPath ?: it.readPath },
                    contentDescription = "Фото задания",
                    emptyMessage = "Фотографии отсутствуют",
                    onOpen = { index ->
                        onMedia(
                            mediaTitle,
                            generalSourceMedia.map { it.thumbnailPath ?: it.readPath },
                            generalSourceMedia.map(WorkerMediaReferenceDto::readPath),
                            index,
                        )
                    },
                )
            }
            item { TaskTimerCard(timing = timing, kpiColor = kpiTimeColor) }
            item {
                TaskMetadataCard(
                    stage = stageOrdinal,
                    priority = detail?.priority ?: task?.priority,
                    repairComplexity = repairComplexity,
                    showRepairComplexity = showRepairComplexity,
                )
            }
            transfer?.let { presentation ->
                item { TransferTaskCard(presentation) }
            }
            if (readyServerEvidence.isNotEmpty()) {
                item {
                    TaskMediaPager(
                        thumbnailPaths = readyServerEvidence.map {
                            it.thumbnailPath ?: requireNotNull(it.readPath)
                        },
                        contentDescription = "Фото результата",
                        onOpen = { index ->
                            onMedia(
                                mediaTitle,
                                readyServerEvidence.map {
                                    it.thumbnailPath ?: requireNotNull(it.readPath)
                                },
                                readyServerEvidence.map { requireNotNull(it.readPath) },
                                index,
                            )
                        },
                    )
                }
            }
            if (localEvidenceWithoutServerPhoto.isNotEmpty()) {
                items(localEvidenceWithoutServerPhoto, key = { "local-${it.evidenceId}" }) { evidence ->
                    EvidenceRow(
                        evidence = evidence,
                        hasValidReservationPayload = evidence.evidenceId in state.retryableEvidenceIds,
                        onRetry = { viewModel.retryEvidence(evidence.evidenceId) },
                    )
                }
            }
            state.error?.let { error ->
                item {
                    Text(
                        error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (transfer == null || !detail?.works.isNullOrEmpty()) {
                item { TaskSectionTitle("Работы:") }
            }
            if (transfer == null && detail?.works.isNullOrEmpty()) {
                item { EmptyTaskSection("Состав работ не указан") }
            } else if (!detail?.works.isNullOrEmpty()) {
                items(requireNotNull(detail).works, key = { it.id }) { work ->
                    val workMedia = sourceMediaPresentation.byWorkId[work.id].orEmpty()
                    WorkRow(
                        work = work,
                        sourceMedia = workMedia,
                        onMedia = { index ->
                            onMedia(
                                "$mediaTitle · ${work.name}",
                                workMedia.map { it.thumbnailPath ?: it.readPath },
                                workMedia.map(WorkerMediaReferenceDto::readPath),
                                index,
                            )
                        },
                        canReportMissing = canReportMissing,
                        onReportMissing = { viewModel.reportMissingItem(work.id, "WORK", work.name) },
                    )
                }
            }

            if (transfer == null) {
                item { TaskSectionTitle("Материалы:") }
            }
            if (transfer == null && detail?.materials.isNullOrEmpty()) {
                item { EmptyTaskSection("Материалы не указаны") }
            } else if (transfer == null) {
                items(requireNotNull(detail).materials, key = { it.id }) { material ->
                    RequirementCard(
                        name = material.name,
                        quantity = "${material.quantity} ${material.unit.orEmpty()}".trim(),
                        availabilityState = material.availabilityState,
                        canReportMissing = canReportMissing,
                        onReportMissing = { viewModel.reportMissingItem(material.id, "MATERIAL", material.name) },
                    )
                }
            }

            if (footerActions.isEmpty()) {
                actionPresentation.message?.let { message ->
                    item {
                        Text(
                            message,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (WorkerTaskAction.COMPLETE in actionPresentation.actions) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showProblemReport = true },
                            enabled = canReportProblem,
                            border = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                                    RoundedCornerShape(16.dp),
                                )
                                .testTag("task-report-problem"),
                        ) {
                            Icon(Icons.Filled.ReportProblem, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text("Сообщить о проблеме", modifier = Modifier.padding(start = 8.dp))
                        }
                        Button(
                            onClick = { showCompletionDialog = true },
                            enabled = actionPresentation.actionsEnabled && photoCapture.enabled,
                            modifier = Modifier.fillMaxWidth().testTag("task-complete"),
                        ) { Text("Завершить задание") }
                        photoCapture.message?.let { message ->
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showProblemReport && detail != null) {
        WorkerProblemReportDialog(
            userId = userId,
            entryId = entryId,
            routeIndex = detail.routeIndex,
            onDismiss = { showProblemReport = false },
            onCapture = onReportCapture,
        )
    }

    if (showCompletionDialog) {
        TaskCompletionDialog(
            isTransfer = transfer != null,
            onDismissRequest = { showCompletionDialog = false },
            onCamera = {
                showCompletionDialog = false
                onCamera(requireNotNull(detail).routeIndex, true)
            },
            // Keep this dialog mounted while the picker is open. Gallery cancellation can then
            // return to the exact completion choice surface instead of an empty intermediate page.
            onGallery = { onGallery(requireNotNull(detail).routeIndex) },
        )
    }
}

/** Fades task rows only as they pass the visible header, while keeping the header untouched. */
private fun Modifier.fadeIntoTaskHeader(fadeEnd: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val mask = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = fadeEnd.toPx().coerceAtLeast(1f),
            )
            onDrawWithContent {
                drawContent()
                drawRect(mask, blendMode = BlendMode.DstIn)
            }
        }

/** Opaque completion choice surface shared by ordinary and inter-warehouse tasks. */
@Composable
internal fun TaskCompletionDialog(
    isTransfer: Boolean,
    onDismissRequest: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .testTag("task-completion-dialog"),
            colors = CardDefaults.cardColors(
                // Dialog content must remain readable over the water background and match the
                // opaque problem-report sheet surface.
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (isTransfer) "Подтвердить выгрузку" else "Завершить задание",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (isTransfer) {
                        "Добавьте фотографии выгруженного груза. После сохранения межскладской рейс закроется через обычную синхронизацию."
                    } else {
                        "Добавьте фотографии результата. После сохранения задание закроется автоматически."
                    },
                )
                Button(
                    onClick = onCamera,
                    modifier = Modifier.fillMaxWidth().testTag("task-completion-camera"),
                ) {
                    Icon(Icons.Filled.AddAPhoto, contentDescription = null)
                    Text(
                        if (isTransfer) "Сделать фото выгрузки" else "Сделать фото",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Button(
                    onClick = onGallery,
                    modifier = Modifier.fillMaxWidth().testTag("task-completion-gallery"),
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Text("Из галереи", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = onDismissRequest,
                    border = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .border(
                            BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                            RoundedCornerShape(16.dp),
                        )
                        .testTag("task-completion-cancel"),
                ) { Text("Отмена") }
            }
        }
    }
}

/** Allows exactly one automatic JOIN after the worker accepts the interruption dialog. */
internal fun shouldAutomaticallyJoinSlingerTask(
    takeSlingerOnOpen: Boolean,
    alreadyRequested: Boolean,
    presentation: TaskActionPresentation,
): Boolean = takeSlingerOnOpen &&
    !alreadyRequested &&
    presentation.actionsEnabled &&
    WorkerTaskAction.JOIN in presentation.actions

/** Automatically claims the one ordinary task opened as the application's root destination. */
internal fun shouldAutomaticallyTakeTask(
    autoTakeOnOpen: Boolean,
    alreadyRequested: Boolean,
    presentation: TaskActionPresentation,
): Boolean = autoTakeOnOpen &&
    !alreadyRequested &&
    presentation.actionsEnabled &&
    WorkerTaskAction.TAKE in presentation.actions

/**
 * Renders only server-projected transfer facts and keeps the existing task action pipeline below
 * the card authoritative for take, pause, evidence and completion transitions.
 */
@Composable
internal fun TransferTaskCard(presentation: TransferTaskPresentation) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .testTag("transfer-task-card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag("transfer-task-badge"),
            ) {
                Text(
                    "Межскладское перемещение",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            TransferCardSection(
                title = "Маршрут",
                lines = listOf(
                    presentation.route
                        ?: "Точки отправления и назначения не переданы в карточку задания",
                ),
                testTag = "transfer-route",
            )
            TransferCardSection(
                title = "Груз",
                lines = presentation.cargoLines.ifEmpty {
                    listOf("Состав груза не передан в карточку задания")
                },
                testTag = "transfer-cargo",
            )
            if (presentation.materialLines.isNotEmpty()) {
                TransferCardSection(
                    title = "Мебель и материалы",
                    lines = presentation.materialLines,
                    testTag = "transfer-materials",
                )
            }
            TransferCardSection(
                title = "Порядок выполнения",
                lines = presentation.instructions.mapIndexed { index, instruction ->
                    "${index + 1}. $instruction"
                },
                testTag = "transfer-instructions",
            )
            if (presentation.routeSteps.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("transfer-related-steps"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Связанные этапы ходки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    presentation.routeSteps.forEach { step ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${step.ordinal}.", fontWeight = FontWeight.Bold)
                            Text(step.label, modifier = Modifier.weight(1f))
                            TaskStatusChip(step.status)
                        }
                    }
                }
            }
            if (presentation.comments.isNotEmpty()) {
                TransferCardSection(
                    title = "Комментарии логиста",
                    lines = presentation.comments,
                    testTag = "transfer-comments",
                )
            }
        }
    }
}

/** Displays one exact transfer subsection with consistent spacing and no hidden overflow. */
@Composable
private fun TransferCardSection(title: String, lines: List<String>, testTag: String) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Resolves the worker-package label from authoritative detail first and then the offline feed
 * projection. Raw evidence route identities are deliberately absent from this presentation path.
 */
internal fun taskDetailStageOrdinal(
    detailRouteStepIndex: Int?,
    detailRouteStepCount: Int?,
    cachedRouteStepIndex: Int?,
    cachedRouteStepCount: Int?,
): String {
    val routeStepIndex = detailRouteStepIndex ?: cachedRouteStepIndex ?: 0
    val routeStepCount = detailRouteStepCount ?: cachedRouteStepCount ?: (routeStepIndex + 1)
    return workerTaskStageOrdinal(routeStepIndex, routeStepCount, "/")
}

/** Keeps task entry and resume actions visible in a safe-area-aware, full-width footer. */
@Composable
private fun TaskEntryActionFooter(
    actions: List<WorkerTaskAction>,
    presentation: TaskActionPresentation,
    onAction: (WorkerTaskAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("task-action-footer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presentation.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        actions.forEach { action ->
            Button(
                onClick = { onAction(action) },
                enabled = presentation.actionsEnabled,
                modifier = Modifier.fillMaxWidth().testTag(
                    "task-action-${action.wireValue.lowercase()}",
                ),
            ) {
                Text(
                    when (action) {
                        WorkerTaskAction.TAKE -> presentation.takeLabel
                        WorkerTaskAction.JOIN -> presentation.joinLabel
                        WorkerTaskAction.RESUME -> "Продолжить"
                        else -> action.wireValue
                    },
                )
            }
        }
    }
}

/** Shows source-owned task classification immediately below the authoritative timer. */
@Composable
private fun TaskMetadataCard(
    stage: String?,
    priority: Int?,
    repairComplexity: String?,
    showRepairComplexity: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("task-metadata"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Этап ${stage ?: "—"}", style = MaterialTheme.typography.bodyLarge)
            Text("Приоритет ${priority ?: "—"}", style = MaterialTheme.typography.bodyLarge)
            if (showRepairComplexity) {
                Text(
                    repairComplexity ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** Renders a consistent heading for one stage of task execution. */
@Composable
private fun TaskSectionTitle(title: String) = Text(
    text = title,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.Bold,
)

/** Makes a missing optional server section explicit without fabricating content. */
@Composable
private fun EmptyTaskSection(message: String) {
    Text(
        message,
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Displays one edge-to-edge-in-content photo at a time with swipe and explicit pager controls. */
@Composable
private fun TaskMediaPager(
    thumbnailPaths: List<String>,
    contentDescription: String,
    onOpen: (index: Int) -> Unit,
    emptyMessage: String? = null,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp),
    testTag: String = "task-photo-pager",
) {
    if (thumbnailPaths.isEmpty()) {
        if (emptyMessage != null) {
            Card(
                modifier = modifier.fillMaxWidth().height(208.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    val pagerState = rememberPagerState(pageCount = { thumbnailPaths.size })
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier.fillMaxWidth().height(208.dp).testTag(testTag),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Card(
                modifier = Modifier.fillMaxSize().clickable { onOpen(page) },
            ) {
                RemoteMediaThumbnail(
                    path = thumbnailPaths[page],
                    contentDescription = "$contentDescription ${page + 1}",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        IconButton(
            onClick = {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
            },
            enabled = pagerState.currentPage > 0,
            modifier = Modifier.align(Alignment.CenterStart).size(56.dp),
        ) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = "Предыдущее фото",
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(
            onClick = {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            },
            enabled = pagerState.currentPage < thumbnailPaths.lastIndex,
            modifier = Modifier.align(Alignment.CenterEnd).size(56.dp),
        ) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "Следующее фото",
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = "${pagerState.currentPage + 1}/${thumbnailPaths.size}",
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Presents elapsed time, remaining time and authoritative KPI percent across the full width. */
@Composable
private fun TaskTimerCard(timing: TaskTimingPresentation, kpiColor: androidx.compose.ui.graphics.Color?) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("task-timer")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimerMetric("Таймер", timing.activeElapsed, Modifier.weight(1f))
            TimerMetric("Осталось", timing.remaining ?: "—", Modifier.weight(1f), kpiColor)
            TimerMetric("KPI", timing.remainingPercent ?: "—", Modifier.weight(1f), kpiColor)
        }
    }
}

/** Keeps one timer metric centered and readable on compact phone widths. */
@Composable
private fun TimerMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = color ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Keeps each source photo inside the card of the work identified by its server media IDs. */
@Composable
internal fun WorkRow(
    work: WorkerWorkDto,
    sourceMedia: List<WorkerMediaReferenceDto>,
    onMedia: (index: Int) -> Unit,
    canReportMissing: Boolean,
    onReportMissing: () -> Unit,
) {
    val presentation = workPresentation(work)
    Card(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        colors = requirementCardColors(work.availabilityState),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(work.name, style = MaterialTheme.typography.bodyLarge)
                    RequirementStatus(work.availabilityState, isMaterial = false)
                }
                Text(presentation.quantity, fontWeight = FontWeight.Bold)
                MissingRequirementButton(work.availabilityState, canReportMissing, onReportMissing, work.name, isMaterial = false)
            }
            if (sourceMedia.isNotEmpty()) {
                Text(
                    "Фото к работе",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                TaskMediaPager(
                    thumbnailPaths = sourceMedia.map { it.thumbnailPath ?: it.readPath },
                    contentDescription = "Фото к работе ${work.name}",
                    onOpen = onMedia,
                    modifier = Modifier,
                    testTag = "task-work-photo-pager-${work.id}",
                )
            }
        }
    }
}

@Composable
internal fun RequirementCard(
    name: String,
    quantity: String,
    availabilityState: String,
    canReportMissing: Boolean,
    onReportMissing: () -> Unit,
) {
    Card(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        colors = requirementCardColors(availabilityState),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                RequirementStatus(availabilityState, isMaterial = true)
            }
            Text(quantity, fontWeight = FontWeight.Bold)
            MissingRequirementButton(availabilityState, canReportMissing, onReportMissing, name, isMaterial = true)
        }
    }
}

@Composable
private fun MissingRequirementButton(
    availabilityState: String,
    canReportMissing: Boolean,
    onClick: () -> Unit,
    name: String,
    isMaterial: Boolean,
) {
    val missing = availabilityState == "MISSING"
    IconButton(
        onClick = onClick,
        enabled = canReportMissingRequirement(availabilityState, canReportMissing),
        modifier = Modifier.semantics { selected = missing },
    ) {
        Icon(
            if (missing) Icons.Filled.Undo else Icons.Filled.ReportProblem,
            contentDescription = when {
                missing -> "Отменить отметку: $name"
                isMaterial -> "Нет на складе: $name"
                else -> "Не выполнена: $name"
            },
        )
    }
}

@Composable
private fun RequirementStatus(availabilityState: String, isMaterial: Boolean) {
    val label = when (availabilityState) {
        "MISSING" -> if (isMaterial) "Нет на складе" else "Не выполнена"
        "RESTORED" -> if (isMaterial) "Есть на складе" else "Можно выполнить"
        "COMPLETED" -> "Выполнено"
        else -> return
    }
    Text(label, style = MaterialTheme.typography.labelMedium)
}

internal enum class RequirementAvailabilityTone { DEFAULT, MISSING }

internal fun requirementAvailabilityTone(availabilityState: String): RequirementAvailabilityTone = when (availabilityState) {
    "MISSING" -> RequirementAvailabilityTone.MISSING
    else -> RequirementAvailabilityTone.DEFAULT
}

internal fun canReportMissingRequirement(availabilityState: String, canReportMissing: Boolean): Boolean =
    canReportMissing && availabilityState != "COMPLETED"

@Composable
internal fun requirementCardColors(availabilityState: String) = CardDefaults.cardColors(
    containerColor = when (requirementAvailabilityTone(availabilityState)) {
        RequirementAvailabilityTone.MISSING -> MaterialTheme.colorScheme.errorContainer
        RequirementAvailabilityTone.DEFAULT -> MaterialTheme.colorScheme.surface
    },
    contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun RemoteMediaThumbnail(
    path: String,
    contentDescription: String,
    modifier: Modifier = Modifier.size(88.dp),
    viewModel: TaskMediaThumbnailViewModel = hiltViewModel(),
) {
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()
    val thumbnail = thumbnails[path]
    LaunchedEffect(path, thumbnail) {
        if (thumbnail == null) viewModel.load(path)
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when (thumbnail) {
            is TaskMediaThumbnail.Ready -> Image(
                bitmap = thumbnail.bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            is TaskMediaThumbnail.Failed -> Text(
                "Фото недоступно",
                style = MaterialTheme.typography.labelSmall,
            )
            null, TaskMediaThumbnail.Loading -> Unit
        }
    }
}

@Composable
private fun EvidenceRow(
    evidence: TaskEvidenceEntity,
    hasValidReservationPayload: Boolean,
    onRetry: () -> Unit,
) {
    val presentation = evidencePresentation(
        evidence.state,
        evidence.uploadPercent,
        evidence.mediaId,
        hasValidReservationPayload,
    )
    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalEvidenceThumbnail(
                encryptedPath = evidence.encryptedFilePath,
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            if (presentation.status.isNotBlank()) {
                Text(presentation.status, style = MaterialTheme.typography.titleSmall)
            }
            presentation.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            evidence.lastError?.let {
                Text(
                    "Последняя попытка отправки не удалась. Фото сохранено и будет отправлено повторно.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (presentation.canRetryReservation) {
                OutlinedButton(onClick = onRetry) { Text("Повторить отправку") }
            }
        }
    }
}

/** Renders only the encrypted local original; transport variants remain an implementation detail. */
@Composable
private fun LocalEvidenceThumbnail(
    encryptedPath: String,
    modifier: Modifier = Modifier,
    viewModel: TaskMediaThumbnailViewModel = hiltViewModel(),
) {
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()
    val thumbnail = thumbnails[encryptedPath]
    LaunchedEffect(encryptedPath, thumbnail) {
        if (thumbnail == null) viewModel.loadLocal(encryptedPath)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (thumbnail) {
            is TaskMediaThumbnail.Ready -> Image(
                bitmap = thumbnail.bitmap.asImageBitmap(),
                contentDescription = "Локальное фото результата",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            is TaskMediaThumbnail.Failed -> Text(
                "Фото сохранено",
                style = MaterialTheme.typography.labelSmall,
            )
            null, TaskMediaThumbnail.Loading -> Unit
        }
    }
}
