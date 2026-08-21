package dev.buhanzaz.rwms.worker.core.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies source-owned labels shared by the worker board and task detail. */
class WorkerLabelsTest {
    @Test
    fun `technical maintenance queue is presented as a work stage`() {
        assertThat(workerTaskStageLabel("  Maintenance   rapair ")).isEqualTo("Работы")
        assertThat(workerTaskStageLabel("Внутренние работы")).isEqualTo("Внутренние работы")
    }

    @Test
    fun `only canonical repair complexity titles are exposed`() {
        assertThat(workerRepairComplexityLabel("Лёгкий ремонт")).isEqualTo("Лёгкий ремонт")
        assertThat(workerRepairComplexityLabel("Средний ремонт")).isEqualTo("Средний ремонт")
        assertThat(workerRepairComplexityLabel("Тяжёлый ремонт")).isEqualTo("Сложный ремонт")
        assertThat(workerRepairComplexityLabel("Капитальный ремонт")).isEqualTo("Капитальный ремонт")
        assertThat(workerRepairComplexityLabel("Maintenance repair")).isNull()
        assertThat(workerRepairComplexityLabel("Погрузка")).isNull()
    }
}
