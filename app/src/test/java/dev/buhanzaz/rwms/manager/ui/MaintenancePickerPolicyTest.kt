package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.LogisticsLineDto
import org.junit.Test

class MaintenancePickerPolicyTest {
    @Test
    fun `direct repair excludes only rented and after rent`() {
        assertThat(maintenanceExcludedRentalItemStatuses(MaintenanceEditorMode.REPAIR))
            .containsExactly("RENTED", "AFTER_RENT")
            .inOrder()
    }

    @Test
    fun `estimate excludes every known status except after rent`() {
        val excluded = maintenanceExcludedRentalItemStatuses(MaintenanceEditorMode.ESTIMATE)

        // RentalItemStatus from the canonical asset-service contract.
        val canonicalStatuses = listOf(
            "RENTED", "BOOKED", "REPAIR", "WAITING_REPAIR_CHECK", "WRITTEN_OFF", "LOST",
            "CAPITAL_REPAIR", "AFTER_RENT", "WAITING_ESTIMATE_CONFIRMATION", "SALE", "USED_SALE",
            "RESERVED", "FREE", "WAREHOUSE", "OWN_NEEDS", "IN_TRANSFER",
        )
        assertThat(maintenanceKnownRentalItemStatuses).containsExactlyElementsIn(canonicalStatuses)
        assertThat(canonicalStatuses - excluded.toSet()).containsExactly("AFTER_RENT")
        assertThat(excluded).contains("LOST")
    }

    @Test
    fun `latest return supplies party snapshot and inspection date`() {
        val metadata = latestMaintenanceReturnMetadata(
            returnDocuments = listOf(
                document(
                    id = "older",
                    createdAt = "2026-07-24T09:00:00Z",
                    party = "Первый контрагент",
                    tenant = "Первый арендатор",
                ),
                document(
                    id = "latest",
                    createdAt = "2026-07-27T12:00:00Z",
                    party = "  ",
                    tenant = "  Последний арендатор ",
                ),
            ),
            rentalItemId = "asset-1",
        )

        assertThat(metadata).isEqualTo(
            MaintenanceReturnMetadata(
                sourceParty = "Последний арендатор",
                dispatchDate = "2026-07-27",
            ),
        )
    }

    @Test
    fun `catalog revisions compare active catalog id and version`() {
        assertThat(
            ActiveMaintenanceCatalogRevision("catalog-1", 4),
        ).isNotEqualTo(ActiveMaintenanceCatalogRevision("catalog-1", 5))
    }

    private fun document(
        id: String,
        createdAt: String,
        party: String?,
        tenant: String?,
    ) = LogisticsDocumentDto(
        id = id,
        version = 1,
        documentType = "RETURN",
        state = "COMPLETED",
        warehouseId = "warehouse-1",
        partySnapshot = party,
        lines = listOf(
            LogisticsLineDto(
                id = "$id-line",
                version = 1,
                lineNumber = 1,
                assetId = "asset-1",
                assetVersion = 1,
                state = "COMPLETED",
                tenantSnapshot = tenant,
            ),
        ),
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
