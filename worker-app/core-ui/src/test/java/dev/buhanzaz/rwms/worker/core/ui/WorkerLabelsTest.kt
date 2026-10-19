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
        assertThat(workerRepairComplexityLabel("Лёгкий ремонт")).isEqualTo("Легкий ремонт")
        assertThat(workerRepairComplexityLabel("Средний ремонт")).isEqualTo("Средний ремонт")
        assertThat(workerRepairComplexityLabel("Тяжёлый ремонт")).isEqualTo("Тяжелый ремонт")
        assertThat(workerRepairComplexityLabel("Капитальный ремонт")).isEqualTo("Капитальный ремонт")
        assertThat(workerRepairComplexityLabel("Maintenance repair")).isNull()
        assertThat(workerRepairComplexityLabel("Погрузка")).isNull()
    }

    @Test
    fun `worker package ordinal is one based and never reports a total below current package`() {
        assertThat(workerTaskStageOrdinal(routeStepIndex = 0, routeStepCount = 2, separator = "/"))
            .isEqualTo("1/2")
        assertThat(workerTaskStageOrdinal(routeStepIndex = 2, routeStepCount = 1, separator = " из "))
            .isEqualTo("3 из 3")
    }

    @Test
    fun `only canonical transfer labels identify an interwarehouse task`() {
        assertThat(
            isInterwarehouseTransferTask(
                "Отгрузить бытовки",
                "Перемещение бытовки между складами. Бытовки: БТ-172, БТ-311",
            ),
        ).isTrue()
        assertThat(
            isInterwarehouseTransferTask(
                "Переместить мебель между складами",
                "Межскладской груз: Мебель: 3 поз., 14 ед.",
            ),
        ).isTrue()
        assertThat(
            isInterwarehouseTransferTask(
                "Отгрузить бытовки",
                "Клиент: ООО Ромашка. Бытовки: БТ-172",
            ),
        ).isFalse()
    }
}
