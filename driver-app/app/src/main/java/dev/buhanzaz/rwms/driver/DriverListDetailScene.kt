package dev.buhanzaz.rwms.driver

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.contains
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND

/**
 * Stable Navigation 3 scene used for the board/task pair on medium and expanded windows.
 *
 * Task selection stays in the navigation back stack, while this scene owns the detail-pane
 * transition. Camera, profile and media routes deliberately remain full-screen destinations.
 */
data class DriverListDetailScene<T : Any>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    val listEntry: NavEntry<T>,
    val detailEntry: NavEntry<T>,
) : Scene<T> {
    override val entries: List<NavEntry<T>> = listOf(listEntry, detailEntry)

    override val content: @Composable (() -> Unit) = {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.4f)) { listEntry.Content() }
            CompositionLocalProvider(LocalTaskDetailBackButtonVisibility provides false) {
                Column(Modifier.weight(0.6f)) {
                    AnimatedContent(
                        targetState = detailEntry,
                        contentKey = { entry -> entry.contentKey },
                        transitionSpec = {
                            slideInHorizontally(initialOffsetX = { it }) togetherWith
                                slideOutHorizontally(targetOffsetX = { -it })
                        },
                    ) { entry -> entry.Content() }
                }
            }
        }
    }

    companion object {
        fun listPane() = metadata { put(ListKey, true) }

        fun detailPane() = metadata { put(DetailKey, true) }
    }

    /** Marks the board entry eligible for the adaptive list pane. */
    object ListKey : NavMetadataKey<Boolean>

    /** Marks a task entry eligible for the adaptive detail pane. */
    object DetailKey : NavMetadataKey<Boolean>
}

/** The task screen uses this to suppress its redundant Up affordance in a two-pane scene. */
val LocalTaskDetailBackButtonVisibility = compositionLocalOf { true }

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T : Any> rememberDriverListDetailSceneStrategy(): DriverListDetailSceneStrategy<T> {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return remember(windowSizeClass) { DriverListDetailSceneStrategy(windowSizeClass) }
}

/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
class DriverListDetailSceneStrategy<T : Any>(
    private val windowSizeClass: WindowSizeClass,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (!windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)) return null

        val detailEntry = entries.lastOrNull()
            ?.takeIf { it.metadata.contains(DriverListDetailScene.DetailKey) }
            ?: return null
        val listEntry = entries.findLast { it.metadata.contains(DriverListDetailScene.ListKey) }
            ?: return null

        return DriverListDetailScene(
            key = listEntry.contentKey,
            previousEntries = entries.dropLast(1),
            listEntry = listEntry,
            detailEntry = detailEntry,
        )
    }
}
