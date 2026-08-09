package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.network.ShipmentPlanRequest
import java.time.LocalDate
import kotlinx.coroutines.flow.update

/**
 * Coordinates the manager shipment workflow: list/read projection, planning, furniture readiness
 * and its fenced confirmation or cancellation commands. It retains no shipment state outside the
 * shared [ManagerUiState] flow.
 */
internal class ManagerShipmentCoordinator(
    private val runtime: ManagerCommandRuntime,
    private val backend: RwmsBackend,
    private val commandKeys: StableCommandKeys,
) {
    private val mutableState
        get() = runtime.mutableState

    private fun command(block: suspend () -> Unit) = runtime.command(block)

    private fun message(value: String) = runtime.message(value)

    private fun requireWarehouseId(): String = runtime.requireWarehouseId()

    fun loadShipments() = command {
        refreshShipments()
    }

    fun openShipment(documentId: String, onReady: () -> Unit) = command {
        val document = backend.api.shipment(documentId)
        require(document.documentType == "SHIPMENT") {
            "Сервис вернул не документ отгрузки"
        }
        val readinessFailure: Throwable?
        val readiness = if (document.needsShipmentFurnitureReadiness()) {
            val result = runCatching {
                backend.api.shipmentFurnitureReadiness(document.id)
            }
            readinessFailure = result.exceptionOrNull()
            result.getOrNull()
        } else {
            readinessFailure = null
            null
        }
        val labels = backend.resolveLogisticsAssetLabels(listOf(document))
        mutableState.update {
            it.copy(
                selectedShipment = document,
                shipmentFurnitureReadiness = readiness,
                logisticsAssetLabels = it.logisticsAssetLabels + labels,
            )
        }
        onReady()
        if (readinessFailure != null) {
            message(
                "Не удалось проверить мебель. Отгрузка доступна для просмотра, " +
                    "но действия заблокированы",
            )
        }
    }

    fun closeShipment() {
        mutableState.update {
            it.copy(
                selectedShipment = null,
                shipmentFurnitureReadiness = null,
            )
        }
    }

    fun planShipment(
        driverSnapshot: String,
        scheduledDate: String,
    ) = command {
        val document = requireNotNull(mutableState.value.selectedShipment) {
            "Откройте отгрузку"
        }
        mutableState.value.requireManagerWarehouseAccess(document.warehouseId, "EDIT")
        require(document.state == "DRAFT" || document.state == "AWAITING_CONFIRMATION") {
            "План этой отгрузки больше нельзя изменить"
        }
        val driver = driverSnapshot.trim()
        require(driver.isNotEmpty()) { "Выберите водителя" }
        require(driver.length <= 512) { "Имя водителя не может быть длиннее 512 символов" }
        val date = scheduledDate.trim()
        runCatching { LocalDate.parse(date) }
            .getOrElse { throw IllegalArgumentException("Укажите корректную дату отгрузки") }
        if (document.shipmentPlanRequiresFurnitureReadiness()) {
            requireShipmentFurnitureReady(document)
        }
        val signature = "shipment-plan:${document.id}:${document.version}:$driver:$date"
        val updated = backend.api.replaceShipmentPlan(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = commandKeys.logisticsCommandKey(signature),
            request = ShipmentPlanRequest(driver, date),
        )
        commandKeys.complete(signature)
        applyShipmentProjection(updated)
        refreshShipmentReadiness(updated)
        message("План отгрузки сохранён")
    }

    fun createShipmentFurnitureTasks() = command {
        val document = requireNotNull(mutableState.value.selectedShipment) {
            "Откройте отгрузку"
        }
        mutableState.value.requireManagerWarehouseAccess(document.warehouseId, "EDIT")
        require(document.state == "DRAFT") {
            "Задания по мебели можно создать только до планирования отгрузки"
        }
        val readiness = requireNotNull(mutableState.value.shipmentFurnitureReadiness) {
            "Не удалось проверить комплектность мебели"
        }
        require(readiness.state == "REQUIRES_TASK_CREATION") {
            "Задания по мебели уже созданы или не требуются"
        }
        val signature = "shipment-furniture:${document.id}:${document.version}"
        backend.api.createShipmentFurnitureTasks(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = commandKeys.logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        val refreshed = backend.api.shipment(document.id)
        applyShipmentProjection(refreshed)
        refreshShipmentReadiness(refreshed)
        message("Задания по мебели созданы")
    }

    fun confirmShipment() = command {
        val document = requireNotNull(mutableState.value.selectedShipment) {
            "Откройте отгрузку"
        }
        mutableState.value.requireManagerWarehouseAccess(document.warehouseId, "EDIT")
        require(document.state == "AWAITING_CONFIRMATION") {
            "Отгрузка больше не ожидает подтверждения"
        }
        requireShipmentFurnitureReady(document)
        val signature = "shipment-confirm:${document.id}:${document.version}"
        val updated = backend.api.confirmShipmentPreparation(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = commandKeys.logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        applyShipmentProjection(updated)
        refreshShipmentReadiness(updated)
        message("Отгрузка подтверждена")
    }

    fun cancelShipment() = command {
        val document = requireNotNull(mutableState.value.selectedShipment) {
            "Откройте отгрузку"
        }
        mutableState.value.requireManagerWarehouseAccess(document.warehouseId, "EDIT")
        require(document.state in SHIPMENT_CANCELLABLE_STATES) {
            "Эту отгрузку больше нельзя отменить"
        }
        val signature = "shipment-cancel:${document.id}:${document.version}"
        val updated = backend.api.cancelShipment(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = commandKeys.logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        applyShipmentProjection(updated)
        refreshShipmentReadiness(updated)
        message("Отгрузка отменена")
    }

    private suspend fun refreshShipments() {
        val warehouseId = requireWarehouseId()
        val documents = backend.api.shipments(warehouseId)
            .filter { it.documentType == "SHIPMENT" }
        val labels = backend.resolveLogisticsAssetLabels(documents)
        if (mutableState.value.selectedWarehouseId != warehouseId) return
        mutableState.update {
            it.copy(
                shipments = documents,
                logisticsAssetLabels = it.logisticsAssetLabels + labels,
            )
        }
    }

    private fun applyShipmentProjection(document: LogisticsDocumentDto) {
        require(document.documentType == "SHIPMENT") {
            "Сервис вернул не документ отгрузки"
        }
        mutableState.update { current ->
            current.copy(
                shipments = current.shipments.replaceLogisticsDocument(document),
                selectedShipment = document,
            )
        }
    }

    private suspend fun refreshShipmentReadiness(document: LogisticsDocumentDto) {
        if (mutableState.value.selectedShipment?.id == document.id) {
            mutableState.update { it.copy(shipmentFurnitureReadiness = null) }
        }
        val readiness = if (document.needsShipmentFurnitureReadiness()) {
            backend.api.shipmentFurnitureReadiness(document.id)
        } else {
            null
        }
        if (mutableState.value.selectedShipment?.id == document.id) {
            mutableState.update { it.copy(shipmentFurnitureReadiness = readiness) }
        }
    }

    private fun requireShipmentFurnitureReady(document: LogisticsDocumentDto) {
        if (!document.needsShipmentFurnitureReadiness()) return
        val state = mutableState.value.shipmentFurnitureReadiness?.state
        require(state == "NOT_REQUIRED" || state == "READY") {
            "Сначала завершите задания по мебели"
        }
    }
}

/** Resolves display labels for independent logistics projections without retaining a second cache. */
internal suspend fun RwmsBackend.resolveLogisticsAssetLabels(
    documents: List<LogisticsDocumentDto>,
): Map<String, String> = documents
    .flatMap(LogisticsDocumentDto::lines)
    .map { it.assetId }
    .distinct()
    .associateWith { assetId -> api.rentalItem(assetId).number }

/** Builds the same stable request fence for the separate logistics command owners. */
internal fun StableCommandKeys.logisticsCommandKey(signature: String): String = key(signature)

/** Replaces one server projection in a list while preserving the list's current order. */
internal fun List<LogisticsDocumentDto>.replaceLogisticsDocument(
    document: LogisticsDocumentDto,
): List<LogisticsDocumentDto> =
    if (any { it.id == document.id }) {
        map { current -> if (current.id == document.id) document else current }
    } else {
        listOf(document) + this
    }

private val SHIPMENT_CANCELLABLE_STATES = setOf(
    "DRAFT",
    "PREPARING",
    "AWAITING_CONFIRMATION",
)
