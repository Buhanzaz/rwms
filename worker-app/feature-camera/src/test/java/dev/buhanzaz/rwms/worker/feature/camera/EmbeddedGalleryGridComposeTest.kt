package dev.buhanzaz.rwms.worker.feature.camera

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.ui.RwmsWorkerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalMaterial3Api::class)
class EmbeddedGalleryGridComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `dismissing gallery sheet leaves the source dialog mounted`() {
        var sheetVisible by mutableStateOf(true)
        compose.setContent {
            RwmsWorkerTheme {
                androidx.compose.foundation.layout.Box {
                    androidx.compose.material3.Text("Завершить задание")
                    if (sheetVisible) {
                        GalleryBottomSheet(saving = false, onDismiss = { sheetVisible = false }) {
                            GallerySheetGrid(
                                photos = listOf(Uri.parse("content://media/external/images/media/1")),
                                selectionLimit = 10,
                                onSelected = { error("No photo was selected") },
                                onDismiss = { sheetVisible = false },
                                maxHeight = 500.dp,
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Отмена").performClick()

        compose.onNodeWithText("Завершить задание").assertIsDisplayed()
    }

    @Test
    fun `selected embedded gallery photos are submitted immediately`() {
        val first = Uri.parse("content://media/external/images/media/1")
        val second = Uri.parse("content://media/external/images/media/2")
        var selected: List<Uri>? = null
        compose.setContent {
            RwmsWorkerTheme {
                GallerySheetGrid(
                    photos = listOf(first, second),
                    selectionLimit = 2,
                    onSelected = { selected = it },
                    onDismiss = {},
                    maxHeight = 500.dp,
                )
            }
        }

        compose.onAllNodesWithContentDescription("Фото из галереи")[0].performClick()
        compose.onNodeWithText("Добавить (1)").assertIsDisplayed().performClick()

        compose.runOnIdle { assertThat(requireNotNull(selected)).containsExactly(first) }
    }

    @Test
    fun `selection cannot exceed the allowed photo count`() {
        val first = Uri.parse("content://media/external/images/media/1")
        val second = Uri.parse("content://media/external/images/media/2")
        var selected: List<Uri>? = null
        compose.setContent {
            RwmsWorkerTheme {
                GallerySheetGrid(
                    photos = listOf(first, second),
                    selectionLimit = 1,
                    onSelected = { selected = it },
                    onDismiss = {},
                    maxHeight = 500.dp,
                )
            }
        }

        compose.onAllNodesWithContentDescription("Фото из галереи")[0].performClick()
        compose.onAllNodesWithContentDescription("Фото из галереи")[1].performClick()
        compose.onNodeWithText("Добавить (1)").performClick()

        compose.runOnIdle { assertThat(requireNotNull(selected)).containsExactly(first) }
    }

    @Test
    fun `production sheet blocks swipe dismissal while saving`() {
        var saving by mutableStateOf(false)
        var dismissed = 0
        compose.setContent {
            RwmsWorkerTheme {
                GalleryBottomSheet(saving = saving, onDismiss = { dismissed++ }) {
                    androidx.compose.material3.Text(
                        "Добавляем фотографии…",
                        modifier = Modifier.fillMaxWidth().height(400.dp),
                    )
                }
            }
        }

        compose.runOnIdle { saving = true }
        compose.onNodeWithText("Добавляем фотографии…").performTouchInput { swipeDown() }
        compose.onNodeWithText("Добавляем фотографии…").assertIsDisplayed()
        compose.runOnIdle { assertThat(dismissed).isEqualTo(0) }
        compose.runOnIdle { saving = false }
        compose.onNodeWithText("Добавляем фотографии…").performTouchInput { swipeDown() }
        compose.runOnIdle { assertThat(dismissed).isEqualTo(1) }
    }
}
