package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
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
    fun `zero cabin draft loads furniture readiness and exposes one departure action`() {
        val transfer = document(
            documentType = "TRANSFER",
            state = "DRAFT",
            rentalOrderId = null,
            lineState = "PENDING",
        ).copy(lines = emptyList())

        assertThat(transfer.needsTransferFurnitureReadiness()).isTrue()
        assertThat(transferFurnitureIsReady(transfer, null)).isFalse()
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

        val action = wholeTransferAction(
            document = transfer,
            canManage = true,
            busy = false,
            furnitureReady = true,
        )

        assertThat(action?.command).isEqualTo(WholeTransferCommand.DEPART)
        assertThat(action?.label).isEqualTo("Начать перемещение")
        assertThat(action?.enabled).isTrue()
        assertThat(action?.blockingMessage).isNull()
    }

    @Test
    fun `zero cabin departure action fails closed for access busy and furniture`() {
        val transfer = document(
            documentType = "TRANSFER",
            state = "DRAFT",
            rentalOrderId = null,
            lineState = "PENDING",
        ).copy(lines = emptyList())

        assertThat(
            wholeTransferAction(
                document = transfer,
                canManage = false,
                busy = false,
                furnitureReady = true,
            ),
        ).isNull()
        assertThat(
            wholeTransferAction(
                document = transfer,
                canManage = true,
                busy = true,
                furnitureReady = true,
            )?.enabled,
        ).isFalse()
        val furnitureBlocked = wholeTransferAction(
            document = transfer,
            canManage = true,
            busy = false,
            furnitureReady = false,
        )
        assertThat(furnitureBlocked?.enabled).isFalse()
        assertThat(furnitureBlocked?.blockingMessage)
            .isEqualTo("Сначала завершите задания по мебели.")
    }

    @Test
    fun `zero cabin arrival is available only for exact in transit state`() {
        val transferWithCabin = document(
            documentType = "TRANSFER",
            state = "IN_TRANSIT",
            rentalOrderId = null,
            lineState = "DEPARTED",
        )
        val transfer = transferWithCabin.copy(lines = emptyList())

        val action = wholeTransferAction(
            document = transfer,
            canManage = true,
            busy = false,
            furnitureReady = false,
        )

        assertThat(action?.command).isEqualTo(WholeTransferCommand.ARRIVE)
        assertThat(action?.label).isEqualTo("Подтвердить прибытие")
        assertThat(action?.enabled).isTrue()
        listOf("DEPARTING", "ARRIVING", "COMPLETED", "CANCELLED").forEach { state ->
            assertThat(
                wholeTransferAction(
                    document = transfer.copy(state = state),
                    canManage = true,
                    busy = false,
                    furnitureReady = true,
                ),
            ).isNull()
        }
        assertThat(
            wholeTransferAction(
                document = transferWithCabin,
                canManage = true,
                busy = false,
                furnitureReady = true,
            ),
        ).isNull()
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
