package dev.buhanzaz.rwms.worker

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.buhanzaz.rwms.worker.core.ui.RwmsWorkerTheme
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import dev.buhanzaz.rwms.worker.feature.camera.CameraScreen
import dev.buhanzaz.rwms.worker.feature.camera.GalleryImportScreen
import dev.buhanzaz.rwms.worker.feature.login.LoginScreen
import dev.buhanzaz.rwms.worker.feature.taskdetail.TaskDetailScreen
import dev.buhanzaz.rwms.worker.feature.tasks.TasksScreen
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Composable
fun WorkerApp(
    appViewModel: WorkerAppViewModel = hiltViewModel(),
) {
    val state by appViewModel.state.collectAsStateWithLifecycle()
    RwmsWorkerTheme {
        when (val current = state) {
            WorkerAppUiState.Loading -> LoadingScreen("Проверяем безопасную сессию…")
            is WorkerAppUiState.SignedOut -> LoginScreen(
                failure = current.message,
                isSubmitting = current.isSubmitting,
                onLogin = appViewModel::login,
            )
            is WorkerAppUiState.Connecting -> ConnectingScreen(current.message, appViewModel::retry)
            is WorkerAppUiState.Ready -> WorkerNavigation(
                userId = current.userId,
                displayName = current.displayName,
                onLogout = appViewModel::logout,
            )
        }
    }
}

@Serializable
private data object BoardRoute : NavKey

@Serializable
private data class TaskRoute(val entryId: String) : NavKey

/**
 * A capture session is deliberately unique even when a worker reopens the
 * same task. Navigation 3 uses the key as the entry content key; reusing the
 * task-only key would otherwise allow a completed CameraViewModel to be
 * reused and immediately pop the newly opened camera route.
 */
@Serializable
internal data class CameraRoute(
    val entryId: String,
    val routeIndex: Int,
    val captureSessionId: String,
    val completeAfterSave: Boolean,
    val fromGallery: Boolean,
) : NavKey

internal fun newCameraRoute(
    entryId: String,
    routeIndex: Int,
    completeAfterSave: Boolean = true,
    fromGallery: Boolean = false,
): CameraRoute = CameraRoute(
    entryId = entryId,
    routeIndex = routeIndex,
    captureSessionId = UUID.randomUUID().toString(),
    completeAfterSave = completeAfterSave,
    fromGallery = fromGallery,
)

/** One-shot camera or gallery result delivered to the task entry that opened the capture flow. */
internal data class CapturedEvidenceResult(
    val entryId: String,
    val evidenceIds: List<String>,
    val completeAfterSave: Boolean,
)

/** Selects the final durable photo in a capture batch as the completion command's evidence. */
internal fun CapturedEvidenceResult.completionEvidenceId(): String? =
    evidenceIds.lastOrNull(String::isNotBlank)

@Serializable
private data object ProfileRoute : NavKey

@Serializable
private data object DownloadsRoute : NavKey

/** Opens one authenticated task-media collection at the thumbnail selected by the worker. */
@Serializable
internal data class PhotoRoute(
    val title: String,
    val previewPaths: List<String>,
    val readPaths: List<String>,
    val initialIndex: Int,
) : NavKey {
    init {
        require(previewPaths.size == readPaths.size) {
            "Photo preview and original collections must have equal size"
        }
    }
}

@Composable
private fun WorkerNavigation(userId: String, displayName: String, onLogout: () -> Unit) {
    val backStack = rememberNavBackStack(BoardRoute)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var capturedEvidenceResult by remember { mutableStateOf<CapturedEvidenceResult?>(null) }
    val profileMonogram = displayName.trim().firstOrNull()?.uppercase() ?: "А"

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    fun showTopLevel(route: NavKey) {
        backStack.removeAll { true }
        backStack.add(route)
        scope.launch { drawerState.close() }
    }

    fun openProfile() {
        backStack.removeAll { it is ProfileRoute }
        backStack.add(ProfileRoute)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = backStack.lastOrNull() !is CameraRoute && backStack.lastOrNull() !is PhotoRoute,
        drawerContent = {
            WorkerDrawerContent(
                current = backStack.firstOrNull(),
                onBoard = { showTopLevel(BoardRoute) },
                onDownloads = { showTopLevel(DownloadsRoute) },
            )
        },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            // Navigation 3 scopes saveable state and Hilt ViewModels to entries.
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
            entry<BoardRoute> {
                val openTask: (String) -> Unit = dropUnlessResumedWithArgument { entryId ->
                    backStack.removeAll { it is TaskRoute }
                    backStack.add(TaskRoute(entryId))
                }
                TasksScreen(
                    userId = userId,
                    onTask = openTask,
                    onMenu = ::openDrawer,
                    profileMonogram = profileMonogram,
                    onProfile = ::openProfile,
                )
            }
            entry<TaskRoute> { route ->
                val openCamera: (Pair<Int, Boolean>) -> Unit = dropUnlessResumedWithArgument { request ->
                    backStack.add(
                        newCameraRoute(
                            route.entryId,
                            request.first,
                            completeAfterSave = request.second,
                        ),
                    )
                }
                val openGallery: (Int) -> Unit = dropUnlessResumedWithArgument { routeIndex ->
                    backStack.add(
                        newCameraRoute(
                            route.entryId,
                            routeIndex,
                            completeAfterSave = true,
                            fromGallery = true,
                        ),
                    )
                }
                val taskViewModel = hiltViewModel<dev.buhanzaz.rwms.worker.feature.taskdetail.TaskDetailViewModel>()
                LaunchedEffect(capturedEvidenceResult, route.entryId) {
                    capturedEvidenceResult?.let { result ->
                        if (result.entryId == route.entryId) {
                            capturedEvidenceResult = null
                            if (result.completeAfterSave) {
                                result.completionEvidenceId()?.let(taskViewModel::completeAfterEvidence)
                            }
                        }
                    }
                }
                TaskDetailScreen(
                    userId = userId,
                    entryId = route.entryId,
                    onMenu = ::openDrawer,
                    profileMonogram = profileMonogram,
                    onProfile = ::openProfile,
                    onCamera = { routeIndex, completeAfterSave ->
                        openCamera(routeIndex to completeAfterSave)
                    },
                    onGallery = openGallery,
                    onMedia = { title, previewPaths, readPaths, initialIndex ->
                        backStack.add(PhotoRoute(title, previewPaths, readPaths, initialIndex))
                    },
                    onCompletionQueued = { showTopLevel(BoardRoute) },
                    viewModel = taskViewModel,
                )
            }
            entry<CameraRoute> { route ->
                val closeCamera = dropUnlessResumed { backStack.removeLastOrNull() }
                val onSaved: (List<String>) -> Unit = { evidenceIds ->
                    capturedEvidenceResult = CapturedEvidenceResult(
                        entryId = route.entryId,
                        evidenceIds = evidenceIds,
                        completeAfterSave = route.completeAfterSave,
                    )
                    closeCamera()
                }
                if (route.fromGallery) {
                    GalleryImportScreen(
                        userId = userId,
                        entryId = route.entryId,
                        routeIndex = route.routeIndex,
                        onBack = closeCamera,
                        onSaved = onSaved,
                        requestSyncAfterSave = !route.completeAfterSave,
                    )
                } else {
                    CameraScreen(
                        userId = userId,
                        entryId = route.entryId,
                        routeIndex = route.routeIndex,
                        onBack = closeCamera,
                        onSaved = onSaved,
                        requestSyncAfterSave = !route.completeAfterSave,
                    )
                }
            }
            entry<ProfileRoute> {
                ProfileScreen(userId, displayName, onBack = { backStack.removeLastOrNull() }, onLogout = onLogout)
            }
            entry<DownloadsRoute> {
                WorkerDownloadsScreen(
                    userId = userId,
                    onMenu = ::openDrawer,
                    profileMonogram = profileMonogram,
                    onProfile = ::openProfile,
                )
            }
            entry<PhotoRoute> { route ->
                PhotoPagerScreen(
                    title = route.title,
                    previewPaths = route.previewPaths,
                    readPaths = route.readPaths,
                    initialIndex = route.initialIndex,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            },
        )
    }
}

/** Contains only the two top-level destinations defined by the WorkerApp design. */
@Composable
private fun WorkerDrawerContent(
    current: NavKey?,
    onBoard: () -> Unit,
    onDownloads: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.widthIn(max = 320.dp)) {
        Image(
            painter = painterResource(dev.buhanzaz.rwms.worker.core.ui.R.drawable.rwms_blockbox_logo),
            contentDescription = "BlockBox",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Filled.ViewKanban, contentDescription = null) },
            label = { Text("Доска задач") },
            selected = current is BoardRoute,
            onClick = onBoard,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Filled.Download, contentDescription = null) },
            label = { Text("Загрузки") },
            selected = current is DownloadsRoute,
            onClick = onDownloads,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** Same lifecycle guard as dropUnlessResumed, for callbacks with a route argument. */
@Composable
private fun <T> dropUnlessResumedWithArgument(block: (T) -> Unit): (T) -> Unit {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentBlock by rememberUpdatedState(block)
    return remember(lifecycleOwner) {
        { value ->
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                currentBlock(value)
            }
        }
    }
}

@Composable
private fun LoadingScreen(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Image(
                painter = painterResource(dev.buhanzaz.rwms.worker.core.ui.R.drawable.rwms_blockbox_mark),
                contentDescription = "BlockBox",
                modifier = Modifier.size(96.dp),
            )
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ConnectingScreen(message: String, onRetry: () -> Unit) {
    WorkerScreenScaffold(title = "RWMS Рабочий") { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message)
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Повторить") }
        }
    }
}

@Composable
private fun ProfileScreen(
    userId: String,
    displayName: String,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    LaunchedEffect(userId) { viewModel.bind(userId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkerScreenScaffold(title = "Профиль", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(displayName)
            state.login?.let { Text("Логин: $it") }
            Text("Текущая группа: ${state.currentGroupName ?: "не выбрана руководителем"}")
            if (state.operationalAvailability == "DISABLED") {
                Text(
                    "Группа временно недоступна",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text("Группы")
            if (state.groups.isEmpty()) Text("Нет назначенных групп")
            state.groups.forEach { group ->
                Text(
                    buildString {
                        if (group.groupId == state.currentGroupId) append("✓ ")
                        append(group.name)
                        if (group.workerClassName.isNotBlank()) append(" · ${group.workerClassName}")
                    },
                )
            }
            Button(onClick = onLogout) { Text("Выйти") }
        }
    }
}
