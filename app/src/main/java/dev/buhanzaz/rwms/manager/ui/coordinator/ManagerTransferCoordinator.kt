package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.media.retryMediaReadAfterOwnerProof
import dev.buhanzaz.rwms.manager.network.CabinFurnitureRequirementDto
import dev.buhanzaz.rwms.manager.network.CreateTransferRequest
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ReconcileLogisticsRequest
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.network.TransferFurnitureReplacementRequest
import dev.buhanzaz.rwms.manager.network.TransferLineRequest
import dev.buhanzaz.rwms.manager.network.WarehouseDto
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadArea
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadDraft
import dev.buhanzaz.rwms.manager.uploads.PendingBackgroundPhoto
import dev.buhanzaz.rwms.manager.uploads.TransferArrivalUploadCommand
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.update

/** Resolves the calendar date owned by the supplied warehouse at an absolute instant. */
internal fun warehouseBusinessDate(warehouse: WarehouseDto, instant: Instant): LocalDate =
    LocalDate.ofInstant(instant, ZoneId.of(warehouse.timeZone))

/**
 * Owns transfer creation, departure, arrival and reconciliation. The coordinator retains only
 * transient editor data in the shared [ManagerUiState]; source and destination access checks stay
 * adjacent to every command that changes a transfer.
 */
internal class ManagerTransferCoordinator(
    private val runtime: ManagerCommandRuntime,
    private val backend: RwmsBackend,
    private val backgroundUploads: Deferred<BackgroundUploadCoordinator>,
    private val commandKeys: StableCommandKeys,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutableState
        get() = runtime.mutableState

    private fun command(block: suspend () -> Unit) = runtime.command(block)

    private fun message(value: String) = runtime.message(value)

    private fun requireWarehouseId(): String = runtime.requireWarehouseId()

    fun loadTransfers() = command {
        refreshTransfers()
    }

    fun startTransferEditor(onReady: () -> Unit) = command {
        val warehouseId = requireWarehouseId()
        val sourceWarehouse = mutableState.value.warehouses.firstOrNull { it.id == warehouseId }
            ?: throw IllegalStateException("Склад отправления недоступен")
        val user = requireNotNull(mutableState.value.currentUser)
        mutableState.value.requireManagerWarehouseAccess(warehouseId, "EDIT")
        val destinations = mutableState.value.warehouses.filter { warehouse ->
            warehouse.id != warehouseId &&
                WarehouseAccessPolicy.hasAccess(user, warehouse.id, "EDIT")
        }
        require(destinations.isNotEmpty()) {
            "Нет другого активного склада с правом редактирования"
        }
        val candidates = backend.api.rentalItems(
            warehouseId = warehouseId,
            page = 0,
            size = 200,
        ).content
            .filter { it.status == "FREE" }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, RentalItemDto::number))
        val furnitureCatalog = backend.api.equipment(warehouseId)
            .map { it.equipment }
            .filter { it.active && it.category == "FURNITURE" }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, EquipmentCatalogItemDto::name))
        mutableState.update {
            it.copy(
                transferEditor = TransferEditorState(
                    scheduledDate = warehouseBusinessDate(sourceWarehouse, clock.instant()).toString(),
                    destinationWarehouseIds = destinations.mapTo(mutableSetOf()) { it.id },
                    candidates = candidates,
                    furnitureCatalog = furnitureCatalog,
                ),
            )
        }
        onReady()
    }

    fun editTransferEditor(update: (TransferEditorState) -> TransferEditorState) {
        mutableState.update { current ->
            current.copy(
                transferEditor = current.transferEditor?.let { editor ->
                    update(editor).copy(idempotencyKey = UUID.randomUUID().toString())
                },
            )
        }
    }

    fun toggleTransferAsset(assetId: String, selected: Boolean) {
        editTransferEditor { editor ->
            if (editor.candidates.none { it.id == assetId && it.status == "FREE" }) {
                editor
            } else if (selected) {
                editor.copy(selectedAssetIds = editor.selectedAssetIds + assetId)
            } else {
                editor.copy(
                    selectedAssetIds = editor.selectedAssetIds - assetId,
                    furnitureReplacements = editor.furnitureReplacements - assetId,
                )
            }
        }
    }

    fun updateTransferFurnitureQuantity(
        assetId: String,
        equipmentId: String,
        quantity: String,
    ) {
        val parsed = quantity.trim().takeIf(String::isNotEmpty)?.toLongOrNull()
            ?: if (quantity.isBlank()) 0L else return
        if (parsed !in 0..999_999L) return
        editTransferEditor { editor ->
            val cabin = editor.candidates.firstOrNull {
                it.id == assetId && assetId in editor.selectedAssetIds
            } ?: return@editTransferEditor editor
            if (editor.furnitureCatalog.none { it.id == equipmentId }) {
                return@editTransferEditor editor
            }
            val initial = editor.furnitureReplacements[assetId]
                ?: cabin.contents
                    .filter { content ->
                        editor.furnitureCatalog.any { catalog ->
                            catalog.id == content.equipmentId
                        }
                    }
                    .associate { it.equipmentId to it.quantity }
            val updated = if (parsed == 0L) {
                initial - equipmentId
            } else {
                initial + (equipmentId to parsed)
            }
            editor.copy(
                furnitureReplacements = editor.furnitureReplacements + (assetId to updated),
            )
        }
    }

    fun resetTransferFurniture(assetId: String) {
        editTransferEditor { editor ->
            editor.copy(furnitureReplacements = editor.furnitureReplacements - assetId)
        }
    }

    fun closeTransferEditor() {
        mutableState.update { it.copy(transferEditor = null) }
    }

    fun createTransfer(onSaved: () -> Unit) = command {
        val editor = requireNotNull(mutableState.value.transferEditor) {
            "Откройте создание перемещения"
        }
        val sourceWarehouseId = requireWarehouseId()
        val destinationWarehouseId = editor.destinationWarehouseId
        require(destinationWarehouseId.isNotBlank()) { "Выберите склад назначения" }
        require(destinationWarehouseId != sourceWarehouseId) {
            "Склад назначения должен отличаться от склада отправления"
        }
        mutableState.value.requireManagerWarehouseAccess(sourceWarehouseId, "EDIT")
        mutableState.value.requireManagerWarehouseAccess(destinationWarehouseId, "EDIT")
        require(editor.selectedAssetIds.isNotEmpty()) {
            "Выберите хотя бы одну свободную бытовку"
        }
        require(editor.selectedAssetIds.size <= 100) {
            "В одном перемещении может быть не больше 100 бытовок"
        }
        val selected = editor.selectedAssetIds.map { id ->
            editor.candidates.firstOrNull { it.id == id && it.status == "FREE" }
                ?: throw IllegalArgumentException(
                    "Список свободных бытовок изменился. Откройте создание заново",
                )
        }
        val date = runCatching { LocalDate.parse(editor.scheduledDate) }
            .getOrElse { throw IllegalArgumentException("Укажите корректную дату задания") }
        val sourceWarehouse = mutableState.value.warehouses.firstOrNull {
            it.id == sourceWarehouseId
        } ?: throw IllegalStateException("Склад отправления недоступен")
        val sourceBusinessDate = warehouseBusinessDate(sourceWarehouse, clock.instant())
        require(!date.isBefore(sourceBusinessDate)) {
            "Для перемещения укажите дату, начиная с сегодняшней"
        }
        val driver = editor.driverSnapshot.trim().takeIf(String::isNotEmpty)
        require((driver?.length ?: 0) <= 512) {
            "Имя водителя не может быть длиннее 512 символов"
        }
        val created = backend.api.createTransfer(
            idempotencyKey = editor.idempotencyKey,
            request = CreateTransferRequest(
                warehouseId = sourceWarehouseId,
                destinationWarehouseId = destinationWarehouseId,
                driverSnapshot = driver,
                scheduledDate = date.toString(),
                lines = selected.map { item ->
                    TransferLineRequest(item.id, item.version)
                },
                furnitureReplacements = editor.furnitureReplacements
                    .filterKeys(editor.selectedAssetIds::contains)
                    .toSortedMap()
                    .map { (assetId, contents) ->
                        TransferFurnitureReplacementRequest(
                            assetId = assetId,
                            contents = contents
                                .filterValues { it > 0 }
                                .toSortedMap()
                                .map { (equipmentId, quantity) ->
                                    require(editor.furnitureCatalog.any { it.id == equipmentId }) {
                                        "Состав мебели изменился. Откройте создание заново"
                                    }
                                    CabinFurnitureRequirementDto(equipmentId, quantity)
                                },
                        )
                    },
            ),
        )
        closeTransferEditor()
        applyTransferProjection(created)
        message("Перемещение создано")
        onSaved()
    }

    fun openTransfer(documentId: String, onReady: () -> Unit) = command {
        val document = backend.api.transfer(documentId)
        require(document.documentType == "TRANSFER") {
            "Сервис вернул не документ перемещения"
        }
        val readinessFailure: Throwable?
        val readiness = if (document.needsTransferFurnitureReadiness()) {
            val result = runCatching {
                backend.api.transferFurnitureReadiness(document.id)
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
                selectedTransfer = document,
                transferFurnitureReadiness = readiness,
                transferArrivalLineId = null,
                transferPhotoUris = emptyList(),
                transferReadyMedia = emptyList(),
                logisticsAssetLabels = it.logisticsAssetLabels + labels,
            )
        }
        onReady()
        if (readinessFailure != null) {
            message(
                "Не удалось проверить мебель. Перемещение доступно для просмотра, " +
                    "но отправка заблокирована",
            )
        }
    }

    fun closeTransfer() {
        mutableState.update {
            it.copy(
                selectedTransfer = null,
                transferFurnitureReadiness = null,
                transferArrivalLineId = null,
                transferPhotoUris = emptyList(),
                transferReadyMedia = emptyList(),
            )
        }
    }

    fun departTransfer() = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        require(document.lines.isEmpty()) {
            "Перемещение с бытовками отправляется отдельно по каждой бытовке"
        }
        require(document.state == "DRAFT") {
            "Это перемещение сейчас нельзя начать"
        }
        requireTransferFurnitureReady(document)
        val signature = "transfer-depart-document:${document.id}:${document.version}"
        val updated = backend.api.departTransfer(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = commandKeys.logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        applyTransferProjection(updated)
        refreshTransferReadiness(updated)
        message("Перемещение начато")
    }

    fun arriveTransfer() = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        require(document.lines.isEmpty()) {
            "Перемещение с бытовками принимается отдельно по каждой бытовке"
        }
        require(document.state == "IN_TRANSIT") {
            "Прибытие этого перемещения сейчас нельзя подтвердить"
        }
        val signature = "transfer-arrive-document:${document.id}:${document.version}"
        val updated = backend.api.arriveTransfer(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = commandKeys.logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        applyTransferProjection(updated)
        refreshTransferReadiness(updated)
        message("Прибытие перемещения подтверждено")
    }

    fun departTransferLine(lineId: String) = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        val line = document.lines.firstOrNull { it.id == lineId }
            ?: throw IllegalArgumentException("Строка перемещения не найдена")
        require(
            line.state == "PENDING" &&
                (document.state == "DRAFT" || document.state == "DEPARTING"),
        ) {
            "Эту бытовку больше нельзя отправить"
        }
        requireTransferFurnitureReady(document)
        val signature =
            "transfer-depart:${document.id}:${document.version}:${line.id}:${line.version}"
        val updated = backend.api.departTransferLine(
            documentId = document.id,
            lineId = line.id,
            expectedVersion = document.version,
            expectedLineVersion = line.version,
            idempotencyKey = commandKeys.logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        applyTransferProjection(updated)
        refreshTransferReadiness(updated)
        message("Бытовка отправлена")
    }

    fun startTransferArrival(lineId: String, onReady: () -> Unit) = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        val line = document.lines.firstOrNull { it.id == lineId }
            ?: throw IllegalArgumentException("Строка перемещения не найдена")
        require(
            line.state == "DEPARTED" &&
                (document.state == "IN_TRANSIT" || document.state == "ARRIVING"),
        ) {
            "Эту бытовку сейчас нельзя принять"
        }
        val ready = retryMediaReadAfterOwnerProof {
            backend.api.ownerMedia(
                ownerType = "LOGISTICS_TRANSFER",
                documentId = document.id,
                lineId = line.id,
                warehouseId = requireNotNull(document.destinationWarehouseId),
                context = "TRANSFER",
            )
        }.items
            .filter { media -> media.status == "READY" && media.generation > 0 }
            .sortedBy { media -> media.sortOrder }
            .map { media -> MediaReferenceDto(media.id, media.generation) }
        mutableState.update {
            it.copy(
                transferArrivalLineId = line.id,
                transferPhotoUris = emptyList(),
                transferReadyMedia = ready,
            )
        }
        onReady()
    }

    fun closeTransferArrival() {
        mutableState.update {
            it.copy(
                transferArrivalLineId = null,
                transferPhotoUris = emptyList(),
                transferReadyMedia = emptyList(),
            )
        }
    }

    fun addTransferPhoto(uri: String) {
        mutableState.update {
            it.copy(
                transferPhotoUris =
                    if (uri in it.transferPhotoUris) it.transferPhotoUris
                    else it.transferPhotoUris + uri,
            )
        }
    }

    fun removeTransferPhoto(uri: String) {
        mutableState.update { it.copy(transferPhotoUris = it.transferPhotoUris - uri) }
    }

    fun arriveTransferLine(onSaved: () -> Unit) = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        val lineId = requireNotNull(mutableState.value.transferArrivalLineId) {
            "Выберите бытовку для приёмки"
        }
        val line = document.lines.firstOrNull { it.id == lineId }
            ?: throw IllegalArgumentException("Строка перемещения не найдена")
        val localUris = mutableState.value.transferPhotoUris
        val existingMedia = mutableState.value.transferReadyMedia
            .distinctBy(MediaReferenceDto::mediaId)
        require(existingMedia.isNotEmpty() || localUris.isNotEmpty()) {
            "Добавьте хотя бы одну фотографию приёмки"
        }
        require(existingMedia.size + localUris.size <= 20) {
            "Для строки можно приложить не больше 20 фотографий"
        }
        val owner = MediaOwner(
            ownerType = "LOGISTICS_TRANSFER",
            documentId = document.id,
            lineId = line.id,
            warehouseId = requireNotNull(document.destinationWarehouseId),
            context = "TRANSFER",
        )
        backgroundUploads.await().enqueue(
            BackgroundUploadDraft(
                area = BackgroundUploadArea.LOGISTICS,
                title = "Приёмка перемещения",
                subtitle = mutableState.value.logisticsAssetLabels[line.assetId]
                    ?: line.assetId,
                photos = localUris.mapIndexed { index, uri ->
                    PendingBackgroundPhoto(uri = uri, owner = owner, sortOrder = index)
                },
                transferArrival = TransferArrivalUploadCommand(
                    documentId = document.id,
                    lineId = line.id,
                    expectedDocumentVersion = document.version,
                    expectedLineVersion = line.version,
                    existingMedia = existingMedia,
                    idempotencyKey = UUID.randomUUID().toString(),
                ),
            ),
        )
        closeTransferArrival()
        message("Приёмка перемещения добавлена в фоновые загрузки")
        onSaved()
    }

    fun cancelTransfer() = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        require(document.state == "DRAFT") { "Это перемещение больше нельзя отменить" }
        val signature = "transfer-cancel:${document.id}:${document.version}"
        val updated = backend.api.cancelTransfer(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = commandKeys.logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        applyTransferProjection(updated)
        refreshTransferReadiness(updated)
        message("Перемещение отменено")
    }

    fun reconcileTransfer(reason: String) = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        require(
            document.state == "CONFLICT" ||
                document.state == "RECONCILIATION_REQUIRED",
        ) {
            "Сверка для этого перемещения не требуется"
        }
        val normalized = reason.trim()
        require(normalized.isNotEmpty()) { "Укажите причину сверки" }
        require(normalized.length <= 500) {
            "Причина сверки не может быть длиннее 500 символов"
        }
        val signature = "transfer-reconcile:${document.id}:${document.version}:$normalized"
        val updated = backend.api.reconcileTransfer(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = commandKeys.logisticsCommandKey(signature),
            request = ReconcileLogisticsRequest(normalized),
        )
        commandKeys.complete(signature)
        applyTransferProjection(updated)
        refreshTransferReadiness(updated)
        message("Сверка перемещения выполнена")
    }

    private suspend fun refreshTransfers() {
        val warehouseId = requireWarehouseId()
        val documents = backend.api.transfers(warehouseId)
            .filter { it.documentType == "TRANSFER" }
        val labels = backend.resolveLogisticsAssetLabels(documents)
        if (mutableState.value.selectedWarehouseId != warehouseId) return
        mutableState.update {
            it.copy(
                transfers = documents,
                logisticsAssetLabels = it.logisticsAssetLabels + labels,
            )
        }
    }

    private fun applyTransferProjection(document: LogisticsDocumentDto) {
        require(document.documentType == "TRANSFER") {
            "Сервис вернул не документ перемещения"
        }
        mutableState.update { current ->
            current.copy(
                transfers = current.transfers.replaceLogisticsDocument(document),
                selectedTransfer =
                    if (current.selectedTransfer?.id == document.id) document
                    else current.selectedTransfer,
            )
        }
    }

    private suspend fun refreshTransferReadiness(document: LogisticsDocumentDto) {
        if (mutableState.value.selectedTransfer?.id == document.id) {
            mutableState.update { it.copy(transferFurnitureReadiness = null) }
        }
        val readiness = if (document.needsTransferFurnitureReadiness()) {
            backend.api.transferFurnitureReadiness(document.id)
        } else {
            null
        }
        if (mutableState.value.selectedTransfer?.id == document.id) {
            mutableState.update { it.copy(transferFurnitureReadiness = readiness) }
        }
    }

    private fun requireTransferFurnitureReady(document: LogisticsDocumentDto) {
        if (!document.needsTransferFurnitureReadiness()) return
        val state = mutableState.value.transferFurnitureReadiness?.state
        require(state == "NOT_REQUIRED" || state == "READY") {
            "Сначала завершите задания по мебели"
        }
    }

    private fun requireTransferManageAccess(document: LogisticsDocumentDto) {
        mutableState.value.requireManagerWarehouseAccess(document.warehouseId, "MANAGE")
        mutableState.value.requireManagerWarehouseAccess(
            requireNotNull(document.destinationWarehouseId) {
                "Сервис не вернул склад назначения"
            },
            "MANAGE",
        )
    }
}
