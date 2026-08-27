package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.buhanzaz.rwms.manager.network.EstimateLineDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairPlanDto
import dev.buhanzaz.rwms.manager.network.RepairStageDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.ui.AcceptanceInlineMediaState
import dev.buhanzaz.rwms.manager.ui.AcceptanceReviewMediaState
import dev.buhanzaz.rwms.manager.ui.MaintenanceAcceptanceEditorState
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.theme.ManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the ManagerApp acceptance media layout on an Android Compose host. */
@RunWith(AndroidJUnit4::class)
class MaintenanceAcceptanceScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inlineSlidersReplacePhotoButtonsAndOpenTheTappedGallery() {
        val editor = acceptanceEditor(includeReviewPhotos = true)
        render(editor)

        composeRule.onNodeWithText("Просмотр фото бытовки").assertDoesNotExist()
        composeRule.onAllNodesWithText("Фото до", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Фото после", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Фото сейчас недоступны.").assertDoesNotExist()
        composeRule.onNodeWithText("Часть фотографий сейчас недоступна.").assertDoesNotExist()
        composeRule.onNodeWithTag("acceptance-cabin-photo-pager").assertExists()
        composeRule.onNodeWithContentDescription("Фото бытовки 1").performClick()
        composeRule.onNodeWithText("Готово").assertExists().performClick()

        composeRule.onNodeWithTag("acceptance-details").performScrollToNode(
            hasTestTag("acceptance-work-photo-pager-work-with-photo"),
        )
        composeRule.onNodeWithTag("acceptance-work-photo-pager-work-with-photo").assertExists()
        composeRule.onNodeWithTag("acceptance-work-photo-pager-work-without-photo")
            .assertDoesNotExist()

        composeRule.onNodeWithTag("acceptance-details").performScrollToNode(
            hasTestTag("acceptance-stage-photo-pager-stage-1"),
        )
        composeRule.onNodeWithTag("acceptance-stage-photo-pager-stage-1").assertExists()
    }

    @Test
    fun workWithoutPhotosShowsOnlyItsDecisionControls() {
        render(acceptanceEditor(includeReviewPhotos = false))

        composeRule.onNodeWithTag("acceptance-details").performScrollToNode(hasText("Принято"))
        composeRule.onNodeWithText("Принято").assertExists()
        composeRule.onNodeWithText("Переделать").assertExists()
        composeRule.onNodeWithText("Фото к работе").assertDoesNotExist()
        composeRule.onNodeWithText("Фото этапа 1").assertDoesNotExist()
        composeRule.onNodeWithTag("acceptance-cabin-photo-pager").assertDoesNotExist()
        composeRule.onNodeWithTag("acceptance-work-photo-pager-work-with-photo")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("acceptance-stage-photo-pager-stage-1")
            .assertDoesNotExist()
        composeRule.onNodeWithText("Фото сейчас недоступны.").assertDoesNotExist()
        composeRule.onNodeWithText("Часть фотографий сейчас недоступна.").assertDoesNotExist()
    }

    private fun render(editor: MaintenanceAcceptanceEditorState) {
        composeRule.setContent {
            ManagerTheme(darkTheme = false) {
                MaintenanceAcceptanceScreen(
                    uiState = ManagerUiState(acceptanceEditor = editor),
                    onBack = {},
                    onLoad = {},
                    onOpen = {},
                    onCloseDetail = {},
                    onEditComment = {},
                    onOpenPhotos = {},
                    onAccept = {},
                    onAcceptWork = {},
                    onReworkWork = { _, _ -> },
                )
            }
        }
    }

    private fun acceptanceEditor(includeReviewPhotos: Boolean): MaintenanceAcceptanceEditorState {
        val first = workLine("work-with-photo")
        val second = workLine("work-without-photo")
        val stage = RepairStageDto(
            id = "stage-1",
            kind = "REPAIR_WORK",
            order = 0,
            state = "DONE",
            routing = RoutingSnapshotDto("queue-1", "Ремонт", "REPAIR"),
            workLines = if (includeReviewPhotos) listOf(first, second) else listOf(first),
        )
        val repair = RepairDto(
            id = "repair-1",
            rootRepairId = "repair-1",
            warehouseId = "warehouse-1",
            rentalItemId = "asset-1",
            origin = "DIRECT_REPAIR",
            kind = "PRIMARY",
            executionState = "COMPLETED",
            acceptanceState = "PENDING",
            version = 5,
            dispatchDate = "2026-08-25",
            plan = RepairPlanDto("repair-1", 5, listOf(stage)),
            createdAt = "2026-08-25T09:00:00Z",
            updatedAt = "2026-08-25T10:00:00Z",
        )
        val empty = AcceptanceInlineMediaState("Нет фото", emptyList())
        val cabin = if (includeReviewPhotos) {
            AcceptanceInlineMediaState("Фото бытовки", listOf("file:///cabin.webp"))
        } else {
            empty.copy(title = "Фото бытовки")
        }
        val workMedia = if (includeReviewPhotos) {
            mapOf(
                first.id to AcceptanceInlineMediaState(
                    title = "Фото к работе: ${first.description}",
                    photoUris = listOf("file:///work.webp"),
                ),
                second.id to empty.copy(title = "Фото к работе: ${second.description}"),
            )
        } else {
            mapOf(first.id to empty.copy(title = "Фото к работе: ${first.description}"))
        }
        val result = if (includeReviewPhotos) {
            AcceptanceInlineMediaState("Фото этапа 1", listOf("file:///result.webp"))
        } else {
            empty.copy(title = "Фото этапа 1")
        }
        return MaintenanceAcceptanceEditorState(
            repair = repair,
            asset = RentalItemDto(
                id = "asset-1",
                version = 1,
                warehouseId = "warehouse-1",
                number = "160780",
                status = "WAITING_REPAIR_CHECK",
            ),
            reviewMedia = AcceptanceReviewMediaState(
                cabin = cabin,
                workByStageId = mapOf(stage.id to workMedia),
                resultByStageId = mapOf(stage.id to result),
            ),
        )
    }

    private fun workLine(id: String) = EstimateLineDto(
        id = id,
        lineType = "WORK",
        description = "Работа $id",
        unit = "шт.",
        quantity = "1",
        unitPrice = "100.00",
        lineTotal = "100.00",
        normativeMinutes = 30,
        mediaReferences = listOf(MediaReferenceDto("source-$id", 1)),
    )
}
