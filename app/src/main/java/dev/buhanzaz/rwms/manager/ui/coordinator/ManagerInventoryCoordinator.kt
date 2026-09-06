package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.media.retryMediaReadAfterOwnerProof
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.CreateFindingAssetRequest
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ObservationInput
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.ResolveInventoryConflictRequest
import dev.buhanzaz.rwms.manager.network.ResolveNumberRequest
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.network.RwmsApi
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadArea
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadDraft
import dev.buhanzaz.rwms.manager.uploads.InventoryUploadCommand
import dev.buhanzaz.rwms.manager.uploads.PendingBackgroundPhoto
import dev.buhanzaz.rwms.manager.uploads.readyOwnerMediaReferencesById
import dev.buhanzaz.rwms.manager.uploads.rebaseRetainedInventoryMediaReferences
import dev.buhanzaz.rwms.manager.uploads.rebaseRetainedWorkLineMedia
import dev.buhanzaz.rwms.manager.ui.components.isManagerVideoUri
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Coordinates inventory refreshes, reinspection editors and background inspection uploads.
 * Its operations reduce the shared state flow and deliberately retain the existing cache, fencing
 * and outbox behavior.
 */
internal class ManagerInventoryCoordinator(
    private val runtime: ManagerCommandRuntime,
    private val api: RwmsApi,
    private val backgroundUploads: kotlinx.coroutines.Deferred<
        dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator,
    >,
    private val commandKeys: StableCommandKeys,
    private val managerReadCache: ManagerReadCache,
    private val catalogAccess: ManagerMaintenanceCatalogAccess,
    private val media: ManagerMediaPort,
    private val draftStore: InventoryDraftStore,
) {
    private val mutableState
        get() = runtime.mutableState

    private fun command(block: suspend () -> Unit) = runtime.command(block)

    private fun message(value: String) = runtime.message(value)

    private fun requireWarehouseId(): String = runtime.requireWarehouseId()

    private suspend fun ensureMaintenanceCatalog(warehouseId: String) =
        catalogAccess.ensureMaintenanceCatalog(warehouseId)

    private fun readCacheScope(warehouseId: String): ManagerReadCacheScope =
        mutableState.value.readCacheScope(warehouseId)

    private val inventoryReadMutex = Mutex()
    private val draftWriteMutex = Mutex()
    private var activeDraftScope: InventoryDraftScope? = null
    private var activeDraftRoute: String = inventoryDraftRouteOrDefault("")

    /** Restores only the draft owned by the newly verified account-and-warehouse pair. */
    suspend fun activateDraftScope(scope: InventoryDraftScope?) {
        if (activeDraftScope == scope) return
        draftWriteMutex.withLock {
            if (activeDraftScope == scope) return@withLock
            activeDraftScope = scope
            activeDraftRoute = inventoryDraftRouteOrDefault("")
            if (scope == null) {
                mutableState.update { current ->
                    current.copy(inventoryEditor = null, inventoryResumeRoute = null)
                }
                return@withLock
            }
            val restored = draftStore.read(scope)
            if (activeDraftScope != scope ||
                mutableState.value.currentUser?.id != scope.ownerAccountId ||
                mutableState.value.selectedWarehouseId != scope.warehouseId
            ) {
                return@withLock
            }
            activeDraftRoute = restored?.route ?: inventoryDraftRouteOrDefault("")
            mutableState.update { current ->
                current.copy(
                    inventorySession = restored?.inventorySession ?: current.inventorySession,
                    inventoryEditor = restored?.editor,
                    inventoryResumeRoute = restored?.route,
                )
            }
        }
    }

    /** Persists the current inventory step together with the latest editor snapshot. */
    fun recordInventoryRoute(route: String) {
        val normalized = inventoryDraftRouteOrDefault(route)
        if (activeDraftRoute == normalized && mutableState.value.inventoryResumeRoute == null) {
            return
        }
        activeDraftRoute = normalized
        mutableState.update { current -> current.copy(inventoryResumeRoute = null) }
        persistCurrentDraft()
    }

    /** Marks the restored route as consumed; the route itself remains in the crash-safe draft. */
    fun consumeInventoryResumeRoute() {
        mutableState.update { current -> current.copy(inventoryResumeRoute = null) }
    }

    fun loadInventory() = command {
        refreshInventory()
    }

    private fun persistCurrentDraft() {
        runtime.scope.launch {
            try {
                draftWriteMutex.withLock { writeCurrentDraftLocked() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runtime.handleFailure(failure)
            }
        }
    }

    private suspend fun writeCurrentDraftLocked(): InventoryDraftSnapshot? {
        val scope = activeDraftScope ?: return null
        val current = mutableState.value
        if (current.currentUser?.id != scope.ownerAccountId ||
            current.selectedWarehouseId != scope.warehouseId
        ) {
            return null
        }
        val editor = current.inventoryEditor ?: return null
        val session = current.inventorySession ?: return null
        val durable = draftStore.write(
            scope,
            InventoryDraftSnapshot(
                editor = editor,
                inventorySession = session,
                route = activeDraftRoute,
            ),
        )
        if (activeDraftScope == scope) {
            mutableState.update { latest ->
                if (latest.inventoryEditor?.findingId == editor.findingId) {
                    latest.copy(inventoryEditor = durable.editor)
                } else {
                    latest
                }
            }
        }
        return durable
    }

    private suspend fun durableDraftEditor(
        editor: InventoryEditorState,
        session: dev.buhanzaz.rwms.manager.network.InventorySessionDto,
        route: String,
    ): InventoryEditorState {
        val scope = activeDraftScope ?: return editor
        if (scope.ownerAccountId != mutableState.value.currentUser?.id ||
            scope.warehouseId != session.warehouseId
        ) {
            return editor
        }
        activeDraftRoute = inventoryDraftRouteOrDefault(route)
        return draftWriteMutex.withLock {
            draftStore.write(
                scope,
                InventoryDraftSnapshot(editor, session, activeDraftRoute),
            ).editor
        }
    }

    fun resolveInventoryNumber(
        number: String,
        onInspectionChoiceRequired: (InventoryFindingDto) -> Unit,
        onReady: () -> Unit,
    ) = command {
        val session = mutableState.value.inventorySession
            ?: throw IllegalStateException("Сначала начните инвентаризацию")
        val normalizedNumber = number.trim()
        if (normalizedNumber.isBlank()) throw IllegalArgumentException("Введите номер бытовки")
        ensureMaintenanceCatalog(session.warehouseId)
        val signature =
            "inventory-resolve:${session.id}:${session.sessionRevision}:$normalizedNumber"
        val resolution = api.resolveInventoryNumber(
            inventoryId = session.id,
            idempotencyKey = commandKeys.key(signature),
            request = ResolveNumberRequest(
                expectedSessionRevision = session.sessionRevision,
                submittedNumber = normalizedNumber,
            ),
        )
        val finding = resolution.finding
        if (finding?.requiresInventoryReinspectionChoice() == true) {
            // Never seed an editor with an implicit mode.  The operator must explicitly choose
            // whether the prior review is kept as a draft or fully replaced.
            mutableState.update { current -> current.copy(inventoryEditor = null) }
            commandKeys.complete(signature)
            onInspectionChoiceRequired(finding)
            return@command
        }
        val passport = finding?.inspectionPassport().orEmpty()
        val creationOptions = if (
            resolution.outcome == "MATCHED" || resolution.outcome == "NOT_FOUND"
        ) {
            api.rentalItemCreationOptions(requireWarehouseId())
        } else {
            null
        }
        val equipmentCatalog = api.equipment(session.warehouseId)
            .map { it.equipment }
            .inventoryFurnitureCatalog()
        val parsedCharacteristics = parseInventoryCharacteristics(
            passport["characteristics"],
        )
        val planContent = finding.inventoryPlanEditorContent()
        val editor = durableDraftEditor(
            editor = InventoryEditorState(
                findingId = finding?.id ?: UUID.randomUUID().toString(),
                number = resolution.displayCanonicalNumber,
                outcome = resolution.outcome,
                finding = finding,
                creationOptions = creationOptions,
                rentalType = passport.text("rentalType"),
                dimensions = passport.text("dimensions"),
                finishing = passport.text("finishing"),
                category = passport.text("category"),
                characteristics = if (resolution.outcome == "NOT_FOUND") {
                    // The canonical asset contract deliberately has no browser/app default
                    // characteristics. A new passport starts empty until the operator picks
                    // values from the service-provided catalog.
                    emptyList<String>()
                } else {
                    parsedCharacteristics.selected
                },
                sanitaryToilets = parsedCharacteristics.toilets,
                sanitarySinks = parsedCharacteristics.sinks,
                sanitaryShowers = parsedCharacteristics.showers,
                linoleum = passport["linoleum"] as? Boolean,
                comment = finding?.comment.orEmpty(),
                equipmentCatalog = equipmentCatalog,
                equipmentQuantities = finding
                    ?.inventoryFurnitureInitialQuantities(equipmentCatalog)
                    .orEmpty(),
                planLines = planContent.lines,
                planStages = planContent.stages,
                planPriority = finding?.frozenPlan?.priority
                    ?: DEFAULT_MAINTENANCE_PRIORITY,
                planForceCapitalRepair = finding?.frozenPlan?.forceCapitalRepair ?: false,
                planMovementToRepair = finding?.frozenPlan?.movementToRepair ?: false,
                planLogisticsPlanningMode = if (
                    finding?.frozenPlan?.movementToRepair == true
                ) {
                    LOGISTICS_PLANNING_MODE_AUTO
                } else {
                    null
                },
                planLogisticsScheduledDate = null,
            ),
            session = session,
            route = "manager-inventory-editor",
        )
        mutableState.update { current -> current.copy(inventoryEditor = editor) }
        commandKeys.complete(signature)
        onReady()
    }

    /** Opens the inspection flow for either a registered or a new rental-item number. */
    fun prepareNewInventoryNumber(
        number: String,
        onInspectionChoiceRequired: (InventoryFindingDto) -> Unit,
        onReady: () -> Unit,
    ) = resolveInventoryNumber(number, onInspectionChoiceRequired, onReady)

    fun openInventoryFinding(
        finding: InventoryFindingDto,
        mode: InventoryReinspectionMode,
        onReady: () -> Unit,
    ) = command {
        val warehouseId = requireWarehouseId()
        val latest = if (finding.requiresInventoryReinspectionChoice()) {
            // A stale revision is not a safe starting point for a repeat inspection. In
            // particular, an old background upload must never overwrite a newer inspection.
            // When offline, force=true deliberately fails instead of opening cached data as an
            // editable reinspection. A first, not-yet-saved inspection keeps its existing
            // offline-capable flow.
            refreshInventory(force = true)
            mutableState.value.inventoryFindings
                .firstOrNull { it.id == finding.id }
                ?: throw IllegalStateException(
                    "Проверка больше недоступна. Обновите инвентаризацию и выберите бытовку снова",
                )
        } else {
            mutableState.value.inventoryFindings
                .firstOrNull { it.id == finding.id }
                ?: finding
        }
        if (mode == InventoryReinspectionMode.REPLACE &&
            !latest.requiresInventoryReinspectionChoice()
        ) {
            throw IllegalStateException("Бытовка ещё не проверена: перезаписывать нечего")
        }
        ensureMaintenanceCatalog(warehouseId)
        val seed = latest.inventoryReinspectionSeed(mode)
        val passport = seed.passport
        val creationOptions = api.rentalItemCreationOptions(warehouseId)
        val equipmentCatalog = api.equipment(warehouseId)
            .map { it.equipment }
            .inventoryFurnitureCatalog()
        val furnitureSeed = latest.inventoryFurnitureReinspectionSeed(equipmentCatalog, mode)
        val persistedPhotos = if (seed.retainPreviousInspection) {
            media.loadInventoryPhotoUris(latest, warehouseId)
        } else {
            emptyList()
        }
        try {
            val persistedPhotoMedia = persistedPhotos.associate { photo ->
                photo.uri to photo.reference
            }
            val parsedCharacteristics = parseInventoryCharacteristics(
                passport["characteristics"],
            )
            val planContent = if (seed.retainPreviousInspection) {
                latest.inventoryPlanEditorContent()
            } else {
                RepairEditorContent(emptyList(), emptyList())
            }
            val previousPlan = latest.frozenPlan.takeIf { seed.retainPreviousInspection }
            val session = mutableState.value.inventorySession
                ?: throw IllegalStateException("Активная инвентаризация не найдена")
            val editor = durableDraftEditor(
                editor = InventoryEditorState(
                    findingId = latest.id,
                    number = latest.displayCanonicalNumber,
                    outcome = "MATCHED",
                    readOnly = mode == InventoryReinspectionMode.REVIEW,
                    finding = latest,
                    creationOptions = creationOptions,
                    rentalType = passport.text("rentalType"),
                    dimensions = passport.text("dimensions"),
                    finishing = passport.text("finishing"),
                    category = passport.text("category"),
                    characteristics = parsedCharacteristics.selected,
                    sanitaryToilets = parsedCharacteristics.toilets,
                    sanitarySinks = parsedCharacteristics.sinks,
                    sanitaryShowers = parsedCharacteristics.showers,
                    linoleum = passport["linoleum"] as? Boolean,
                    comment = seed.comment,
                    photoUris = persistedPhotos.map(ScopedMediaResult::uri),
                    coverPhotoUri = persistedPhotos
                        .firstOrNull { photo -> photo.reference.mediaId == latest.coverMediaId }
                        ?.uri,
                    persistedPhotoMedia = persistedPhotoMedia,
                    removedPersistedMediaIds = latest.inventoryReinspectionRemovedMediaIds(mode),
                    equipmentCatalog = equipmentCatalog,
                    equipmentObservationRequested = furnitureSeed.observationRequested,
                    equipmentQuantities = furnitureSeed.quantities,
                    planLines = planContent.lines,
                    planStages = planContent.stages,
                    planPriority = previousPlan?.priority ?: DEFAULT_MAINTENANCE_PRIORITY,
                    planForceCapitalRepair = previousPlan?.forceCapitalRepair ?: false,
                    planMovementToRepair = previousPlan?.movementToRepair ?: false,
                    planLogisticsPlanningMode = if (previousPlan?.movementToRepair == true) {
                        LOGISTICS_PLANNING_MODE_AUTO
                    } else {
                        null
                    },
                    planLogisticsScheduledDate = null,
                ),
                session = session,
                route = "manager-inventory-editor",
            )
            mutableState.update { current -> current.copy(inventoryEditor = editor) }
            onReady()
        } finally {
            media.releasePhotoUris(persistedPhotos.map(ScopedMediaResult::uri))
        }
    }

    /** Enables the retained review editor in place so navigation stays on the requested step. */
    fun beginInventorySupplement() {
        editInventory(InventoryEditorState::beginInventorySupplement)
    }

    fun resolveInventoryConflict(
        finding: InventoryFindingDto,
        strategy: String,
        reason: String?,
        onResolved: () -> Unit,
    ) = command {
        require(strategy == "ACCEPT_REGISTRY" || strategy == "KEEP_INSPECTION") {
            "Неизвестный способ разрешения конфликта"
        }
        val session = mutableState.value.inventorySession
            ?: throw IllegalStateException("Активная инвентаризация не найдена")
        val latest = mutableState.value.inventoryFindings
            .firstOrNull { it.id == finding.id }
            ?: finding
        if (latest.conflicts.isEmpty()) {
            throw IllegalStateException("Конфликт уже разрешён. Обновите данные")
        }
        val normalizedReason = reason?.trim()?.takeIf(String::isNotEmpty)
        if (strategy == "KEEP_INSPECTION" && normalizedReason == null) {
            throw IllegalArgumentException(
                "Укажите причину, почему нужно оставить данные осмотра",
            )
        }
        if ((normalizedReason?.length ?: 0) > 2_000) {
            throw IllegalArgumentException("Причина не может быть длиннее 2000 символов")
        }
        try {
            api.resolveInventoryConflict(
                inventoryId = session.id,
                findingId = latest.id,
                request = ResolveInventoryConflictRequest(
                    expectedSessionRevision = session.sessionRevision,
                    expectedFindingRevision = latest.findingRevision,
                    strategy = strategy,
                    reason = if (strategy == "KEEP_INSPECTION") {
                        normalizedReason
                    } else {
                        null
                    },
                ),
            )
        } catch (failure: HttpException) {
            if (failure.code() == 409) {
                refreshInventory()
                throw IllegalStateException(
                    "Конфликт уже изменился. Данные обновлены — выберите действие снова",
                    failure,
                )
            }
            throw failure
        }
        refreshInventory()
        message(
            if (strategy == "ACCEPT_REGISTRY") {
                "Данные реестра приняты"
            } else {
                "Данные осмотра сохранены"
            },
        )
        onResolved()
    }

    fun editInventory(update: (InventoryEditorState) -> InventoryEditorState) {
        mutableState.update { current ->
            current.copy(inventoryEditor = current.inventoryEditor?.let(update))
        }
        persistCurrentDraft()
    }

    fun editInventoryPlan(
        update: (MaintenanceEditorState) -> MaintenanceEditorState,
    ) {
        editInventory { inventory ->
            val current = inventory.toMaintenancePlanEditor()
            inventory.withMaintenancePlanEditor(
                normalizeMaintenanceEditor(
                    editor = update(current),
                    catalogNodesById = catalogAccess.nodesById,
                    catalogLinks = mutableState.value.maintenanceCatalogLinks,
                ),
            )
        }
    }

    fun addInventoryCatalogNodes(
        nodes: List<CatalogNodeDto>,
        quantity: String,
        comment: String,
        existingWorkLineId: String? = null,
        photoUris: List<String> = emptyList(),
        mediaReferences: List<MediaReferenceDto> = emptyList(),
    ): Boolean {
        val inventory = mutableState.value.inventoryEditor ?: return false
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
        val canonicalNodes = nodes
            .mapNotNull { node -> catalogAccess.nodesById[node.id] }
            .filter(CatalogNodeDto::isOperationalEstimateNode)
            .filter { node ->
                node.isAvailableForMaintenanceMode(
                    mode = MaintenanceEditorMode.REPAIR,
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
        if (!inventory.toMaintenancePlanEditor().hasSelectedCatalogWork(
                nodes = canonicalNodes,
                lineId = existingWorkLineId,
            )
        ) {
            message("Выбранная работа уже изменилась. Выберите её снова.")
            return false
        }
        editInventoryPlan { editor ->
            applyMaintenanceCatalogNodes(
                editor = editor,
                nodes = canonicalNodes,
                quantity = canonicalQuantity,
                comment = canonicalComment,
                existingWorkLineId = existingWorkLineId,
                photoUris = photoUris,
                mediaReferences = mediaReferences,
            )
        }
        return true
    }

    fun chooseInventoryOrigin(origin: String) {
        require(origin == "ADDED_NEW" || origin == "ADDED_USED")
        editInventory { it.withInventoryCreationOrigin(origin) }
    }

    fun addInventoryPhoto(uri: String) {
        runtime.scope.launch {
            try {
                draftWriteMutex.withLock {
                    val scope = activeDraftScope
                        ?: throw IllegalStateException("Хранилище черновика ещё не готово")
                    val current = mutableState.value
                    val editor = current.inventoryEditor
                        ?: throw IllegalStateException("Бытовка не выбрана")
                    val session = current.inventorySession
                        ?: throw IllegalStateException("Активная инвентаризация не найдена")
                    val durableUri = draftStore.importMedia(scope, uri)
                    val updatedEditor = if (durableUri in editor.photoUris) {
                        editor
                    } else {
                        editor.copy(photoUris = editor.photoUris + durableUri)
                    }
                    val durable = draftStore.write(
                        scope,
                        InventoryDraftSnapshot(updatedEditor, session, activeDraftRoute),
                    )
                    mutableState.update { latest ->
                        if (activeDraftScope == scope &&
                            latest.inventoryEditor?.findingId == editor.findingId
                        ) {
                            latest.copy(inventoryEditor = durable.editor)
                        } else {
                            latest
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runtime.handleFailure(failure)
            }
        }
    }

    fun removeInventoryPhoto(uri: String) {
        editInventory { editor -> editor.removeInventoryPhoto(uri) }
    }

    fun selectInventoryCoverPhoto(uri: String) {
        if (isManagerVideoUri(uri)) {
            message("Обложкой может быть только фотография")
            return
        }
        if (uri !in mutableState.value.inventoryEditor?.photoUris.orEmpty()) {
            message("Выбранная фотография не найдена")
            return
        }
        editInventory { editor ->
            editor.copy(coverPhotoUri = uri)
        }
    }

    fun closeInventoryEditor() {
        mutableState.update {
            it.copy(inventoryEditor = null, inventoryResumeRoute = null)
        }
        runtime.scope.launch {
            try {
                draftWriteMutex.withLock {
                    if (mutableState.value.inventoryEditor == null) {
                        activeDraftScope?.let { scope -> draftStore.clear(scope) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runtime.handleFailure(failure)
            }
        }
    }

    fun saveInventoryInspection(onSaved: () -> Unit) = command {
        val initialSession = mutableState.value.inventorySession
            ?: throw IllegalStateException("Активная инвентаризация не найдена")
        val editor = mutableState.value.inventoryEditor
            ?: throw IllegalStateException("Бытовка не выбрана")
        if (!editor.canInspect) {
            throw IllegalStateException(
                editor.finding?.conflicts?.joinToString { it.message }
                    ?: "Конфликт сверки не позволяет подтвердить бытовку",
            )
        }
        editor.inventoryPhotoValidationError()
            ?.let { throw IllegalArgumentException(it) }
        editor.inventoryEquipmentObservationValidationError()
            ?.let { throw IllegalArgumentException(it) }
        val passport = editor.passport()
        val equipmentObservation = editor.inventoryEquipmentObservation()
        var finding = editor.finding
        var session = initialSession
        var createSignature: String? = null
        if (editor.isCreation) {
            editor.inventoryCreationValidationError(
                hasCreationPhoto = editor.photoUris.isNotEmpty(),
                hasCoverPhoto = editor.coverPhotoUri in editor.photoUris,
            )
                ?.let { throw IllegalArgumentException(it) }
            val origin = editor.creationOrigin
                ?: throw IllegalArgumentException("Выберите: новая или б/у")
            val signature = buildString {
                append(
                    "inventory-create:${session.id}:${session.sessionRevision}:" +
                        "${editor.findingId}:$origin:${editor.number}:",
                )
                append(passport.toSortedMap().entries.joinToString())
            }
            createSignature = signature
            finding = api.createInventoryAsset(
                inventoryId = session.id,
                findingId = editor.findingId,
                idempotencyKey = commandKeys.key(signature),
                request = CreateFindingAssetRequest(
                    expectedSessionRevision = session.sessionRevision,
                    expectedFindingRevision = 0,
                    origin = origin,
                    displayCanonicalNumber = editor.number,
                    safePassport = passport,
                ),
            )
            session = api.inventory(session.id)
        }
        val attached = requireNotNull(finding) { "Сервис не вернул найденную бытовку" }
        val orderedPhotoUris = editor.inventoryPhotoUrisForUpload()
        val pendingPhotoUris = editor.pendingInventoryPhotoUris()
        val photoSortOrderByUri = orderedPhotoUris.withIndex().associate { (index, uri) ->
            uri to index
        }
        val owner = MediaOwner(
            ownerType = "INVENTORY_FINDING",
            ownerId = attached.id,
            warehouseId = session.warehouseId,
            context = "INSPECTION",
        )
        val persistedMedia = editor.persistedInventoryMediaReferences()
        val planLineIds = editor.planLines.map(MaintenanceLineEditorState::id)
        val planWorkLineIds = editor.planLines
            .asSequence()
            .filter { line -> line.lineType == "WORK" }
            .map(MaintenanceLineEditorState::id)
            .toList()
        val hasRetainedPlanMedia = editor.planLines
            .asSequence()
            .filter { line -> line.lineType == "WORK" }
            .any { line -> line.mediaReferences.isNotEmpty() }
        val readyOwnerReferencesById = if (
            persistedMedia.isNotEmpty() ||
                hasRetainedPlanMedia ||
                editor.uploadedPhotoMedia.isNotEmpty()
        ) {
            retryMediaReadAfterOwnerProof {
                readyOwnerMediaReferencesById(
                    api.ownerMedia(
                        ownerType = "INVENTORY_FINDING",
                        ownerId = attached.id,
                        warehouseId = session.warehouseId,
                        context = "INSPECTION",
                    ).items,
                )
            }
        } else {
            emptyMap()
        }
        val readyPersistedMedia = rebaseRetainedInventoryMediaReferences(
            references = persistedMedia,
            currentReadyByMediaId = readyOwnerReferencesById,
        )
        val existingMedia = rebaseRetainedInventoryMediaReferences(
            references = inventoryExistingMediaReferences(
                persisted = readyPersistedMedia,
                uploadedByUri = editor.uploadedPhotoMedia,
            ),
            currentReadyByMediaId = readyOwnerReferencesById,
        )
        if (orderedPhotoUris.isEmpty() && existingMedia.isEmpty()) {
            throw IllegalArgumentException("Добавьте хотя бы одну фотографию")
        }
        val existingCoverMediaId = editor.coverPhotoUri
            ?.let { uri ->
                editor.persistedPhotoMedia[uri] ?: editor.uploadedPhotoMedia[uri]
            }
            ?.mediaId
            ?: attached.coverMediaId?.takeIf { mediaId ->
                existingMedia.any { reference -> reference.mediaId == mediaId }
            }
            ?: existingMedia.firstOrNull()?.mediaId
        val planSelection = buildInventoryPlanSelection(
            editor = editor,
            coverMediaId = existingCoverMediaId,
            catalogNodesById = catalogAccess.nodesById,
            catalogLinks = mutableState.value.maintenanceCatalogLinks,
        )?.rebaseRetainedWorkLineMedia(
            currentReadyByMediaId = readyOwnerReferencesById,
            lineIds = planLineIds,
            workLineIds = planWorkLineIds.toSet(),
        )
        backgroundUploads.await().enqueue(
            BackgroundUploadDraft(
                area = BackgroundUploadArea.INVENTORY,
                title = "Проверка бытовки ${editor.number}",
                subtitle = "Инвентаризация",
                photos = pendingPhotoUris.map { uri ->
                    PendingBackgroundPhoto(
                        uri = uri,
                        owner = owner,
                        sortOrder = requireNotNull(photoSortOrderByUri[uri]),
                        cover = uri == editor.coverPhotoUri,
                    )
                } + editor.planLines
                    .asSequence()
                    .filter { line -> line.lineType == "WORK" }
                    .flatMapIndexed { lineIndex, line ->
                        line.photoUris.distinct().mapIndexed { photoIndex, uri ->
                        PendingBackgroundPhoto(
                            uri = uri,
                            owner = owner,
                            sortOrder = orderedPhotoUris.size + lineIndex * 100 + photoIndex,
                            lineId = line.id,
                        )
                        }
                    }
                    .toList(),
                inventory = InventoryUploadCommand(
                    inventoryId = session.id,
                    findingId = attached.id,
                    expectedFindingRevision = attached.findingRevision,
                    inspection = if (planSelection == null) "READY" else "WORK_STAGED",
                    comment = editor.comment.trim(),
                    passportObservation = if (editor.observationPassport().isEmpty()) {
                        ObservationInput("ABSENT", null)
                    } else {
                        ObservationInput("PRESENT", editor.observationPassport())
                    },
                    equipmentObservation = equipmentObservation,
                    existingMedia = existingMedia,
                    existingCoverMediaId = existingCoverMediaId,
                    planSelection = planSelection,
                    planLineIds = planLineIds,
                    planWorkLineIds = planWorkLineIds,
                ),
            ),
        )
        createSignature?.let(commandKeys::complete)
        mutableState.update { current ->
            current.copy(
                inventoryEditor = current.inventoryEditor?.takeUnless {
                    it.findingId == editor.findingId
                },
                inventoryResumeRoute = null,
            )
        }
        val completedDraftScope = activeDraftScope
        if (completedDraftScope != null) {
            withContext(NonCancellable) {
                draftWriteMutex.withLock {
                    if (activeDraftScope == completedDraftScope) {
                        draftStore.clear(completedDraftScope)
                    }
                }
            }
        }
        message("Проверка добавлена в фоновые загрузки")
        onSaved()
    }

    /** Editable maintenance content reconstructed from one frozen inventory plan. */
    private data class RepairEditorContent(
        val lines: List<MaintenanceLineEditorState>,
        val stages: List<MaintenanceStageEditorState>,
    )

    private fun InventoryFindingDto?.inventoryPlanEditorContent(): RepairEditorContent {
        val plan = this?.frozenPlan ?: return RepairEditorContent(emptyList(), emptyList())
        val lines = plan.lines.map { line ->
            val frozenRouting = if (
                line.routingQueueId == null &&
                line.routingQueueName == null &&
                line.routingQueueType == null
            ) {
                null
            } else {
                RoutingSnapshotDto(
                    queueId = requireNotNull(line.routingQueueId) {
                        "Сервис вернул неполный маршрут строки инвентаризации"
                    },
                    queueName = requireNotNull(line.routingQueueName) {
                        "Сервис вернул неполный маршрут строки инвентаризации"
                    },
                    queueType = requireNotNull(line.routingQueueType) {
                        "Сервис вернул неполный маршрут строки инвентаризации"
                    },
                )
            }
            MaintenanceLineEditorState(
                id = line.id,
                catalogNodeId = line.catalogNodeId,
                description = line.description,
                lineType = line.lineType,
                unit = line.unit,
                quantity = line.quantity,
                unitPrice = BigDecimal(line.unitPriceMinor)
                    .movePointLeft(2)
                    .stripTrailingZeros()
                    .toPlainString(),
                normativeMinutes = if (line.lineType == "MATERIAL") {
                    0
                } else {
                    BigDecimal(line.normativeMinutes).toInt()
                },
                comment = line.groupComment.orEmpty().takeIf {
                    line.lineType == "WORK"
                }.orEmpty(),
                catalogSnapshot = line.catalogNodeId?.let(catalogAccess.nodesById::get)
                    ?.toCatalogSnapshot(),
                customRouting = frozenRouting.takeIf { line.catalogNodeId == null },
                mediaReferences = line.mediaReferences.takeIf {
                    line.lineType == "WORK"
                }.orEmpty(),
            ).normalizedMaintenanceAnnotations()
        }
        val repairStages = plan.stages
            .asSequence()
            .filter { stage -> stage.kind == "REPAIR_WORK" }
            .sortedBy { it.order }
            .toList()
        val lineIndexesByStageId = inventoryFrozenPlanLineIndexesByStage(
            lines = plan.lines,
            stages = repairStages,
        )
        val stages = repairStages.map { stage ->
            val includedLines = lineIndexesByStageId.getValue(stage.id).map(lines::get)
            val primary = includedLines.firstOrNull { line -> line.lineType == "WORK" }
            MaintenanceStageEditorState(
                id = stage.id,
                kind = stage.kind,
                routing = RoutingSnapshotDto(
                    queueId = stage.routingQueueId,
                    queueName = stage.routingQueueName,
                    queueType = stage.routingQueueType,
                ),
                includedLineIds = includedLines.map(MaintenanceLineEditorState::id),
                primaryLineId = primary?.id,
                groupComment = includedLines
                    .asSequence()
                    .filter { line -> line.lineType == "WORK" }
                    .map(MaintenanceLineEditorState::comment)
                    .filter(String::isNotBlank)
                    .distinct()
                    .joinToString("; "),
                originalOrder = stage.order,
            )
        }
        return RepairEditorContent(lines, stages)
    }

    private suspend fun refreshInventory(force: Boolean = false) {
        val warehouseId = requireWarehouseId()
        val scope = readCacheScope(warehouseId)
        inventoryReadMutex.withLock {
            val cached = managerReadCache.readInventory(scope)
            val snapshot = try {
                val active = api.activeInventory(
                    warehouseId = warehouseId,
                    ifNoneMatch = if (force) null else cached?.activeEtag,
                )
                when (active.code()) {
                    304 -> requireNotNull(cached) {
                        "Сервер подтвердил старую инвентаризацию, которой нет на телефоне"
                    }.copy(activeEtag = active.headers()["ETag"] ?: cached.activeEtag)

                    204 -> CachedInventoryRead(
                        activeEtag = active.headers()["ETag"],
                        rentalItems = cached?.rentalItems.orEmpty(),
                    )

                    else -> {
                        if (!active.isSuccessful) throw HttpException(active)
                        val session = requireNotNull(active.body()) {
                            "RWMS не вернул активную инвентаризацию"
                        }
                        CachedInventoryRead(
                            activeEtag = active.headers()["ETag"],
                            rentalItems = loadInventoryRentalItems(warehouseId),
                            session = session,
                            findings = loadInventoryFindings(session.id),
                        )
                    }
                }
            } catch (failure: Throwable) {
                if (!force && cached != null && canUseCachedReadAfter(failure)) {
                    applyInventoryRead(warehouseId, cached)
                    return@withLock
                }
                throw failure
            }
            if (mutableState.value.selectedWarehouseId != warehouseId) return@withLock
            managerReadCache.writeInventory(scope, snapshot)
            applyInventoryRead(warehouseId, snapshot)
        }
    }

    private suspend fun loadInventoryRentalItems(warehouseId: String): List<RentalItemDto> {
        val rentalItems = mutableListOf<RentalItemDto>()
        var rentalPage = 0
        var rentalTotalPages: Int
        do {
            val result = api.rentalItems(
                warehouseId = warehouseId,
                page = rentalPage,
                size = 200,
            )
            rentalItems += result.content
            rentalTotalPages = result.totalPages
            rentalPage += 1
        } while (rentalPage < rentalTotalPages)
        return rentalItems
            .distinctBy(RentalItemDto::id)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { item -> item.number })
    }

    private suspend fun loadInventoryFindings(inventoryId: String): List<InventoryFindingDto> {
        val findings = mutableListOf<InventoryFindingDto>()
        var page = 0
        var totalPages: Int
        do {
            val result = api.inventoryFindings(inventoryId, page)
            findings += result.content
            totalPages = result.page.totalPages
            page += 1
        } while (page < totalPages)
        return findings
    }

    private fun applyInventoryRead(
        warehouseId: String,
        snapshot: CachedInventoryRead,
    ) {
        mutableState.update { current ->
            if (current.selectedWarehouseId == warehouseId) {
                current.copy(
                    inventoryRentalItems = snapshot.rentalItems,
                    inventorySession = snapshot.session,
                    inventoryFindings = snapshot.findings,
                )
            } else {
                current
            }
        }
    }
}
