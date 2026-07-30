package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.InventoryCurrentSnapshotDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.ObservationDto
import org.junit.Test

class InventoryConflictPolicyTest {
    @Test
    fun `business statuses have visible russian labels`() {
        assertThat(inventoryBusinessStatusLabel("FREE")).isEqualTo("Свободная")
        assertThat(inventoryBusinessStatusLabel("OWN_NEEDS")).isEqualTo("Собственные нужды")
        assertThat(inventoryBusinessStatusLabel("USED_SALE")).isEqualTo("Продажа б/у")
        assertThat(inventoryBusinessStatusLabel("REPAIR")).isEqualTo("Ремонт")
        assertThat(inventoryBusinessStatusLabel("WAITING_REPAIR_CHECK"))
            .isEqualTo("Ожидает проверки ремонта")
        assertThat(inventoryBusinessStatusLabel("CAPITAL_REPAIR"))
            .isEqualTo("Капитальный ремонт")
    }

    @Test
    fun `inspection states have compact russian labels`() {
        assertThat(inventoryInspectionLabel("NOT_INSPECTED")).isEqualTo("Непроверена")
        assertThat(inventoryInspectionLabel("READY")).isEqualTo("Проверена")
        assertThat(inventoryInspectionLabel("WORK_STAGED")).isEqualTo("Проверена")
    }

    @Test
    fun `raw asset version change is absent from semantic comparison`() {
        val finding = finding(
            before = snapshot(version = 4),
            after = snapshot(version = 5),
        )

        assertThat(finding.inventorySemanticChanges()).isEmpty()
    }

    @Test
    fun `semantic comparison renders business and passport changes`() {
        val finding = finding(
            before = snapshot(
                version = 4,
                status = "FREE",
                passport = mapOf("finishing" to "ДВП"),
            ),
            after = snapshot(
                version = 5,
                status = "REPAIR",
                passport = mapOf("finishing" to "ОСБ"),
            ),
        )

        assertThat(finding.inventorySemanticChanges()).containsExactly(
            InventorySemanticChange("Статус", "Свободная", "Ремонт"),
            InventorySemanticChange("Паспорт", "finishing: ДВП", "finishing: ОСБ"),
        ).inOrder()
        assertThat(finding.inventoryBusinessStatus()).isEqualTo("REPAIR")
    }

    private fun finding(
        before: InventoryCurrentSnapshotDto,
        after: InventoryCurrentSnapshotDto,
    ) = InventoryFindingDto(
        id = "finding-1",
        inventoryId = "inventory-1",
        findingRevision = 3,
        origin = "EXPECTED",
        inspection = "READY",
        reconciliation = "CONFLICT",
        displayCanonicalNumber = "БЫТ-001",
        identityMatchKey = "БЫТ-001",
        passportObservation = ObservationDto("ABSENT"),
        equipmentObservation = ObservationDto("ABSENT"),
        mutationState = "IDLE",
        comment = "",
        inspectionBaseline = before,
        currentSnapshot = after,
    )

    private fun snapshot(
        version: Long,
        status: String = "FREE",
        passport: Map<String, Any?> = emptyMap(),
    ) = InventoryCurrentSnapshotDto(
        assetId = "asset-1",
        assetVersion = version,
        warehouseId = "warehouse-1",
        status = status,
        displayCanonicalNumber = "БЫТ-001",
        passportSnapshot = passport,
    )
}
