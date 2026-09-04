package dev.buhanzaz.rwms.worker.feature.taskdetail

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SlingerAutomaticJoinTest {
    @Test
    fun `accepted interruption automatically joins exactly once when action is ready`() {
        val presentation = TaskActionPresentation(
            actions = listOf(WorkerTaskAction.JOIN),
            actionsEnabled = true,
            message = null,
            performers = listOf("Водитель"),
            takeLabel = "Взять задание",
            joinLabel = "Взять задание",
        )

        assertThat(
            shouldAutomaticallyJoinSlingerTask(
                takeSlingerOnOpen = true,
                alreadyRequested = false,
                presentation = presentation,
            ),
        ).isTrue()
        assertThat(
            shouldAutomaticallyJoinSlingerTask(
                takeSlingerOnOpen = true,
                alreadyRequested = true,
                presentation = presentation,
            ),
        ).isFalse()
    }

    @Test
    fun `normal navigation and unavailable join never auto-submit`() {
        val unavailable = TaskActionPresentation(
            actions = listOf(WorkerTaskAction.JOIN),
            actionsEnabled = false,
            message = "Нет текущей группы",
            performers = emptyList(),
            takeLabel = "Взять задание",
            joinLabel = "Взять задание",
        )
        val ordinary = unavailable.copy(
            actions = listOf(WorkerTaskAction.TAKE),
            actionsEnabled = true,
        )

        assertThat(
            shouldAutomaticallyJoinSlingerTask(true, false, unavailable),
        ).isFalse()
        assertThat(
            shouldAutomaticallyJoinSlingerTask(true, false, ordinary),
        ).isFalse()
        assertThat(
            shouldAutomaticallyJoinSlingerTask(false, false, ordinary),
        ).isFalse()
    }

    @Test
    fun `single ordinary root task is automatically taken exactly once`() {
        val available = TaskActionPresentation(
            actions = listOf(WorkerTaskAction.TAKE),
            actionsEnabled = true,
            message = null,
            performers = emptyList(),
            takeLabel = "Взять задание",
            joinLabel = "Присоединиться",
        )

        assertThat(shouldAutomaticallyTakeTask(true, false, available)).isTrue()
        assertThat(shouldAutomaticallyTakeTask(true, true, available)).isFalse()
        assertThat(shouldAutomaticallyTakeTask(false, false, available)).isFalse()
        assertThat(
            shouldAutomaticallyTakeTask(
                true,
                false,
                available.copy(actionsEnabled = false),
            ),
        ).isFalse()
    }
}
