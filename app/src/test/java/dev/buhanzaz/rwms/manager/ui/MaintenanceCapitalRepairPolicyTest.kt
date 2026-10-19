package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.EstimateLineDto
import dev.buhanzaz.rwms.manager.network.RepairComplexityDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairPlanDto
import dev.buhanzaz.rwms.manager.ui.screens.capitalRepairQuantityLabel
import dev.buhanzaz.rwms.manager.ui.screens.capitalRepairStatusLabel
import org.junit.Test

class MaintenanceCapitalRepairPolicyTest {
    @Test
    fun `ordinary repair table excludes active and terminal calculated capital repairs`() {
        val ordinary = repair("ordinary", complexity = "MEDIUM")
        val activeCapital = repair("active-capital", complexity = "CAPITAL")
        val acceptedCapital = repair(
            id = "accepted-capital",
            complexity = "CAPITAL",
            acceptanceState = "ACCEPTED",
        )

        val result = splitMaintenanceRepairLists(
            repairs = listOf(ordinary, activeCapital, acceptedCapital),
            activeCapitalRepairs = listOf(activeCapital),
        )

        assertThat(result.first.map(RepairDto::id)).containsExactly("ordinary")
        assertThat(result.second.map(RepairDto::id)).containsExactly("active-capital")
    }

    @Test
    fun `legacy cached repair is excluded when active capital endpoint owns its id`() {
        val cachedWithoutComplexity = repair("legacy", complexity = null)

        val result = splitMaintenanceRepairLists(
            repairs = listOf(cachedWithoutComplexity),
            activeCapitalRepairs = listOf(repair("legacy", complexity = "CAPITAL")),
        )

        assertThat(result.first).isEmpty()
    }

    @Test
    fun `capital presentation keeps acceptance status and exact quantity unit`() {
        val pending = repair(
            id = "pending",
            complexity = "CAPITAL",
            executionState = "COMPLETED",
            acceptanceState = "PENDING",
        )
        val line = EstimateLineDto(
            id = "line-1",
            lineType = "MATERIAL",
            description = "Линолеум",
            unit = "м²",
            quantity = "12.5",
            unitPrice = "0.00",
            lineTotal = "0.00",
        )

        assertThat(capitalRepairStatusLabel(pending)).isEqualTo("Ожидание приёмки")
        assertThat(capitalRepairStatusLabel(repair("queued", "CAPITAL")))
            .isEqualTo("В очереди капремонта")
        assertThat(capitalRepairQuantityLabel(line)).isEqualTo("12.5 м²")
    }

    private fun repair(
        id: String,
        complexity: String?,
        executionState: String = "QUEUED",
        acceptanceState: String = "NOT_READY",
    ) = RepairDto(
        id = id,
        rootRepairId = id,
        warehouseId = "warehouse-1",
        rentalItemId = "asset-1",
        origin = "DIRECT_REPAIR",
        kind = "PRIMARY",
        executionState = executionState,
        acceptanceState = acceptanceState,
        version = 1,
        dispatchDate = "2026-08-17",
        complexity = complexity?.let {
            RepairComplexityDto(
                type = it,
                name = if (it == "CAPITAL") "Капитальный ремонт" else "Средний ремонт",
                color = "#B91C1C",
                plannedMinutes = "60",
                forcedCapital = it == "CAPITAL",
            )
        },
        plan = RepairPlanDto(id, 1),
        createdAt = "2026-08-17T00:00:00Z",
        updatedAt = "2026-08-17T00:00:00Z",
    )
}
