package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.LogisticsLineDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ShipmentFurnitureReadinessDto
import dev.buhanzaz.rwms.manager.network.TransferFurnitureReadinessDto
import org.junit.Test

class LogisticsPoliciesTest {
    @Test
    fun `order shipment fails closed while readiness is unavailable`() {
        val shipment = document(
            documentType = "SHIPMENT",
            state = "DRAFT",
            rentalOrderId = "order-1",
            lineState = "PENDING",
        )

        assertThat(shipmentFurnitureIsReady(shipment, null)).isFalse()
        assertThat(
            shipmentFurnitureIsReady(
                shipment,
                ShipmentFurnitureReadinessDto(
                    shipmentId = shipment.id,
                    shipmentVersion = shipment.version,
                    state = "AWAITING_TASK_COMPLETION",
                    tasks = emptyList(),
                ),
            ),
        ).isFalse()
        assertThat(
            shipmentFurnitureIsReady(
                shipment,
                ShipmentFurnitureReadinessDto(
                    shipmentId = shipment.id,
                    shipmentVersion = shipment.version,
                    state = "READY",
                    tasks = emptyList(),
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `furniture blocks initial shipment plan but not rescheduling`() {
        val draft = document(
            documentType = "SHIPMENT",
            state = "DRAFT",
            rentalOrderId = "order-1",
            lineState = "PENDING",
        )
        val awaitingConfirmation = draft.copy(state = "AWAITING_CONFIRMATION")

        assertThat(draft.shipmentPlanRequiresFurnitureReadiness()).isTrue()
        assertThat(awaitingConfirmation.needsShipmentFurnitureReadiness()).isTrue()
        assertThat(awaitingConfirmation.shipmentPlanRequiresFurnitureReadiness()).isFalse()
    }

    @Test
    fun `pending transfer fails closed while readiness is unavailable`() {
        val transfer = document(
            documentType = "TRANSFER",
            state = "DRAFT",
            rentalOrderId = null,
            lineState = "PENDING",
        )

        assertThat(transferFurnitureIsReady(transfer, null)).isFalse()
        assertThat(
            transferFurnitureIsReady(
                transfer,
                TransferFurnitureReadinessDto(
                    transferId = transfer.id,
                    transferVersion = transfer.version,
                    state = "BLOCKED",
                    tasks = emptyList(),
                ),
            ),
        ).isFalse()
        assertThat(
            transferFurnitureIsReady(
                transfer,
                TransferFurnitureReadinessDto(
                    transferId = transfer.id,
                    transferVersion = transfer.version,
                    state = "NOT_REQUIRED",
                    tasks = emptyList(),
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `late return media response cannot overwrite another opened return`() {
        val first = document(
            documentType = "RETURN",
            state = "INSPECTION",
            rentalOrderId = null,
            lineState = "PENDING",
        ).copy(id = "return-a")
        val second = first.copy(id = "return-b")
        val existing = mapOf("line-b" to listOf(MediaReferenceDto("media-b", 1)))
        val late = mapOf("line-a" to listOf(MediaReferenceDto("media-a", 2)))
        val current = ManagerUiState(
            selectedReturn = second,
            returnReadyMedia = existing,
        )

        val ignored = current.withLoadedReturnReadyMedia(first.id, late)
        val applied = current.copy(selectedReturn = first)
            .withLoadedReturnReadyMedia(first.id, late)

        assertThat(ignored).isSameInstanceAs(current)
        assertThat(ignored.returnReadyMedia).isEqualTo(existing)
        assertThat(applied.returnReadyMedia).isEqualTo(late)
    }

    @Test
    fun `return shortage catalog keeps active equipment sorted by name`() {
        val returnDocument = document(
            documentType = "RETURN",
            state = "INSPECTION",
            rentalOrderId = null,
            lineState = "PENDING",
        )
        val loaded = ManagerUiState(selectedReturn = returnDocument)
            .withLoadedReturnEquipmentCatalog(
                returnDocument.id,
                listOf(
                    equipment(id = "table", name = "Стол", category = "Мебель"),
                    equipment(id = "archived", name = "Архивный стол", active = false),
                    equipment(id = "chair", name = "Стул", category = "Мебель"),
                ),
            )

        assertThat(loaded.returnEquipmentCatalogStatus)
            .isEqualTo(ReturnEquipmentCatalogStatus.AVAILABLE)
        assertThat(loaded.returnEquipmentCatalog.map(EquipmentCatalogItemDto::name))
            .containsExactly("Стол", "Стул")
            .inOrder()
        assertThat(
            loaded.returnEquipmentCatalog.selectedReturnShortageEquipment("chair")?.name,
        ).isEqualTo("Стул")
        assertThat(
            loaded.returnEquipmentCatalog.selectedReturnShortageEquipment("not-selected"),
        ).isNull()
    }

    @Test
    fun `empty return shortage catalog disables the estimate source honestly`() {
        val returnDocument = document(
            documentType = "RETURN",
            state = "INSPECTION",
            rentalOrderId = null,
            lineState = "PENDING",
        )

        val loaded = ManagerUiState(selectedReturn = returnDocument)
            .withLoadedReturnEquipmentCatalog(
                returnDocument.id,
                listOf(equipment(id = "archived", name = "Архивный стол", active = false)),
            )

        assertThat(loaded.returnEquipmentCatalog).isEmpty()
        assertThat(loaded.returnEquipmentCatalogStatus)
            .isEqualTo(ReturnEquipmentCatalogStatus.EMPTY)
    }

    @Test
    fun `unavailable return shortage catalog clears prior choices`() {
        val returnDocument = document(
            documentType = "RETURN",
            state = "INSPECTION",
            rentalOrderId = null,
            lineState = "PENDING",
        )
        val current = ManagerUiState(
            selectedReturn = returnDocument,
            returnEquipmentCatalog = listOf(equipment(id = "chair", name = "Стул")),
            returnEquipmentCatalogStatus = ReturnEquipmentCatalogStatus.AVAILABLE,
            returnShortageEquipment = mapOf("line-1" to "chair"),
            returnShortageQuantity = mapOf("line-1" to "2"),
        )

        val unavailable = current.withUnavailableReturnEquipmentCatalog(returnDocument.id)

        assertThat(unavailable.returnEquipmentCatalog).isEmpty()
        assertThat(unavailable.returnEquipmentCatalogStatus)
            .isEqualTo(ReturnEquipmentCatalogStatus.UNAVAILABLE)
        assertThat(unavailable.returnShortageEquipment).isEmpty()
        assertThat(unavailable.returnShortageQuantity).isEmpty()
    }

    @Test
    fun `late return equipment catalog cannot overwrite another opened return`() {
        val first = document(
            documentType = "RETURN",
            state = "INSPECTION",
            rentalOrderId = null,
            lineState = "PENDING",
        ).copy(id = "return-a")
        val second = first.copy(id = "return-b")
        val existing = listOf(equipment(id = "chair", name = "Стул"))
        val late = listOf(equipment(id = "table", name = "Стол"))
        val current = ManagerUiState(
            selectedReturn = second,
            returnEquipmentCatalog = existing,
            returnEquipmentCatalogStatus = ReturnEquipmentCatalogStatus.AVAILABLE,
        )

        val ignored = current.withLoadedReturnEquipmentCatalog(first.id, late)
        val applied = current.copy(selectedReturn = first)
            .withLoadedReturnEquipmentCatalog(first.id, late)

        assertThat(ignored).isSameInstanceAs(current)
        assertThat(ignored.returnEquipmentCatalog).isEqualTo(existing)
        assertThat(applied.returnEquipmentCatalog.map(EquipmentCatalogItemDto::id))
            .containsExactly("table")
    }

    private fun equipment(
        id: String,
        name: String,
        category: String = "OTHER",
        active: Boolean = true,
    ) = EquipmentCatalogItemDto(
        id = id,
        version = 1,
        name = name,
        category = category,
        active = active,
    )

    private fun document(
        documentType: String,
        state: String,
        rentalOrderId: String?,
        lineState: String,
    ) = LogisticsDocumentDto(
        id = "document-1",
        version = 3,
        documentType = documentType,
        state = state,
        warehouseId = "warehouse-1",
        destinationWarehouseId =
            if (documentType == "TRANSFER") "warehouse-2" else null,
        partySnapshot = if (documentType == "SHIPMENT") "Клиент" else null,
        rentalOrderId = rentalOrderId,
        lines = listOf(
            LogisticsLineDto(
                id = "line-1",
                version = 2,
                lineNumber = 1,
                assetId = "asset-1",
                assetVersion = 5,
                state = lineState,
                rentalOrderId = rentalOrderId,
            ),
        ),
        createdAt = "2026-07-28T00:00:00Z",
        updatedAt = "2026-07-28T00:00:00Z",
    )
}
