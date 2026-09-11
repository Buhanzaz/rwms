package dev.buhanzaz.rwms.worker.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkerComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `header keeps the client shell inset and exposes navigation controls`() {
        var menuClicks = 0
        var profileClicks = 0
        compose.setContent {
            RwmsWorkerTheme {
                WorkerScreenScaffold(
                    title = "Задание",
                    onMenu = { menuClicks++ },
                    profileMonogram = "А",
                    onProfile = { profileClicks++ },
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }

        val headerBounds = compose.onNodeWithTag("worker-header").fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertThat(headerBounds.left).isGreaterThan(0f)
        assertThat(headerBounds.right).isLessThan(rootBounds.right)
        compose.onNodeWithText("Задание").assertIsDisplayed()
        compose.onNodeWithTag("menu-button").performClick()
        compose.onNodeWithContentDescription("Профиль").performClick()

        compose.runOnIdle {
            assertThat(menuClicks).isEqualTo(1)
            assertThat(profileClicks).isEqualTo(1)
        }
    }

    @Test
    fun `lazy content keeps first and last rows inside shell safe areas while scrolling`() {
        compose.setContent {
            RwmsWorkerTheme {
                WorkerScreenScaffold(
                    title = "Задание",
                    bottomBar = {
                        Box(
                            Modifier.fillMaxWidth().height(72.dp).testTag("worker-footer"),
                        )
                    },
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("worker-scroll"),
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding() + 12.dp,
                            bottom = padding.calculateBottomPadding() + 28.dp,
                        ),
                    ) {
                        items((0 until 24).toList(), key = { it }) { index ->
                            Box(
                                Modifier.fillMaxWidth().height(48.dp)
                                    .testTag("worker-row-$index"),
                            )
                        }
                    }
                }
            }
        }

        val headerBounds = compose.onNodeWithTag("worker-header").fetchSemanticsNode().boundsInRoot
        val firstBounds = compose.onNodeWithTag("worker-row-0").fetchSemanticsNode().boundsInRoot
        assertThat(firstBounds.top).isAtLeast(headerBounds.bottom)

        compose.onNodeWithTag("worker-scroll")
            .performScrollToNode(hasTestTag("worker-row-23"))
        compose.waitForIdle()

        val footerBounds = compose.onNodeWithTag("worker-footer").fetchSemanticsNode().boundsInRoot
        val lastBounds = compose.onNodeWithTag("worker-row-23").fetchSemanticsNode().boundsInRoot
        assertThat(lastBounds.bottom).isAtMost(footerBounds.top)
        compose.onNodeWithTag("worker-footer").assertIsDisplayed()
    }
}
