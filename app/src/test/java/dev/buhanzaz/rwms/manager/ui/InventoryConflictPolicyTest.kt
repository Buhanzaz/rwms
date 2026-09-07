package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.InventoryCurrentSnapshotDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanDto
import dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanLineDto
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
        assertThat(finding.inventoryBusinessStatus()).isEqualTo("FREE")
    }

    @Test
    fun `session status follows inspected no-work ordinary repair and capital repair outcomes`() {
        val current = snapshot(version = 4, status = "WAREHOUSE")

        assertThat(finding(current, current, inspection = "NOT_INSPECTED").inventoryBusinessStatus())
            .isEqualTo("WAREHOUSE")
        assertThat(finding(current, current, inspection = "READY").inventoryBusinessStatus())
            .isEqualTo("FREE")
        assertThat(
            finding(
                current,
                current,
                inspection = "WORK_STAGED",
                frozenPlan = frozenPlan(),
            ).inventoryBusinessStatus(),
        ).isEqualTo("REPAIR")
        assertThat(
            finding(
                current,
                current,
                inspection = "WORK_STAGED",
                frozenPlan = frozenPlan(forceCapitalRepair = true),
            ).inventoryBusinessStatus(),
        ).isEqualTo("CAPITAL_REPAIR")
    }

    private fun finding(
        before: InventoryCurrentSnapshotDto,
        after: InventoryCurrentSnapshotDto,
        inspection: String = "READY",
        frozenPlan: InventoryFrozenPlanDto? = null,
    ) = InventoryFindingDto(
        id = "finding-1",
        inventoryId = "inventory-1",
        findingRevision = 3,
        origin = "EXPECTED",
        inspection = inspection,
        reconciliation = "CONFLICT",
        displayCanonicalNumber = "БЫТ-001",
        identityMatchKey = "БЫТ-001",
        passportObservation = ObservationDto("ABSENT"),
        equipmentObservation = ObservationDto("ABSENT"),
        mutationState = "IDLE",
        comment = "",
        inspectionBaseline = before,
        currentSnapshot = after,
        frozenPlan = frozenPlan,
    )

    private fun frozenPlan(forceCapitalRepair: Boolean = false) = InventoryFrozenPlanDto(
        mode = "MANUAL",
        catalogVersionId = "catalog-1",
        fingerprintSha256 = "a".repeat(64),
        forceCapitalRepair = forceCapitalRepair,
        lines = listOf(
            InventoryFrozenPlanLineDto(
                id = "line-1",
                sourceKind = "MANUAL",
                lineType = "WORK",
                description = "Ремонт",
                unit = "шт",
                quantity = "1",
                unitPriceMinor = 0,
                normativeMinutes = "1",
            ),
        ),
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
