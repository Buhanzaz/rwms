package dev.buhanzaz.rwms.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.buhanzaz.rwms.worker.feature.login.LoginScreen
import dev.buhanzaz.rwms.worker.feature.taskdetail.TaskDetailScreen
import dev.buhanzaz.rwms.worker.feature.tasks.TasksScreen
import java.util.UUID
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
) : NavKey

internal fun newCameraRoute(entryId: String, routeIndex: Int): CameraRoute =
    CameraRoute(entryId, routeIndex, UUID.randomUUID().toString())

@Serializable
private data object ProfileRoute : NavKey

@Serializable
private data class PhotoRoute(val title: String, val readPaths: List<String>) : NavKey

@Composable
private fun WorkerNavigation(userId: String, displayName: String, onLogout: () -> Unit) {
    val backStack = rememberNavBackStack(BoardRoute)
    val listDetailStrategy = rememberWorkerListDetailSceneStrategy<NavKey>()
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        sceneStrategies = listOf(listDetailStrategy),
        // Navigation 3 does not scope ViewModels to entries by default. Both
        // task detail and camera are Hilt ViewModels, so install its standard
        // state + ViewModel entry decorators. This makes every CameraRoute
        // independent and destroys a completed camera state after returning.
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<BoardRoute>(metadata = WorkerListDetailScene.listPane()) {
                val openTask: (String) -> Unit = dropUnlessResumedWithArgument { entryId ->
                    backStack.removeAll { it is TaskRoute }
                    backStack.add(TaskRoute(entryId))
                }
                TasksScreen(
                    userId = userId,
                    onTask = openTask,
                    onProfile = dropUnlessResumed { backStack.add(ProfileRoute) },
                )
            }
            entry<TaskRoute>(metadata = WorkerListDetailScene.detailPane()) { route ->
                val showBack = LocalTaskDetailBackButtonVisibility.current
                val openCamera: (Int) -> Unit = dropUnlessResumedWithArgument { routeIndex ->
                    backStack.add(newCameraRoute(route.entryId, routeIndex))
                }
                TaskDetailScreen(
                    userId = userId,
                    entryId = route.entryId,
                    onBack = if (showBack) ({ backStack.removeLastOrNull() }) else null,
                    onCamera = openCamera,
                    onMedia = { title, paths ->
                        backStack.add(PhotoRoute(title, paths))
                    },
                )
            }
            entry<CameraRoute> { route ->
                val closeCamera = dropUnlessResumed { backStack.removeLastOrNull() }
                CameraScreen(
                    userId = userId,
                    entryId = route.entryId,
                    routeIndex = route.routeIndex,
                    onBack = closeCamera,
                    onSaved = closeCamera,
                )
            }
            entry<ProfileRoute> {
                ProfileScreen(userId, displayName, onBack = { backStack.removeLastOrNull() }, onLogout = onLogout)
            }
            entry<PhotoRoute> { route ->
                PhotoPagerScreen(route.title, route.readPaths, onBack = { backStack.removeLastOrNull() })
            }
        },
    )
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
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { Text(message) }
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
