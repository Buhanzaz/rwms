package dev.buhanzaz.rwms.driver.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriverKpiColorsTest {
    private val ranges = listOf(
        DriverKpiColorRange(100, 80, "#16803A"),
        DriverKpiColorRange(79, 50, "#D99B00"),
        DriverKpiColorRange(49, 0, "#C15B00"),
    )

    @Test
    fun `uses descending server ranges and dedicated overdue color`() {
        assertEquals(Color(0xFF16803A), driverKpiTimeColor(91.0, ranges, "#C62828"))
        assertEquals(Color(0xFFD99B00), driverKpiTimeColor(64.0, ranges, "#C62828"))
        assertEquals(Color(0xFFC15B00), driverKpiTimeColor(12.0, ranges, "#C62828"))
        assertEquals(Color(0xFFC62828), driverKpiTimeColor(-0.1, ranges, "#C62828"))
    }

    @Test
    fun `does not invent a color for absent invalid or out of range policy`() {
        assertNull(driverKpiTimeColor(50.0, emptyList(), null))
        assertNull(driverKpiTimeColor(50.0, listOf(DriverKpiColorRange(0, 100, "red")), null))
        assertNull(driverKpiTimeColor(101.0, ranges, "#C62828"))
    }
}
