package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.CreateCabinFurnitureTaskRequest
import dev.buhanzaz.rwms.manager.network.EstimateLineDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairStageDto
import dev.buhanzaz.rwms.manager.network.ReworkCandidateDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.ui.components.isManagerVideoUri
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */

/**
 * Coordinates estimate, repair, rework and furniture editor transitions. It keeps editor drafts
 * in the shared state flow and uses narrowly scoped catalog, media and read ports.
 */
internal class ManagerMaintenanceEditorCoordinator(
    private val runtime: ManagerCommandRuntime,
    private val backend: RwmsBackend,
    private val commandKeys: StableCommandKeys,
    private val catalogAccess: ManagerMaintenanceCatalogAccess,
    private val maintenanceAssetRead: ManagerMaintenanceAssetReadPort,
    private val maintenanceRefresh: ManagerMaintenanceRefreshPort,
    private val media: ManagerMediaPort,
) : ManagerMaintenanceEditorResetPort, ManagerMaintenanceEditorClosePort {
    private val mutableState
        get() = runtime.mutableState
    private val viewModelScope
        get() = runtime.scope
    private var assetSearchGeneration = 0L

    /** Invalidates in-flight asset lookups after a warehouse or session transition. */
    override fun invalidateAssetSearch() {
        assetSearchGeneration += 1
    }

    private fun command(block: suspend () -> Unit) = runtime.command(block)

    private suspend fun handleFailure(failure: Throwable) = runtime.handleFailure(failure)

    private fun message(value: String) = runtime.message(value)

    private fun requireWarehouseId(): String = runtime.requireWarehouseId()

    private suspend fun ensureMaintenanceCatalog(warehouseId: String) =
        catalogAccess.ensureMaintenanceCatalog(warehouseId)

    private suspend fun restoreMaintenanceCatalogFromDisk(warehouseId: String): Boolean =
        catalogAccess.restoreMaintenanceCatalogFromDisk(warehouseId)

    private suspend fun refreshMaintenance(force: Boolean = false) =
        maintenanceRefresh.refreshMaintenance(force)

    private suspend fun refreshRepairTaskBoards(force: Boolean = false) =
        maintenanceRefresh.refreshRepairTaskBoards(force)

    private suspend fun maintenanceRentalItem(
        rentalItemId: String,
        warehouseId: String,
    ): RentalItemDto = maintenanceAssetRead.maintenanceRentalItem(rentalItemId, warehouseId)

    private suspend fun loadMaintenancePhotoUris(
        references: List<MediaReferenceDto>,
        scopes: List<MaintenanceMediaScope>,
        warehouseId: String,
    ): Map<String, String> = media.loadMaintenancePhotoUris(references, scopes, warehouseId)

    private fun requireWarehouseAccess(warehouseId: String, level: String) {
        val user = requireNotNull(mutableState.value.currentUser) {
            "Не удалось проверить права пользователя"
        }
        require(WarehouseAccessPolicy.hasAccess(user, warehouseId, level)) {
            "Недостаточно прав для операции на складе"
        }
    }

    fun startMaintenanceEditor(
        mode: MaintenanceEditorMode,
        onReady: () -> Unit,
    ) = command {
        val warehouseId = requireWarehouseId()
        ensureMaintenanceCatalog(warehouseId)
        refreshRepairTaskBoards()
        assetSearchGeneration += 1
        mutableState.update {
            it.withStartedMaintenanceEditor(newMaintenanceEditor(mode))
        }
        onReady()
    }

    fun openEstimateEditor(id: String, onReady: () -> Unit) = command {
        val warehouseId = requireWarehouseId()
        ensureMaintenanceCatalog(warehouseId)
        refreshRepairTaskBoards()
        val estimate = backend.api.estimate(id, warehouseId)
        val linkedRepair = estimate.repairId?.let { repairId ->
            backend.api.repair(repairId, warehouseId)
        }
        val canAmend = estimate.lifecycle == "COMPLETED" &&
            linkedRepair?.executionState in PRE_START_REPAIR_STATES
        val asset = maintenanceRentalItem(estimate.rentalItemId, warehouseId)
        val revision = estimate.revisions.firstOrNull { it.revision == estimate.currentRevision }
            ?: throw IllegalStateException("Сервис не вернул текущую редакцию сметы")
        val workLineMedia = revision.lines
            .asSequence()
            .filter { line -> line.lineType == "WORK" }
            .flatMap { line -> line.mediaReferences.asSequence() }
            .toList()
        val readyPhotoUris = loadMaintenancePhotoUris(
            references = estimate.mediaReferences + workLineMedia,
            scopes = buildList {
                add(
                    MaintenanceMediaScope(
                        ownerType = "MAINTENANCE_ESTIMATE",
                        ownerId = estimate.id,
                        context = "ESTIMATE",
                    ),
                )
                linkedRepair?.let { repair ->
                    add(
                        MaintenanceMediaScope(
                            ownerType = "MAINTENANCE_REPAIR",
                            ownerId = repair.id,
                            context = "REPAIR",
                        ),
                    )
                }
            },
            warehouseId = warehouseId,
        )
        val routingByLineId = revision.plan.flatMap { stage ->
            stage.includedLineIds.map { lineId -> lineId to stage.routing }
        }.toMap()
        mutableState.update { current ->
            current.copy(
                maintenanceAssetLabels = current.maintenanceAssetLabels + (asset.id to asset.number),
                maintenanceEditor = MaintenanceEditorState(
                    mode = MaintenanceEditorMode.ESTIMATE,
                    entityId = estimate.id,
                    expectedVersion = estimate.version,
                    readOnly = estimate.lifecycle != "DRAFT" && !canAmend,
                    selectedAsset = asset,
                    dispatchDate = revision.dispatchDate,
                    sourceParty = revision.sourceParty.orEmpty(),
                    lines = revision.lines.map { line ->
                        line.toMaintenanceLineEditor(
                            customRouting = routingByLineId[line.id],
                        )
                    },
                    photoUris = emptyList(),
                    readyMedia = estimate.mediaReferences,
                    readyPhotoUris = readyPhotoUris,
                    priority = linkedRepair?.priority ?: DEFAULT_MAINTENANCE_PRIORITY,
                    forceCapitalRepair = revision.forceCapitalRepair,
                    movementToRepair = linkedRepair?.movementToRepair ?: false,
                    logisticsPlanningMode = linkedRepair?.logisticsPlanningMode,
                    logisticsScheduledDate = linkedRepair?.logisticsScheduledDate,
                    step = 1,
                    stages = revision.plan
                        .filter { stage -> stage.kind == "REPAIR_WORK" }
                        .map { stage -> stage.toMaintenanceStageEditor() },
                    documentState = estimate.lifecycle,
                    linkedRepairExpectedVersion = linkedRepair?.version,
                    coverPhotoKey = estimate.coverMediaId
                        ?.let(::maintenanceReadyPhotoKey)
                        ?: maintenanceInitialCoverPhotoKey(estimate.mediaReferences),
                ),
            )
        }
        onReady()
    }

    fun openRepairEditor(id: String, onReady: () -> Unit) = command {
        val warehouseId = requireWarehouseId()
        ensureMaintenanceCatalog(warehouseId)
        refreshRepairTaskBoards()
        val repair = backend.api.repair(id, warehouseId)
        val linkedEstimate = repair.estimateId?.let { estimateId ->
            backend.api.estimate(estimateId, warehouseId)
        }
        val asset = maintenanceRentalItem(repair.rentalItemId, warehouseId)
        val content = repairEditorContent(repair)
        val reworkCandidates = if (repair.kind == "REWORK") {
            backend.api.reworkCandidates(repair.sourceRepairId ?: repair.id, warehouseId).items
        } else {
            emptyList()
        }
        val readyReferences = (repair.mediaReferences + linkedEstimate?.mediaReferences.orEmpty())
            .distinctBy(MediaReferenceDto::mediaId)
        val workLineMedia = content.lines
            .asSequence()
            .filter { line -> line.lineType == "WORK" }
            .flatMap { line -> line.mediaReferences.asSequence() }
            .toList()
        val readyPhotoUris = loadMaintenancePhotoUris(
            references = readyReferences + workLineMedia,
            scopes = buildList {
                add(
                    MaintenanceMediaScope(
                        ownerType = "MAINTENANCE_REPAIR",
                        ownerId = repair.id,
                        context = "REPAIR",
                    ),
                )
                repair.estimateId?.let { estimateId ->
                    add(
                        MaintenanceMediaScope(
                            ownerType = "MAINTENANCE_ESTIMATE",
                            ownerId = estimateId,
                            context = "ESTIMATE",
                        ),
                    )
                }
                repair.sourceRepairId?.let { sourceId ->
                    add(
                        MaintenanceMediaScope(
                            ownerType = "MAINTENANCE_REPAIR",
                            ownerId = sourceId,
                            context = "REPAIR",
                        ),
                    )
                }
            },
            warehouseId = warehouseId,
        )
        mutableState.update { current ->
            current.copy(
                maintenanceAssetLabels = current.maintenanceAssetLabels + (asset.id to asset.number),
                maintenanceEditor = MaintenanceEditorState(
                    mode = MaintenanceEditorMode.REPAIR,
                    entityId = repair.id,
                    expectedVersion = repair.version,
                    readOnly = repair.executionState !in PRE_START_REPAIR_STATES,
                    selectedAsset = asset,
                    dispatchDate = repair.dispatchDate,
                    sourceParty = repair.sourceParty.orEmpty(),
                    lines = content.lines,
                    photoUris = emptyList(),
                    readyMedia = readyReferences,
                    readyPhotoUris = readyPhotoUris,
                    priority = repair.priority,
                    forceCapitalRepair = repair.forceCapitalRepair,
                    movementToRepair = repair.movementToRepair,
                    logisticsPlanningMode = repair.logisticsPlanningMode,
                    logisticsScheduledDate = repair.logisticsScheduledDate,
                    step = 1,
                    stages = content.stages,
                    documentState = repair.executionState,
                    repairKind = repair.kind,
                    sourceRepairId = repair.sourceRepairId,
                    coverPhotoKey = repair.coverMediaId
                        ?.let(::maintenanceReadyPhotoKey)
                        ?: maintenanceInitialCoverPhotoKey(readyReferences),
                    reworkCandidates = reworkCandidates,
                ),
            )
        }
        onReady()
    }

    fun startReworkEditor(sourceRepairId: String, onReady: () -> Unit) =
        startReworkEditor(
            sourceRepairId = sourceRepairId,
            selectedSourceLineId = null,
            onReady = onReady,
        )

    fun startReworkEditorForLine(
        sourceRepairId: String,
        sourceLineId: String,
        onReady: () -> Unit,
    ) = startReworkEditor(
        sourceRepairId = sourceRepairId,
        selectedSourceLineId = sourceLineId,
        onReady = onReady,
    )

    private fun startReworkEditor(
        sourceRepairId: String,
        selectedSourceLineId: String?,
        onReady: () -> Unit,
    ) = command {
        val warehouseId = requireWarehouseId()
        ensureMaintenanceCatalog(warehouseId)
        refreshRepairTaskBoards()
        val source = backend.api.repair(sourceRepairId, warehouseId)
        if (source.executionState != "COMPLETED" || source.acceptanceState != "PENDING") {
            throw IllegalStateException("Ремонт больше не ожидает приёмки")
        }
        val asset = maintenanceRentalItem(source.rentalItemId, warehouseId)
        val candidates = backend.api.reworkCandidates(sourceRepairId, warehouseId).items
        if (candidates.isEmpty()) {
            throw IllegalStateException("В исходном ремонте нет плана для доработки")
        }
        val selectedCandidate = selectedSourceLineId?.let { sourceLineId ->
            candidates.singleOrNull { candidate ->
                candidate.sourceLineId == sourceLineId && candidate.line.lineType == "WORK"
            } ?: throw IllegalStateException(
                "Выбранная работа больше не доступна для доработки",
            )
        }
        val editor = MaintenanceEditorState(
            mode = MaintenanceEditorMode.REPAIR,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = asset,
            dispatchDate = LocalDate.now().toString(),
            sourceParty = source.sourceParty.orEmpty(),
            lines = emptyList(),
            photoUris = emptyList(),
            readyMedia = emptyList(),
            priority = source.priority,
            forceCapitalRepair = source.forceCapitalRepair,
            step = 1,
            stages = emptyList(),
            repairKind = "REWORK",
            sourceRepairId = source.id,
            sourceRepairExpectedVersion = source.version,
            reworkCandidates = candidates,
        )
        assetSearchGeneration += 1
        mutableState.update { current ->
            current.copy(
                acceptanceEditor = null,
                acceptanceGallery = null,
                maintenanceAssetLabels = current.maintenanceAssetLabels + (asset.id to asset.number),
                maintenanceEditor = selectedCandidate?.let(editor::toggleReworkCandidate) ?: editor,
            )
        }
        onReady()
    }

    override fun closeMaintenanceEditor() {
        assetSearchGeneration += 1
        mutableState.update {
            it.withClosedMaintenanceEditor().copy(maintenanceFurnitureEditor = null)
        }
        mutableState.value.selectedWarehouseId?.let { warehouseId ->
            viewModelScope.launch {
                restoreMaintenanceCatalogFromDisk(warehouseId)
            }
        }
    }

    /**
     * Loads the authoritative cabin contents and warehouse equipment catalogue for either
     * maintenance mode. The editor never derives a movement itself: it only prepares the
     * complete desired composition for the logistics command.
     */
    fun openMaintenanceFurniture(onReady: () -> Unit) = command {
        val maintenanceEditor = requireMaintenanceEditor()
        val selectedAsset = requireNotNull(maintenanceEditor.selectedAsset) {
            "Сначала выберите бытовку"
        }
        val warehouseId = requireWarehouseId()
        requireWarehouseAccess(warehouseId, "EDIT")
        require(selectedAsset.warehouseId == warehouseId) {
            "Выбранная бытовка относится к другому складу"
        }

        val (rentalItem, furnitureCatalog) = coroutineScope {
            val rentalItemRequest = async { backend.api.rentalItem(selectedAsset.id) }
            val catalogRequest = async {
                backend.api.equipment(warehouseId)
                    .map { it.equipment }
                    .maintenanceFurnitureCatalog()
            }
            rentalItemRequest.await() to catalogRequest.await()
        }
        require(rentalItem.warehouseId == warehouseId) {
            "Сервис вернул бытовку другого склада"
        }
        val currentEditor = mutableState.value.maintenanceEditor
        require(
            currentEditor?.mode == maintenanceEditor.mode &&
                currentEditor.selectedAsset?.id == selectedAsset.id,
        ) {
            "Смета или ремонт изменены. Откройте наполнение снова"
        }
        mutableState.update { current ->
            current.copy(
                maintenanceFurnitureEditor = MaintenanceFurnitureEditorState(
                    mode = maintenanceEditor.mode,
                    rentalItem = rentalItem,
                    furnitureCatalog = furnitureCatalog,
                    quantities = rentalItem.maintenanceFurnitureInitialQuantities(furnitureCatalog),
                ),
            )
        }
        onReady()
    }

    fun closeMaintenanceFurniture() {
        mutableState.update { it.copy(maintenanceFurnitureEditor = null) }
    }

    fun updateMaintenanceFurnitureQuantity(equipmentId: String, quantity: String) {
        mutableState.update { current ->
            val editor = current.maintenanceFurnitureEditor
            if (editor == null || editor.furnitureCatalog.none { it.id == equipmentId }) {
                current
            } else {
                current.copy(
                    maintenanceFurnitureEditor = editor.copy(
                        quantities = editor.quantities + (equipmentId to quantity),
                    ),
                )
            }
        }
    }

    fun completeMaintenanceFurniture(
        action: MaintenanceFurnitureCompletionAction,
        onCompleted: () -> Unit,
    ) = command {
        val furnitureEditor = requireNotNull(mutableState.value.maintenanceFurnitureEditor) {
            "Откройте наполнение бытовки"
        }
        val maintenanceEditor = requireMaintenanceEditor()
        require(
            maintenanceEditor.mode == furnitureEditor.mode &&
                maintenanceEditor.selectedAsset?.id == furnitureEditor.rentalItem.id,
        ) {
            "Смета или ремонт изменены. Откройте наполнение снова"
        }
        val warehouseId = requireWarehouseId()
        requireWarehouseAccess(warehouseId, "EDIT")
        require(furnitureEditor.rentalItem.warehouseId == warehouseId) {
            "Бытовка относится к другому складу"
        }
        val scheduledDate = furnitureEditor.scheduledDate.trim()
        maintenanceFurnitureScheduledDateValidationError(scheduledDate)?.let { error ->
            throw IllegalArgumentException(error)
        }
        val contents = when (action) {
            MaintenanceFurnitureCompletionAction.KEEP_IN_CABIN -> {
                furnitureEditor.maintenanceFurnitureCompositionValidationError()?.let { error ->
                    throw IllegalArgumentException(error)
                }
                furnitureEditor.maintenanceFurnitureDesiredContents()
            }

            MaintenanceFurnitureCompletionAction.MOVE_TO_STOCK -> emptyList()
        }
        val signature = buildString {
            append("maintenance-furniture:")
            append(furnitureEditor.mode.name)
            append(':')
            append(furnitureEditor.rentalItem.id)
            append(':')
            append(scheduledDate)
            append(':')
            append(action.name)
            contents.forEach { requirement ->
                append(':')
                append(requirement.equipmentId)
                append('=')
                append(requirement.quantity)
            }
        }
        val result = backend.api.createCabinFurnitureTask(
            rentalItemId = furnitureEditor.rentalItem.id,
            idempotencyKey = commandKeys.key(signature),
            request = CreateCabinFurnitureTaskRequest(
                warehouseId = warehouseId,
                scheduledDate = scheduledDate,
                contents = contents,
            ),
        )
        require(result.rentalItemId == furnitureEditor.rentalItem.id) {
            "Сервис логистики вернул задание другой бытовки"
        }
        commandKeys.complete(signature)
        mutableState.update { current ->
            val active = current.maintenanceFurnitureEditor
            if (active?.rentalItem?.id == furnitureEditor.rentalItem.id &&
                active.mode == furnitureEditor.mode
            ) {
                current.copy(maintenanceFurnitureEditor = null)
            } else {
                current
            }
        }
        message(
            if (result.taskId == null) {
                "Наполнение бытовки уже соответствует выбранному составу"
            } else {
                "Задание на изменение наполнения создано"
            },
        )
        onCompleted()
    }

    fun editMaintenance(update: (MaintenanceEditorState) -> MaintenanceEditorState) {
        mutableState.update { current ->
            val editor = current.maintenanceEditor
            if (editor == null) {
                current
            } else if (editor.readOnly) {
                // Started or closed records stay immutable, but the wizard remains navigable.
                current.copy(
                    maintenanceEditor = readOnlyMaintenanceEditorStepChange(editor, update(editor)),
                )
            } else {
                val edited = preserveMaintenanceImmutableFields(
                    original = editor,
                    candidate = update(editor).copy(
                        submitIdempotencyKey = null,
                        amendmentIdempotencyKey = null,
                    ),
                )
                current.copy(
                    maintenanceEditor = normalizeMaintenanceEditor(
                        normalizeReworkEditorLines(edited),
                    ),
                )
            }
        }
    }

    fun selectMaintenanceAsset(item: RentalItemDto) = command {
        val warehouseId = requireWarehouseId()
        val editor = requireMaintenanceEditor()
        if (item.warehouseId != warehouseId) {
            throw IllegalArgumentException("Выберите бытовку текущего склада")
        }
        if (editor.entityId != null) {
            throw IllegalStateException("Нельзя изменить бытовку уже сохранённого документа")
        }
        if (editor.readOnly) return@command
        if (editor.mode == MaintenanceEditorMode.ESTIMATE && item.status != "AFTER_RENT") {
            throw IllegalArgumentException("Для сметы доступна только бытовка после возврата")
        }
        if (editor.mode == MaintenanceEditorMode.REPAIR &&
            item.status in maintenanceExcludedRentalItemStatuses(MaintenanceEditorMode.REPAIR)
        ) {
            throw IllegalArgumentException("Эта бытовка недоступна для прямого ремонта")
        }

        // The picker is not allowed to rely on a stale browser-owned tenant value. For an
        // estimate, retrieve the current return documents again at the moment of selection.
        val returnMetadata = if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
            latestMaintenanceReturnMetadata(backend.api.returns(warehouseId), item.id)
        } else {
            null
        }
        mutableState.update { current ->
            val currentEditor = current.maintenanceEditor
            if (currentEditor == null || currentEditor.entityId != null || currentEditor.readOnly) {
                current
            } else {
                val selectedEditor = when (currentEditor.mode) {
                    MaintenanceEditorMode.ESTIMATE -> currentEditor.copy(
                        selectedAsset = item,
                        dispatchDate = returnMetadata?.dispatchDate ?: currentEditor.dispatchDate,
                        sourceParty = returnMetadata?.sourceParty ?: currentEditor.sourceParty,
                        submitIdempotencyKey = null,
                    )

                    MaintenanceEditorMode.REPAIR -> currentEditor.copy(
                        selectedAsset = item,
                        dispatchDate = LocalDate.now().toString(),
                        sourceParty = DIRECT_REPAIR_SOURCE_PARTY,
                        submitIdempotencyKey = null,
                    )
                }.copy(step = 2)
                current.copy(
                    message = null,
                    maintenanceAssetLabels = current.maintenanceAssetLabels + (item.id to item.number),
                    maintenanceReturnMetadata = if (returnMetadata == null) {
                        current.maintenanceReturnMetadata - item.id
                    } else {
                        current.maintenanceReturnMetadata + (item.id to returnMetadata)
                    },
                    maintenanceEditor = normalizeMaintenanceEditor(selectedEditor),
                )
            }
        }
    }

    fun addMaintenanceCatalogNodes(
        nodes: List<CatalogNodeDto>,
        quantity: String,
        comment: String,
        existingWorkLineId: String? = null,
        photoUris: List<String> = emptyList(),
        mediaReferences: List<MediaReferenceDto> = emptyList(),
    ): Boolean {
        val activeEditor = mutableState.value.maintenanceEditor ?: return false
        val canonicalQuantity = runCatching {
            canonicalMaintenanceQuantity(quantity)
        }.getOrElse { failure ->
            message(failure.message ?: "Проверьте количество")
            return false
        }
        val canonicalComment = comment.trim()
        if (canonicalComment.length > 2_000) {
            message("Комментарий строки не может быть длиннее 2000 символов")
            return false
        }
        val editorMode = activeEditor.mode
        val canonicalNodes = nodes
            .mapNotNull { node -> catalogAccess.nodesById[node.id] }
            .filter(CatalogNodeDto::isOperationalEstimateNode)
            .filter { node ->
                node.isAvailableForMaintenanceMode(
                    mode = editorMode,
                    nodesById = catalogAccess.nodesById,
                )
            }
            .distinctBy(CatalogNodeDto::id)
        if (canonicalNodes.isEmpty()) {
            message("Выбранные позиции больше недоступны в активном каталоге")
            return false
        }
        if ((photoUris.isNotEmpty() || mediaReferences.isNotEmpty()) &&
            canonicalNodes.count { node -> node.nodeType == "WORK" } != 1
        ) {
            message("Фото можно прикрепить только к одной выбранной работе")
            return false
        }
        if (!activeEditor.hasSelectedCatalogWork(
                nodes = canonicalNodes,
                lineId = existingWorkLineId,
            )
        ) {
            message("Выбранная работа уже изменилась. Выберите её снова.")
            return false
        }

        editMaintenance { editor ->
            val updated = applyMaintenanceCatalogNodes(
                editor = editor,
                nodes = canonicalNodes,
                quantity = canonicalQuantity,
                comment = canonicalComment,
                existingWorkLineId = existingWorkLineId,
                photoUris = photoUris,
                mediaReferences = mediaReferences,
            )
            val movedMediaIds = mediaReferences.mapTo(mutableSetOf(), MediaReferenceDto::mediaId)
            if (movedMediaIds.isEmpty()) {
                updated
            } else {
                updated.copy(
                    readyMedia = updated.readyMedia.filterNot { reference ->
                        reference.mediaId in movedMediaIds
                    },
                    coverPhotoKey = updated.coverPhotoKey.takeUnless { key ->
                        key?.removePrefix("media:") in movedMediaIds
                    },
                )
            }
        }
        return true
    }

    fun toggleReworkCandidate(candidate: ReworkCandidateDto) {
        editMaintenance { editor ->
            editor.toggleReworkCandidate(candidate)
        }
    }

    fun addMaintenancePhoto(uri: String) {
        editMaintenance { editor ->
            if (uri in editor.photoUris) editor
            else editor.copy(photoUris = editor.photoUris + uri)
        }
    }

    fun removeMaintenancePhoto(uri: String) {
        editMaintenance { editor ->
            removeMaintenanceLocalPhoto(editor, uri)
        }
    }

    fun selectMaintenanceCoverPhoto(photoKey: String) {
        val current = mutableState.value.maintenanceEditor ?: return
        val mediaId = photoKey.removePrefix("media:").takeIf { photoKey.startsWith("media:") }
        val uri = current.photoUris.firstOrNull { candidate ->
            maintenanceLocalPhotoKey(candidate) == photoKey
        } ?: mediaId?.let(current.readyPhotoUris::get)
        if (uri == null) {
            message("Медиафайл ещё не доступен для выбора обложки")
            return
        }
        if (isManagerVideoUri(uri)) {
            message("Обложкой может быть только фотография")
            return
        }
        editMaintenance { editor ->
            val valid = editor.photoUris.any { uri ->
                maintenanceLocalPhotoKey(uri) == photoKey
            } || editor.readyMedia.any { media ->
                maintenanceReadyPhotoKey(media.mediaId) == photoKey
            }
            if (valid) editor.copy(coverPhotoKey = photoKey) else editor
        }
    }

    fun updateAssetSearch(value: String) {
        assetSearchGeneration += 1
        mutableState.update { state ->
            state.copy(
                assetSearch = value,
                assetSearchResults = if (value.isBlank()) emptyList() else state.assetSearchResults,
                assetSearchBusy = false,
                assetSearchCompletedQuery = null,
                assetSearchFailedQuery = null,
            )
        }
    }

    fun searchAssets() {
        val editor = mutableState.value.maintenanceEditor ?: return
        val query = mutableState.value.assetSearch.trim()
        val warehouseId = mutableState.value.selectedWarehouseId ?: return
        val requestGeneration = ++assetSearchGeneration
        mutableState.update {
            it.copy(
                assetSearchBusy = true,
                assetSearchCompletedQuery = null,
                assetSearchFailedQuery = null,
            )
        }
        viewModelScope.launch {
            try {
                val items = backend.api.rentalItems(
                    warehouseId = warehouseId,
                    size = 200,
                    search = query.takeIf(String::isNotEmpty),
                    excludeStatuses = maintenanceExcludedRentalItemStatuses(editor.mode),
                ).content
                val returnMetadata = if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                    val documents = backend.api.returns(warehouseId)
                    items.mapNotNull { item ->
                        latestMaintenanceReturnMetadata(documents, item.id)?.let { metadata ->
                            item.id to metadata
                        }
                    }.toMap()
                } else {
                    emptyMap()
                }
                mutableState.update { current ->
                    if (requestGeneration != assetSearchGeneration ||
                        current.selectedWarehouseId != warehouseId ||
                        current.maintenanceEditor?.mode != editor.mode ||
                        current.assetSearch.trim() != query
                    ) {
                        current
                    } else {
                        current.copy(
                            assetSearchResults = items,
                            assetSearchCompletedQuery = query,
                            assetSearchFailedQuery = null,
                            maintenanceReturnMetadata =
                                if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                                    current.maintenanceReturnMetadata + returnMetadata
                                } else {
                                    current.maintenanceReturnMetadata
                                },
                        )
                    }
                }
            } catch (failure: Throwable) {
                if (requestGeneration == assetSearchGeneration) {
                    mutableState.update { current ->
                        if (current.selectedWarehouseId == warehouseId &&
                            current.maintenanceEditor?.mode == editor.mode &&
                            current.assetSearch.trim() == query
                        ) {
                            current.copy(assetSearchFailedQuery = query)
                        } else {
                            current
                        }
                    }
                    handleFailure(failure)
                }
            } finally {
                mutableState.update { current ->
                    if (requestGeneration == assetSearchGeneration) {
                        current.copy(assetSearchBusy = false)
                    } else {
                        current
                    }
                }
            }
        }
    }

    /** Editable lines and stages reconstructed from one authoritative repair projection. */
    private data class RepairEditorContent(
        val lines: List<MaintenanceLineEditorState>,
        val stages: List<MaintenanceStageEditorState>,
    )

    private fun repairEditorContent(repair: RepairDto): RepairEditorContent {
        val stages = repair.plan.stages
            .asSequence()
            .filter { stage -> stage.kind == "REPAIR_WORK" }
            .sortedBy(RepairStageDto::order)
            .toList()
        val linesById = linkedMapOf<String, EstimateLineDto>()
        val routingByLineId = linkedMapOf<String, RoutingSnapshotDto>()
        stages.forEach { stage ->
            (stage.workLines + stage.materialLines).forEach { line ->
                linesById.putIfAbsent(line.id, line)
                routingByLineId.putIfAbsent(line.id, stage.routing)
            }
        }
        return RepairEditorContent(
            lines = linesById.values.map { line ->
                line.toMaintenanceLineEditor(customRouting = routingByLineId[line.id])
            },
            stages = stages.map(RepairStageDto::toMaintenanceStageEditor),
        )
    }

    private fun newMaintenanceEditor(mode: MaintenanceEditorMode): MaintenanceEditorState =
        MaintenanceEditorState(
            mode = mode,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = null,
            dispatchDate = LocalDate.now().toString(),
            sourceParty = if (mode == MaintenanceEditorMode.REPAIR) {
                DIRECT_REPAIR_SOURCE_PARTY
            } else {
                ""
            },
            lines = emptyList(),
            photoUris = emptyList(),
            readyMedia = emptyList(),
            priority = DEFAULT_MAINTENANCE_PRIORITY,
            step = 1,
        )

    private fun requireMaintenanceEditor(): MaintenanceEditorState =
        requireNotNull(mutableState.value.maintenanceEditor) {
            "Откройте смету или ремонт"
        }

    private fun normalizeMaintenanceEditor(editor: MaintenanceEditorState): MaintenanceEditorState =
        normalizeMaintenanceEditor(
            editor = editor,
            catalogNodesById = catalogAccess.nodesById,
            catalogLinks = mutableState.value.maintenanceCatalogLinks,
        )
}
