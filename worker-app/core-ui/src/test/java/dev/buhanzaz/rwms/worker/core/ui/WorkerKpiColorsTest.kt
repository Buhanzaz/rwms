package dev.buhanzaz.rwms.worker.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkerKpiColorsTest {
    private val ranges = listOf(
        WorkerKpiColorRange(100, 80, "#16803A"),
        WorkerKpiColorRange(79, 50, "#D99B00"),
        WorkerKpiColorRange(49, 0, "#C15B00"),
    )

    @Test
    fun `uses descending server ranges and dedicated overdue color`() {
        assertEquals(Color(0xFF16803A), workerKpiTimeColor(91.0, ranges, "#C62828"))
        assertEquals(Color(0xFFD99B00), workerKpiTimeColor(64.0, ranges, "#C62828"))
        assertEquals(Color(0xFFC15B00), workerKpiTimeColor(12.0, ranges, "#C62828"))
        assertEquals(Color(0xFFC62828), workerKpiTimeColor(-0.1, ranges, "#C62828"))
    }

    @Test
    fun `does not invent a color for absent invalid or out of range policy`() {
        assertNull(workerKpiTimeColor(50.0, emptyList(), null))
        assertNull(workerKpiTimeColor(50.0, listOf(WorkerKpiColorRange(0, 100, "red")), null))
        assertNull(workerKpiTimeColor(101.0, ranges, "#C62828"))
    }
}
