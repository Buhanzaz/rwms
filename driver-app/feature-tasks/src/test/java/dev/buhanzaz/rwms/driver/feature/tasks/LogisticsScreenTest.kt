package dev.buhanzaz.rwms.driver.feature.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.database.DriverTaskEntity
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the dated logistics projection and its Compose presentation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
class LogisticsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `calendar index keeps today centered with neighboring dates`() {
        val today = LocalDate.of(2026, 8, 12)
        val todayIndex = requireNotNull(logisticsDateIndex(today, today))

        assertThat(logisticsDateAt(today, todayIndex - 1))
            .isEqualTo(LocalDate.of(2026, 8, 11))
        assertThat(logisticsDateAt(today, todayIndex)).isEqualTo(today)
        assertThat(logisticsDateAt(today, todayIndex + 1))
            .isEqualTo(LocalDate.of(2026, 8, 13))
        assertThat(logisticsDateIndex(today, today.plusYears(11))).isNull()
    }

    @Test
    fun `russian calendar labels show month weekday and full date`() {
        val date = LocalDate.of(2026, 8, 12)

        assertThat(logisticsMonthLabel(date)).isEqualTo("Август 2026")
        assertThat(logisticsWeekdayLabel(date)).isEqualTo("ср")
        assertThat(logisticsFullDateLabel(date)).isEqualTo("12 августа")
    }

    @Test
    fun `today is selected and choosing neighboring month updates fixed heading`() {
        val today = LocalDate.of(2026, 8, 31)
        val selectedDate = mutableStateOf(today)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 180.dp)) {
                    LogisticsDateCarousel(
                        today = today,
                        selectedDate = selectedDate.value,
                        onDateSelected = { selectedDate.value = it },
                    )
                }
            }
        }

        compose.onNodeWithText("Август 2026").assertIsDisplayed()
        compose.onNodeWithTag("logistics-date-2026-08-31").assertIsSelected()
        compose.onNodeWithTag("logistics-date-2026-09-01").performClick()
        compose.onNodeWithText("Сентябрь 2026").assertIsDisplayed()
        compose.onNodeWithTag("logistics-date-2026-09-01").assertIsSelected()
        assertThat(selectedDate.value).isEqualTo(LocalDate.of(2026, 9, 1))
    }

    @Test
    fun `swiping dates selects the centered day and advances the month heading`() {
        val today = LocalDate.of(2026, 8, 31)
        val selectedDate = mutableStateOf(today)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 180.dp)) {
                    LogisticsDateCarousel(
                        today = today,
                        selectedDate = selectedDate.value,
                        onDateSelected = { selectedDate.value = it },
                    )
                }
            }
        }

        compose.onNodeWithTag("logistics-date-carousel").performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 5_000) { selectedDate.value.isAfter(today) }

        compose.onNodeWithText("Сентябрь 2026").assertIsDisplayed()
    }

    @Test
    fun `dated logistics cards start expanded and open existing photo details`() {
        var openedEntryId: String? = null
        val task = task("shipment").copy(
            title = "Отгрузить бытовку",
            taskText = "Клиент и маршрут: ООО Ромашка, Москва",
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 500.dp)) {
                    LogisticsTaskList(
                        selectedDate = LocalDate.of(2026, 8, 12),
                        tasks = listOf(task),
                        kpiPalette = null,
                        onTask = { openedEntryId = it },
                    )
                }
            }
        }

        compose.onNodeWithText("12 августа · заданий: 1").assertIsDisplayed()
        compose.onNodeWithText("Клиент и маршрут: ООО Ромашка, Москва").assertIsDisplayed()
        compose.onAllNodesWithText("Дата: 2026-08-12").assertCountEquals(0)
        compose.onNodeWithText("Фото и детали").assertIsDisplayed().performClick()
        assertThat(openedEntryId).isEqualTo("shipment")
    }

    private fun task(entryId: String) = DriverTaskEntity(
        localId = "driver:$entryId",
        userId = "driver",
        entryId = entryId,
        taskId = "task-$entryId",
        version = 1,
        categoryId = "drivers",
        categoryName = "Водители",
        categorySortOrder = 5,
        title = entryId,
        unitNumber = "БТ-1",
        taskText = null,
        scheduledDate = "2026-08-12",
        deadlineAt = null,
        priority = 3,
        queuePosition = 0,
        status = "AVAILABLE",
        availabilityMode = "AVAILABLE",
        plannedDurationMinutes = null,
        activeStartedAt = null,
        activeWorkSeconds = 0,
        readyEvidenceCount = 0,
        resultPhotoMinCount = 1,
        lastServerRevision = 12,
        locallyPending = false,
        updatedAtEpochMillis = 1,
        driverAudienceMode = "ASSIGNED_DRIVER",
    )
}
