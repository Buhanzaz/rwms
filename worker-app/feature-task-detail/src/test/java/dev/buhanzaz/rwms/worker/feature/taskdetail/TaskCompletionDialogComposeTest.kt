package dev.buhanzaz.rwms.worker.feature.taskdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.buhanzaz.rwms.worker.core.ui.RwmsWorkerTheme
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the completion choices as a real Compose surface, including picker cancellation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
class TaskCompletionDialogComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `ordinary task exposes opaque photo choices and cancel dismisses`() {
        val cameraClicks = AtomicInteger()
        val galleryClicks = AtomicInteger()
        val dismisses = AtomicInteger()

        compose.setContent {
            RwmsWorkerTheme {
                TaskCompletionDialog(
                    isTransfer = false,
                    onDismissRequest = { dismisses.incrementAndGet() },
                    onCamera = { cameraClicks.incrementAndGet() },
                    onGallery = { galleryClicks.incrementAndGet() },
                )
            }
        }

        compose.onNodeWithTag("task-completion-dialog").assertIsDisplayed()
        compose.onNodeWithText("Завершить задание").assertIsDisplayed()
        compose.onNodeWithTag("task-completion-cancel").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("task-completion-camera").performClick()
        compose.onNodeWithTag("task-completion-gallery").performClick()
        compose.onNodeWithTag("task-completion-cancel").performClick()
        compose.waitForIdle()

        check(cameraClicks.get() == 1)
        check(galleryClicks.get() == 1)
        check(dismisses.get() == 1)
    }

    @Test
    fun `transfer task uses the unload wording`() {
        compose.setContent {
            RwmsWorkerTheme {
                TaskCompletionDialog(
                    isTransfer = true,
                    onDismissRequest = {},
                    onCamera = {},
                    onGallery = {},
                )
            }
        }

        compose.onNodeWithTag("task-completion-dialog").assertIsDisplayed()
        compose.onNodeWithText("Подтвердить выгрузку").assertIsDisplayed()
        compose.onNodeWithText("Сделать фото выгрузки").assertIsDisplayed()
    }
}
