package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.InventoryCompletionPreviewDto
import dev.buhanzaz.rwms.manager.network.InventoryCompletionRiskDto
import dev.buhanzaz.rwms.manager.network.InventoryConflictDto
import dev.buhanzaz.rwms.manager.network.InventoryCurrentSnapshotDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventoryFrozenStatisticsDto
import dev.buhanzaz.rwms.manager.network.InventoryValidatedFindingDto
import dev.buhanzaz.rwms.manager.network.ObservationDto
import org.junit.Test

class InventoryCompletionPolicyTest {
    @Test
    fun `revision expectations preserve exact revisions in stable finding order`() {
        val expectations = inventoryCompletionRevisionExpectations(
            listOf(
                finding(id = "finding-b", revision = 9),
                finding(id = "finding-a", revision = 4),
            ),
        )

        assertThat(expectations.map { it.findingId })
            .containsExactly("finding-a", "finding-b")
            .inOrder()
        assertThat(expectations.map { it.expectedFindingRevision })
            .containsExactly(4L, 9L)
            .inOrder()
    }

    @Test
    fun `fresh validation merges server truth without inventing local conflicts`() {
        val inspected = finding(id = "finding-inspected", revision = 5)
        val notInspected = finding(
            id = "finding-pending",
            revision = 3,
            inspection = "NOT_INSPECTED",
        )
        val serverConflict = InventoryConflictDto(
            code = "STATUS_CHANGED",
            message = "Статус изменился",
            expected = "FREE",
            actual = "REPAIR",
        )
        val preview = preview().copy(
            validatedFindings = listOf(
                InventoryValidatedFindingDto(
                    findingId = inspected.id,
                    currentSnapshot = currentSnapshot(status = "REPAIR"),
                    conflicts = listOf(serverConflict),
                ),
                InventoryValidatedFindingDto(
                    findingId = notInspected.id,
                    currentSnapshot = currentSnapshot(status = "FREE"),
                    conflicts = listOf(serverConflict),
                ),
            ),
        )

        val merged = mergeInventoryCompletionValidation(
            findings = listOf(inspected, notInspected),
            preview = preview,
        )

        assertThat(merged[0].currentSnapshot?.status).isEqualTo("REPAIR")
        assertThat(merged[0].conflicts).containsExactly(serverConflict)
        assertThat(merged[1].currentSnapshot?.status).isEqualTo("FREE")
        assertThat(merged[1].conflicts).isEmpty()
    }

    @Test
    fun `blocking server risks cannot be acknowledged on device`() {
        inventoryBlockingCompletionRiskCodes.forEach { code ->
            val preview = preview(riskCodes = listOf(code))

            assertThat(
                preview.canCompleteInventory(
                    confirmNotInspected = true,
                    confirmMissing = true,
                ),
            ).isFalse()
            assertThat(preview.inventoryBlockingCompletionRisks().single().code)
                .isEqualTo(code)
        }
    }

    @Test
    fun `missing and not inspected each require explicit confirmation`() {
        val preview = preview(riskCodes = listOf("NOT_INSPECTED", "MISSING"))

        assertThat(preview.canCompleteInventory(false, false)).isFalse()
        assertThat(preview.canCompleteInventory(true, false)).isFalse()
        assertThat(preview.canCompleteInventory(false, true)).isFalse()
        assertThat(preview.canCompleteInventory(true, true)).isTrue()
    }

    @Test
    fun `valid preview without risks can complete and damaged hashes cannot`() {
        assertThat(preview().canCompleteInventory(false, false)).isTrue()
        assertThat(
            preview(validationSha256 = "not-a-sha").canCompleteInventory(
                confirmNotInspected = false,
                confirmMissing = false,
            ),
        ).isFalse()
    }

    private fun preview(
        riskCodes: List<String> = emptyList(),
        validationSha256: String = "b".repeat(64),
    ) = InventoryCompletionPreviewDto(
        inventoryId = "inventory-1",
        sessionRevision = 12,
        findingRevisions = inventoryCompletionRevisionExpectations(
            listOf(finding("finding-1", 5)),
        ),
        validationSha256 = validationSha256,
        validatedAt = "2026-07-27T20:15:00Z",
        acknowledgementSha256 = "a".repeat(64),
        statistics = InventoryFrozenStatisticsDto(
            expectedCount = 1,
            inspectedCount = 1,
            missingCount = 0,
            readyCount = 1,
            withWorkCount = 0,
            addedCount = 0,
            unexpectedExistingCount = 0,
            conflictCount = 0,
            workLineCount = 0,
            materialLineCount = 0,
            workTotalMinor = 0,
            materialTotalMinor = 0,
            grandTotalMinor = 0,
            roundingAdjustmentMinor = 0,
            normativeMinutes = "0",
            durationSeconds = 60,
        ),
        risks = riskCodes.map { code ->
            InventoryCompletionRiskDto(findingId = "finding-1", code = code)
        },
    )

    private fun finding(
        id: String,
        revision: Long,
        inspection: String = "READY",
    ) = InventoryFindingDto(
        id = id,
        inventoryId = "inventory-1",
        findingRevision = revision,
        origin = "EXPECTED",
        inspection = inspection,
        reconciliation = "MATCHED",
        displayCanonicalNumber = id,
        identityMatchKey = id,
        passportObservation = ObservationDto("ABSENT"),
        equipmentObservation = ObservationDto("ABSENT"),
        mutationState = "IDLE",
        comment = "",
    )

    private fun currentSnapshot(status: String) = InventoryCurrentSnapshotDto(
        assetId = "asset-1",
        assetVersion = 9,
        warehouseId = "warehouse-1",
        status = status,
        displayCanonicalNumber = "БЫТ-001",
    )
}
