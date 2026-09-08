package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorMode
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState
import dev.buhanzaz.rwms.manager.ui.MaintenanceLineEditorState
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.theme.ManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the catalog photo drawer on the existing Robolectric Compose host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1280dp-xxhdpi")
class MaintenanceCatalogPhotoDrawerInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val editor = mutableStateOf(catalogEditor(photoUris = listOf(
        "file:///catalog-condition-first.jpg",
        "file:///catalog-condition-second.jpg",
    )))

    @Test
    fun `drawer snaps around half threshold moves selector opens gallery and falls back empty`() {
        render()

        compose.onNodeWithTag("maintenance-catalog-photo-drawer-handle").assertExists()
        compose.onNodeWithContentDescription("Потяните вниз, чтобы открыть фотографии").assertExists()
        compose.onNodeWithTag("maintenance-catalog-photo-pager").assertDoesNotExist()
        compose.onNodeWithText("Существующая работа").assertExists()

        compose.onNodeWithTag("maintenance-catalog-photo-drawer-handle").performTouchInput {
            click()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("maintenance-catalog-photo-pager").assertExists()
        compose.onNodeWithText("Существующая работа").assertDoesNotExist()

        compose.onNodeWithTag("maintenance-catalog-photo-drawer-handle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("maintenance-catalog-photo-pager").assertDoesNotExist()
        compose.onNodeWithText("Существующая работа").assertExists()
        val revealTravelPx = collapsedDrawerRevealTravelPx()

        dragHandleBy(revealTravelPx * 0.2f)
        compose.onNodeWithTag("maintenance-catalog-photo-pager").assertDoesNotExist()

        dragHandleBy(revealTravelPx * 0.8f)
        compose.onNodeWithTag("maintenance-catalog-photo-pager").assertExists()
        compose.onNodeWithContentDescription("Потяните вверх, чтобы скрыть фотографии").assertExists()
        compose.onNodeWithText("Существующая работа").assertDoesNotExist()
        assertSelectorIsImmediatelyAboveAddLine()

        compose.onNodeWithContentDescription("Фото состояния 1").performClick()
        compose.onNodeWithContentDescription("Фото состояния · 1 из 2").assertExists()
        compose.onNodeWithText("Готово").assertExists().performClick()
        compose.waitForIdle()

        dragHandleBy(-revealTravelPx * 0.2f)
        compose.onNodeWithTag("maintenance-catalog-photo-pager").assertExists()

        dragHandleBy(-revealTravelPx * 0.8f)
        compose.onNodeWithTag("maintenance-catalog-photo-pager").assertDoesNotExist()
        compose.onNodeWithText("Существующая работа").assertExists()

        compose.onNodeWithTag("maintenance-catalog-photo-drawer-handle").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Существующая работа").assertDoesNotExist()
        compose.runOnIdle { editor.value = catalogEditor(photoUris = emptyList()) }
        compose.onNodeWithTag("maintenance-catalog-photo-drawer-handle").assertDoesNotExist()
        compose.onNodeWithTag("maintenance-catalog-photo-pager").assertDoesNotExist()
        compose.onNodeWithText("Существующая работа").assertExists()
        compose.onNodeWithText("Состав").assertExists()
    }

    private fun collapsedDrawerRevealTravelPx(): Float {
        val footerBottom = compose.onNodeWithTag("maintenance-catalog-photo-drawer-footer")
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
        val addLineTop = compose.onNodeWithText("Добавить строку")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val travelPx = addLineTop - footerBottom
        assertThat(travelPx).isGreaterThan(200f)
        return travelPx
    }

    private fun assertSelectorIsImmediatelyAboveAddLine() {
        val footerBottom = compose.onNodeWithTag("maintenance-catalog-photo-drawer-footer")
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
        val addLineTop = compose.onNodeWithText("Добавить строку")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertThat(addLineTop - footerBottom).isGreaterThan(0f)
        assertThat(addLineTop - footerBottom).isLessThan(48f)
    }

    private fun dragHandleBy(deltaY: Float) {
        compose.onNodeWithTag("maintenance-catalog-photo-drawer-handle").performTouchInput {
            down(Offset(centerX, centerY))
            repeat(8) {
                moveBy(Offset(0f, deltaY / 8f))
            }
            up()
        }
        compose.waitForIdle()
    }

    private fun render() {
        compose.setContent {
            ManagerTheme(darkTheme = false) {
                MaintenanceCatalogStep(
                    editor = editor.value,
                    uiState = ManagerUiState(),
                    onAddCatalogNodes = { _, _, _, _, _, _ -> true },
                    onRefreshCatalog = {},
                    onEdit = { update -> editor.value = update(editor.value) },
                    onContinue = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun catalogEditor(photoUris: List<String>) = MaintenanceEditorState(
        mode = MaintenanceEditorMode.ESTIMATE,
        entityId = "estimate-1",
        expectedVersion = 1,
        readOnly = false,
        selectedAsset = null,
        dispatchDate = "2026-09-08",
        sourceParty = "",
        lines = listOf(
            MaintenanceLineEditorState(
                id = "existing-work",
                catalogNodeId = "work-1",
                description = "Существующая работа",
                lineType = "WORK",
                unit = "шт.",
                quantity = "1",
                unitPrice = "0.00",
                normativeMinutes = 30,
                comment = "",
            ),
        ),
        photoUris = photoUris,
        readyMedia = emptyList(),
        priority = 3,
        step = 3,
    )
}
