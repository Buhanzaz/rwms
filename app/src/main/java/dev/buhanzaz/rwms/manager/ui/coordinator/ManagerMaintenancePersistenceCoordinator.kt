package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.network.AmendEstimateRequest
import dev.buhanzaz.rwms.manager.network.CreateDirectRepairRequest
import dev.buhanzaz.rwms.manager.network.CreateEstimateRequest
import dev.buhanzaz.rwms.manager.network.CreateReworkRequest
import dev.buhanzaz.rwms.manager.network.EstimateCommandResultDto
import dev.buhanzaz.rwms.manager.network.EstimateDto
import dev.buhanzaz.rwms.manager.network.EstimateLineInputDto
import dev.buhanzaz.rwms.manager.network.PlanStageInputDto
import dev.buhanzaz.rwms.manager.network.PriorityVersionRequest
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.ReworkLineInputDto
import dev.buhanzaz.rwms.manager.network.ReplaceEstimateRequest
import dev.buhanzaz.rwms.manager.network.ReplaceRepairPlanRequest
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadArea
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadDraft
import dev.buhanzaz.rwms.manager.uploads.MaintenanceReplaceKind
import dev.buhanzaz.rwms.manager.uploads.MaintenanceUploadCommand
import dev.buhanzaz.rwms.manager.uploads.PendingBackgroundPhoto
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.update

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */

/** Closes the editor while invalidating its in-flight search generation after durable commands. */
internal interface ManagerMaintenanceEditorClosePort {
    fun closeMaintenanceEditor()
}

/**
 * Owns durable maintenance commands: draft persistence, command DTO construction, optimistic
 * replacements and background upload enqueueing. It reduces the same editor state owned by the
 * facade; it never keeps an independent document draft.
 */
internal class ManagerMaintenancePersistenceCoordinator(
    private val runtime: ManagerCommandRuntime,
    private val backend: RwmsBackend,
    private val backgroundUploads: kotlinx.coroutines.Deferred<
        dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator,
    >,
    private val maintenanceRefresh: ManagerMaintenanceRefreshPort,
    private val catalogAccess: ManagerMaintenanceCatalogAccess,
    private val editorClose: ManagerMaintenanceEditorClosePort,
) {
    private val mutableState
        get() = runtime.mutableState

    private fun command(block: suspend () -> Unit) = runtime.command(block)

    private fun message(value: String) = runtime.message(value)

    private fun requireWarehouseId(): String = runtime.requireWarehouseId()

    private suspend fun refreshMaintenance() = maintenanceRefresh.refreshMaintenance()

    private suspend fun refreshAcceptance() = maintenanceRefresh.refreshAcceptance()

    fun saveMaintenanceDraft(onSaved: () -> Unit) = command {
        val existingDocument = maintenanceDocumentAlreadySubmitted(requireMaintenanceEditor())
        val current = requireMaintenanceEditor()
        val queued = if (current.hasPendingMaintenancePhotos()) {
            enqueueMaintenanceBackground(submit = false)
        } else {
            null
        }
        val editor = queued ?: persistMaintenanceDraft()
        if (queued == null) {
            refreshMaintenance()
            if (editor.repairKind == "REWORK") refreshAcceptance()
        } else {
            closeMaintenanceEditorAfterPersistence()
        }
        message(
            if (queued != null) {
                if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                    "Смета добавлена в фоновые загрузки"
                } else {
                    "Ремонт добавлен в фоновые загрузки"
                }
            } else if (existingDocument && editor.mode == MaintenanceEditorMode.ESTIMATE) {
                "Изменения сметы сохранены"
            } else if (existingDocument) {
                "Изменения ремонта сохранены"
            } else if (editor.repairKind == "REWORK") {
                "Черновик доработки сохранён"
            } else if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                "Черновик сметы сохранён"
            } else {
                "Черновик ремонта сохранён"
            },
        )
        onSaved()
    }

    fun submitMaintenance(onSaved: () -> Unit) = command {
        val beforeSave = requireMaintenanceEditor().normalizedLogisticsPlanning()
        mutableState.update { current ->
            current.copy(maintenanceEditor = beforeSave)
        }
        validateMaintenanceSubmit(beforeSave)
        if (maintenanceDocumentAlreadySubmitted(beforeSave)) {
            val saved = enqueueMaintenanceBackground(submit = false)
            closeMaintenanceEditorAfterPersistence()
            message(
                if (saved.mode == MaintenanceEditorMode.ESTIMATE) {
                    "Изменения сметы добавлены в фоновые загрузки"
                } else {
                    "Изменения ремонта добавлены в фоновые загрузки"
                },
            )
            onSaved()
            return@command
        }
        val saved = enqueueMaintenanceBackground(submit = true)
        closeMaintenanceEditorAfterPersistence()
        message(
            if (saved.mode == MaintenanceEditorMode.ESTIMATE) {
                "Смета добавлена в фоновые загрузки"
            } else if (saved.repairKind == "REWORK") {
                "Доработка добавлена в фоновые загрузки"
            } else {
                "Ремонт добавлен в фоновые загрузки"
            },
        )
        onSaved()
    }

    /**
     * Keeps the established list-screen shortcut on the durable-command owner instead of making
     * the editor depend back on persistence.
     */
    fun createEstimate(item: RentalItemDto) = command {
        val warehouseId = requireWarehouseId()
        if (item.warehouseId != warehouseId) {
            throw IllegalArgumentException("Выберите бытовку текущего склада")
        }
        mutableState.update { current ->
            current.copy(
                maintenanceEditor = MaintenanceEditorState(
                    mode = MaintenanceEditorMode.ESTIMATE,
                    entityId = null,
                    expectedVersion = null,
                    readOnly = false,
                    selectedAsset = item,
                    dispatchDate = LocalDate.now().toString(),
                    sourceParty = "",
                    lines = emptyList(),
                    photoUris = emptyList(),
                    readyMedia = emptyList(),
                    priority = DEFAULT_MAINTENANCE_PRIORITY,
                    step = 1,
                ),
            )
        }
        persistMaintenanceDraft()
        refreshMaintenance()
        mutableState.update {
            it.copy(
                assetSearchResults = emptyList(),
                assetSearch = "",
                assetSearchCompletedQuery = null,
                assetSearchFailedQuery = null,
            )
        }
        message("Черновик сметы для ${item.number} создан")
    }

    /** Canonical draft payload retained while a maintenance entity is created or updated. */
    private data class MaintenanceDraftContent(
        val asset: RentalItemDto,
        val dispatchDate: String,
        val sourceParty: String?,
        val lines: List<EstimateLineInputDto>,
        val stages: List<PlanStageInputDto>,
        val mediaReferences: List<MediaReferenceDto>,
        val coverMediaId: String?,
    )

    /** Server identity, fence and media state returned after a durable maintenance save. */
    private data class PersistedMaintenanceEntity(
        val id: String,
        val version: Long,
        val mediaReferences: List<MediaReferenceDto>,
        val coverMediaId: String?,
        val linkedRepairVersion: Long? = null,
    )

    private suspend fun enqueueMaintenanceBackground(
        submit: Boolean,
    ): MaintenanceEditorState {
        var editor = requireMaintenanceEditor().normalizedLogisticsPlanning()
        mutableState.update { current -> current.copy(maintenanceEditor = editor) }
        if (editor.readOnly) {
            throw IllegalStateException("Документ нельзя изменить после начала работы")
        }
        var content = maintenanceDraftContent(editor)
        val wasNew = editor.entityId == null
        if (wasNew) {
            val created = createMaintenanceEntity(editor, content)
            updateMaintenanceEditorAfterSave(created)
            editor = requireMaintenanceEditor()
            content = maintenanceDraftContent(editor)
        }

        val entityId = requireNotNull(editor.entityId) { "Не удалось сохранить черновик" }
        val expectedVersion = requireNotNull(editor.expectedVersion) {
            "Сервис не вернул версию черновика"
        }
        val warehouseId = requireWarehouseId()
        val localPhotoUris = orderedMaintenanceLocalPhotoUris(editor)
        val linePhotoUris = editor.lines
            .asSequence()
            .filter { line -> line.lineType == "WORK" }
            .flatMap { line ->
                line.photoUris.distinct().map { uri -> line.id to uri }
            }
            .toList()
        val owner = MediaOwner(
            ownerType = if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                "MAINTENANCE_ESTIMATE"
            } else {
                "MAINTENANCE_REPAIR"
            },
            ownerId = entityId,
            warehouseId = warehouseId,
            context = if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                "ESTIMATE"
            } else {
                "REPAIR"
            },
        )
        val existingMedia = orderedMaintenanceReadyMedia(editor)
        val existingCoverMediaId = editor.coverPhotoKey
            ?.takeIf { it.startsWith("media:") }
            ?.removePrefix("media:")
            ?.takeIf { mediaId -> existingMedia.any { it.mediaId == mediaId } }
        val submittedDocument = maintenanceDocumentAlreadySubmitted(editor)
        val replaceKind = when {
            wasNew && localPhotoUris.isEmpty() && linePhotoUris.isEmpty() ->
                MaintenanceReplaceKind.NONE
            editor.mode == MaintenanceEditorMode.ESTIMATE && submittedDocument ->
                MaintenanceReplaceKind.ESTIMATE_AMENDMENT
            editor.mode == MaintenanceEditorMode.ESTIMATE -> MaintenanceReplaceKind.ESTIMATE
            else -> MaintenanceReplaceKind.REPAIR
        }
        val submitRequest = if (submit) {
            PriorityVersionRequest(
                expectedVersion = expectedVersion,
                priority = editor.priority,
                movementToRepair = editor.movementToRepair,
                logisticsPlanningMode = editor.logisticsPlanningMode,
                logisticsScheduledDate = editor.logisticsScheduledDate,
            )
        } else {
            null
        }
        backgroundUploads.await().enqueue(
            BackgroundUploadDraft(
                area = BackgroundUploadArea.MAINTENANCE,
                title = if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                    "Смета ${content.asset.number}"
                } else {
                    "Ремонт ${content.asset.number}"
                },
                subtitle = if (submit) "Отправка в очередь" else "Сохранение",
                photos = localPhotoUris.mapIndexed { index, uri ->
                    PendingBackgroundPhoto(
                        uri = uri,
                        owner = owner,
                        sortOrder = existingMedia.size + index,
                        cover = editor.coverPhotoKey == maintenanceLocalPhotoKey(uri),
                    )
                } + linePhotoUris.mapIndexed { index, (lineId, uri) ->
                    PendingBackgroundPhoto(
                        uri = uri,
                        owner = owner,
                        sortOrder = existingMedia.size + localPhotoUris.size + index,
                        lineId = lineId,
                    )
                },
                maintenance = MaintenanceUploadCommand(
                    mode = editor.mode.name,
                    entityId = entityId,
                    warehouseId = warehouseId,
                    expectedVersion = expectedVersion,
                    dispatchDate = content.dispatchDate,
                    sourceParty = content.sourceParty,
                    lines = content.lines,
                    stages = content.stages,
                    existingMedia = existingMedia,
                    existingCoverMediaId = existingCoverMediaId,
                    replaceKind = replaceKind,
                    expectedLinkedRepairVersion = editor.linkedRepairExpectedVersion,
                    amendmentIdempotencyKey = if (
                        replaceKind == MaintenanceReplaceKind.ESTIMATE_AMENDMENT
                    ) {
                        editor.amendmentIdempotencyKey ?: UUID.randomUUID().toString()
                    } else {
                        null
                    },
                    amendmentReason = if (
                        replaceKind == MaintenanceReplaceKind.ESTIMATE_AMENDMENT
                    ) {
                        MAINTENANCE_AMENDMENT_REASON
                    } else {
                        null
                    },
                    submitRequest = submitRequest,
                    submitIdempotencyKey = if (submit) {
                        editor.submitIdempotencyKey ?: UUID.randomUUID().toString()
                    } else {
                        null
                    },
                ),
            ),
        )
        return editor
    }

    internal suspend fun persistMaintenanceDraft(): MaintenanceEditorState {
        var editor = requireMaintenanceEditor().normalizedLogisticsPlanning()
        mutableState.update { current ->
            current.copy(maintenanceEditor = editor)
        }
        if (editor.readOnly) {
            throw IllegalStateException("Документ нельзя изменить после начала работы")
        }
        var content = maintenanceDraftContent(editor)
        val wasNew = editor.entityId == null
        if (wasNew) {
            val created = createMaintenanceEntity(editor, content)
            updateMaintenanceEditorAfterSave(created)
            editor = requireMaintenanceEditor()
        }

        require(!editor.hasPendingMaintenancePhotos()) {
            "Фотографии должны отправляться через фоновые загрузки"
        }
        if (!wasNew) {
            val replaced = replaceMaintenanceEntity(editor, content)
            updateMaintenanceEditorAfterSave(replaced)
            editor = requireMaintenanceEditor()
        }
        return editor
    }

    private fun maintenanceDraftContent(
        editor: MaintenanceEditorState,
        mediaReferences: List<MediaReferenceDto> = orderedMaintenanceReadyMedia(editor),
    ): MaintenanceDraftContent {
        val asset = requireNotNull(editor.selectedAsset) { "Выберите бытовку" }
        if (maintenanceRequiresPhotos(editor) && !maintenanceHasPhotos(editor)) {
            throw IllegalArgumentException("Добавьте хотя бы одну фотографию")
        }
        if (maintenanceHasPhotos(editor) && !maintenanceHasCoverPhoto(editor)) {
            throw IllegalArgumentException("Выберите титульную фотографию")
        }
        val warehouseId = requireWarehouseId()
        if (asset.warehouseId != warehouseId) {
            throw IllegalArgumentException("Выберите бытовку текущего склада")
        }
        val dispatchDate = if (editor.mode == MaintenanceEditorMode.REPAIR &&
            editor.entityId == null
        ) {
            LocalDate.now().toString()
        } else {
            editor.dispatchDate.trim().let { date ->
            runCatching { LocalDate.parse(date) }.getOrElse {
                throw IllegalArgumentException("Укажите корректную дату отправки")
            }
            date
            }
        }
        val sourceParty = if (editor.mode == MaintenanceEditorMode.REPAIR &&
            editor.entityId == null &&
            editor.repairKind != "REWORK"
        ) {
            DIRECT_REPAIR_SOURCE_PARTY
        } else {
            editor.sourceParty.trim().takeIf(String::isNotEmpty)
        }
        if (sourceParty != null && sourceParty.length > 512) {
            throw IllegalArgumentException("Источник не может быть длиннее 512 символов")
        }
        if (editor.repairKind == "REWORK" && editor.entityId == null) {
            val reason = editor.reworkReason.trim()
            if (reason.isEmpty()) {
                throw IllegalArgumentException("Укажите причину доработки")
            }
            if (reason.length > 2_000) {
                throw IllegalArgumentException("Причина доработки не может быть длиннее 2000 символов")
            }
        }
        val lines = editor.lines.map { line ->
            maintenanceLineInput(line, catalogAccess.nodesById)
        }
        val stages = buildMaintenanceStages(
            editor = editor,
            catalogNodesById = catalogAccess.nodesById,
            catalogLinks = mutableState.value.maintenanceCatalogLinks,
        )
        if (editor.mode == MaintenanceEditorMode.REPAIR &&
            editor.lines.isNotEmpty() &&
            stages.none { it.kind == "REPAIR_WORK" }
        ) {
            throw IllegalArgumentException("Для прямого ремонта добавьте хотя бы один этап")
        }
        return MaintenanceDraftContent(
            asset = asset,
            dispatchDate = dispatchDate,
            sourceParty = sourceParty,
            lines = lines,
            stages = stages,
            mediaReferences = mediaReferences.distinctBy(MediaReferenceDto::mediaId),
            coverMediaId = editor.coverPhotoKey
                ?.takeIf { it.startsWith("media:") }
                ?.removePrefix("media:")
                ?.takeIf { mediaId ->
                    mediaReferences.any { it.mediaId == mediaId }
                },
        )
    }

    private fun validateMaintenanceSubmit(editor: MaintenanceEditorState) {
        if (editor.readOnly) {
            throw IllegalStateException("Завершённые данные нельзя отправить повторно")
        }
        if (maintenanceRequiresPhotos(editor) && !maintenanceHasPhotos(editor)) {
            throw IllegalArgumentException("Добавьте хотя бы одну фотографию")
        }
        if (maintenanceHasPhotos(editor) && !maintenanceHasCoverPhoto(editor)) {
            throw IllegalArgumentException("Выберите титульную фотографию")
        }
        editor.logisticsTaskPriorityValidationError()?.let { error ->
            throw IllegalArgumentException(error)
        }
        editor.logisticsPlanningValidationError()?.let { error ->
            throw IllegalArgumentException(error)
        }
        val content = maintenanceDraftContent(editor)
        if (editor.lines.isNotEmpty() &&
            content.stages.none { it.kind == "REPAIR_WORK" }
        ) {
            throw IllegalArgumentException("Для отправки требуется маршрут работ")
        }
    }

    private suspend fun createMaintenanceEntity(
        editor: MaintenanceEditorState,
        content: MaintenanceDraftContent,
    ): PersistedMaintenanceEntity = when (editor.mode) {
        MaintenanceEditorMode.ESTIMATE -> backend.api.createEstimate(
            idempotencyKey = editor.createIdempotencyKey,
            request = CreateEstimateRequest(
                warehouseId = requireWarehouseId(),
                rentalItemId = content.asset.id,
                dispatchDate = content.dispatchDate,
                sourceParty = content.sourceParty,
                lines = content.lines,
                plan = content.stages,
                mediaReferences = content.mediaReferences,
                coverMediaId = content.coverMediaId,
            ),
        ).toPersistedMaintenanceEntity()

        MaintenanceEditorMode.REPAIR -> {
            if (editor.repairKind == "REWORK") {
                val sourceRepairId = requireNotNull(editor.sourceRepairId) {
                    "Не указан исходный ремонт"
                }
                val sourceVersion = requireNotNull(editor.sourceRepairExpectedVersion) {
                    "Не указана версия исходного ремонта"
                }
                backend.api.createRework(
                    repairId = sourceRepairId,
                    warehouseId = requireWarehouseId(),
                    idempotencyKey = editor.createIdempotencyKey,
                    request = CreateReworkRequest(
                        expectedVersion = sourceVersion,
                        reason = editor.reworkReason.trim(),
                        lines = reworkLineInputs(editor, content.lines),
                        plan = content.stages,
                        mediaReferences = emptyList(),
                        coverMediaId = null,
                    ),
                ).toPersistedMaintenanceEntity()
            } else {
                backend.api.createDirectRepair(
                    idempotencyKey = editor.createIdempotencyKey,
                    request = CreateDirectRepairRequest(
                        warehouseId = requireWarehouseId(),
                        rentalItemId = content.asset.id,
                        dispatchDate = content.dispatchDate,
                        sourceParty = content.sourceParty,
                        lines = content.lines,
                        plan = content.stages,
                        mediaReferences = content.mediaReferences,
                        coverMediaId = content.coverMediaId,
                    ),
                ).toPersistedMaintenanceEntity()
            }
        }
    }

    private fun reworkLineInputs(
        editor: MaintenanceEditorState,
        canonicalLines: List<EstimateLineInputDto>,
    ): List<ReworkLineInputDto> {
        val canonicalById = canonicalLines.associateBy(EstimateLineInputDto::id)
        return editor.lines.map { line ->
            when (line.reworkDisposition) {
                "REPEAT" -> ReworkLineInputDto(
                    id = line.id,
                    disposition = "REPEAT",
                    sourceRepairId = requireNotNull(line.sourceRepairId) {
                        "Для повторной строки не указан исходный ремонт"
                    },
                    sourceLineId = requireNotNull(line.sourceLineId) {
                        "Для повторной строки не указана исходная позиция"
                    },
                    quantity = canonicalMaintenanceQuantity(line.quantity),
                    comment = line.comment.trim().takeIf {
                        line.lineType == "WORK" && it.isNotEmpty()
                    },
                )

                "ADDED" -> ReworkLineInputDto(
                    id = line.id,
                    disposition = "ADDED",
                    line = requireNotNull(canonicalById[line.id]) {
                        "Новая строка доработки не прошла проверку"
                    },
                )

                else -> throw IllegalArgumentException(
                    "Выберите исходные позиции для переделки или добавьте новые",
                )
            }
        }
    }

    private suspend fun replaceMaintenanceEntity(
        editor: MaintenanceEditorState,
        content: MaintenanceDraftContent,
    ): PersistedMaintenanceEntity {
        val entityId = requireNotNull(editor.entityId) { "Сначала сохраните черновик" }
        val expectedVersion = requireNotNull(editor.expectedVersion) {
            "Сервис не вернул версию черновика"
        }
        return when (editor.mode) {
            MaintenanceEditorMode.ESTIMATE -> {
                if (maintenanceDocumentAlreadySubmitted(editor)) {
                    val expectedLinkedRepairVersion =
                        requireNotNull(editor.linkedRepairExpectedVersion) {
                            "Сервис не вернул версию связанного ремонта"
                        }
                    backend.api.amendEstimate(
                        estimateId = entityId,
                        warehouseId = requireWarehouseId(),
                        idempotencyKey = maintenanceAmendmentIdempotencyKey(editor),
                        request = AmendEstimateRequest(
                            expectedVersion = expectedVersion,
                            expectedLinkedRepairVersion = expectedLinkedRepairVersion,
                            dispatchDate = content.dispatchDate,
                            reason = MAINTENANCE_AMENDMENT_REASON,
                            sourceParty = content.sourceParty,
                            lines = content.lines,
                            plan = content.stages,
                            mediaReferences = content.mediaReferences,
                            coverMediaId = content.coverMediaId,
                        ),
                    ).toPersistedMaintenanceEntity()
                } else {
                    backend.api.replaceEstimate(
                        estimateId = entityId,
                        warehouseId = requireWarehouseId(),
                        request = ReplaceEstimateRequest(
                            expectedVersion = expectedVersion,
                            dispatchDate = content.dispatchDate,
                            sourceParty = content.sourceParty,
                            lines = content.lines,
                            plan = content.stages,
                            mediaReferences = content.mediaReferences,
                            coverMediaId = content.coverMediaId,
                        ),
                    ).toPersistedMaintenanceEntity()
                }
            }

            MaintenanceEditorMode.REPAIR -> backend.api.replaceRepairPlan(
                repairId = entityId,
                warehouseId = requireWarehouseId(),
                request = ReplaceRepairPlanRequest(
                    expectedVersion = expectedVersion,
                    lines = content.lines,
                    stages = content.stages,
                    mediaReferences = content.mediaReferences,
                    coverMediaId = content.coverMediaId,
                ),
            ).toPersistedMaintenanceEntity()
        }
    }

    private fun maintenanceAmendmentIdempotencyKey(editor: MaintenanceEditorState): String {
        editor.amendmentIdempotencyKey?.let { return it }
        val generated = UUID.randomUUID().toString()
        mutableState.update { current ->
            val currentEditor = current.maintenanceEditor
            if (currentEditor != null && currentEditor.entityId == editor.entityId) {
                current.copy(
                    maintenanceEditor = currentEditor.copy(
                        amendmentIdempotencyKey = generated,
                    ),
                )
            } else {
                current
            }
        }
        return generated
    }

    private fun updateMaintenanceEditorAfterSave(
        persisted: PersistedMaintenanceEntity,
        uploadedLocalUris: List<String> = emptyList(),
        uploadedReferences: List<MediaReferenceDto> = emptyList(),
    ) {
        val uploadedSet = uploadedLocalUris.toSet()
        mutableState.update { current ->
            val editor = current.maintenanceEditor
            if (editor == null || (editor.entityId != null && editor.entityId != persisted.id)) {
                current
            } else {
                val uploadedPreviewUris = uploadedReferences
                    .zip(uploadedLocalUris)
                    .associate { (reference, uri) -> reference.mediaId to uri }
                current.copy(
                    maintenanceEditor = editor.copy(
                        entityId = persisted.id,
                        expectedVersion = persisted.version,
                        readyMedia = persisted.mediaReferences,
                        readyPhotoUris = editor.readyPhotoUris + uploadedPreviewUris,
                        photoUris = editor.photoUris.filterNot(uploadedSet::contains),
                        coverPhotoKey =
                            persisted.coverMediaId
                                ?.let(::maintenanceReadyPhotoKey)
                                ?: maintenanceInitialCoverPhotoKey(persisted.mediaReferences)
                                ?: editor.coverPhotoKey,
                        linkedRepairExpectedVersion =
                            persisted.linkedRepairVersion ?: editor.linkedRepairExpectedVersion,
                        amendmentIdempotencyKey = null,
                    ),
                )
            }
        }
    }

    private fun EstimateDto.toPersistedMaintenanceEntity(): PersistedMaintenanceEntity =
        PersistedMaintenanceEntity(id, version, mediaReferences, coverMediaId)

    private fun EstimateCommandResultDto.toPersistedMaintenanceEntity(): PersistedMaintenanceEntity =
        PersistedMaintenanceEntity(
            id = estimate.id,
            version = estimate.version,
            mediaReferences = estimate.mediaReferences,
            coverMediaId = estimate.coverMediaId,
            linkedRepairVersion = repair?.version,
        )

    private fun RepairDto.toPersistedMaintenanceEntity(): PersistedMaintenanceEntity =
        PersistedMaintenanceEntity(id, version, mediaReferences, coverMediaId)

    private fun requireMaintenanceEditor(): MaintenanceEditorState =
        requireNotNull(mutableState.value.maintenanceEditor) {
            "Откройте смету или ремонт"
        }

    private fun closeMaintenanceEditorAfterPersistence() {
        editorClose.closeMaintenanceEditor()
    }
}
