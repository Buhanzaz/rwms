package dev.buhanzaz.rwms.driver.feature.shift

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.database.DriverShiftDraftEntity
import dev.buhanzaz.rwms.driver.core.network.DriverShiftClosingReportDto
import dev.buhanzaz.rwms.driver.core.network.DriverShiftDto
import dev.buhanzaz.rwms.driver.core.network.DriverShiftVehicleDto
import dev.buhanzaz.rwms.driver.core.network.DriverVehicleInspectionDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Executes the production next-action selector and the actual inspection completion button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class DriverShiftScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `each server action renders its production screen and unknown action exposes retry`() {
        val screens = linkedMapOf(
            "SHIFT_NOT_AVAILABLE" to "На текущую рабочую дату смена пока не назначена",
            "SHOW_DAILY_BRIEFING" to "Хорошей смены",
            "COMPLETE_MEDICAL_CHECK" to "Предрейсовый медосмотр",
            "COMPLETE_VEHICLE_INSPECTION" to "Осмотр автомобиля",
            "START_SHIFT" to "Всё готово",
            "SHOW_TASKS" to "Tasks enabled=true",
            "START_SHIFT_CLOSING" to "Сегодняшние ходки завершены",
            "CONFIRM_WAREHOUSE_RETURN" to "Вернулись на склад?",
            "COMPLETE_END_OF_SHIFT_REPORT" to "Итог смены",
            "CLOSE_SHIFT" to "Вернулись на склад",
            "SHIFT_CLOSED" to "Смена завершена",
        )
        val state = mutableStateOf(state(screens.keys.first()))
        var refreshes = 0
        compose.setContent {
            MaterialTheme {
                key(state.value.today?.nextRequiredAction) {
                    DriverShiftContent(state.value, "Driver", {}, { Text("Tasks enabled=$it") },
                        actions().copy(refresh = { refreshes += 1 }))
                }
            }
        }
        screens.forEach { (action, label) ->
            compose.runOnIdle { state.value = state(action) }
            compose.onAllNodesWithText(label).onFirst().assertIsDisplayed()
        }
        compose.runOnIdle { state.value = state("FUTURE_STEP") }
        compose.onNodeWithText("RWMS вернул неизвестный следующий шаг: FUTURE_STEP").assertIsDisplayed()
        compose.onNodeWithText("Обновить").performClick()
        compose.runOnIdle {
            assertThat(refreshes).isEqualTo(1)
            state.value = state("SHOW_TASKS").let { it.copy(today = it.today!!.copy(enabled = false)) }
        }
        compose.onNodeWithText("Tasks enabled=false").assertIsDisplayed()
    }

    @Test
    fun `incomplete or blocking inspection disables completion and complete inspection calls its callback`() {
        val state = mutableStateOf(state("COMPLETE_VEHICLE_INSPECTION", checked = 1))
        var completions = 0
        compose.setContent {
            MaterialTheme {
                DriverShiftContent(state.value, "Driver", {}, {},
                    actions().copy(completeInspection = { completions += 1 }))
            }
        }
        compose.onNodeWithText("1 из 2 проверено").assertIsDisplayed()
        completionButton().assertIsNotEnabled()
        compose.runOnIdle { state.value = state("COMPLETE_VEHICLE_INSPECTION", checked = 2, blocking = 1) }
        completionButton().assertIsNotEnabled()
        compose.onNodeWithText("Автомобиль не готов к смене").assertIsDisplayed()
        compose.runOnIdle { state.value = state("COMPLETE_VEHICLE_INSPECTION", checked = 2) }
        completionButton().assertIsEnabled().performClick()
        compose.runOnIdle { assertThat(completions).isEqualTo(1) }
        compose.runOnIdle { state.value = state.value.copy(submitting = true) }
        completionButton().assertIsNotEnabled()
    }

    private fun completionButton(): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Подтвердить осмотр"))
        return compose.onNodeWithText("Подтвердить осмотр")
    }

    private fun state(action: String, checked: Int = 1, blocking: Int = 0) = DriverShiftUiState(
        loading = false,
        today = TodayDriverShiftDto(
            enabled = true, serverTime = "2026-09-06T10:00:00Z", nextRequiredAction = action,
            shift = DriverShiftDto("shift", 7, "driver", "Driver", "warehouse", "2026-09-06", "Europe/Moscow", "ACTIVE"),
            vehicle = DriverShiftVehicleDto("vehicle", "Truck", "A001AA", configurationType = "TRUCK"),
            inspection = DriverVehicleInspectionDto(
                id = "inspection", version = 3, totalRequired = 2, checkedRequired = checked,
                blockingDefectCount = blocking, items = emptyList(),
            ),
            closingReport = DriverShiftClosingReportDto("NO_NEW_DEFECTS", 1020, 20, 50, photoCount = 2,
                completedAt = "2026-09-06T10:00:00Z"),
        ),
        draft = DriverShiftDraftEntity("draft", "driver", "shift", "SUMMARY", "NO_NEW_DEFECTS", "1020", 50,
            null, "", false, 1),
    )

    private fun actions() = DriverShiftActions(
        refresh = {}, loadTraffic = { _, _ -> }, attachTrafficMap = {}, detachTrafficMap = {},
        confirmBriefing = {}, clearError = {}, confirmMedicalCheck = {}, updateInspectionItem = { _, _, _, _ -> },
        completeInspection = {}, startShift = {}, startClosing = {}, confirmWarehouseReturn = {},
        ensureClosingDraft = {}, updateClosingDraft = {}, submitClosingReport = {}, closeShift = {},
    )
}
