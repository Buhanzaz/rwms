package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.CabinFurnitureRequirementDto
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeSnapshotDto
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import dev.buhanzaz.rwms.manager.network.EstimateLineDto
import dev.buhanzaz.rwms.manager.network.EstimateLineInputDto
import dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanLineDto
import dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanStageDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanLineInputDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanSelectionDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanStageSelectionDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.PlanStageInputDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RepairStageDto
import dev.buhanzaz.rwms.manager.network.ReworkCandidateDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * Pure maintenance, catalog and furniture reducers. Workflow coordinators call these functions,
 * while screen imports remain stable in the parent UI package.
 */

internal fun List<EquipmentCatalogItemDto>.maintenanceFurnitureCatalog(): List<EquipmentCatalogItemDto> =
    filter { equipment -> equipment.active && equipment.category == "FURNITURE" }
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, EquipmentCatalogItemDto::name)
                .thenBy(EquipmentCatalogItemDto::id),
        )

internal fun RentalItemDto.maintenanceFurnitureInitialQuantities(
    furnitureCatalog: List<EquipmentCatalogItemDto>,
): Map<String, String> {
    val currentByEquipmentId = linkedMapOf<String, Long>()
    contents.forEach { content ->
        if (content.quantity > 0) {
            currentByEquipmentId[content.equipmentId] =
                (currentByEquipmentId[content.equipmentId] ?: 0L) + content.quantity
        }
    }
    return furnitureCatalog.associate { equipment ->
        equipment.id to (currentByEquipmentId[equipment.id] ?: 0L).toString()
    }
}

internal fun MaintenanceFurnitureEditorState.maintenanceFurnitureCompositionValidationError(): String? {
    var nonZeroCount = 0
    for (equipment in furnitureCatalog) {
        val text = quantities[equipment.id]?.trim().orEmpty()
        if (text.isEmpty() || text.any { character -> !character.isDigit() }) {
            return "Количество для «${equipment.name}» должно быть целым числом"
        }
        val quantity = text.toLongOrNull()
            ?: return "Количество для «${equipment.name}» слишком велико"
        if (quantity > 0) nonZeroCount += 1
    }
    return if (nonZeroCount > 100) {
        "В составе бытовки может быть не больше 100 позиций"
    } else {
        null
    }
}

internal fun MaintenanceFurnitureEditorState.maintenanceFurnitureDesiredContents():
    List<CabinFurnitureRequirementDto> {
    check(maintenanceFurnitureCompositionValidationError() == null) {
        "Состав мебели заполнен некорректно"
    }
    return furnitureCatalog.mapNotNull { equipment ->
        val quantity = requireNotNull(quantities[equipment.id])
            .trim()
            .toLong()
        if (quantity > 0) CabinFurnitureRequirementDto(equipment.id, quantity) else null
    }
}

/** The disposition question only makes sense when the operator actually recorded furniture. */
internal fun MaintenanceFurnitureEditorState.hasObservedFurniture(): Boolean =
    furnitureCatalog.any { equipment ->
        quantities[equipment.id]?.trim()?.toLongOrNull()?.let { quantity -> quantity > 0L } == true
    }

internal fun maintenanceFurnitureScheduledDateValidationError(scheduledDate: String): String? =
    if (runCatching { LocalDate.parse(scheduledDate) }.isSuccess) {
        null
    } else {
        "Укажите корректную дату задания"
    }

internal fun ManagerUiState.withStartedMaintenanceEditor(
    editor: MaintenanceEditorState,
): ManagerUiState = copy(
    maintenanceEditor = editor,
    maintenanceFurnitureEditor = null,
    assetSearch = "",
    assetSearchResults = emptyList(),
    assetSearchBusy = false,
    assetSearchCompletedQuery = null,
    assetSearchFailedQuery = null,
)

internal fun ManagerUiState.withClosedMaintenanceEditor(): ManagerUiState = copy(
    maintenanceEditor = null,
    maintenanceFurnitureEditor = null,
    assetSearch = "",
    assetSearchResults = emptyList(),
    assetSearchBusy = false,
    assetSearchCompletedQuery = null,
    assetSearchFailedQuery = null,
)

internal fun readOnlyMaintenanceEditorStepChange(
    original: MaintenanceEditorState,
    candidate: MaintenanceEditorState,
): MaintenanceEditorState = original.copy(step = candidate.step)

internal fun preserveMaintenanceImmutableFields(
    original: MaintenanceEditorState,
    candidate: MaintenanceEditorState,
): MaintenanceEditorState {
    var preserved = candidate.copy(
        entityId = original.entityId,
        expectedVersion = original.expectedVersion,
        readOnly = original.readOnly,
        createIdempotencyKey = original.createIdempotencyKey,
        documentState = original.documentState,
        linkedRepairExpectedVersion = original.linkedRepairExpectedVersion,
        repairKind = original.repairKind,
        sourceRepairId = original.sourceRepairId,
        sourceRepairExpectedVersion = original.sourceRepairExpectedVersion,
    )
    if (original.entityId != null) {
        preserved = preserved.copy(selectedAsset = original.selectedAsset)
    }
    if (original.mode == MaintenanceEditorMode.REPAIR && original.entityId != null) {
        preserved = preserved.copy(
            dispatchDate = original.dispatchDate,
            sourceParty = original.sourceParty,
        )
    }
    return preserved
}

internal fun maintenanceDocumentAlreadySubmitted(editor: MaintenanceEditorState): Boolean =
    when (editor.mode) {
        MaintenanceEditorMode.ESTIMATE -> editor.documentState == "COMPLETED"
        MaintenanceEditorMode.REPAIR -> editor.documentState == "QUEUED"
    }

internal fun CatalogNodeDto.isOperationalEstimateNode(): Boolean =
    active &&
        includeInEstimate &&
        nodeType in setOf("WORK", "MATERIAL", "OPTION")

/**
 * The user selects a route for a manual inventory line, while an inventory stage still needs
 * one catalog node as its technical reference. The routing can be inherited from a category,
 * while catalog administration may legitimately contain several operational descendants on that
 * route. Choose the lowest stable node id rather than making a valid manual line impossible to
 * save.
 */
internal fun inventoryStageCatalogNodeId(
    catalogNodes: Collection<CatalogNodeDto>,
    selectedRouting: RoutingSnapshotDto,
): String? {
    val activeNodesById = catalogNodes
        .asSequence()
        .filter(CatalogNodeDto::active)
        .associateBy(CatalogNodeDto::id)
    return activeNodesById.values
        .asSequence()
        .filter { node ->
            node.isOperationalEstimateNode() && node.nodeType in setOf("WORK", "MATERIAL")
        }
        .filter { node ->
            effectiveCatalogNodeRouting(node.id, activeNodesById)?.routingKey() ==
                selectedRouting.routingKey()
        }
        .map(CatalogNodeDto::id)
        .minOrNull()
}

/**
 * Every manual inventory line keeps its user-selected routing, but the inventory command also
 * needs an active catalog node as the technical route reference. Resolve each line independently
 * so materials and works with different inherited routes cannot accidentally share a stage node.
 */
internal fun inventoryManualLineRoutingCatalogNodeIds(
    catalogNodes: Collection<CatalogNodeDto>,
    selectedRoutingByLineId: Map<String, RoutingSnapshotDto?>,
): Map<String, String> = selectedRoutingByLineId.mapValues { (_, selectedRouting) ->
    val routing = selectedRouting
        ?: throw IllegalArgumentException("Для пользовательской строки назначьте маршрут")
    if (!routing.isValidMaintenanceRouting()) {
        throw IllegalArgumentException("Для пользовательской строки назначьте корректный маршрут")
    }
    inventoryStageCatalogNodeId(catalogNodes, routing)
        ?: throw IllegalArgumentException("Для пользовательской строки не найден маршрут каталога")
}

/**
 * Replays the maintenance-owned frozen-plan allocation when an inventory finding is reopened.
 * Every source line index is returned under exactly one stage id, including repeated catalog
 * works and manual lines whose only identity is their frozen queue route.
 */
internal fun inventoryFrozenPlanLineIndexesByStage(
    lines: List<InventoryFrozenPlanLineDto>,
    stages: List<InventoryFrozenPlanStageDto>,
): Map<String, List<Int>> {
    val orderedStages = stages
        .filter { stage -> stage.kind == "REPAIR_WORK" }
        .sortedBy(InventoryFrozenPlanStageDto::order)
    require(orderedStages.map(InventoryFrozenPlanStageDto::id).distinct().size == orderedStages.size) {
        "Сервис вернул повторяющийся этап инвентаризации"
    }
    val allocations = orderedStages.map { stage -> stage to mutableListOf<Int>() }
    val allocated = mutableSetOf<Int>()

    fun routeMatches(line: InventoryFrozenPlanLineDto, stage: InventoryFrozenPlanStageDto): Boolean =
        line.routingQueueId != null && line.routingQueueId == stage.routingQueueId

    fun catalogMatches(line: InventoryFrozenPlanLineDto, stage: InventoryFrozenPlanStageDto): Boolean =
        line.catalogNodeId != null && line.catalogNodeId == stage.catalogNodeId

    fun firstAvailable(predicate: (InventoryFrozenPlanLineDto) -> Boolean): Int? =
        lines.indices.firstOrNull { index -> index !in allocated && predicate(lines[index]) }

    fun assign(allocation: Pair<InventoryFrozenPlanStageDto, MutableList<Int>>, index: Int?): Boolean {
        if (index == null) return false
        allocation.second += index
        allocated += index
        return true
    }

    fun routeAllocation(
        line: InventoryFrozenPlanLineDto,
        lineIndex: Int,
    ): Pair<InventoryFrozenPlanStageDto, MutableList<Int>>? {
        val matching = allocations.filter { allocation -> routeMatches(line, allocation.first) }
        if (matching.isEmpty()) return null
        return matching
            .filter { allocation -> (allocation.second.lastOrNull() ?: -1) < lineIndex }
            .maxByOrNull { allocation -> allocation.second.lastOrNull() ?: -1 }
            ?: matching.first()
    }

    allocations.forEach { allocation ->
        val stage = allocation.first
        if (assign(allocation, firstAvailable { line ->
                line.lineType == "WORK" && catalogMatches(line, stage)
            })) {
            return@forEach
        }
        if (assign(allocation, firstAvailable { line -> catalogMatches(line, stage) })) {
            return@forEach
        }
        assign(allocation, firstAvailable { line ->
            line.lineType == "WORK" && routeMatches(line, stage)
        })
    }
    lines.forEachIndexed { lineIndex, line ->
        if (line.lineType != "WORK" || lineIndex in allocated) return@forEachIndexed
        val allocation = routeAllocation(line, lineIndex)
            ?: throw IllegalArgumentException("Сервис вернул работу без выбранного маршрута")
        assign(allocation, lineIndex)
    }
    lines.forEachIndexed { lineIndex, line ->
        if (line.lineType != "MATERIAL" || lineIndex in allocated) return@forEachIndexed
        val allocation = allocations.firstOrNull { allocation ->
            catalogMatches(line, allocation.first) && routeMatches(line, allocation.first)
        } ?: routeAllocation(line, lineIndex)
            ?: throw IllegalArgumentException("Сервис вернул материал без выбранного маршрута")
        assign(allocation, lineIndex)
    }
    require(allocated.size == lines.size) {
        "Сервис вернул неоднозначный план ремонтных работ"
    }
    return allocations.associate { (stage, indexes) -> stage.id to indexes.toList() }
}

internal fun effectiveCatalogNodeRouting(
    nodeId: String,
    activeNodesById: Map<String, CatalogNodeDto>,
): RoutingSnapshotDto? {
    var current = activeNodesById[nodeId]
    val visited = mutableSetOf<String>()
    while (current != null) {
        if (!visited.add(current.id)) return null
        current.routing?.takeIf(RoutingSnapshotDto::isValidMaintenanceRouting)?.let { return it }
        current = current.parentNodeId?.let(activeNodesById::get)
    }
    return null
}

internal fun CatalogNodeDto.isAvailableForMaintenanceMode(
    mode: MaintenanceEditorMode,
    nodesById: Map<String, CatalogNodeDto>,
): Boolean {
    var current: CatalogNodeDto? = this
    val visited = mutableSetOf<String>()
    var belongsToFurnitureTree = false
    while (current != null && visited.add(current.id)) {
        if (current.furnitureCategory) {
            belongsToFurnitureTree = true
            break
        }
        current = current.parentNodeId?.let(nodesById::get)
    }
    if (!belongsToFurnitureTree) return true
    if (mode == MaintenanceEditorMode.REPAIR) return false
    return nodeType != "MATERIAL" || furnitureEquipment != null
}

internal fun CatalogNodeDto.toCatalogSnapshot(): CatalogNodeSnapshotDto =
    CatalogNodeSnapshotDto(
        catalogVersionId = catalogVersionId,
        nodeId = id,
        nodeType = nodeType,
        name = name,
        unit = unit,
        unitPrice = unitPrice,
        durationMinutes = durationMinutes,
        routing = routing,
        furnitureEquipment = furnitureEquipment,
    )

/**
 * Validates one editor line against the catalog snapshot and maps it to the public command DTO.
 * The snapshot is explicit so inventory planning and maintenance persistence share one canonical
 * validation path without sharing coordinator state.
 */
internal fun maintenanceLineInput(
    line: MaintenanceLineEditorState,
    catalogNodesById: Map<String, CatalogNodeDto>,
): EstimateLineInputDto {
    val description = line.description.trim()
    if (description.isEmpty()) {
        throw IllegalArgumentException("Укажите описание каждой строки")
    }
    if (description.length > 1000) {
        throw IllegalArgumentException("Описание строки не может быть длиннее 1000 символов")
    }
    if (!isMaintenanceLineType(line.lineType)) {
        throw IllegalArgumentException("Тип строки должен быть работой или материалом")
    }
    val requestedComment = line.comment.trim().takeIf(String::isNotEmpty)
    if (requestedComment != null && requestedComment.length > 2000) {
        throw IllegalArgumentException("Комментарий строки не может быть длиннее 2000 символов")
    }
    val node = line.catalogNodeId?.let(catalogNodesById::get)
    val lineType = if (node == null) {
        line.lineType
    } else {
        if (!node.isOperationalEstimateNode()) {
            throw IllegalArgumentException("Позиция каталога для строки больше недоступна")
        }
        catalogMaintenanceLineType(node.nodeType).also { canonicalType ->
            if (line.lineType != canonicalType) {
                throw IllegalArgumentException("Тип строки не совпадает с позицией каталога")
            }
        }
    }
    val comment = requestedComment.takeIf { lineType == "WORK" }
    val normativeMinutes = when (lineType) {
        "MATERIAL" -> 0
        "WORK" -> line.normativeMinutes.also { minutes ->
            val validRange = if (node == null) 1..525_600 else 0..525_600
            if (minutes !in validRange) {
                throw IllegalArgumentException("Укажите норматив от ${validRange.first} до 525600 минут")
            }
        }
        else -> error("Validated above")
    }
    val unit = if (node == null) {
        line.unit.trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Укажите единицу измерения пользовательской строки")
    } else {
        node.unit?.trim()?.takeIf(String::isNotEmpty)
    }
    if (unit != null && unit.length > 32) {
        throw IllegalArgumentException("Единица измерения не может быть длиннее 32 символов")
    }
    return EstimateLineInputDto(
        id = line.id,
        catalogSnapshot = node?.toCatalogSnapshot(),
        lineType = lineType,
        description = description,
        unit = unit,
        quantity = canonicalMaintenanceQuantity(line.quantity),
        unitPrice = canonicalMaintenanceMoney(line.unitPrice),
        normativeMinutes = normativeMinutes,
        comment = comment,
        mediaReferences = if (lineType == "WORK") {
            line.mediaReferences.distinctBy(MediaReferenceDto::mediaId)
        } else {
            emptyList()
        },
    )
}

internal fun CatalogNodeDto.toNewMaintenanceLine(): MaintenanceLineEditorState =
    MaintenanceLineEditorState(
        id = UUID.randomUUID().toString(),
        catalogNodeId = id,
        description = name,
        lineType = catalogMaintenanceLineType(nodeType),
        unit = unit.orEmpty(),
        quantity = "1",
        unitPrice = unitPrice ?: "0.00",
        normativeMinutes = if (nodeType == "WORK") durationMinutes else 0,
        comment = "",
    )

internal fun MaintenanceEditorState.hasSelectedCatalogWork(
    nodes: List<CatalogNodeDto>,
    lineId: String?,
): Boolean {
    if (lineId == null) return true
    val selectedWorkNodeIds = nodes
        .asSequence()
        .filter { node -> node.nodeType == "WORK" }
        .map(CatalogNodeDto::id)
        .toSet()
    return lines.any { line ->
        line.id == lineId &&
            line.lineType == "WORK" &&
            line.catalogNodeId in selectedWorkNodeIds
    }
}

internal fun applyMaintenanceCatalogNodes(
    editor: MaintenanceEditorState,
    nodes: List<CatalogNodeDto>,
    quantity: String,
    comment: String,
    existingWorkLineId: String? = null,
    photoUris: List<String> = emptyList(),
    mediaReferences: List<MediaReferenceDto> = emptyList(),
): MaintenanceEditorState {
    val nextLines = editor.lines.toMutableList()
    val selectedNodes = nodes.distinctBy(CatalogNodeDto::id)
    val selectedWorkNodes = selectedNodes
        .asSequence()
        .filter { node -> node.nodeType == "WORK" }
        .toList()
    val selectedWorkNodeIds = selectedWorkNodes.mapTo(mutableSetOf(), CatalogNodeDto::id)
    if ((photoUris.isNotEmpty() || mediaReferences.isNotEmpty()) && selectedWorkNodes.size != 1) {
        throw IllegalArgumentException(
            "Фото можно прикрепить только при выборе одной работы",
        )
    }
    val photoWorkNodeId = selectedWorkNodes.singleOrNull()?.id
    val existingWorkIndex = existingWorkLineId?.let { lineId ->
        nextLines.indexOfFirst { line -> line.id == lineId }
    } ?: -1
    if (existingWorkLineId != null) {
        require(existingWorkIndex >= 0) {
            "Выбранная работа больше не существует в документе"
        }
        val existing = nextLines[existingWorkIndex]
        require(existing.lineType == "WORK" && existing.catalogNodeId in selectedWorkNodeIds) {
            "Выбранная работа не соответствует позиции каталога"
        }
    }

    selectedNodes.forEach { node ->
        if (node.nodeType == "WORK") {
            val matchingExistingWorkIndex = existingWorkIndex.takeIf { index ->
                index >= 0 && nextLines[index].catalogNodeId == node.id
            }
            if (matchingExistingWorkIndex != null) {
                val existing = nextLines[matchingExistingWorkIndex]
                nextLines[matchingExistingWorkIndex] = existing.copy(
                    quantity = accumulatedMaintenanceQuantity(existing.quantity, quantity),
                    comment = mergeMaintenanceLineComments(existing.comment, comment),
                    photoUris = if (node.id == photoWorkNodeId) {
                        (existing.photoUris + photoUris).distinct()
                    } else {
                        existing.photoUris
                    },
                    mediaReferences = if (node.id == photoWorkNodeId) {
                        (existing.mediaReferences + mediaReferences)
                            .distinctBy(MediaReferenceDto::mediaId)
                    } else {
                        existing.mediaReferences
                    },
                ).normalizedMaintenanceAnnotations()
            } else {
                nextLines += node.toNewMaintenanceLine().copy(
                    quantity = quantity,
                    comment = comment,
                    photoUris = photoUris.takeIf { node.id == photoWorkNodeId }.orEmpty(),
                    mediaReferences = mediaReferences
                        .takeIf { node.id == photoWorkNodeId }
                        .orEmpty()
                        .distinctBy(MediaReferenceDto::mediaId),
                ).normalizedMaintenanceAnnotations()
            }
        } else {
            val existingMaterialIndex = nextLines.indexOfFirst { line ->
                line.catalogNodeId == node.id && line.lineType == "MATERIAL"
            }
            if (existingMaterialIndex >= 0) {
                val existing = nextLines[existingMaterialIndex]
                nextLines[existingMaterialIndex] = existing.copy(
                    quantity = accumulatedMaintenanceQuantity(existing.quantity, quantity),
                    comment = "",
                    mediaReferences = emptyList(),
                    photoUris = emptyList(),
                ).normalizedMaintenanceAnnotations()
            } else {
                nextLines += node.toNewMaintenanceLine().copy(
                    quantity = quantity,
                    comment = "",
                    mediaReferences = emptyList(),
                    photoUris = emptyList(),
                ).normalizedMaintenanceAnnotations()
            }
        }
    }
    return editor.copy(lines = nextLines)
}

internal fun EstimateLineDto.toMaintenanceLineEditor(
    customRouting: RoutingSnapshotDto? = null,
): MaintenanceLineEditorState {
    val canonicalLineType = lineType.also { type ->
        require(isMaintenanceLineType(type)) { "Сервис вернул неподдерживаемый тип строки" }
    }
    return MaintenanceLineEditorState(
        id = id,
        catalogNodeId = catalogSnapshot?.nodeId,
        description = description,
        lineType = canonicalLineType,
        unit = unit ?: catalogSnapshot?.unit.orEmpty(),
        quantity = quantity,
        unitPrice = unitPrice,
        normativeMinutes = if (canonicalLineType == "MATERIAL") 0 else normativeMinutes,
        comment = comment.orEmpty().takeIf { canonicalLineType == "WORK" }.orEmpty(),
        catalogSnapshot = catalogSnapshot,
        customRouting = if (catalogSnapshot == null) customRouting else null,
        mediaReferences = mediaReferences.takeIf { canonicalLineType == "WORK" }.orEmpty(),
        reworkDisposition = disposition,
        sourceRepairId = sourceRepairId,
        sourceLineId = sourceLineId,
        lineageRootLineId = lineageRootLineId,
        repeatSourceDescription = description.takeIf { disposition == "REPEAT" },
    ).normalizedMaintenanceAnnotations()
}

internal fun MaintenanceEditorState.toggleReworkCandidate(
    candidate: ReworkCandidateDto,
    newLineId: () -> String = { UUID.randomUUID().toString() },
): MaintenanceEditorState {
    require(repairKind == "REWORK") {
        "Переделка доступна только для доработки"
    }
    val existing = lines.firstOrNull { line ->
        line.reworkDisposition == "REPEAT" &&
            line.lineageRootLineId == candidate.lineageRootLineId
    }
    if (existing != null) {
        return copy(lines = lines.filterNot { it.id == existing.id })
    }
    val canonical = candidate.line.toMaintenanceLineEditor(
        customRouting = candidate.line.catalogSnapshot?.routing,
    )
    return copy(
        lines = lines + canonical.copy(
            id = newLineId(),
            reworkDisposition = "REPEAT",
            sourceRepairId = candidate.sourceRepairId,
            sourceLineId = candidate.sourceLineId,
            lineageRootLineId = candidate.lineageRootLineId,
            repeatSourceDescription = candidate.line.description,
        ),
    )
}

internal fun InventoryEditorState.toMaintenancePlanEditor(): MaintenanceEditorState {
    val workLineMediaIds = planLines
        .asSequence()
        .filter { line -> line.lineType == "WORK" }
        .flatMap { line -> line.mediaReferences.asSequence() }
        .map(MediaReferenceDto::mediaId)
        .toSet()
    val resolvedPhotoMedia = (persistedPhotoMedia + uploadedPhotoMedia)
        .filter { (uri, reference) ->
            uri in photoUris || reference.mediaId in workLineMediaIds
        }
    return MaintenanceEditorState(
        mode = MaintenanceEditorMode.REPAIR,
        entityId = findingId,
        expectedVersion = finding?.findingRevision,
        readOnly = readOnly,
        selectedAsset = null,
        dispatchDate = LocalDate.now().toString(),
        sourceParty = "Инвентаризация",
        lines = planLines,
        photoUris = photoUris.filterNot(resolvedPhotoMedia::containsKey),
        readyMedia = resolvedPhotoMedia.values
            .filterNot { reference -> reference.mediaId in workLineMediaIds }
            .distinctBy(MediaReferenceDto::mediaId),
        readyPhotoUris = resolvedPhotoMedia.entries.associate { (uri, reference) ->
            reference.mediaId to uri
        },
        priority = planPriority,
        forceCapitalRepair = planForceCapitalRepair && planLines.isNotEmpty(),
        movementToRepair = planMovementToRepair,
        logisticsPlanningMode = planLogisticsPlanningMode,
        logisticsScheduledDate = planLogisticsScheduledDate,
        step = 3,
        stages = planStages,
        repairKind = "PRIMARY",
    )
}

internal fun InventoryEditorState.withMaintenancePlanEditor(
    editor: MaintenanceEditorState,
): InventoryEditorState {
    val workLineMediaIds = editor.lines
        .asSequence()
        .filter { line -> line.lineType == "WORK" }
        .flatMap { line -> line.mediaReferences.asSequence() }
        .mapTo(linkedSetOf(), MediaReferenceDto::mediaId)
    val remainingReadyMediaIds = editor.readyMedia
        .asSequence()
        .map(MediaReferenceDto::mediaId)
        .filterNot(workLineMediaIds::contains)
        .toCollection(linkedSetOf())
    val retainedMediaIds = remainingReadyMediaIds + workLineMediaIds
    val remainingPhotoUris = buildSet {
        addAll(editor.photoUris)
        editor.readyMedia
            .filterNot { reference -> reference.mediaId in workLineMediaIds }
            .mapNotNullTo(this) { reference ->
                editor.readyPhotoUris[reference.mediaId]
            }
    }
    return copy(
        photoUris = photoUris.filter(remainingPhotoUris::contains),
        coverPhotoUri = coverPhotoUri?.takeIf(remainingPhotoUris::contains),
        persistedPhotoMedia = persistedPhotoMedia.filterValues { reference ->
            reference.mediaId in retainedMediaIds
        },
        uploadedPhotoMedia = uploadedPhotoMedia.filterValues { reference ->
            reference.mediaId in retainedMediaIds
        },
        planLines = editor.lines,
        planStages = editor.stages,
        planPriority = editor.priority,
        planForceCapitalRepair = editor.forceCapitalRepair && editor.lines.isNotEmpty(),
        planMovementToRepair = editor.movementToRepair,
        planLogisticsPlanningMode = editor.logisticsPlanningMode,
        planLogisticsScheduledDate = editor.logisticsScheduledDate,
    )
}

internal fun normalizeReworkEditorLines(
    editor: MaintenanceEditorState,
): MaintenanceEditorState {
    if (editor.repairKind != "REWORK") return editor
    return editor.copy(
        lines = editor.lines.map { line ->
            if (line.reworkDisposition == null) {
                line.copy(reworkDisposition = "ADDED")
            } else {
                line
            }
        },
    )
}

internal fun PlanStageInputDto.toMaintenanceStageEditor(): MaintenanceStageEditorState =
    MaintenanceStageEditorState(
        id = id,
        kind = kind,
        routing = routing,
        includedLineIds = includedLineIds,
        primaryLineId = primaryLineId,
        // A null primary is the contract's material-only stage marker, so an old/stale stage
        // comment must not reappear in the Android editor.
        groupComment = groupComment.takeIf { primaryLineId != null }.orEmpty(),
        taskDeadline = taskDeadline,
        originalOrder = order,
    )

internal fun RepairStageDto.toMaintenanceStageEditor(): MaintenanceStageEditorState =
    MaintenanceStageEditorState(
        id = id,
        kind = kind,
        routing = routing,
        includedLineIds = (workLines + materialLines).map(EstimateLineDto::id).distinct(),
        primaryLineId = primaryLineId,
        groupComment = groupComment.takeIf { workLines.isNotEmpty() }.orEmpty(),
        taskDeadline = taskDeadline,
        originalOrder = order,
    )

internal fun planMaintenanceStages(
    editor: MaintenanceEditorState,
    routingForLine: (MaintenanceLineEditorState) -> RoutingSnapshotDto?,
): List<PlanStageInputDto> {
    /** Editor line paired with its validated route and stable original position. */
    data class RoutedLine(
        val line: MaintenanceLineEditorState,
        val routing: RoutingSnapshotDto,
        val index: Int,
    )

    /** Lines that must be emitted in one stage because they share work ownership and routing. */
    data class LineGroup(
        val key: String,
        val routing: RoutingSnapshotDto,
        val lines: MutableList<RoutedLine> = mutableListOf(),
    )

    /** Generated stage plus the ordering evidence used to keep edited plans deterministic. */
    data class PlannedStage(
        val originalOrder: Int,
        val generatedOrder: Int,
        val stage: PlanStageInputDto,
    )

    val routedLines = editor.lines.mapIndexed { index, line ->
        line.effectiveLineType()
        val routing = routingForLine(line)
            ?: throw IllegalArgumentException("Для каждой строки назначьте маршрут")
        if (!routing.isValidMaintenanceRouting()) {
            throw IllegalArgumentException("Для каждой строки назначьте корректный маршрут")
        }
        RoutedLine(line = line, routing = routing, index = index)
    }
    val groupsByKey = linkedMapOf<String, LineGroup>()
    val groupKeyByWorkLineId = mutableMapOf<String, String>()

    fun groupFor(key: String, routing: RoutingSnapshotDto): LineGroup =
        groupsByKey.getOrPut(key) {
            LineGroup(key = key, routing = routing)
    }

    routedLines.filter { routed -> routed.line.lineType == "WORK" }.forEach { routed ->
        val key = "work:${routed.line.id}"
        groupFor(key, routed.routing).lines += routed
        groupKeyByWorkLineId[routed.line.id] = key
    }

    val previousWorkStages = editor.stages
        .filter { it.kind == "REPAIR_WORK" }
        .toMutableList()

    routedLines.filter { routed -> routed.line.lineType == "MATERIAL" }.forEach { material ->
        val routeKey = material.routing.routingKey()
        val groupFromExistingStage = editor.stages
            .firstOrNull { stage ->
                stage.kind == "REPAIR_WORK" && material.line.id in stage.includedLineIds
            }
            ?.primaryLineId
            ?.let(groupKeyByWorkLineId::get)
            ?.let(groupsByKey::get)
            ?.takeIf { group -> group.routing.routingKey() == routeKey }
        val groupsForRoute = groupsByKey.values.filter { group ->
            group.routing.routingKey() == routeKey
        }
        val routeGroup = groupsByKey["route:$routeKey"]
        val precedingWorkGroup = groupsForRoute
            .asSequence()
            .filter { group -> group.lines.any { it.line.lineType == "WORK" } }
            .mapNotNull { group ->
                group.lines
                    .asSequence()
                    .filter { line -> line.line.lineType == "WORK" && line.index < material.index }
                    .maxByOrNull(RoutedLine::index)
                    ?.let { precedingLine -> group to precedingLine.index }
            }
            .maxByOrNull { (_, lineIndex) -> lineIndex }
            ?.first
        val target = groupFromExistingStage
            ?: routeGroup
            ?: precedingWorkGroup
            ?: groupsForRoute.singleOrNull()
            ?: groupFor("route:$routeKey", material.routing)
        target.lines += material
    }

    val planned = groupsByKey.values.mapIndexed { generatedOrder, group ->
        val groupedLines = group.lines.sortedBy(RoutedLine::index)
        val includedLineIds = groupedLines.map { routed -> routed.line.id }
        val primaryLineId = groupedLines
            .firstOrNull { routed -> routed.line.lineType == "WORK" }
            ?.line
            ?.id
        val previousIndex = previousWorkStages.indexOfFirst { stage ->
            stage.routing.routingKey() == group.routing.routingKey() &&
                if (primaryLineId != null) {
                    stage.primaryLineId == primaryLineId
                } else {
                    stage.primaryLineId == null && stage.includedLineIds == includedLineIds
                }
        }
        val previous = if (previousIndex >= 0) {
            previousWorkStages.removeAt(previousIndex)
        } else {
            null
        }
        PlannedStage(
            originalOrder = previous?.originalOrder ?: Int.MAX_VALUE,
            generatedOrder = generatedOrder,
            stage = PlanStageInputDto(
                id = previous?.id ?: UUID.randomUUID().toString(),
                kind = "REPAIR_WORK",
                order = 0,
                routing = group.routing,
                includedLineIds = includedLineIds,
                primaryLineId = primaryLineId,
                groupComment = groupedLines
                    .map(RoutedLine::line)
                    .commentsForMaintenanceStage()
                    .ifBlank {
                        previous?.groupComment
                            ?.takeIf { primaryLineId != null }
                            .orEmpty()
                    },
                taskDeadline = previous?.taskDeadline,
            ),
        )
    }

    val orderedWorkStages = planned
        .sortedWith(
            compareBy<PlannedStage>(PlannedStage::originalOrder)
                .thenBy(PlannedStage::generatedOrder),
        )
        .map(PlannedStage::stage)
    return orderedWorkStages.mapIndexed { index, stage -> stage.copy(order = index) }
}

/**
 * Rebuilds the transport plan from editor lines using the catalog snapshot held by the catalog
 * coordinator. Keeping the snapshot explicit makes this reducer usable by both form-state and
 * persistence workflows without either coordinator becoming the other's dependency.
 */
internal fun buildMaintenanceStages(
    editor: MaintenanceEditorState,
    catalogNodesById: Map<String, CatalogNodeDto>,
    catalogLinks: List<CatalogLinkDto>,
): List<PlanStageInputDto> = planMaintenanceStages(editor) { line ->
    line.maintenanceRouting(catalogNodesById, catalogLinks)
}

/**
 * Converts the inventory editor's optional repair plan into the inventory API representation.
 * It is a pure boundary reducer: inventory owns the editor state, while the caller supplies the
 * catalog snapshot that makes routing and catalog references deterministic.
 */
internal fun buildInventoryPlanSelection(
    editor: InventoryEditorState,
    coverMediaId: String?,
    catalogNodesById: Map<String, CatalogNodeDto>,
    catalogLinks: List<CatalogLinkDto>,
): InventoryPlanSelectionDto? {
    if (editor.planLines.isEmpty()) {
        require(!editor.planMovementToRepair) {
            "Передача в ремонт доступна, когда в проверке есть работы или материалы"
        }
        return null
    }
    val maintenanceEditor = editor.toMaintenancePlanEditor()
        .copy(
            logisticsPlanningMode = if (editor.planMovementToRepair) {
                LOGISTICS_PLANNING_MODE_AUTO
            } else {
                null
            },
            logisticsScheduledDate = null,
        )
        .normalizedLogisticsPlanning()
    maintenanceEditor.logisticsTaskPriorityValidationError()?.let { error ->
        throw IllegalArgumentException(error)
    }
    maintenanceEditor.logisticsPlanningValidationError()?.let { error ->
        throw IllegalArgumentException(error)
    }
    val normalizedLines = maintenanceEditor.lines.associateBy(
        MaintenanceLineEditorState::id,
    )
    val normalizedLineInputs = maintenanceEditor.lines.associate { line ->
        line.id to maintenanceLineInput(line, catalogNodesById)
    }
    val routingCatalogNodeIdByManualLineId = inventoryManualLineRoutingCatalogNodeIds(
        catalogNodes = catalogNodesById.values,
        selectedRoutingByLineId = maintenanceEditor.lines
            .asSequence()
            .filter { line ->
                normalizedLineInputs.getValue(line.id).catalogSnapshot == null
            }
            .associate { line ->
                line.id to line.maintenanceRouting(
                    catalogNodesById = catalogNodesById,
                    catalogLinks = catalogLinks,
                )
            },
    )
    val stageCommentByLine = maintenanceEditor.stages
        .filter { it.kind == "REPAIR_WORK" }
        .flatMap { stage ->
            stage.includedLineIds.map { lineId -> lineId to stage.groupComment }
        }
        .toMap()
    val lines = maintenanceEditor.lines.map { line ->
        val normalized = normalizedLineInputs.getValue(line.id)
        if (normalized.catalogSnapshot != null) {
            InventoryPlanLineInputDto(
                aggregationKind = "CATALOG",
                catalogNodeId = normalized.catalogSnapshot.nodeId,
                routingCatalogNodeId = null,
                description = null,
                type = null,
                unit = null,
                quantity = normalized.quantity,
                unitPriceMinor = null,
                normativeMinutes = null,
                groupComment = if (normalized.lineType == "WORK") {
                    stageCommentByLine[line.id]
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: normalized.comment
                } else {
                    null
                },
                mediaReferences = normalized.mediaReferences,
            )
        } else {
            InventoryPlanLineInputDto(
                aggregationKind = "MANUAL",
                catalogNodeId = null,
                routingCatalogNodeId = routingCatalogNodeIdByManualLineId.getValue(line.id),
                description = normalized.description,
                type = normalized.lineType,
                unit = normalized.unit,
                quantity = normalized.quantity,
                unitPriceMinor = BigDecimal(normalized.unitPrice)
                    .movePointRight(2)
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .longValueExact(),
                normativeMinutes = (normalized.normativeMinutes ?: 0).toString(),
                groupComment = if (normalized.lineType == "WORK") {
                    stageCommentByLine[line.id]
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: normalized.comment
                } else {
                    null
                },
                mediaReferences = normalized.mediaReferences,
            )
        }
    }
    val plannedStages = buildMaintenanceStages(
        editor = maintenanceEditor,
        catalogNodesById = catalogNodesById,
        catalogLinks = catalogLinks,
    )
    val inventoryStages = plannedStages.map { stage ->
        val catalogNodeId = when (stage.kind) {
            "REPAIR_WORK" -> stage.primaryLineId
                ?.let(normalizedLines::get)
                ?.catalogNodeId
                ?: stage.includedLineIds
                    .asSequence()
                    .mapNotNull(normalizedLines::get)
                    .mapNotNull(MaintenanceLineEditorState::catalogNodeId)
                    .firstOrNull()
                ?: inventoryStageCatalogNodeId(
                    catalogNodes = catalogNodesById.values,
                    selectedRouting = stage.routing,
                )
                ?: throw IllegalArgumentException(
                    "Для пользовательской строки не найден маршрут каталога",
                )

            else -> throw IllegalArgumentException("Неподдерживаемый этап инвентаризации")
        }
        InventoryPlanStageSelectionDto(
            catalogNodeId = catalogNodeId,
            kind = stage.kind,
            order = stage.order,
        )
    }
    return InventoryPlanSelectionDto(
        mode = "MANUAL",
        priority = maintenanceEditor.priority,
        coverMediaId = coverMediaId,
        movementToRepair = maintenanceEditor.movementToRepair,
        forceCapitalRepair = maintenanceEditor.forceCapitalRepair,
        logisticsPlanningMode = maintenanceEditor.logisticsPlanningMode,
        logisticsScheduledDate = maintenanceEditor.logisticsScheduledDate,
        lines = lines,
        stages = inventoryStages,
    )
}

internal fun MaintenanceLineEditorState.maintenanceRouting(
    catalogNodesById: Map<String, CatalogNodeDto>,
    catalogLinks: List<CatalogLinkDto>,
): RoutingSnapshotDto? =
    if (catalogNodeId == null) customRouting
    else effectiveMaintenanceRouting(catalogNodeId, catalogNodesById, catalogLinks)

private fun effectiveMaintenanceRouting(
    nodeId: String?,
    catalogNodesById: Map<String, CatalogNodeDto>,
    catalogLinks: List<CatalogLinkDto>,
): RoutingSnapshotDto? {
    val activeNodesById = catalogNodesById.filterValues(CatalogNodeDto::active)
    var current = nodeId?.let(activeNodesById::get)
    val incomingParents = maintenanceCatalogIncomingParents(activeNodesById, catalogLinks)
    val visited = mutableSetOf<String>()
    while (current != null) {
        if (!visited.add(current.id)) return null
        current.routing?.takeIf(RoutingSnapshotDto::isValidMaintenanceRouting)?.let { return it }
        current = if (current.parentNodeId != null) {
            activeNodesById[current.parentNodeId]
        } else {
            incomingParents[current.id]?.firstOrNull { it.id !in visited }
        }
    }
    return null
}

private fun maintenanceCatalogIncomingParents(
    activeNodesById: Map<String, CatalogNodeDto>,
    catalogLinks: List<CatalogLinkDto>,
): Map<String, List<CatalogNodeDto>> = catalogLinks
    .asSequence()
    .filter { link ->
        link.fromNodeId in activeNodesById && link.toNodeId in activeNodesById
    }
    .groupBy(CatalogLinkDto::toNodeId)
    .mapValues { (_, links) ->
        links.sortedWith(
            compareBy<CatalogLinkDto> { it.sortOrder }
                .thenBy { activeNodesById[it.fromNodeId]?.name.orEmpty() }
                .thenBy(CatalogLinkDto::id),
        ).map { link -> requireNotNull(activeNodesById[link.fromNodeId]) }
    }

internal fun MaintenanceLineEditorState.effectiveLineType(): String =
    lineType.also { type ->
        require(isMaintenanceLineType(type)) { "Тип строки должен быть работой или материалом" }
    }

internal fun RoutingSnapshotDto?.routingKey(): String = this?.let { routing ->
    "${routing.queueId.trim()}:${routing.queueName.trim()}:${routing.queueType.trim()}"
} ?: "UNBOUND"

internal fun RoutingSnapshotDto.isValidMaintenanceRouting(): Boolean =
    queueId.isNotBlank() && queueName.isNotBlank() && queueType.isNotBlank()

internal fun List<MaintenanceLineEditorState>.commentsForMaintenanceStage(): String =
    asSequence()
        .filter { line -> line.lineType == "WORK" }
        .map { it.comment.trim() }
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString("; ")

internal fun mergeMaintenanceLineComments(existing: String, added: String): String =
    sequenceOf(existing, added)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString("; ")

internal fun accumulatedMaintenanceQuantity(existing: String, added: String): String =
    BigDecimal(existing)
        .add(BigDecimal(added))
        .stripTrailingZeros()
        .toPlainString()

internal fun canonicalMaintenanceQuantity(value: String): String {
    val normalized = value.trim().replace(',', '.')
    if (!MAINTENANCE_QUANTITY_PATTERN.matches(normalized)) {
        throw IllegalArgumentException("Количество должно быть положительным числом до трёх знаков")
    }
    val quantity = BigDecimal(normalized)
    if (quantity <= BigDecimal.ZERO) {
        throw IllegalArgumentException("Количество должно быть больше нуля")
    }
    return quantity.stripTrailingZeros().toPlainString()
}

internal fun canonicalMaintenanceMoney(value: String): String {
    val normalized = value.trim().replace(',', '.')
    if (!MAINTENANCE_MONEY_PATTERN.matches(normalized)) {
        throw IllegalArgumentException("Цена должна быть неотрицательным числом с двумя знаками")
    }
    val money = BigDecimal(normalized)
    if (money < BigDecimal.ZERO) {
        throw IllegalArgumentException("Цена не может быть отрицательной")
    }
    return money.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
}

/**
 * Rebuilds derived repair stages from the current catalog without retaining editor state.
 *
 * <p>Inventory and maintenance editors share this reducer so neither workflow becomes the
 * authoritative owner of routing normalization.
 */
internal fun normalizeMaintenanceEditor(
    editor: MaintenanceEditorState,
    catalogNodesById: Map<String, CatalogNodeDto>,
    catalogLinks: List<CatalogLinkDto>,
): MaintenanceEditorState {
    val exclusiveDestination = if (editor.forceCapitalRepair) {
        editor.withForceCapitalRepair(true)
    } else {
        editor
    }
    val normalized = exclusiveDestination.normalizedLogisticsPlanning().copy(
        forceCapitalRepair = editor.forceCapitalRepair &&
            (editor.lines.isNotEmpty() || editor.repairKind == "REWORK"),
        lines = editor.lines.map(MaintenanceLineEditorState::normalizedMaintenanceAnnotations),
    )
    val rebuiltStages = runCatching {
        buildMaintenanceStages(
            editor = normalized,
            catalogNodesById = catalogNodesById,
            catalogLinks = catalogLinks,
        )
    }.getOrNull()
        ?: return normalized
    return normalized.copy(
        stages = rebuiltStages.map { stage ->
            MaintenanceStageEditorState(
                id = stage.id,
                kind = stage.kind,
                routing = stage.routing,
                includedLineIds = stage.includedLineIds,
                primaryLineId = stage.primaryLineId,
                groupComment = stage.groupComment,
                taskDeadline = stage.taskDeadline,
                originalOrder = stage.order,
            )
        },
    )
}

internal const val MAINTENANCE_AMENDMENT_REASON = "Изменение работ до начала ремонта"
internal val PRE_START_REPAIR_STATES = setOf("DRAFT", "QUEUED")
internal val MAINTENANCE_QUANTITY_PATTERN = Regex("^(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,3})?$")
internal val MAINTENANCE_MONEY_PATTERN = Regex("^(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?$")
