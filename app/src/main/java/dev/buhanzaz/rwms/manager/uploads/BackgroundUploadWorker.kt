package dev.buhanzaz.rwms.manager.uploads

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.buhanzaz.rwms.manager.BuildConfig
import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.media.MediaUploader
import dev.buhanzaz.rwms.manager.media.PhotoPayloadReader
import dev.buhanzaz.rwms.manager.media.retryInventoryCommitAfterMediaReady
import dev.buhanzaz.rwms.manager.network.AcceptReturnLineRequest
import dev.buhanzaz.rwms.manager.network.AcceptReturnRequest
import dev.buhanzaz.rwms.manager.network.AmendEstimateRequest
import dev.buhanzaz.rwms.manager.network.ArriveTransferLineRequest
import dev.buhanzaz.rwms.manager.network.CreateCabinFurnitureTaskRequest
import dev.buhanzaz.rwms.manager.network.EstimateLineInputDto
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RepairDecisionRequest
import dev.buhanzaz.rwms.manager.network.ReplaceEstimateRequest
import dev.buhanzaz.rwms.manager.network.ReplaceRepairPlanRequest
import dev.buhanzaz.rwms.manager.network.RequestReturnEstimateLine
import dev.buhanzaz.rwms.manager.network.RequestReturnEstimateRequest
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.network.SaveInspectionRequest
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException

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

    override suspend fun doWork(): Result {
        store.initialize()
        return operationPermit.withPermit {
            executeOperation()
        }
    }

    private suspend fun executeOperation(): Result {
        val operationId = inputData.getString(INPUT_OPERATION_ID)
            ?: return Result.failure()
        val selectedPhotoId = inputData.getString(INPUT_PHOTO_ID)
        val operation = store.operation(operationId) ?: return Result.success()
        return try {
            markRunning(operation.id)
            uploadPendingPhotos(operation.id, selectedPhotoId)
            val afterPhotos = store.operation(operation.id) ?: return Result.success()
            if (afterPhotos.photos.any { it.status != BackgroundPhotoStatus.READY }) {
                store.update(operation.id) { current ->
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
            store.remove(operation.id)
            Result.success()
        } catch (cancelled: CancellationException) {
            store.update(operation.id) { current ->
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
            val message = failureMessage(failure)
            store.update(operation.id) { current ->
                current.copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    status = BackgroundUploadStatus.FAILED,
                    stage = "Ошибка отправки",
                    error = message,
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
        } finally {
            backend.auth.close()
        }
    }

    private fun markRunning(operationId: String) {
        store.update(operationId) { operation ->
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
        val snapshot = store.operation(operationId) ?: return
        val targets = snapshot.photos.filter { photo ->
            photo.status != BackgroundPhotoStatus.READY &&
                (selectedPhotoId == null || photo.id == selectedPhotoId)
        }
        if (targets.isEmpty()) return
        val targetIds = targets.mapTo(hashSetOf(), BackgroundUploadPhoto::id)
        store.update(operationId) { operation ->
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
            store.update(operationId) { operation ->
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
        val current = store.operation(operationId)?.photos
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

        val uploaded = store.operation(operationId)?.photos
            ?.filter { it.id in selectedIds }
            .orEmpty()
        val readyForProcessing = uploaded.filter { it.reference != null }
        val rotations = readyForProcessing.filter { it.rotationDegrees != 0 }
        val assetsById = if (rotations.isEmpty()) {
            emptyMap()
        } else {
            backend.api.ownerMedia(
                ownerType = owner.ownerType,
                ownerId = owner.ownerId,
                documentId = owner.documentId,
                lineId = owner.lineId,
                warehouseId = owner.warehouseId,
                context = owner.context,
            ).items.associateBy(MediaAssetDto::id)
        }
        readyForProcessing.forEach { photo ->
            val reference = requireNotNull(photo.reference)
            val readyReference = if (photo.rotationDegrees == 0) {
                reference
            } else {
                val asset = requireNotNull(assetsById[reference.mediaId]) {
                    "Не удалось найти ${photo.sourceName} для поворота"
                }
                uploader.rotate(owner, asset, photo.rotationDegrees)
            }
            updatePhoto(operationId, photo.durableUri) { currentPhoto ->
                currentPhoto.copy(
                    status = BackgroundPhotoStatus.READY,
                    reference = readyReference,
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
        store.update(operationId) { operation ->
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
        val media = operation.aggregateReferences(command.existingMedia)
        val coverMediaId = operation.coverMediaId() ?: command.existingCoverMediaId
        if (!command.inspectionSaved) {
            updateStage(operation.id, "Сохранение инвентаризации")
            try {
                retryInventoryCommitAfterMediaReady {
                    val currentSession = backend.api.inventory(command.inventoryId)
                    backend.api.saveInventoryInspection(
                        inventoryId = command.inventoryId,
                        findingId = command.findingId,
                        request = SaveInspectionRequest(
                            expectedSessionRevision = currentSession.sessionRevision,
                            expectedFindingRevision = command.expectedFindingRevision,
                            inspection = command.inspection,
                            comment = command.comment,
                            passportObservation = command.passportObservation,
                            equipmentObservation = command.equipmentObservation,
                            media = media,
                            coverMediaId = coverMediaId,
                            planSelection = command.planSelection?.withUploadedMedia(
                                coverMediaId,
                                operation.lineReferences(),
                                command.planLineIds,
                                command.planWorkLineIds.toSet(),
                            ),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!inventoryInspectionAlreadySaved(command, media, coverMediaId)) throw failure
            }
            store.update(operation.id) { current ->
                current.copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    inventory = current.inventory?.copy(inspectionSaved = true),
                )
            }
        }
        command.furnitureMove?.let { furniture ->
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
                ),
            ).version
        }
        if (command.replaceKind != MaintenanceReplaceKind.NONE) {
            store.update(operation.id) { current ->
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
                    request = request,
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
            ReturnUploadAction.REQUEST_ESTIMATE -> backend.api.requestReturnEstimate(
                documentId = command.documentId,
                expectedVersion = command.expectedVersion,
                idempotencyKey = command.idempotencyKey,
                request = RequestReturnEstimateRequest(
                    command.lines.map { line ->
                        RequestReturnEstimateLine(
                            lineId = line.lineId,
                            shortages = line.shortages,
                            references = referencesByLine.getValue(line.lineId),
                        )
                    },
                ),
            )
        }
    }

    private fun updateStage(operationId: String, stage: String) {
        store.update(operationId) { operation ->
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
        var page = 0
        var totalPages = 1
        var found = false
        do {
            val findings = backend.api.inventoryFindings(
                inventoryId = command.inventoryId,
                page = page,
            )
            totalPages = findings.page.totalPages
            val finding = findings.content.firstOrNull { it.id == command.findingId }
            if (finding != null) {
                val expectedMedia = media.map { it.mediaId to it.generation }.toSet()
                val actualMedia = finding.media.map { it.mediaId to it.generation }.toSet()
                found = finding.findingRevision > command.expectedFindingRevision &&
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
                break
            }
            page += 1
        } while (page < totalPages)
        found
    }.getOrDefault(false)

    private fun BackgroundUploadOperation.aggregateReferences(
        existing: List<MediaReferenceDto>,
    ): List<MediaReferenceDto> = (
        existing + photos
            .filter { it.lineId == null }
            .map { requireNotNull(it.reference) }
        )
        .distinctBy(MediaReferenceDto::mediaId)

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
        private val operationPermit = Semaphore(1)
        const val INPUT_OPERATION_ID = "operation_id"
        const val INPUT_PHOTO_ID = "photo_id"
        const val WORK_TAG = "rwms-background-uploads"
    }
}
