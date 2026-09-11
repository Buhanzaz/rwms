package dev.buhanzaz.rwms.worker.feature.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.ui.RwmsWorkerTheme
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h600dp")
class TaskQueueListComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `one translucent task card stays readable and bounded on a wide window`() {
        compose.setContent {
            RwmsWorkerTheme {
                Box(Modifier.size(width = 840.dp, height = 600.dp)) {
                    SingleTaskCard(
                        task = task("repair").copy(
                            categoryName = "Maintenance repair",
                            unitNumber = "БТ-314",
                            plannedDurationMinutes = 90,
                        ),
                        kpiPalette = null,
                        onOpen = {},
                        modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                    )
                }
            }
        }

        compose.onNodeWithTag("single-task-card-repair")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(620.dp)
        compose.onNodeWithText("БТ-314").assertIsDisplayed()
        compose.onNodeWithText("Работы").assertIsDisplayed()
        compose.onNodeWithText("Выделенное время: 1:30:00").assertIsDisplayed()
        compose.onNodeWithText("Открыть и взять").assertIsDisplayed()
        compose.onAllNodesWithText("Доска задач").assertCountEquals(0)
    }

    @Test
    fun `active task card exposes one continuation action`() {
        val opened = AtomicBoolean(false)
        compose.setContent {
            RwmsWorkerTheme {
                SingleTaskCard(
                    task = task("active").copy(
                        status = "IN_PROGRESS",
                        activeWorkSeconds = 3_661,
                        routeStepIndex = 1,
                        routeStepCount = 3,
                        readyEvidenceCount = 2,
                    ),
                    kpiPalette = null,
                    onOpen = { opened.set(true) },
                )
            }
        }

        compose.onNodeWithText("Время работы: 1:01:01").assertIsDisplayed()
        compose.onNodeWithText("Этап 2 из 3").assertIsDisplayed()
        compose.onNodeWithText("Фото: 2").assertIsDisplayed()
        compose.onNodeWithText("Продолжить выполнение").performClick()

        compose.runOnIdle { assertThat(opened.get()).isTrue() }
    }

    @Test
    fun `historical problem does not add a general problem banner`() {
        compose.setContent {
            RwmsWorkerTheme {
                SingleTaskCard(
                    task = task("reported").copy(hasProblem = true, incomplete = false),
                    kpiPalette = null,
                    onOpen = {},
                )
            }
        }

        compose.onNodeWithTag("task-problem-reported").assertDoesNotExist()
        compose.onNodeWithText("В задании отмечена проблема").assertDoesNotExist()
    }

    @Test
    fun `problem task card exposes server incomplete progress`() {
        compose.setContent {
            RwmsWorkerTheme {
                SingleTaskCard(
                    task = task("blocked").copy(
                        hasProblem = true,
                        incomplete = true,
                        completedWorkPercent = 80.0,
                    ),
                    kpiPalette = null,
                    onOpen = {},
                )
            }
        }

        compose.onNodeWithTag("task-problem-blocked").assertIsDisplayed()
        compose.onNodeWithText("Незавершено: 80.0% работ выполнено").assertIsDisplayed()
    }

    @Test
    fun `problem task card uses the configured worker palette color`() {
        assertThat(workerTaskProblemColor("#0A62C9")).isEqualTo(Color(0xFF0A62C9))
        assertThat(workerTaskProblemColor("not-a-color")).isEqualTo(Color(0xFFFF3B30))
    }

    @Test
    fun `slinger interruption overlays the current task with a take action`() {
        val taken = AtomicBoolean(false)
        compose.setContent {
            RwmsWorkerTheme {
                Box(Modifier.size(width = 840.dp, height = 600.dp)) {
                    SlingerTaskInterruptionDialog(
                        task = task("slinger").copy(
                            categoryName = "Стропальные работы",
                            status = "IN_PROGRESS",
                            availabilityMode = "REQUIRED_JOIN",
                            priority = 5,
                        ),
                        currentTaskVisible = true,
                        onTake = { taken.set(true) },
                    )
                }
            }
        }

        compose.onNodeWithTag("slinger-task-dialog").assertIsDisplayed()
        compose.onNodeWithText("Задание стропальщика").assertIsDisplayed()
        compose.onNodeWithText(
            "После принятия текущее задание будет приостановлено для всей бригады и автоматически продолжится после этой работы.",
        ).assertIsDisplayed()
        compose.onNodeWithTag("slinger-task-take").assertHeightIsEqualTo(56.dp).performClick()

        compose.runOnIdle { assertThat(taken.get()).isTrue() }
    }

    @Test
    fun `transfer task keeps its explicit badge without exposing technical task text`() {
        val technicalText = "Перемещение бытовки между складами. Бытовки: БТ-172"
        compose.setContent {
            RwmsWorkerTheme {
                SingleTaskCard(
                    task = task("transfer").copy(
                        title = "Отгрузить бытовку",
                        unitNumber = "БТ-172",
                        taskText = technicalText,
                    ),
                    kpiPalette = null,
                    onOpen = {},
                )
            }
        }

        compose.onNodeWithTag("single-task-transfer-transfer").assertIsDisplayed()
        compose.onNodeWithText("Межскладское перемещение").assertIsDisplayed()
        compose.onAllNodesWithText(technicalText).assertCountEquals(0)
    }

    private fun task(entryId: String) = WorkerTaskEntity(
        localId = "worker:$entryId",
        userId = "worker",
        entryId = entryId,
        taskId = "task-$entryId",
        version = 1,
        categoryId = "repair",
        categoryName = "Ремонты",
        categorySortOrder = 10,
        title = entryId,
        unitNumber = "БТ-1",
        taskText = null,
        scheduledDate = "2026-07-26",
        deadlineAt = null,
        priority = 3,
        queuePosition = 0,
        status = "WAITING",
        availabilityMode = "AVAILABLE",
        plannedDurationMinutes = null,
        activeStartedAt = null,
        activeWorkSeconds = 0,
        readyEvidenceCount = 0,
        resultPhotoMinCount = 1,
        lastServerRevision = 12,
        locallyPending = false,
        updatedAtEpochMillis = 1,
    )
}
