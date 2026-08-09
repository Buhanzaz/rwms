package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.media.retryMediaReadAfterOwnerProof
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ReturnEstimateSourceDto
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.network.StartReturnEstimateLine
import dev.buhanzaz.rwms.manager.network.StartReturnEstimatesRequest
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadArea
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadDraft
import dev.buhanzaz.rwms.manager.uploads.PendingBackgroundPhoto
import dev.buhanzaz.rwms.manager.uploads.ReturnUploadAction
import dev.buhanzaz.rwms.manager.uploads.ReturnUploadCommand
import dev.buhanzaz.rwms.manager.uploads.ReturnUploadLineCommand
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update

/**
 * Owns return inspection, its line-scoped media outbox commands and return-to-estimate handoff.
 * It deliberately reads current return documents before mutable actions and publishes only to the
 * shared [ManagerUiState] flow.
 */
internal class ManagerReturnCoordinator(
    private val runtime: ManagerCommandRuntime,
    private val backend: RwmsBackend,
    private val backgroundUploads: Deferred<BackgroundUploadCoordinator>,
    private val commandKeys: StableCommandKeys,
    private val maintenanceRefresh: ManagerMaintenanceRefreshPort,
) {
    private val mutableState
        get() = runtime.mutableState

    private fun command(block: suspend () -> Unit) = runtime.command(block)

    private fun message(value: String) = runtime.message(value)

    private fun requireWarehouseId(): String = runtime.requireWarehouseId()

    fun loadReturns() = command {
        val warehouseId = requireWarehouseId()
        val documents = backend.api.returns(warehouseId)
        if (mutableState.value.selectedWarehouseId != warehouseId) return@command
        mutableState.update { it.copy(returns = documents) }
    }

    fun openReturn(document: LogisticsDocumentDto) {
        mutableState.update {
            it.copy(
                selectedReturn = document,
                returnPhotoUris = document.lines.associate { line -> line.id to emptyList() },
                returnReadyMedia = document.lines.associate { line -> line.id to emptyList() },
                returnEquipmentConfirmed = emptySet(),
            )
        }
        command {
            val currentDocument = backend.api.returnDocument(document.id).also { loaded ->
                require(loaded.documentType == "RETURN") {
                    "RWMS вернул не документ возврата"
                }
            }
            applyCurrentReturnDocument(currentDocument)
            val ready = loadReturnReadyMedia(currentDocument)
            mutableState.update { current ->
                current.withLoadedReturnReadyMedia(
                    documentId = currentDocument.id,
                    readyMedia = ready,
                )
            }
        }
    }

    fun closeReturn() {
        mutableState.update {
            it.copy(
                selectedReturn = null,
                returnPhotoUris = emptyMap(),
                returnReadyMedia = emptyMap(),
                returnEquipmentConfirmed = emptySet(),
            )
        }
    }

    fun addReturnPhoto(lineId: String, uri: String) {
        mutableState.update { current ->
            val photos = current.returnPhotoUris[lineId].orEmpty()
            current.copy(
                returnPhotoUris = current.returnPhotoUris +
                    (lineId to if (uri in photos) photos else photos + uri),
            )
        }
    }

    fun removeReturnPhoto(lineId: String, uri: String) {
        mutableState.update { current ->
            current.copy(
                returnPhotoUris = current.returnPhotoUris +
                    (lineId to (current.returnPhotoUris[lineId].orEmpty() - uri)),
            )
        }
    }

    fun confirmReturnEquipment(lineId: String, confirmed: Boolean) {
        mutableState.update {
            it.copy(
                returnEquipmentConfirmed =
                    if (confirmed) it.returnEquipmentConfirmed + lineId
                    else it.returnEquipmentConfirmed - lineId,
            )
        }
    }

    fun acceptReturn(onSaved: () -> Unit) = command {
        val displayedDocument = requireNotNull(mutableState.value.selectedReturn)
        val document = currentReturnInspectionDocument(displayedDocument)
        val missingConfirmation = document.lines.firstOrNull {
            it.id !in mutableState.value.returnEquipmentConfirmed
        }
        if (missingConfirmation != null) {
            throw IllegalArgumentException(
                "Подтвердите комплектность мебели для строки ${missingConfirmation.lineNumber}",
            )
        }
        enqueueReturnUpload(
            document = document,
            action = ReturnUploadAction.ACCEPT,
        )
        closeReturn()
        message("Приёмка возврата добавлена в фоновые загрузки")
        onSaved()
    }

    fun startReturnEstimates(
        onSourcesReady: (List<ReturnEstimateSourceDto>) -> Unit,
        onQueued: () -> Unit,
    ) = command {
        val displayedDocument = requireNotNull(mutableState.value.selectedReturn)
        val document = currentReturnInspectionDocument(displayedDocument)
        if (document.lines.any { line ->
                mutableState.value.returnPhotoUris[line.id].orEmpty().isNotEmpty()
            }
        ) {
            enqueueReturnUpload(
                document = document,
                action = ReturnUploadAction.START_ESTIMATES,
            )
            closeReturn()
            message("Фотографии загружаются. Отдельные сметы будут созданы после их проверки")
            onQueued()
            return@command
        }
        val readyMedia = loadReturnReadyMedia(document)
        val signature = "return-start-estimates:${document.id}:${document.version}"
        val updated = backend.api.startReturnEstimates(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = commandKeys.logisticsCommandKey(signature),
            request = StartReturnEstimatesRequest(
                document.lines.map { line ->
                    StartReturnEstimateLine(
                        lineId = line.id,
                        references = requireNotNull(readyMedia[line.id]) {
                            "Не найдены фотографии для строки ${line.lineNumber}"
                        }.also { references ->
                            require(references.isNotEmpty()) {
                                "Добавьте и дождитесь загрузки фотографии для строки ${line.lineNumber}"
                            }
                        },
                    )
                },
            ),
        )
        commandKeys.complete(signature)
        applyCurrentReturnDocument(updated)
        val sources = awaitReturnEstimateSources(document)
        closeReturn()
        if (sources.size == document.lines.size) {
            maintenanceRefresh.refreshMaintenance(force = true)
            message(
                if (sources.size == 1) {
                    "Черновик сметы создан"
                } else {
                    "Созданы отдельные черновики смет: ${sources.size}"
                },
            )
            onSourcesReady(sources)
        } else {
            message("Сметы создаются. Откройте раздел «Сметы» через несколько секунд")
            onQueued()
        }
    }

    private suspend fun enqueueReturnUpload(
        document: LogisticsDocumentDto,
        action: ReturnUploadAction,
    ) {
        val photos = document.lines.flatMap { line ->
            val existing = mutableState.value.returnReadyMedia[line.id].orEmpty()
            val local = mutableState.value.returnPhotoUris[line.id].orEmpty()
            if (existing.isEmpty() && local.isEmpty()) {
                throw IllegalArgumentException(
                    "Добавьте фотографию для строки ${line.lineNumber}",
                )
            }
            val owner = MediaOwner(
                ownerType = "LOGISTICS_RETURN",
                documentId = document.id,
                lineId = line.id,
                warehouseId = document.warehouseId,
                context = "RETURN_INSPECTION",
            )
            local.mapIndexed { index, uri ->
                PendingBackgroundPhoto(
                    uri = uri,
                    owner = owner,
                    sortOrder = existing.size + index,
                )
            }
        }
        backgroundUploads.await().enqueue(
            BackgroundUploadDraft(
                area = BackgroundUploadArea.LOGISTICS,
                title = if (action == ReturnUploadAction.ACCEPT) {
                    "Приёмка возврата"
                } else {
                    "Возврат со сметой"
                },
                subtitle = "${document.lines.size} бытовок",
                photos = photos,
                returnAction = ReturnUploadCommand(
                    documentId = document.id,
                    warehouseId = document.warehouseId,
                    expectedVersion = document.version,
                    action = action,
                    lines = document.lines.map { line ->
                        ReturnUploadLineCommand(
                            lineId = line.id,
                            equipmentConfirmed = true,
                            existingMedia = mutableState.value.returnReadyMedia[line.id].orEmpty(),
                        )
                    },
                    idempotencyKey = UUID.randomUUID().toString(),
                ),
            ),
        )
    }

    private suspend fun loadReturnReadyMedia(
        document: LogisticsDocumentDto,
    ): Map<String, List<MediaReferenceDto>> = document.lines.associate { line ->
        line.id to retryMediaReadAfterOwnerProof {
            backend.api.ownerMedia(
                ownerType = "LOGISTICS_RETURN",
                documentId = document.id,
                lineId = line.id,
                warehouseId = document.warehouseId,
                context = "RETURN_INSPECTION",
            )
        }.items
            .filter { it.status == "READY" && it.generation > 0 }
            .map { MediaReferenceDto(it.id, it.generation) }
    }

    private suspend fun awaitReturnEstimateSources(
        document: LogisticsDocumentDto,
    ): List<ReturnEstimateSourceDto> {
        val expectedLineIds = document.lines.mapTo(linkedSetOf()) { line -> line.id }
        repeat(RETURN_ESTIMATE_SOURCE_POLL_ATTEMPTS) { attempt ->
            val sources = backend.api.returnEstimateSources(
                warehouseId = document.warehouseId,
                returnId = document.id,
            )
            val sourceLineIds = sources.mapTo(linkedSetOf()) { source ->
                require(source.returnId == document.id) {
                    "Сервис смет вернул источник другого возврата"
                }
                require(source.warehouseId == document.warehouseId) {
                    "Сервис смет вернул источник другого склада"
                }
                source.lineId
            }
            require(sourceLineIds.size == sources.size) {
                "Сервис смет вернул повторный источник для одной бытовки"
            }
            require(sourceLineIds.all(expectedLineIds::contains)) {
                "Сервис смет вернул источник отсутствующей строки возврата"
            }
            if (sourceLineIds == expectedLineIds) return sources
            if (attempt < RETURN_ESTIMATE_SOURCE_POLL_ATTEMPTS - 1) {
                delay(RETURN_ESTIMATE_SOURCE_POLL_DELAY_MILLIS)
            }
        }
        return emptyList()
    }

    private suspend fun currentReturnInspectionDocument(
        displayedDocument: LogisticsDocumentDto,
    ): LogisticsDocumentDto {
        val currentDocument = backend.api.returnDocument(displayedDocument.id)
        require(currentDocument.documentType == "RETURN") {
            "RWMS вернул не документ возврата"
        }
        require(currentDocument.warehouseId == requireWarehouseId()) {
            "Возврат относится к другому складу"
        }
        applyCurrentReturnDocument(currentDocument)
        currentDocument.returnInspectionActionError()?.let { error ->
            throw IllegalStateException(error)
        }
        val displayedLineIds = displayedDocument.lines.mapTo(linkedSetOf()) { it.id }
        val currentLineIds = currentDocument.lines.mapTo(linkedSetOf()) { it.id }
        require(displayedLineIds == currentLineIds) {
            "Состав возврата изменился. Экран обновлён, проверьте данные и повторите действие."
        }
        return currentDocument
    }

    private fun applyCurrentReturnDocument(document: LogisticsDocumentDto) {
        mutableState.update { current ->
            val selected = current.selectedReturn
            current.copy(
                selectedReturn = if (selected?.id == document.id) document else selected,
                returns = current.returns.map { listed ->
                    if (listed.id == document.id) document else listed
                },
            )
        }
    }
}

private const val RETURN_ESTIMATE_SOURCE_POLL_ATTEMPTS = 15
private const val RETURN_ESTIMATE_SOURCE_POLL_DELAY_MILLIS = 1_000L
