package dev.buhanzaz.rwms.worker

import android.graphics.Bitmap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
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
import dev.buhanzaz.rwms.worker.core.ui.WorkerButton
import dev.buhanzaz.rwms.worker.core.ui.WorkerGlassSurface
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import dev.buhanzaz.rwms.worker.core.ui.WorkerStoreLaunchGate
import dev.buhanzaz.rwms.worker.core.ui.WorkerStoreLogo
import dev.buhanzaz.rwms.worker.feature.camera.CameraScreen
import dev.buhanzaz.rwms.worker.feature.camera.GalleryImportScreen
import dev.buhanzaz.rwms.worker.feature.login.LoginScreen
import dev.buhanzaz.rwms.worker.feature.taskdetail.TaskDetailScreen
import dev.buhanzaz.rwms.worker.feature.taskdetail.TaskDetailViewModel
import dev.buhanzaz.rwms.worker.feature.tasks.SlingerTaskInterruptionDialog
import dev.buhanzaz.rwms.worker.feature.tasks.TasksViewModel
import dev.buhanzaz.rwms.worker.feature.tasks.selectCurrentWorkerTask
import dev.buhanzaz.rwms.worker.feature.tasks.selectIncomingSlingerTask
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Composable
fun WorkerApp(
    appViewModel: WorkerAppViewModel = hiltViewModel(),
) {
    val state by appViewModel.state.collectAsStateWithLifecycle()
    RwmsWorkerTheme {
        WorkerStoreLaunchGate {
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
}

@Serializable
private data object BoardRoute : NavKey

@Serializable
private data class TaskRoute(
    val entryId: String,
    val takeSlingerOnOpen: Boolean = false,
) : NavKey

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

@Serializable
private data object ProfileRoute : NavKey

@Serializable
private data class AvatarEditorRoute(val sourceUri: String) : NavKey

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
    val tasksViewModel = hiltViewModel<TasksViewModel>()
    val profileViewModel = hiltViewModel<ProfileViewModel>()
    LaunchedEffect(userId) { tasksViewModel.bind(userId) }
    LaunchedEffect(userId) { profileViewModel.bind(userId) }
    val tasksState by tasksViewModel.uiState.collectAsStateWithLifecycle()
    val profileState by profileViewModel.state.collectAsStateWithLifecycle()
    val profileMonogram = displayName.trim().firstOrNull()?.uppercase() ?: "А"
    val currentTask = selectCurrentWorkerTask(
        userId = userId,
        currentGroupId = tasksState.session?.currentGroupId,
        categories = tasksState.categories,
        tasks = tasksState.tasks,
        assignments = tasksState.assignments,
    )
    val incomingSlingerTask = selectIncomingSlingerTask(
        userId = userId,
        currentGroupId = tasksState.session?.currentGroupId,
        operationalAvailability = tasksState.session?.operationalAvailability ?: "DISABLED",
        categories = tasksState.categories,
        tasks = tasksState.tasks,
        assignments = tasksState.assignments,
    )

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    fun showTasks() {
        while (backStack.size > 1) backStack.removeLastOrNull()
        scope.launch { drawerState.close() }
    }

    fun showDownloads() {
        while (backStack.size > 1) backStack.removeLastOrNull()
        if (backStack.lastOrNull() !is DownloadsRoute) backStack.add(DownloadsRoute)
        scope.launch { drawerState.close() }
    }

    fun openProfile() {
        if (backStack.lastOrNull() !is ProfileRoute) backStack.add(ProfileRoute)
    }

    fun takeSlingerTask(entryId: String) {
        if ((backStack.lastOrNull() as? TaskRoute)?.entryId == entryId) return
        backStack.add(TaskRoute(entryId = entryId, takeSlingerOnOpen = true))
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = backStack.lastOrNull() !is CameraRoute &&
            backStack.lastOrNull() !is PhotoRoute &&
            backStack.lastOrNull() !is AvatarEditorRoute,
        drawerContent = {
            WorkerDrawerContent(
                current = backStack.lastOrNull(),
                onTasks = ::showTasks,
                onDownloads = ::showDownloads,
            )
        },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = {
                val route = backStack.lastOrNull()
                if (route !is TaskRoute || !route.takeSlingerOnOpen) {
                    backStack.removeLastOrNull()
                }
            },
            transitionSpec = {
                (
                    slideInHorizontally(
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                        initialOffsetX = { width -> width / 4 },
                    ) + fadeIn(tween(220))
                ) togetherWith
                    (
                        slideOutHorizontally(
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                            targetOffsetX = { width -> -width / 10 },
                        ) + fadeOut(tween(180))
                    )
            },
            popTransitionSpec = {
                (
                    slideInHorizontally(
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                        initialOffsetX = { width -> -width / 10 },
                    ) + fadeIn(tween(210))
                ) togetherWith
                    (
                        slideOutHorizontally(
                            animationSpec = tween(280, easing = FastOutSlowInEasing),
                            targetOffsetX = { width -> width / 4 },
                        ) + fadeOut(tween(180))
                    )
            },
            // Navigation 3 scopes saveable state and Hilt ViewModels to entries.
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
            entry<BoardRoute> {
                val task = currentTask
                if (task == null) {
                    NoCurrentTaskScreen(
                        online = tasksState.online,
                        syncing = tasksState.progress?.stage != null &&
                            tasksState.progress?.stage != "IDLE",
                        onMenu = ::openDrawer,
                        profileMonogram = profileMonogram,
                        profileAvatar = profileState.avatar,
                        onProfile = ::openProfile,
                        onRefresh = tasksViewModel::syncNow,
                    )
                } else {
                    val openCamera: (Pair<Int, Boolean>) -> Unit =
                        dropUnlessResumedWithArgument { request ->
                            backStack.add(
                                newCameraRoute(
                                    task.entryId,
                                    request.first,
                                    completeAfterSave = request.second,
                                ),
                            )
                        }
                    val openGallery: (Int) -> Unit = dropUnlessResumedWithArgument { routeIndex ->
                        backStack.add(
                            newCameraRoute(
                                task.entryId,
                                routeIndex,
                                completeAfterSave = true,
                                fromGallery = true,
                            ),
                        )
                    }
                    WorkerTaskContent(
                        userId = userId,
                        entryId = task.entryId,
                        onMenu = ::openDrawer,
                        profileMonogram = profileMonogram,
                        profileAvatar = profileState.avatar,
                        onProfile = ::openProfile,
                        onCamera = { routeIndex, completeAfterSave ->
                            openCamera(routeIndex to completeAfterSave)
                        },
                        onGallery = openGallery,
                        onMedia = { title, previewPaths, readPaths, initialIndex ->
                            backStack.add(PhotoRoute(title, previewPaths, readPaths, initialIndex))
                        },
                        onCompletionQueued = tasksViewModel::syncNow,
                        autoTakeOnOpen = true,
                    )
                }
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
                WorkerTaskContent(
                    userId = userId,
                    entryId = route.entryId,
                    onMenu = null,
                    profileMonogram = profileMonogram,
                    profileAvatar = profileState.avatar,
                    onProfile = ::openProfile,
                    onCamera = { routeIndex, completeAfterSave ->
                        openCamera(routeIndex to completeAfterSave)
                    },
                    onGallery = openGallery,
                    onMedia = { title, previewPaths, readPaths, initialIndex ->
                        backStack.add(PhotoRoute(title, previewPaths, readPaths, initialIndex))
                    },
                    onCompletionQueued = {
                        backStack.removeLastOrNull()
                        tasksViewModel.syncNow()
                    },
                    takeSlingerOnOpen = route.takeSlingerOnOpen,
                )
            }
            entry<CameraRoute> { route ->
                val closeCamera = dropUnlessResumed { backStack.removeLastOrNull() }
                val onSaved: (List<String>) -> Unit = { closeCamera() }
                if (route.fromGallery) {
                    GalleryImportScreen(
                        userId = userId,
                        entryId = route.entryId,
                        routeIndex = route.routeIndex,
                        onBack = closeCamera,
                        onSaved = onSaved,
                        requestSyncAfterSave = !route.completeAfterSave,
                        completeAfterSave = route.completeAfterSave,
                    )
                } else {
                    CameraScreen(
                        userId = userId,
                        entryId = route.entryId,
                        routeIndex = route.routeIndex,
                        onBack = closeCamera,
                        onSaved = onSaved,
                        requestSyncAfterSave = !route.completeAfterSave,
                        completeAfterSave = route.completeAfterSave,
                    )
                }
            }
            entry<ProfileRoute> {
                ProfileScreen(
                    fallbackDisplayName = displayName,
                    state = profileState,
                    onBack = { backStack.removeLastOrNull() },
                    onAvatarSelected = { sourceUri -> backStack.add(AvatarEditorRoute(sourceUri)) },
                    onRefresh = profileViewModel::refresh,
                    onDismissError = profileViewModel::dismissAvatarError,
                    onLogout = onLogout,
                )
            }
            entry<AvatarEditorRoute> { route ->
                val closeEditor = dropUnlessResumed { backStack.removeLastOrNull() }
                AvatarEditorScreen(
                    initialUri = route.sourceUri,
                    isSaving = profileState.isAvatarUploading,
                    error = profileState.avatarError,
                    onBack = closeEditor,
                    onDismissError = profileViewModel::dismissAvatarError,
                    onSave = { bitmap -> profileViewModel.uploadAvatar(bitmap, closeEditor) },
                )
            }
            entry<DownloadsRoute> {
                WorkerDownloadsScreen(
                    userId = userId,
                    onMenu = ::openDrawer,
                    profileMonogram = profileMonogram,
                    profileAvatar = profileState.avatar,
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

    incomingSlingerTask
        ?.takeUnless { task ->
            (backStack.lastOrNull() as? TaskRoute)?.entryId == task.entryId
        }
        ?.let { task ->
            SlingerTaskInterruptionDialog(
                task = task,
                currentTaskVisible = currentTask != null && currentTask.entryId != task.entryId,
                onTake = { takeSlingerTask(task.entryId) },
            )
        }
}

/** Hosts the single ordinary task or the temporary slinger interruption in one stable route. */
@Composable
private fun WorkerTaskContent(
    userId: String,
    entryId: String,
    onMenu: (() -> Unit)?,
    profileMonogram: String,
    profileAvatar: Bitmap?,
    onProfile: () -> Unit,
    onCamera: (routeIndex: Int, completeAfterSave: Boolean) -> Unit,
    onGallery: (routeIndex: Int) -> Unit,
    onMedia: (
        title: String,
        previewPaths: List<String>,
        readPaths: List<String>,
        initialIndex: Int,
    ) -> Unit,
    onCompletionQueued: () -> Unit,
    takeSlingerOnOpen: Boolean = false,
    autoTakeOnOpen: Boolean = false,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    TaskDetailScreen(
        userId = userId,
        entryId = entryId,
        onMenu = onMenu,
        profileMonogram = profileMonogram,
        profileAvatar = profileAvatar,
        onProfile = onProfile,
        onCamera = onCamera,
        onGallery = onGallery,
        onMedia = onMedia,
        onCompletionQueued = onCompletionQueued,
        takeSlingerOnOpen = takeSlingerOnOpen,
        autoTakeOnOpen = autoTakeOnOpen,
        viewModel = viewModel,
    )
}

/** Keeps the app on the one-task surface while the next assignment is being synchronized. */
@Composable
private fun NoCurrentTaskScreen(
    online: Boolean,
    syncing: Boolean,
    onMenu: () -> Unit,
    profileMonogram: String,
    profileAvatar: Bitmap?,
    onProfile: () -> Unit,
    onRefresh: () -> Unit,
) {
    WorkerScreenScaffold(
        title = "Задание",
        onMenu = onMenu,
        profileMonogram = profileMonogram,
        profileAvatar = profileAvatar,
        onProfile = onProfile,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = WorkerGlassSurface,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (syncing) CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(
                        if (syncing) "Получаем задание…" else "Сейчас активного задания нет",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (online) {
                            "Новое задание появится здесь автоматически."
                        } else {
                            "Нет связи с RWMS. Проверьте сеть и обновите данные."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WorkerButton(onClick = onRefresh, enabled = !syncing) {
                        Text("Обновить")
                    }
                }
            }
        }
    }
}

/** Contains only the two top-level destinations defined by the WorkerApp design. */
@Composable
private fun WorkerDrawerContent(
    current: NavKey?,
    onTasks: () -> Unit,
    onDownloads: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 320.dp),
        drawerContainerColor = WorkerGlassSurface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        WorkerStoreLogo(
            modifier = Modifier.padding(vertical = 24.dp),
            horizontalPadding = 54.dp,
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = null) },
            label = { Text("Моё задание") },
            selected = current is BoardRoute,
            onClick = onTasks,
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
            WorkerButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("Повторить")
            }
        }
    }
}
