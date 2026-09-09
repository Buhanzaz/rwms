package dev.buhanzaz.rwms.worker.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
}
