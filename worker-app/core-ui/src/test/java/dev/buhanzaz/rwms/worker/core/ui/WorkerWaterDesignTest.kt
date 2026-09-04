package dev.buhanzaz.rwms.worker.core.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkerWaterDesignTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `logo greeting remains for three seconds before worker content`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalWorkerStoreVideoBackgroundEnabled provides false) {
                RwmsWorkerTheme {
                    WorkerStoreLaunchGate { Text("Рабочее приложение") }
                }
            }
        }

        compose.onNodeWithTag("worker-hello-screen").assertExists()
        compose.onNodeWithContentDescription("BlockBox").assertIsDisplayed()
        compose.onNodeWithText("Рабочее приложение").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(WorkerStoreGreetingDurationMillis - 200)
        compose.onNodeWithTag("worker-hello-screen").assertExists()
        compose.mainClock.advanceTimeBy(200)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Рабочее приложение").assertExists()
    }
}
