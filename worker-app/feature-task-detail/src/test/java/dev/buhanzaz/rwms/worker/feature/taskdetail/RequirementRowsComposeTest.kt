package dev.buhanzaz.rwms.worker.feature.taskdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.buhanzaz.rwms.worker.core.ui.RwmsWorkerTheme
import dev.buhanzaz.rwms.worker.core.network.WorkerWorkDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises row feedback and repeat-tap restoration with independent neighboring requirements. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
class RequirementRowsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun missingMaterialCanBeRestoredWithoutSelectingUnrelatedRow() {
        val availability = mutableStateOf("AVAILABLE")
        compose.setContent {
            MaterialTheme {
                Column {
                    RequirementCard("Вешалка", "1 шт", availability.value, true, {
                        availability.value = if (availability.value == "MISSING") "RESTORED" else "MISSING"
                    })
                    RequirementCard("Краска", "1 л", "AVAILABLE", true, {})
                }
            }
        }
        compose.onNodeWithContentDescription("Нет на складе: Вешалка").performClick()
        compose.onNodeWithText("Нет на складе").assertIsDisplayed()
        compose.onNodeWithContentDescription("Отменить отметку: Вешалка").assertIsSelected().performClick()
        compose.onNodeWithText("Есть на складе").assertIsDisplayed()
        compose.onNodeWithContentDescription("Нет на складе: Вешалка").assertIsEnabled()
        compose.onNodeWithContentDescription("Нет на складе: Краска").assertIsEnabled()
    }

    @Test
    fun restoredMaterialUsesTheOriginalCardSurfaceColor() {
        val availability = mutableStateOf("AVAILABLE")
        var renderedColor = Color.Unspecified
        compose.setContent {
            RwmsWorkerTheme {
                renderedColor = requirementCardColors(availability.value).containerColor
                RequirementCard("Вешалка", "1 шт", availability.value, true, {
                    availability.value = if (availability.value == "MISSING") "RESTORED" else "MISSING"
                })
            }
        }

        var initial = Color.Unspecified
        compose.runOnIdle { initial = renderedColor }
        compose.onNodeWithContentDescription("Нет на складе: Вешалка").performClick()
        compose.runOnIdle {
            check(renderedColor != initial) { "MISSING card must visibly change its surface color" }
        }
        compose.onNodeWithContentDescription("Отменить отметку: Вешалка").performClick()
        compose.runOnIdle {
            check(renderedColor == initial) { "RESTORED card must use the original surface color" }
        }
    }

    @Test
    fun workShowsItsOwnStatusAndAllowsRepeatTap() {
        val availability = mutableStateOf("MISSING")
        compose.setContent {
            MaterialTheme {
                WorkRow(
                    WorkerWorkDto("work", "Установка вешалки", 1.0, "шт", 10, null,
                        availabilityState = availability.value),
                    emptyList(), {}, true, { availability.value = "RESTORED" },
                )
            }
        }
        compose.onNodeWithText("Не выполнена").assertIsDisplayed()
        compose.onNodeWithContentDescription("Отменить отметку: Установка вешалки").performClick()
        compose.onNodeWithText("Можно выполнить").assertIsDisplayed()
        compose.onNodeWithContentDescription("Не выполнена: Установка вешалки").assertIsEnabled()
    }

    @Test
    fun pendingAndCompletedRowsCannotQueueAnotherChange() {
        compose.setContent {
            MaterialTheme {
                Column {
                    RequirementCard("Вешалка", "1 шт", "MISSING", false, { error("Pending") })
                    RequirementCard("Краска", "1 л", "COMPLETED", true, { error("Completed") })
                }
            }
        }
        compose.onNodeWithContentDescription("Отменить отметку: Вешалка").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Нет на складе: Краска").assertIsNotEnabled()
    }
}
