package dev.buhanzaz.rwms.worker.feature.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
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
    fun everyAuthorizedQueueIsReachableInOneVerticalScroll() {
        val sections = (1..12).map { index ->
            TaskQueueSection(
                queueId = "queue-$index",
                name = "Очередь $index",
                queuePurpose = "GENERAL",
                sortOrder = index,
                tasks = emptyList(),
            )
        }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 320.dp, height = 400.dp)) {
                    TaskQueueList(
                        sections = sections,
                        onTask = {},
                        modifier = Modifier.testTag("queue-list"),
                    )
                }
            }
        }

        compose.onNodeWithText("Очередь 1").assertIsDisplayed()
        compose.onAllNodesWithText("В этой очереди пока нет заданий").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("queue-list").performScrollToNode(hasText("Очередь 12"))
        compose.onNodeWithText("Очередь 12").assertIsDisplayed()
    }

    @Test
    fun twoGroupColumnsAreSideBySideOnlyWhenEnoughWidthIsAvailable() {
        val columns = listOf(
            WorkBoardColumn("a", "Группа А", personal = false, sections = emptyList()),
            WorkBoardColumn("b", "Группа Б", personal = false, sections = emptyList()),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 800.dp, height = 400.dp)) {
                    WorkBoardColumns(columns, kpiPalette = null, onTask = {})
                }
            }
        }

        compose.onAllNodesWithTag("work-board-wide").assertCountEquals(1)
        compose.onAllNodesWithTag("work-board-narrow").assertCountEquals(0)
        compose.onAllNodesWithTag("work-column-a").assertCountEquals(1)
        compose.onAllNodesWithTag("work-column-b").assertCountEquals(1)
    }

    @Test
    fun narrowBoardPlacesGroupColumnsOneAfterAnotherInOneVerticalList() {
        val columns = listOf(
            WorkBoardColumn("a", "Группа А", personal = false, sections = emptyList()),
            WorkBoardColumn("b", "Группа Б", personal = false, sections = emptyList()),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 400.dp)) {
                    WorkBoardColumns(columns, kpiPalette = null, onTask = {})
                }
            }
        }

        compose.onNodeWithTag("work-board-narrow").assertIsDisplayed()
        compose.onAllNodesWithTag("work-board-wide").assertCountEquals(0)
        compose.onNodeWithText("Группа А").assertIsDisplayed()
        compose.onNodeWithText("Группа Б").assertIsDisplayed()
    }

    @Test
    fun roleAndQueuePanelsCollapseIndependently() {
        val section = TaskQueueSection(
            queueId = "repair",
            name = "Ремонты",
            queuePurpose = "GENERAL",
            sortOrder = 10,
            tasks = listOf(task("repair-task")),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 500.dp)) {
                    WorkBoardColumns(
                        columns = listOf(
                            WorkBoardColumn(
                                id = "repair-role",
                                name = "Ремонтники",
                                personal = false,
                                sections = listOf(section),
                            ),
                        ),
                        kpiPalette = null,
                        onTask = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("task-card-repair-role-repair-repair-task").assertIsDisplayed()
        compose.onNodeWithTag("queue-section-toggle-repair-role-repair").performClick()
        compose.onAllNodesWithTag("task-card-repair-role-repair-repair-task").assertCountEquals(0)
        compose.onNodeWithTag("queue-section-toggle-repair-role-repair").performClick()
        compose.onNodeWithTag("task-card-repair-role-repair-repair-task").assertIsDisplayed()
        compose.onNodeWithTag("work-column-toggle-repair-role").performClick()
        compose.onAllNodesWithTag("queue-section-repair-role-repair").assertCountEquals(0)
    }

    @Test
    fun groupHeaderShowsOnlyNameAndChevronWithoutTaskTotal() {
        val section = TaskQueueSection(
            queueId = "repair",
            name = "Ремонты",
            queuePurpose = "GENERAL",
            sortOrder = 10,
            tasks = (1..8).map { index -> task("repair-$index") },
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 500.dp)) {
                    WorkBoardColumns(
                        columns = listOf(
                            WorkBoardColumn(
                                id = "general-workers",
                                name = "Бригада разнорабочих 1",
                                personal = false,
                                sections = listOf(section),
                            ),
                        ),
                        kpiPalette = null,
                        onTask = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Бригада разнорабочих 1").assertIsDisplayed()
        compose.onAllNodesWithText("8").assertCountEquals(0)
        compose.onNodeWithTag("work-column-chevron-general-workers", useUnmergedTree = true)
            .assertWidthIsEqualTo(36.dp)
            .assertHeightIsEqualTo(36.dp)
        compose.onAllNodesWithText("Групповая роль").assertCountEquals(0)
    }

    @Test
    fun activeTaskOmitsDateAndShowsElapsedTimeWithKpiThenOpensFullScreenTask() {
        var openedEntryId: String? = null
        val section = TaskQueueSection(
            queueId = "repair",
            name = "Ремонты",
            queuePurpose = "GENERAL",
            sortOrder = 10,
            tasks = listOf(
                task("timed-task").copy(
                    status = "IN_PROGRESS",
                    activeWorkSeconds = 65,
                    timerCountedActiveSeconds = 65,
                    timerRemainingSeconds = 65,
                    timerRemainingPercent = 50.0,
                    timerState = "WORKING",
                    timerServerTime = "2026-08-10T10:00:00Z",
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 500.dp)) {
                    TaskQueueList(
                        sections = listOf(section),
                        onTask = { openedEntryId = it },
                    )
                }
            }
        }

        compose.onAllNodesWithText("Дата: 2026-08-10").assertCountEquals(0)
        compose.onNodeWithText("Время работы: 0:01:05").assertIsDisplayed()
        compose.onNodeWithText("KPI: 50.0%").assertIsDisplayed()
        compose.onNodeWithText("Открыть задание").assertIsDisplayed().performClick()
        assertThat(openedEntryId).isEqualTo("timed-task")
    }

    @Test
    fun waitingTaskShowsAllocatedTimeAndBareDifficultyOnce() {
        val section = TaskQueueSection(
            queueId = "repair",
            name = "Ремонты",
            queuePurpose = "GENERAL",
            sortOrder = 10,
            tasks = listOf(
                task("waiting").copy(
                    title = "Лёгкий ремонт",
                    plannedDurationMinutes = 75,
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 500.dp)) {
                    TaskQueueList(sections = listOf(section), onTask = {})
                }
            }
        }

        compose.onNodeWithText("Выделенное время: 1:15:00").assertIsDisplayed()
        compose.onAllNodesWithText("Легкий ремонт").assertCountEquals(1)
        compose.onAllNodesWithText("Время работы: 0:00:00").assertCountEquals(0)
    }

    @Test
    fun expandedRepairTaskShowsStagePriorityAndComplexity() {
        val section = TaskQueueSection(
            queueId = "repair",
            name = "Ремонты",
            queuePurpose = "GENERAL",
            sortOrder = 10,
            tasks = listOf(
                task("metadata").copy(
                    categoryName = "Внутренние работы",
                    status = "PAUSED",
                    priority = 4,
                    title = "Средний ремонт",
                    routeIndex = 1,
                    routeStepCount = 3,
                    timerState = "PAUSED",
                    timerServerTime = "2026-08-10T10:00:00Z",
                    timerCountedActiveSeconds = 65,
                    timerRemainingSeconds = 120,
                    timerRemainingPercent = 75.0,
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 500.dp)) {
                    TaskQueueList(sections = listOf(section), onTask = {})
                }
            }
        }

        compose.onNodeWithTag("task-card-toggle-repair-metadata").performClick()

        compose.onNodeWithText("Этап 2 из 3").assertIsDisplayed()
        compose.onNodeWithText("Приоритет 4").assertIsDisplayed()
        compose.onNodeWithText("Средний ремонт").assertIsDisplayed()
        compose.onNodeWithText("Загружено фото: 1").assertIsDisplayed()
        compose.onAllNodesWithText("Ремонт: Средний ремонт").assertCountEquals(0)
        compose.onAllNodesWithText("Пауза").assertCountEquals(0)
        compose.onAllNodesWithText("осталось", substring = true).assertCountEquals(0)
    }

    @Test
    fun groupedLogisticsTaskUsesCabinSummaryAsTitleAndNeverShowsTaskText() {
        val taskText = "Клиент: ООО «Ромашка» · Бытовки: БТ-101, БТ-102, БТ-103"
        val section = TaskQueueSection(
            queueId = "logistics",
            name = "Логистика",
            queuePurpose = "LOGISTICS_DRIVER",
            sortOrder = 10,
            tasks = listOf(
                task("shipment-group").copy(
                    unitNumber = "3 бытовки",
                    taskText = taskText,
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 500.dp)) {
                    TaskQueueList(sections = listOf(section), onTask = {})
                }
            }
        }

        compose.onNodeWithText("3 бытовки").assertIsDisplayed()
        compose.onAllNodesWithText(taskText).assertCountEquals(0)
        compose.onNodeWithTag("task-card-toggle-logistics-shipment-group").performClick()
        compose.onAllNodesWithText(taskText).assertCountEquals(0)
    }

    @Test
    fun oneCabinTaskUsesOnlyCabinNumberAsCardTitle() {
        val section = TaskQueueSection(
            queueId = "logistics",
            name = "Логистика",
            queuePurpose = "LOGISTICS_DRIVER",
            sortOrder = 10,
            tasks = listOf(task("shipment-single").copy(unitNumber = "БТ-101")),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 500.dp)) {
                    TaskQueueList(sections = listOf(section), onTask = {})
                }
            }
        }

        compose.onNodeWithText("БТ-101").assertIsDisplayed()
        compose.onAllNodesWithText("Бытовка: БТ-101").assertCountEquals(0)
    }

    @Test
    fun technicalMaintenanceTitleIsNeverRendered() {
        val technicalTitle = "  MaInTeNaNcE   RePaIr  "
        val section = TaskQueueSection(
            queueId = "repair",
            name = technicalTitle,
            queuePurpose = "GENERAL",
            sortOrder = 10,
            tasks = listOf(
                task("maintenance").copy(title = technicalTitle, unitNumber = "БТ-314"),
            ),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 500.dp)) {
                    TaskQueueList(sections = listOf(section), onTask = {})
                }
            }
        }

        compose.onNodeWithText("Работы").assertIsDisplayed()
        compose.onNodeWithText("БТ-314").assertIsDisplayed()
        compose.onAllNodesWithText(technicalTitle).assertCountEquals(0)
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
        title = "Задание $entryId",
        unitNumber = "БТ-1",
        taskText = "Проверить бытовку",
        scheduledDate = "2026-08-10",
        deadlineAt = null,
        priority = 3,
        queuePosition = 0,
        status = "WAITING",
        availabilityMode = "AVAILABLE",
        plannedDurationMinutes = null,
        activeStartedAt = null,
        activeWorkSeconds = 0,
        readyEvidenceCount = 1,
        resultPhotoMinCount = 1,
        lastServerRevision = 12,
        locallyPending = false,
        updatedAtEpochMillis = 1,
    )
}
