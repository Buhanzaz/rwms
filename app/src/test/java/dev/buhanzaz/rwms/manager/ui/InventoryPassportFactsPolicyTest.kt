package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.InventoryCurrentSnapshotDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventorySnapshotDto
import dev.buhanzaz.rwms.manager.network.ObservationDto
import org.junit.Test

class InventoryPassportFactsPolicyTest {
    @Test
    fun `present inspection passport wins over every registry snapshot`() {
        val finding = finding(
            observation = ObservationDto(
                presence = "PRESENT",
                value = mapOf(
                    "rentalType" to "БК-Осмотр",
                    "dimensions" to "2.4x7",
                    "finishing" to "ПВХ",
                    "category" to "ИТР",
                    "linoleum" to false,
                ),
            ),
            baseline = currentSnapshot("БК-База", "2.4x6", true),
            expected = expectedSnapshot("БК-План", "2.4x5", true),
            current = currentSnapshot("БК-Реестр", "2.4x8", true),
        )

        assertThat(finding.inventoryPassportFacts()).isEqualTo(
            InventoryPassportFacts(
                rentalType = "БК-Осмотр",
                dimensions = "2.4x7",
                finishing = "ПВХ",
                category = "ИТР",
                linoleum = false,
            ),
        )
    }

    @Test
    fun `explicit empty passport does not fall back or invent values`() {
        val finding = finding(
            observation = ObservationDto("EXPLICIT_EMPTY", emptyMap<String, Any?>()),
            baseline = currentSnapshot("БК-База", "2.4x6", true),
            expected = expectedSnapshot("БК-План", "2.4x5", false),
            current = currentSnapshot("БК-Реестр", "2.4x8", true),
        )

        assertThat(finding.inventoryPassportFacts()).isEqualTo(
            InventoryPassportFacts(null, null, null, null, null),
        )
    }

    @Test
    fun `absent observation uses baseline then expected then current`() {
        val baseline = finding(
            observation = ObservationDto("ABSENT"),
            baseline = currentSnapshot("БК-База", "2.4x6", false),
            expected = expectedSnapshot("БК-План", "2.4x5", true),
            current = currentSnapshot("БК-Реестр", "2.4x8", true),
        )
        val expected = finding(
            observation = ObservationDto("ABSENT"),
            expected = expectedSnapshot("БК-План", "2.4x5", false),
            current = currentSnapshot("БК-Реестр", "2.4x8", true),
        )
        val current = finding(
            observation = ObservationDto("ABSENT"),
            current = currentSnapshot("БК-Реестр", "2.4x8", false),
        )

        assertThat(baseline.inventoryPassportFacts())
            .isEqualTo(InventoryPassportFacts("БК-База", "2.4x6", "ДВП", "Обычная", false))
        assertThat(expected.inventoryPassportFacts())
            .isEqualTo(InventoryPassportFacts("БК-План", "2.4x5", "ДВП", "Обычная", false))
        assertThat(current.inventoryPassportFacts())
            .isEqualTo(InventoryPassportFacts("БК-Реестр", "2.4x8", "ДВП", "Обычная", false))
    }

    @Test
    fun `return proof without passport uses the latest current registry passport`() {
        val finding = finding(
            observation = ObservationDto("ABSENT"),
            expected = expectedSnapshot("БК-План", "2.4x5", false),
            current = currentSnapshot("БК-Реестр", "2.4x8", true),
            inspectionSource = "LOGISTICS_RETURN",
        )

        assertThat(finding.inventoryPassportFacts())
            .isEqualTo(InventoryPassportFacts("БК-Реестр", "2.4x8", "ДВП", "Обычная", true))
    }

    private fun finding(
        observation: ObservationDto,
        baseline: InventoryCurrentSnapshotDto? = null,
        expected: InventorySnapshotDto? = null,
        current: InventoryCurrentSnapshotDto? = null,
        inspectionSource: String? = "INVENTORY",
    ) = InventoryFindingDto(
        id = "finding-1",
        inventoryId = "inventory-1",
        findingRevision = 3,
        origin = "EXPECTED",
        inspection = "READY",
        reconciliation = "MATCHED",
        displayCanonicalNumber = "БЫТ-001",
        identityMatchKey = "БЫТ-001",
        passportObservation = observation,
        equipmentObservation = ObservationDto("ABSENT"),
        mutationState = "IDLE",
        comment = "",
        expectedSnapshot = expected,
        inspectionBaseline = baseline,
        currentSnapshot = current,
        inspectionSource = inspectionSource,
    )

    private fun expectedSnapshot(
        rentalType: String,
        dimensions: String,
        linoleum: Boolean,
    ) = InventorySnapshotDto(
        assetId = "asset-1",
        assetVersion = 1,
        warehouseId = "warehouse-1",
        status = "FREE",
        displayCanonicalNumber = "БЫТ-001",
        passportSnapshot = passport(rentalType, dimensions, linoleum),
    )

    private fun currentSnapshot(
        rentalType: String,
        dimensions: String,
        linoleum: Boolean,
    ) = InventoryCurrentSnapshotDto(
        assetId = "asset-1",
        assetVersion = 2,
        warehouseId = "warehouse-1",
        status = "FREE",
        displayCanonicalNumber = "БЫТ-001",
        passportSnapshot = passport(rentalType, dimensions, linoleum),
    )

    private fun passport(
        rentalType: String,
        dimensions: String,
        linoleum: Boolean,
    ): Map<String, Any?> = mapOf(
        "rentalType" to rentalType,
        "dimensions" to dimensions,
        "finishing" to "ДВП",
        "category" to "Обычная",
        "linoleum" to linoleum,
    )
}
