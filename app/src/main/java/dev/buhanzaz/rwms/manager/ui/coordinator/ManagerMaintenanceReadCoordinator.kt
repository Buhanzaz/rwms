package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.media.retryMediaReadAfterOwnerProof
import dev.buhanzaz.rwms.manager.network.EstimateDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.uploads.AcceptanceUploadCommand
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadArea
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadDraft
import dev.buhanzaz.rwms.manager.uploads.PendingBackgroundPhoto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */

/** Refreshes maintenance projections after a successful command without exposing their state. */
internal interface ManagerMaintenanceRefreshPort {
    suspend fun refreshMaintenance(force: Boolean = false)

    /** Refreshes the single aggregate ordinary-board snapshot used by maintenance screens. */
    suspend fun refreshRepairTaskBoard(force: Boolean = false)

    suspend fun refreshAcceptance()
}

/** Reads one warehouse-scoped rental item for an editor that has already selected its identity. */
internal interface ManagerMaintenanceAssetReadPort {
    suspend fun maintenanceRentalItem(
        rentalItemId: String,
        warehouseId: String,
    ): RentalItemDto
}

/**
 * Coordinates maintenance lists, one aggregate repair-board snapshot, and acceptance review.
 * The cache fallback and owner-scoped acceptance media behavior remain in this workflow boundary.
 */
internal class ManagerMaintenanceReadCoordinator(
    private val runtime: ManagerCommandRuntime,
    private val backend: RwmsBackend,
    private val backgroundUploads: kotlinx.coroutines.Deferred<
        dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator,
    >,
    private val managerReadCache: ManagerReadCache,
    private val catalogAccess: ManagerMaintenanceCatalogAccess,
    private val media: ManagerMediaPort,
) : ManagerMaintenanceRefreshPort, ManagerMaintenanceAssetReadPort {
    private val mutableState
        get() = runtime.mutableState
    private val maintenanceReadMutex = Mutex()
    private val repairQueueMutex = Mutex()

    private fun command(block: suspend () -> Unit) = runtime.command(block)

    private fun message(value: String) = runtime.message(value)

    private fun readCacheScope(warehouseId: String): ManagerReadCacheScope =
        mutableState.value.readCacheScope(warehouseId)

    private fun requireWarehouseId(): String = runtime.requireWarehouseId()

    private suspend fun ensureMaintenanceCatalog(warehouseId: String) =
        catalogAccess.ensureMaintenanceCatalog(warehouseId)

    private suspend fun loadScopedPhotoUris(
        requests: List<ScopedMediaDownload>,
        warehouseId: String,
        preferCurrentOwnerReference: Boolean = false,
    ): List<ScopedMediaResult> = media.loadScopedPhotoUris(
        requests = requests,
        warehouseId = warehouseId,
        preferCurrentOwnerReference = preferCurrentOwnerReference,
    )

    fun loadMaintenance() = command { refreshMaintenance() }

    fun loadRepairQueue() = command {
        coroutineScope {
            async { refreshMaintenance() }
            async { refreshRepairTaskBoard() }
        }
    }

    fun loadAcceptance() = command { refreshAcceptance() }

    fun openAcceptance(repairId: String) = command {
        val warehouseId = requireWarehouseId()
        val repair = backend.api.repair(repairId, warehouseId)
        if (repair.executionState != "COMPLETED" || repair.acceptanceState != "PENDING") {
            throw IllegalStateException("Ремонт больше не ожидает приёмки")
        }
        val linkedEstimate = repair.estimateId?.let { estimateId ->
            backend.api.estimate(estimateId, warehouseId)
        }
        val asset = maintenanceRentalItem(repair.rentalItemId, warehouseId)
        val readyAcceptanceMedia = retryMediaReadAfterOwnerProof {
            backend.api.ownerMedia(
                ownerType = "MAINTENANCE_ACCEPTANCE",
                ownerId = repair.id,
                warehouseId = warehouseId,
                context = "ACCEPTANCE",
            )
        }.items
            .filter { media -> media.status == "READY" && media.generation > 0 }
            .sortedBy { media -> media.sortOrder }
            .map { media -> MediaReferenceDto(media.id, media.generation) }
        mutableState.update { current ->
            current.copy(
                maintenanceAssetLabels = current.maintenanceAssetLabels + (asset.id to asset.number),
                acceptanceEditor = MaintenanceAcceptanceEditorState(
                    repair = repair,
                    asset = asset,
                    cabinPhotos = acceptanceCabinMedia(repair, linkedEstimate),
                    readyMedia = readyAcceptanceMedia,
                ),
                acceptanceGallery = null,
            )
        }
    }

    fun closeAcceptance() {
        mutableState.update {
            it.copy(
                acceptanceEditor = null,
                acceptanceGallery = null,
            )
        }
    }

    fun openAcceptanceCabinPhotos() = command {
        val editor = requireNotNull(mutableState.value.acceptanceEditor) {
            "Откройте ремонт для приёмки"
        }
        loadAcceptanceGallery(editor.repair.id, editor.cabinPhotos)
    }

    fun openAcceptanceStagePhotos(stageId: String) = command {
        val editor = requireNotNull(mutableState.value.acceptanceEditor) {
            "Откройте ремонт для приёмки"
        }
        val stage = editor.repair.plan.stages.firstOrNull { it.id == stageId }
            ?: throw IllegalStateException("Этап ремонта больше не найден")
        loadAcceptanceGallery(editor.repair.id, acceptanceStageMedia(stage))
    }

    fun openAcceptanceWorkSourcePhotos(stageId: String, workLineId: String) = command {
        val editor = requireNotNull(mutableState.value.acceptanceEditor) {
            "Откройте ремонт для приёмки"
        }
        val stage = editor.repair.plan.stages.firstOrNull { it.id == stageId }
            ?: throw IllegalStateException("Этап ремонта больше не найден")
        val work = stage.workLines.firstOrNull { line -> line.id == workLineId }
            ?: throw IllegalStateException("Работа больше не найдена в этапе")
        loadAcceptanceGallery(
            editor.repair.id,
            acceptanceWorkSourceMedia(editor.repair, stage, work),
        )
    }

    fun closeAcceptanceGallery() {
        mutableState.update { it.copy(acceptanceGallery = null) }
    }

    fun editAcceptanceComment(value: String) {
        mutableState.update { current ->
            current.copy(
                acceptanceEditor = current.acceptanceEditor?.copy(
                    comment = value.take(2_000),
                ),
            )
        }
    }

    fun acceptAcceptanceWork(workLineId: String) {
        mutableState.update { current ->
            val editor = current.acceptanceEditor ?: return@update current
            val exists = editor.repair.plan.stages.any { stage ->
                stage.workLines.any { line -> line.id == workLineId }
            }
            require(exists) { "Работа больше не найдена в ремонте" }
            current.copy(
                acceptanceEditor = editor.copy(
                    acceptedWorkLineIds = editor.acceptedWorkLineIds + workLineId,
                ),
            )
        }
    }

    fun addAcceptancePhoto(uri: String) {
        mutableState.update { current ->
            val editor = current.acceptanceEditor ?: return@update current
            current.copy(
                acceptanceEditor = editor.copy(
                    photoUris = if (uri in editor.photoUris) {
                        editor.photoUris
                    } else {
                        editor.photoUris + uri
                    },
                ),
            )
        }
    }

    fun removeAcceptancePhoto(uri: String) {
        mutableState.update { current ->
            current.copy(
                acceptanceEditor = current.acceptanceEditor?.let { editor ->
                    editor.copy(photoUris = editor.photoUris - uri)
                },
            )
        }
    }

    fun acceptMaintenanceRepair(onSaved: () -> Unit) = command {
        val editor = requireNotNull(mutableState.value.acceptanceEditor) {
            "Откройте ремонт для приёмки"
        }
        val localUris = editor.photoUris
        val existingMedia = editor.readyMedia.distinctBy(MediaReferenceDto::mediaId)
        require(existingMedia.isNotEmpty() || localUris.isNotEmpty()) {
            "Добавьте хотя бы одно фото приёмки перед принятием"
        }
        require(editor.hasAcceptedAllWorkLines()) {
            "Примите каждую работу или отправьте её на доработку"
        }
        val warehouseId = requireWarehouseId()
        val owner = MediaOwner(
            ownerType = "MAINTENANCE_ACCEPTANCE",
            ownerId = editor.repair.id,
            warehouseId = warehouseId,
            context = "ACCEPTANCE",
        )
        backgroundUploads.await().enqueue(
            BackgroundUploadDraft(
                area = BackgroundUploadArea.ACCEPTANCE,
                title = "Приёмка ремонта",
                subtitle = mutableState.value.maintenanceAssetLabels[editor.repair.rentalItemId]
                    ?: editor.repair.rentalItemId,
                photos = localUris.mapIndexed { index, uri ->
                    PendingBackgroundPhoto(uri = uri, owner = owner, sortOrder = index)
                },
                acceptance = AcceptanceUploadCommand(
                    repairId = editor.repair.id,
                    warehouseId = warehouseId,
                    expectedVersion = editor.repair.version,
                    comment = editor.comment.trim().takeIf(String::isNotEmpty),
                    existingMedia = existingMedia,
                    idempotencyKey = editor.idempotencyKey,
                ),
            ),
        )
        closeAcceptance()
        message("Приёмка добавлена в фоновые загрузки")
        onSaved()
    }

    private suspend fun loadAcceptanceGallery(
        repairId: String,
        collection: AcceptanceMediaCollection,
    ) {
        val warehouseId = requireWarehouseId()
        if (mutableState.value.acceptanceEditor?.repair?.id != repairId) return
        val uniqueItems = collection.items.distinctBy { media ->
            listOf(
                media.ownerType,
                media.ownerId,
                media.context,
                media.reference.mediaId,
                media.reference.generation.toString(),
            ).joinToString(":")
        }
        val requests = uniqueItems.map { media ->
            ScopedMediaDownload(
                reference = media.reference,
                scopes = listOf(
                    MaintenanceMediaScope(
                        ownerType = media.ownerType,
                        ownerId = media.ownerId,
                        context = media.context,
                    ),
                ),
            )
        }
        val photoUris = loadScopedPhotoUris(requests, warehouseId)
            .map(ScopedMediaResult::uri)
        if (mutableState.value.acceptanceEditor?.repair?.id != repairId) return
        mutableState.update {
            it.copy(
                acceptanceGallery = AcceptanceGalleryState(
                    title = collection.title,
                    photoUris = photoUris,
                    emptyMessage = if (uniqueItems.isNotEmpty() && photoUris.isEmpty()) {
                        "Фотографии есть, но сейчас не загрузились. Проверьте связь с сервером и повторите."
                    } else {
                        collection.emptyMessage
                    },
                ),
            )
        }
    }

    override suspend fun refreshMaintenance(force: Boolean) {
        val warehouseId = requireWarehouseId()
        val scope = readCacheScope(warehouseId)
        ensureMaintenanceCatalog(warehouseId)
        maintenanceReadMutex.withLock {
            val cached = managerReadCache.readMaintenance(scope)
            val refreshed = try {
                coroutineScope {
                    val estimatesRequest = async {
                        backend.api.estimates(
                            warehouseId = warehouseId,
                            size = 200,
                            ifNoneMatch = if (force) null else cached?.estimatesEtag,
                        )
                    }
                    val repairsRequest = async {
                        backend.api.repairs(
                            warehouseId = warehouseId,
                            size = 200,
                            ifNoneMatch = if (force) null else cached?.repairsEtag,
                        )
                    }
                    val capitalRepairsRequest = async {
                        backend.api.activeCapitalRepairs(
                            warehouseId = warehouseId,
                            size = 200,
                        )
                    }
                    val estimates = conditionalRead(
                        response = estimatesRequest.await(),
                        cachedValue = cached?.estimates,
                        cachedEtag = cached?.estimatesEtag,
                        missingCacheMessage = "Сервер подтвердил старую смету, которой нет на телефоне",
                    )
                    val repairs = conditionalRead(
                        response = repairsRequest.await(),
                        cachedValue = cached?.repairs,
                        cachedEtag = cached?.repairsEtag,
                        missingCacheMessage = "Сервер подтвердил старый ремонт, которого нет на телефоне",
                    )
                    Triple(estimates, repairs, capitalRepairsRequest.await())
                }
            } catch (failure: Throwable) {
                if (cached?.capitalRepairs != null && canUseCachedReadAfter(failure)) {
                    applyMaintenanceRead(warehouseId, cached)
                    return@withLock
                }
                throw failure
            }
            if (mutableState.value.selectedWarehouseId != warehouseId) return@withLock

            val estimates = refreshed.first.value.items
            val repairs = refreshed.second.value.items
            val capitalRepairs = refreshed.third.items
            val retainedLabels = cached?.assetLabels.orEmpty()
            val labels = retainedLabels + resolveMaintenanceAssetLabels(
                (estimates.asSequence().map(EstimateDto::rentalItemId) +
                    repairs.asSequence().map(RepairDto::rentalItemId) +
                    capitalRepairs.asSequence().map(RepairDto::rentalItemId))
                    .distinct()
                    .filterNot(retainedLabels::containsKey)
                    .toList(),
            )
            val snapshot = CachedMaintenanceRead(
                estimates = refreshed.first.value,
                estimatesEtag = refreshed.first.etag,
                repairs = refreshed.second.value,
                repairsEtag = refreshed.second.etag,
                capitalRepairs = refreshed.third,
                assetLabels = labels,
            )
            managerReadCache.writeMaintenance(scope, snapshot)
            applyMaintenanceRead(warehouseId, snapshot)
        }
    }

    private fun applyMaintenanceRead(
        warehouseId: String,
        snapshot: CachedMaintenanceRead,
    ) {
        val repairLists = splitMaintenanceRepairLists(
            repairs = snapshot.repairs?.items.orEmpty(),
            activeCapitalRepairs = snapshot.capitalRepairs?.items.orEmpty(),
        )
        mutableState.update { current ->
            if (current.selectedWarehouseId != warehouseId) {
                current
            } else {
                current.copy(
                    estimates = snapshot.estimates?.items.orEmpty(),
                    repairs = repairLists.first,
                    capitalRepairs = repairLists.second,
                    maintenanceAssetLabels = current.maintenanceAssetLabels + snapshot.assetLabels,
                )
            }
        }
    }

    override suspend fun refreshRepairTaskBoard(force: Boolean) {
        val warehouseId = requireWarehouseId()
        val scope = readCacheScope(warehouseId)
        repairQueueMutex.withLock {
            val cached = managerReadCache.readRepairQueue(scope)
            val refreshed = try {
                conditionalRead(
                    response = backend.api.taskBoard(
                        warehouseId = warehouseId,
                        ifNoneMatch = if (force) null else cached?.etag,
                    ),
                    cachedValue = cached?.snapshot,
                    cachedEtag = cached?.etag,
                    missingCacheMessage = "Сервер подтвердил старую очередь, которой нет на телефоне",
                )
            } catch (failure: Throwable) {
                if (cached != null && canUseCachedReadAfter(failure)) {
                    applyRepairQueueRead(warehouseId, cached)
                    return@withLock
                }
                throw failure
            }
            if (mutableState.value.selectedWarehouseId != warehouseId) return@withLock

            val snapshot = CachedRepairQueueRead(
                snapshot = refreshed.value,
                etag = refreshed.etag,
            )
            managerReadCache.writeRepairQueue(scope, snapshot)
            applyRepairQueueRead(warehouseId, snapshot)
        }
    }

    private fun applyRepairQueueRead(
        warehouseId: String,
        snapshot: CachedRepairQueueRead,
    ) {
        mutableState.update { current ->
            if (current.selectedWarehouseId == warehouseId) {
                current.copy(repairTaskBoard = snapshot.snapshot)
            } else {
                current
            }
        }
    }

    override suspend fun refreshAcceptance() {
        val warehouseId = requireWarehouseId()
        val projections = backend.api.acceptance(
            warehouseId = warehouseId,
            size = 200,
        ).items
        val repairs = coroutineScope {
            projections
                .filter { projection ->
                    projection.executionState == "COMPLETED" &&
                        projection.acceptanceState == "PENDING"
                }
                .map { projection ->
                    async { backend.api.repair(projection.repairId, warehouseId) }
                }
                .map { request -> request.await() }
        }
        val labels = resolveMaintenanceAssetLabels(repairs.map(RepairDto::rentalItemId))
        if (mutableState.value.selectedWarehouseId != warehouseId) return
        mutableState.update { current ->
            val retainedEditor = current.acceptanceEditor?.takeIf { editor ->
                repairs.any { repair -> repair.id == editor.repair.id }
            }
            current.copy(
                acceptanceRepairs = repairs,
                maintenanceAssetLabels = current.maintenanceAssetLabels + labels,
                acceptanceEditor = retainedEditor,
                acceptanceGallery = current.acceptanceGallery.takeIf { retainedEditor != null },
            )
        }
    }

    internal suspend fun resolveMaintenanceAssetLabels(ids: List<String>): Map<String, String> =
        ids.distinct().associateWith { itemId ->
            backend.api.rentalItem(itemId).number.also { number ->
                require(number.isNotBlank()) { "Сервис не вернул номер бытовки" }
            }
        }

    override suspend fun maintenanceRentalItem(
        rentalItemId: String,
        warehouseId: String,
    ): RentalItemDto {
        val item = backend.api.rentalItem(rentalItemId)
        if (item.warehouseId != warehouseId) {
            throw IllegalStateException("Бытовка не относится к выбранному складу")
        }
        return item
    }
}

/**
 * Keeps every calculated CAPITAL repair out of the ordinary table while the separate list uses
 * the server-owned active-capital lifecycle. IDs cover a cache written before complexity was
 * client-visible; current responses use the authoritative calculated complexity.
 */
internal fun splitMaintenanceRepairLists(
    repairs: List<RepairDto>,
    activeCapitalRepairs: List<RepairDto>,
): Pair<List<RepairDto>, List<RepairDto>> {
    val activeCapitalIds = activeCapitalRepairs.mapTo(mutableSetOf(), RepairDto::id)
    val ordinary = repairs.filterNot { repair ->
        repair.complexity?.type == "CAPITAL" || repair.id in activeCapitalIds
    }
    return ordinary to activeCapitalRepairs
}
