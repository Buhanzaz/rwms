package dev.buhanzaz.rwms.manager.uploads

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.buhanzaz.rwms.manager.BuildConfig
import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.media.MediaUploader
import dev.buhanzaz.rwms.manager.media.PhotoPayloadReader
import dev.buhanzaz.rwms.manager.media.problemCode
import dev.buhanzaz.rwms.manager.media.retryInventoryCommitAfterMediaReady
import dev.buhanzaz.rwms.manager.media.retryMediaReadAfterOwnerProof
import dev.buhanzaz.rwms.manager.network.AcceptReturnLineRequest
import dev.buhanzaz.rwms.manager.network.AcceptReturnRequest
import dev.buhanzaz.rwms.manager.network.AmendEstimateRequest
import dev.buhanzaz.rwms.manager.network.ArriveTransferLineRequest
import dev.buhanzaz.rwms.manager.network.CreateCabinFurnitureTaskRequest
import dev.buhanzaz.rwms.manager.network.CompleteEstimateRequest
import dev.buhanzaz.rwms.manager.network.CurrentUserDto
import dev.buhanzaz.rwms.manager.network.EstimateLineInputDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventorySessionDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RepairDecisionRequest
import dev.buhanzaz.rwms.manager.network.ReplaceEstimateRequest
import dev.buhanzaz.rwms.manager.network.ReplaceRepairPlanRequest
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.network.SaveInspectionRequest
import dev.buhanzaz.rwms.manager.network.StartReturnEstimateLine
import dev.buhanzaz.rwms.manager.network.StartReturnEstimatesRequest
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException

/**
 * Executes one account-and-warehouse-bound WorkManager request. Before any media or domain
 * command, it resolves `/me` with the current session and proves that the persisted owner and
 * all declared warehouse scopes still belong to that same eligible manager account.
 */
class BackgroundUploadWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    private val store = BackgroundUploadStore.get(applicationContext)
    private val backend = RwmsBackend(applicationContext, BuildConfig.PUBLIC_BASE_URL)
    private val uploader = MediaUploader(
        backend.api,
        PhotoPayloadReader(applicationContext.contentResolver),
    )
    private var executionScope: BackgroundUploadScope? = null

    override suspend fun doWork(): Result {
        store.initialize()
        return operationPermit.withPermit {
            try {
                executeOperation()
            } finally {
                // Logout acquires the same permit after cancellation, so this snapshot is closed
                // before another principal can become active.
                backend.auth.close()
            }
        }
    }

    private suspend fun executeOperation(): Result {
        val operationId = inputData.getString(INPUT_OPERATION_ID)
            ?: return Result.failure()
        val ownerAccountId = inputData.getString(INPUT_OWNER_ACCOUNT_ID)
            ?: return Result.failure()
        val warehouseId = inputData.getString(INPUT_WAREHOUSE_ID)
            ?: return Result.failure()
        val scope = runCatching {
            BackgroundUploadScope(ownerAccountId, warehouseId)
        }.getOrElse { return Result.failure() }
        executionScope = scope
        val selectedPhotoId = inputData.getString(INPUT_PHOTO_ID)
        val operation = store.operation(scope, operationId) ?: return Result.success()
        return try {
            val currentUser = backend.api.currentUser()
            if (!BackgroundUploadAuthorizationPolicy.canExecute(currentUser, operation)) {
                return Result.success()
            }
            markRunning(operation.id)
            uploadPendingPhotos(operation.id, selectedPhotoId)
            val afterPhotos = store.operation(scope, operation.id) ?: return Result.success()
            if (afterPhotos.photos.any { it.status != BackgroundPhotoStatus.READY }) {
                store.update(scope, operation.id) { current ->
                    current.copy(
                        updatedAtEpochMillis = System.currentTimeMillis(),
                        status = BackgroundUploadStatus.FAILED,
                        stage = "Требуется дозагрузка фотографий",
                        error = current.photos
                            .firstNotNullOfOrNull(BackgroundUploadPhoto::error)
                            ?: "Не все фотографии загружены",
                    )
                }
                return Result.success()
            }
            finalizeOperation(afterPhotos)
            store.remove(scope, operation.id)
            Result.success()
        } catch (cancelled: CancellationException) {
            store.update(scope, operation.id) { current ->
                current.copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    status = BackgroundUploadStatus.QUEUED,
                    stage = "Ожидание продолжения",
                    error = null,
                    photos = current.photos.map { photo ->
                        if (photo.status == BackgroundPhotoStatus.UPLOADING) {
                            photo.copy(status = BackgroundPhotoStatus.QUEUED)
                        } else {
                            photo
                        }
                    },
                )
            }
            throw cancelled
        } catch (failure: Throwable) {
            val requiresUnaccountedFurnitureConfirmation =
                failure is HttpException &&
                    failure.problemCode() == UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED &&
                    operation.maintenance?.let { command ->
                        command.mode == "ESTIMATE" &&
                            command.submitRequest != null &&
                            !command.allowUnaccountedFurniture
                    } == true
            val message = failureMessage(failure)
            store.update(scope, operation.id) { current ->
                current.copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    status = BackgroundUploadStatus.FAILED,
                    stage = if (requiresUnaccountedFurnitureConfirmation) {
                        "Требуется подтверждение учёта мебели"
                    } else {
                        "Ошибка отправки"
                    },
                    error = message,
                    maintenance = if (requiresUnaccountedFurnitureConfirmation) {
                        current.maintenance?.copy(
                            requiresUnaccountedFurnitureConfirmation = true,
                        )
                    } else {
                        current.maintenance
                    },
                    photos = current.photos.map { photo ->
                        if (photo.status == BackgroundPhotoStatus.UPLOADING) {
                            photo.copy(status = BackgroundPhotoStatus.FAILED, error = message)
                        } else {
                            photo
                        }
                    },
                )
            }
            Result.success()
        }
    }

    private fun requireExecutionScope(): BackgroundUploadScope = requireNotNull(executionScope) {
        "Worker не получил проверенную область фоновой загрузки"
    }

    private fun markRunning(operationId: String) {
        store.update(requireExecutionScope(), operationId) { operation ->
            val ready = operation.photos.count { it.status == BackgroundPhotoStatus.READY }
            operation.copy(
                updatedAtEpochMillis = System.currentTimeMillis(),
                status = BackgroundUploadStatus.RUNNING,
                stage = if (operation.photos.isEmpty()) {
                    "Сохранение данных"
                } else {
                    "Загрузка фото $ready из ${operation.photos.size}"
                },
                error = null,
            )
        }
    }

    private suspend fun uploadPendingPhotos(operationId: String, selectedPhotoId: String?) {
        val snapshot = store.operation(requireExecutionScope(), operationId) ?: return
        val targets = snapshot.photos.filter { photo ->
            photo.status != BackgroundPhotoStatus.READY &&
                (selectedPhotoId == null || photo.id == selectedPhotoId)
        }
        if (targets.isEmpty()) return
        val targetIds = targets.mapTo(hashSetOf(), BackgroundUploadPhoto::id)
        store.update(requireExecutionScope(), operationId) { operation ->
            operation.copy(
                updatedAtEpochMillis = System.currentTimeMillis(),
                photos = operation.photos.map { photo ->
                    if (photo.id in targetIds) {
                        photo.copy(status = BackgroundPhotoStatus.UPLOADING, error = null)
                    } else {
                        photo
                    }
                },
            )
        }

        val ownerGroups = targets.groupBy(BackgroundUploadPhoto::owner)
        val ownerPermits = Semaphore(2)
        val failures = supervisorScope {
            ownerGroups.map { (owner, photos) ->
                async {
                    ownerPermits.withPermit {
                        runCatching { uploadOwnerPhotos(operationId, owner, photos) }
                            .exceptionOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (failures.isNotEmpty()) {
            val message = failureMessage(failures.first())
            store.update(requireExecutionScope(), operationId) { operation ->
                operation.copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    photos = operation.photos.map { photo ->
                        if (photo.id in targetIds && photo.status != BackgroundPhotoStatus.READY) {
                            photo.copy(status = BackgroundPhotoStatus.FAILED, error = message)
                        } else {
                            photo
                        }
                    },
                )
            }
        }
    }

    private suspend fun uploadOwnerPhotos(
        operationId: String,
        owner: MediaOwner,
        selected: List<BackgroundUploadPhoto>,
    ) {
        val selectedIds = selected.mapTo(hashSetOf(), BackgroundUploadPhoto::id)
        val current = store.operation(requireExecutionScope(), operationId)?.photos
            ?.filter { it.id in selectedIds }
            .orEmpty()
        val withoutReference = current.filter { it.reference == null }
        var uploadFailure: Throwable? = null
        if (withoutReference.isNotEmpty()) {
            try {
                uploader.upload(
                    owner = owner,
                    photoUris = withoutReference.map(BackgroundUploadPhoto::durableUri),
                    sortOrderByUri = withoutReference.associate { it.durableUri to it.sortOrder },
                    onReady = { uri, reference ->
                        updatePhoto(operationId, uri) { photo ->
                            photo.copy(reference = reference, error = null)
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                uploadFailure = failure
            }
        }

        val uploaded = store.operation(requireExecutionScope(), operationId)?.photos
            ?.filter { it.id in selectedIds }
            .orEmpty()
        uploaded.filter { it.reference != null }.forEach { photo ->
            val reference = requireNotNull(photo.reference)
            updatePhoto(operationId, photo.durableUri) { currentPhoto ->
                currentPhoto.copy(
                    status = BackgroundPhotoStatus.READY,
                    reference = reference,
                    error = null,
                )
            }
        }
        val missing = uploaded.firstOrNull { it.reference == null }
        if (missing != null) {
            throw uploadFailure
                ?: IllegalStateException("Фотография ${missing.sourceName} не была загружена")
        }
        uploadFailure?.let { throw it }
    }

    private fun updatePhoto(
        operationId: String,
        durableUri: String,
        transform: (BackgroundUploadPhoto) -> BackgroundUploadPhoto,
    ) {
        store.update(requireExecutionScope(), operationId) { operation ->
            val updatedPhotos = operation.photos.map { photo ->
                if (photo.durableUri == durableUri) transform(photo) else photo
            }
            val ready = updatedPhotos.count { it.status == BackgroundPhotoStatus.READY }
            operation.copy(
                updatedAtEpochMillis = System.currentTimeMillis(),
                stage = "Загрузка фото $ready из ${updatedPhotos.size}",
                photos = updatedPhotos,
            )
        }
    }

    private suspend fun finalizeOperation(operation: BackgroundUploadOperation) {
        when {
            operation.inventory != null -> finalizeInventory(operation, operation.inventory)
            operation.maintenance != null -> finalizeMaintenance(operation, operation.maintenance)
            operation.acceptance != null -> finalizeAcceptance(operation, operation.acceptance)
            operation.transferArrival != null ->
                finalizeTransferArrival(operation, operation.transferArrival)
            operation.returnAction != null -> finalizeReturn(operation, operation.returnAction)
            else -> error("Неизвестный тип фоновой операции")
        }
    }

    private suspend fun finalizeInventory(
        operation: BackgroundUploadOperation,
        command: InventoryUploadCommand,
    ) {
        val mediaPrepared = prepareInventoryRetainedMedia(operation, command)
        var preparedOperation = mediaPrepared.operation
        var preparedCommand = mediaPrepared.command
        if (!preparedCommand.inspectionSaved) {
            var revisionConflictRetryCount = 0
            while (true) {
                val revisionPrepared = prepareInventoryRevision(preparedOperation, preparedCommand)
                preparedOperation = revisionPrepared.operation
                preparedCommand = revisionPrepared.command
                val media = preparedOperation.aggregateReferences(preparedCommand.existingMedia)
                val coverMediaId = preparedOperation.coverMediaId() ?: preparedCommand.existingCoverMediaId
                updateStage(operation.id, "Сохранение инвентаризации")
                try {
                    retryInventoryCommitAfterMediaReady {
                        backend.api.saveInventoryInspection(
                            inventoryId = preparedCommand.inventoryId,
                            findingId = preparedCommand.findingId,
                            request = SaveInspectionRequest(
                                expectedSessionRevision = revisionPrepared.session.sessionRevision,
                                expectedFindingRevision = preparedCommand.expectedFindingRevision,
                                inspection = preparedCommand.inspection,
                                comment = preparedCommand.comment,
                                passportObservation = preparedCommand.passportObservation,
                                equipmentObservation = preparedCommand.equipmentObservation,
                                media = media,
                                coverMediaId = coverMediaId,
                                planSelection = preparedCommand.planSelection?.withUploadedMedia(
                                    coverMediaId,
                                    preparedOperation.lineReferences(),
                                    preparedCommand.planLineIds,
                                    preparedCommand.planWorkLineIds.toSet(),
                                ),
                            ),
                        )
                    }
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    if (inventoryInspectionAlreadySaved(preparedCommand, media, coverMediaId)) break
                    if (!shouldRetryInventoryRevisionConflict(failure, revisionConflictRetryCount)) {
                        throw failure
                    }
                    revisionConflictRetryCount += 1
                }
            }
            store.update(requireExecutionScope(), operation.id) { current ->
                current.copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    inventory = current.inventory?.copy(inspectionSaved = true),
                )
            }
        }
        preparedCommand.furnitureMove?.let { furniture ->
            updateStage(operation.id, "Создание задания по мебели")
            backend.api.createCabinFurnitureTask(
                rentalItemId = furniture.rentalItemId,
                idempotencyKey = furniture.idempotencyKey,
                request = CreateCabinFurnitureTaskRequest(
                    warehouseId = furniture.warehouseId,
                    scheduledDate = furniture.scheduledDate,
                    contents = furniture.desiredContents,
                ),
            )
        }
    }

    private data class PreparedInventoryUpload(
        val operation: BackgroundUploadOperation,
        val command: InventoryUploadCommand,
    )

    /**
     * Reconciles a durable first-inspection command with the current active finding before its
     * final side effect. A live asset-status refresh can legitimately advance this fence while
     * photos upload; a non-initial inspection is deliberately left fail-closed.
     */
    private suspend fun prepareInventoryRevision(
        operation: BackgroundUploadOperation,
        command: InventoryUploadCommand,
    ): PreparedInventoryRevision {
        updateStage(operation.id, "Проверка актуальности инвентаризации")
        val currentFinding = requireActiveInventoryFindingForUpload(
            activeInventoryFinding(command.inventoryId, command.findingId),
        )
        val session = backend.api.inventory(command.inventoryId)
        val rebasedCommand = command.preparePendingInspectionFence(currentFinding)
        if (rebasedCommand != command) {
            store.update(requireExecutionScope(), operation.id) { current ->
                current.copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    inventory = current.inventory?.takeIf { it.findingId == command.findingId }
                        ?.copy(expectedFindingRevision = rebasedCommand.expectedFindingRevision)
                        ?: current.inventory,
                )
            }
        }
        return PreparedInventoryRevision(
            operation = operation.copy(inventory = rebasedCommand),
            command = rebasedCommand,
            session = session,
        )
    }

    /** Captures the current active finding through the paginated public inventory read. */
    private suspend fun activeInventoryFinding(
        inventoryId: String,
        findingId: String,
    ): InventoryFindingDto? {
        var page = 0
        var totalPages = 1
        do {
            val findings = backend.api.inventoryFindings(inventoryId = inventoryId, page = page)
            totalPages = findings.page.totalPages
            findings.content.firstOrNull { it.id == findingId }?.let { return it }
            page += 1
        } while (page < totalPages)
        return null
    }

    /** Bundles the authoritative session fence with the safely rebased initial inspection. */
    private data class PreparedInventoryRevision(
        val operation: BackgroundUploadOperation,
        val command: InventoryUploadCommand,
        val session: InventorySessionDto,
    )

    /**
     * A durable operation can survive an app update while retained media is reprocessed. Rebase
     * it immediately before every final inventory save, so retry uses the current owner
     * generation for both aggregate evidence and the frozen work-line evidence.
     */
    private suspend fun prepareInventoryRetainedMedia(
        operation: BackgroundUploadOperation,
        command: InventoryUploadCommand,
    ): PreparedInventoryUpload {
        if (command.inspectionSaved || !operation.hasRetainedInventoryMedia(command)) {
            return PreparedInventoryUpload(operation, command)
        }
        updateStage(operation.id, "Проверка сохранённых фотографий")
        val session = backend.api.inventory(command.inventoryId)
        val readyOwnerReferencesById = retryMediaReadAfterOwnerProof {
            readyOwnerMediaReferencesById(
                backend.api.ownerMedia(
                    ownerType = "INVENTORY_FINDING",
                    ownerId = command.findingId,
                    warehouseId = session.warehouseId,
                    context = "INSPECTION",
                ).items,
            )
        }
        val rebasedOperation = operation.rebaseRetainedInventoryMedia(
            command = command,
            currentReadyByMediaId = readyOwnerReferencesById,
        )
        val rebasedCommand = requireNotNull(rebasedOperation.inventory)
        if (rebasedOperation != operation) {
            store.update(requireExecutionScope(), operation.id) { current ->
                current.copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    photos = rebasedOperation.photos,
                    inventory = rebasedCommand,
                )
            }
        }
        return PreparedInventoryUpload(rebasedOperation, rebasedCommand)
    }

    private suspend fun finalizeMaintenance(
        operation: BackgroundUploadOperation,
        command: MaintenanceUploadCommand,
    ) {
        updateStage(operation.id, "Сохранение ${if (command.mode == "ESTIMATE") "сметы" else "ремонта"}")
        val media = operation.aggregateReferences(command.existingMedia)
        val coverMediaId = operation.coverMediaId() ?: command.existingCoverMediaId
        val lineReferences = operation.lineReferences()
        val commandLineIds = command.lines.mapTo(mutableSetOf(), EstimateLineInputDto::id)
        require(lineReferences.keys.all(commandLineIds::contains)) {
            "Фоновая операция содержит фото отсутствующей строки"
        }
        val lines = command.lines.map { line ->
            if (line.lineType == "WORK") {
                line.copy(
                    mediaReferences = (
                        line.mediaReferences + lineReferences[line.id].orEmpty()
                        ).distinctBy(MediaReferenceDto::mediaId),
                )
            } else {
                line.copy(comment = null, mediaReferences = emptyList())
            }
        }
        val persistedVersion = when (command.replaceKind) {
            MaintenanceReplaceKind.NONE -> command.expectedVersion
            MaintenanceReplaceKind.ESTIMATE -> backend.api.replaceEstimate(
                estimateId = command.entityId,
                warehouseId = command.warehouseId,
                request = ReplaceEstimateRequest(
                    expectedVersion = command.expectedVersion,
                    dispatchDate = command.dispatchDate,
                    sourceParty = command.sourceParty,
                    lines = lines,
                    plan = command.stages,
                    mediaReferences = media,
                    coverMediaId = coverMediaId,
                    forceCapitalRepair = command.forceCapitalRepair,
                ),
            ).version
            MaintenanceReplaceKind.ESTIMATE_AMENDMENT -> backend.api.amendEstimate(
                estimateId = command.entityId,
                warehouseId = command.warehouseId,
                idempotencyKey = requireNotNull(command.amendmentIdempotencyKey),
                request = AmendEstimateRequest(
                    expectedVersion = command.expectedVersion,
                    expectedLinkedRepairVersion = command.expectedLinkedRepairVersion,
                    dispatchDate = command.dispatchDate,
                    reason = requireNotNull(command.amendmentReason),
                    sourceParty = command.sourceParty,
                    lines = lines,
                    plan = command.stages,
                    mediaReferences = media,
                    coverMediaId = coverMediaId,
                    forceCapitalRepair = command.forceCapitalRepair,
                ),
            ).estimate.version
            MaintenanceReplaceKind.REPAIR -> backend.api.replaceRepairPlan(
                repairId = command.entityId,
                warehouseId = command.warehouseId,
                request = ReplaceRepairPlanRequest(
                    expectedVersion = command.expectedVersion,
                    lines = lines,
                    stages = command.stages,
                    mediaReferences = media,
                    coverMediaId = coverMediaId,
                    forceCapitalRepair = command.forceCapitalRepair,
                ),
            ).version
        }
        if (command.replaceKind != MaintenanceReplaceKind.NONE) {
            store.update(requireExecutionScope(), operation.id) { current ->
                current.copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    maintenance = current.maintenance?.copy(
                        expectedVersion = persistedVersion,
                        existingMedia = media,
                        existingCoverMediaId = coverMediaId,
                        replaceKind = MaintenanceReplaceKind.NONE,
                    ),
                )
            }
        }
        command.submitRequest?.let { submit ->
            updateStage(operation.id, "Отправка в очередь")
            val request = submit.copy(expectedVersion = persistedVersion)
            val idempotencyKey = requireNotNull(command.submitIdempotencyKey)
            if (command.mode == "ESTIMATE") {
                backend.api.completeEstimate(
                    estimateId = command.entityId,
                    warehouseId = command.warehouseId,
                    idempotencyKey = idempotencyKey,
                    request = CompleteEstimateRequest(
                        expectedVersion = request.expectedVersion,
                        priority = request.priority,
                        movementToRepair = request.movementToRepair,
                        logisticsPlanningMode = request.logisticsPlanningMode,
                        logisticsScheduledDate = request.logisticsScheduledDate,
                        allowUnaccountedFurniture = command.allowUnaccountedFurniture,
                    ),
                )
            } else {
                backend.api.queueRepairPlan(
                    repairId = command.entityId,
                    warehouseId = command.warehouseId,
                    idempotencyKey = idempotencyKey,
                    request = request,
                )
            }
        }
    }

    private suspend fun finalizeAcceptance(
        operation: BackgroundUploadOperation,
        command: AcceptanceUploadCommand,
    ) {
        updateStage(operation.id, "Подтверждение приёмки")
        backend.api.acceptRepair(
            repairId = command.repairId,
            warehouseId = command.warehouseId,
            idempotencyKey = command.idempotencyKey,
            request = RepairDecisionRequest(
                expectedVersion = command.expectedVersion,
                comment = command.comment,
                mediaReferences = operation.aggregateReferences(command.existingMedia),
            ),
        )
    }

    private suspend fun finalizeTransferArrival(
        operation: BackgroundUploadOperation,
        command: TransferArrivalUploadCommand,
    ) {
        updateStage(operation.id, "Подтверждение перемещения")
        backend.api.arriveTransferLine(
            documentId = command.documentId,
            lineId = command.lineId,
            expectedVersion = command.expectedDocumentVersion,
            expectedLineVersion = command.expectedLineVersion,
            idempotencyKey = command.idempotencyKey,
            request = ArriveTransferLineRequest(
                operation.aggregateReferences(command.existingMedia),
            ),
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun finalizeReturn(
        operation: BackgroundUploadOperation,
        command: ReturnUploadCommand,
    ) {
        updateStage(operation.id, "Сохранение приёмки возврата")
        val referencesByLine = command.lines.associate { line ->
            val uploaded = operation.photos
                .filter { it.owner.lineId == line.lineId }
                .map { requireNotNull(it.reference) }
            line.lineId to (line.existingMedia + uploaded)
                .distinctBy(MediaReferenceDto::mediaId)
                .also { require(it.isNotEmpty()) { "Для каждой бытовки нужна фотография" } }
        }
        when (command.action) {
            ReturnUploadAction.ACCEPT -> backend.api.acceptReturn(
                documentId = command.documentId,
                expectedVersion = command.expectedVersion,
                idempotencyKey = command.idempotencyKey,
                request = AcceptReturnRequest(
                    command.lines.map { line ->
                        AcceptReturnLineRequest(
                            lineId = line.lineId,
                            equipmentConfirmed = line.equipmentConfirmed,
                            references = referencesByLine.getValue(line.lineId),
                        )
                    },
                ),
            )
            ReturnUploadAction.START_ESTIMATES,
            ReturnUploadAction.REQUEST_ESTIMATE -> backend.api.startReturnEstimates(
                documentId = command.documentId,
                expectedVersion = command.expectedVersion,
                idempotencyKey = command.idempotencyKey,
                request = StartReturnEstimatesRequest(
                    command.lines.map { line ->
                        StartReturnEstimateLine(
                            lineId = line.lineId,
                            references = referencesByLine.getValue(line.lineId),
                        )
                    },
                ),
            )
        }
    }

    private fun updateStage(operationId: String, stage: String) {
        store.update(requireExecutionScope(), operationId) { operation ->
            operation.copy(
                updatedAtEpochMillis = System.currentTimeMillis(),
                status = BackgroundUploadStatus.RUNNING,
                stage = stage,
                error = null,
            )
        }
    }

    private suspend fun inventoryInspectionAlreadySaved(
        command: InventoryUploadCommand,
        media: List<MediaReferenceDto>,
        coverMediaId: String?,
    ): Boolean = runCatching {
        val finding = activeInventoryFinding(command.inventoryId, command.findingId)
            ?: return@runCatching false
        val expectedMedia = media.map { it.mediaId to it.generation }.toSet()
        val actualMedia = finding.media.map { it.mediaId to it.generation }.toSet()
        finding.findingRevision > command.expectedFindingRevision &&
            finding.inspection == command.inspection &&
            finding.comment == command.comment &&
            actualMedia == expectedMedia &&
            finding.coverMediaId == coverMediaId &&
            if (command.planSelection == null) {
                finding.frozenPlan == null
            } else {
                finding.frozenPlan?.let { plan ->
                    plan.priority == command.planSelection.priority &&
                        plan.movementToRepair == command.planSelection.movementToRepair &&
                        plan.lines.size == command.planSelection.lines.size &&
                        plan.stages.size == command.planSelection.stages.size
                } == true
            }
    }.getOrDefault(false)

    private fun BackgroundUploadOperation.aggregateReferences(
        existing: List<MediaReferenceDto>,
    ): List<MediaReferenceDto> = (
        existing + photos
            .filter { it.lineId == null }
            .map { requireNotNull(it.reference) }
        )
        .distinctBy(MediaReferenceDto::mediaId)

    private fun BackgroundUploadOperation.hasRetainedInventoryMedia(
        command: InventoryUploadCommand,
    ): Boolean = command.existingMedia.isNotEmpty() ||
        command.planSelection?.lines.orEmpty().any { line ->
            line.mediaReferences.isNotEmpty()
        } ||
        photos.any { photo -> photo.reference != null }

    private fun BackgroundUploadOperation.lineReferences(): Map<String, List<MediaReferenceDto>> =
        photos
            .mapNotNull { photo ->
                photo.lineId?.let { lineId -> lineId to requireNotNull(photo.reference) }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, references) -> references.distinctBy(MediaReferenceDto::mediaId) }

    private fun BackgroundUploadOperation.coverMediaId(): String? =
        photos.firstOrNull(BackgroundUploadPhoto::cover)?.reference?.mediaId

    private fun failureMessage(failure: Throwable): String = when (failure) {
        is HttpException -> backend.problemMessage(failure)
        is IOException -> "Нет связи с RWMS. Загрузка сохранена и её можно повторить"
        else -> failure.message?.takeIf(String::isNotBlank)
            ?: "Не удалось отправить данные в RWMS"
    }

    companion object {
        private const val UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED =
            "MAINTENANCE_UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED"
        private val operationPermit = Semaphore(1)
        const val INPUT_OPERATION_ID = "operation_id"
        const val INPUT_OWNER_ACCOUNT_ID = "owner_account_id"
        const val INPUT_WAREHOUSE_ID = "warehouse_id"
        const val INPUT_PHOTO_ID = "photo_id"
        const val WORK_TAG = "rwms-background-uploads"

        /** Waits until the running worker has closed its account-bound authentication snapshot. */
        internal suspend fun awaitIdle() {
            operationPermit.withPermit { }
        }
    }
}

/**
 * Fail-closed admission policy applied to a persisted upload before its first side effect. It
 * checks the authoritative `/me` response rather than trusting WorkManager input or a previous
 * process's session, and requires every warehouse explicitly retained by the command/media data.
 */
internal object BackgroundUploadAuthorizationPolicy {
    fun canExecute(
        user: CurrentUserDto,
        operation: BackgroundUploadOperation,
    ): Boolean {
        if (user.id != operation.ownerAccountId ||
            user.principalType != "USER" ||
            user.globalRole !in MANAGER_ROLES
        ) {
            return false
        }
        if (user.warehouseAccessAll) return true
        val granted = user.warehouseAccesses.mapTo(hashSetOf()) { it.warehouseId }
        return operation.requiredWarehouseIds().all(granted::contains)
    }

    private fun BackgroundUploadOperation.requiredWarehouseIds(): Set<String> = buildSet {
        add(warehouseId)
        photos.forEach { add(it.owner.warehouseId) }
        inventory?.furnitureMove?.let { add(it.warehouseId) }
        maintenance?.let { add(it.warehouseId) }
        acceptance?.let { add(it.warehouseId) }
        returnAction?.let { add(it.warehouseId) }
    }

    private val MANAGER_ROLES =
        setOf("SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER")
}
