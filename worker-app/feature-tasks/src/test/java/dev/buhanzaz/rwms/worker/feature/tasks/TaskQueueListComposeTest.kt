package dev.buhanzaz.rwms.worker.feature.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
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
        compose.onNodeWithTag("queue-list").performScrollToNode(hasText("Очередь 12"))
        compose.onNodeWithText("Очередь 12").assertIsDisplayed()
        compose.onAllNodesWithText("В этой очереди пока нет заданий").onFirst().assertIsDisplayed()
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
}
