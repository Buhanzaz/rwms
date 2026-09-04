package dev.buhanzaz.rwms.driver.core.sync

import dev.buhanzaz.rwms.driver.core.database.PendingEvidenceReservation
import dev.buhanzaz.rwms.driver.core.database.PendingDriverAction
import dev.buhanzaz.rwms.driver.core.database.PendingShiftCommand
import dev.buhanzaz.rwms.driver.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.driver.core.database.DriverCategoryEntity
import dev.buhanzaz.rwms.driver.core.database.DriverDatabase
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.database.DriverOutboxEntity
import dev.buhanzaz.rwms.driver.core.database.DriverSessionEntity
import dev.buhanzaz.rwms.driver.core.database.DriverSyncProgressEntity
import dev.buhanzaz.rwms.driver.core.media.EvidenceUploadResult
import dev.buhanzaz.rwms.driver.core.media.DriverEvidenceUploader
import dev.buhanzaz.rwms.driver.core.network.EvidenceReservationRequestDto
import dev.buhanzaz.rwms.driver.core.network.GatewayFailureDisposition
import dev.buhanzaz.rwms.driver.core.network.GatewayProblemException
import dev.buhanzaz.rwms.driver.core.network.DriverActionRequestDto
import dev.buhanzaz.rwms.driver.core.network.DriverCategoryDto
import dev.buhanzaz.rwms.driver.core.network.DriverContextDto
import dev.buhanzaz.rwms.driver.core.network.DriverFeedCategoryDto
import dev.buhanzaz.rwms.driver.core.network.DriverFeedResponse
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayClient
import dev.buhanzaz.rwms.driver.core.network.ConfirmMedicalCheckRequestDto
import dev.buhanzaz.rwms.driver.core.network.ReserveShiftPhotoRequestDto
import dev.buhanzaz.rwms.driver.core.network.ReturnToWarehouseRequestDto
import dev.buhanzaz.rwms.driver.core.network.ShiftTransitionRequestDto
import dev.buhanzaz.rwms.driver.core.network.SubmitClosingReportRequestDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import dev.buhanzaz.rwms.driver.core.network.UpdateInspectionItemRequestDto
import dev.buhanzaz.rwms.driver.core.network.gatewayFailureDisposition
import dev.buhanzaz.rwms.driver.core.network.gatewayProblemUserMessage
import dev.buhanzaz.rwms.driver.core.network.isProvenGatewayTransportFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Reports one sync pass to the bounded driver job. Only an explicitly
 * classified temporary gateway or transport failure can return [Retry].
 */
sealed interface DriverSyncOutcome {
    /** All pending work and the authoritative driver projection are synchronized. */
    data object Complete : DriverSyncOutcome

    /** A bounded automatic retry is permitted for a transient dependency or transport failure. */
    data class Retry(val reason: String) : DriverSyncOutcome

    /** A server-confirmed evidence dependency is pending until a later explicit sync trigger. */
    data class Deferred(val reason: String) : DriverSyncOutcome

    /** The gateway's token refresh opportunity was exhausted and login is required. */
    data class AuthenticationRequired(val reason: String) : DriverSyncOutcome

    /** A valid session lacks the current grant and must not be retried in the background. */
    data class UserActionRequired(val reason: String) : DriverSyncOutcome

    /** A command conflict has been persisted and requires authoritative state before another action. */
    data class Conflict(val reason: String) : DriverSyncOutcome

    /** A non-recoverable local or protocol failure stopped this sync attempt safely. */
    data class Failed(val reason: String) : DriverSyncOutcome
}

/**
 * Carries only an explicitly classified temporary failure to the bounded
 * WorkManager recovery loop.
 */
private class RetryableSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Stops background replay when the current driver grant cannot perform the requested action. */
private class UserActionRequiredSyncException(message: String) : Exception(message)

/** Stops automatic recovery for an invalid local state or terminal gateway/protocol response. */
private class TerminalSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Represents one complete authoritative feed fetch. A changed result contains
 * every consistent page; a not-modified result is the validated first-page
 * cache response.
 */
private sealed interface FetchedFeed {
    /** Every page was fetched at one consistent server revision. */
    data class Changed(
        val revision: Long,
        val serverTime: String,
        val categories: List<DriverFeedCategoryDto>,
        val etag: String?,
    ) : FetchedFeed

    /** The first page confirmed the persisted ETag without replacing cache rows. */
    data class NotModified(val etag: String?) : FetchedFeed
}

/**
 * Carries media-phase progress and an optional blocked-evidence outcome so the
 * coordinator can reconcile the authoritative feed before returning.
 */
private data class EvidenceSyncResult(
    val outcome: DriverSyncOutcome?,
    val completedUnits: Int,
)

/**
 * Executes the only permitted ordering for offline work: prerequisite actions,
 * evidence reservations, media upload/finalization, then completion actions.
 * Room is the UI source of truth during every stage.
 */
@Singleton
class DriverSyncCoordinator @Inject constructor(
    private val gateway: DriverGatewayClient,
    private val database: DriverDatabase,
    private val localStore: DriverLocalStore,
    private val projections: DriverProjectionWriter,
    private val mediaUploadPipeline: DriverEvidenceUploader,
    private val json: Json,
) {
    /**
     * Runs one authenticated recovery pass in the fixed command/evidence/feed order and returns a
     * durable outcome; it never activates a new offline lease from a partial sync.
     */
    suspend fun sync(userId: String): DriverSyncOutcome {
        localStore.hideExpiredCacheIfNeeded(userId)
        return try {
            updateProgress(userId, "CONTEXT", 0, 1, null, null, "Проверяем доступ")
            val context = gateway.context()
            if (context.driver.id != userId) {
                throw UserActionRequiredSyncException("Ответ RWMS получен для другого пользователя")
            }
            // Do not activate this new lease yet. A dropped upload or failed
            // feed must not grant another 24 offline hours.
            projections.stageContextIdentity(context)

            val outbox = localStore.pendingOutbox(userId)
            val shiftCommands = outbox
                .filter { it.kind == DriverLocalStore.OUTBOX_SHIFT_COMMAND }
                .sortedWith(
                    compareBy<DriverOutboxEntity> { shiftCommandPayload(it).expectedVersion }
                        .thenBy { it.createdAtEpochMillis }
                        .thenBy { it.operationId },
                )
            if (shiftCommands.isEmpty()) {
                projections.applyTodayShift(userId, gateway.todayDriverShift())
            }
            val actions = outbox.filter { it.kind == DriverLocalStore.OUTBOX_ACTION }
            val prerequisites = actions.filter { actionPayload(it).action != "COMPLETE" }
            val completions = actions.filter { actionPayload(it).action == "COMPLETE" }
            val reservations = outbox.filter { it.kind == DriverLocalStore.OUTBOX_EVIDENCE_RESERVATION }
            // Captured rows already exist before their reservation outbox row is
            // applied, so count media once up front for a stable progress total.
            val mediaCount = database.evidenceDao().pending(userId).size
            val earlyShiftCommands = shiftCommands.filterNot { operation ->
                shiftCommandPayload(operation).action in LATE_SHIFT_ACTIONS
            }
            val lateShiftCommands = shiftCommands.filter { operation ->
                shiftCommandPayload(operation).action in LATE_SHIFT_ACTIONS
            }
            val total = shiftCommands.size + prerequisites.size + reservations.size + mediaCount + completions.size + 3
            var completed = 1

            earlyShiftCommands.forEach { operation ->
                applyShiftCommand(userId, operation)
                completed += 1
                updateProgress(userId, "SHIFT", completed, total, null, null, "Синхронизируем смену")
            }

            prerequisites.forEach { operation ->
                applyAction(userId, operation)
                completed += 1
                updateProgress(userId, "COMMANDS", completed, total, null, null, "Передаём действия")
            }
            reservations.forEach { operation ->
                reserveEvidence(userId, operation)
                completed += 1
                updateProgress(userId, "EVIDENCE", completed, total, null, null, "Резервируем фото")
            }

            val mediaResult = try {
                uploadEvidence(userId, completed, total, mediaCount)
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayProblemException) {
                if (error.disposition == GatewayFailureDisposition.AUTHENTICATION_REQUIRED) {
                    throw error
                }
                if (error.disposition == GatewayFailureDisposition.USER_ACTION_REQUIRED) {
                    return DriverSyncOutcome.UserActionRequired(
                        gatewayProblemUserMessage(error.problem),
                    )
                }
                reconcileFeedAfterBlockedMedia(userId, context)
                updateProgress(
                    userId,
                    "WAITING_FOR_EVIDENCE",
                    completed,
                    total,
                    null,
                    null,
                    "Фото не отправлено; задания обновлены по данным RWMS",
                )
                return driverSyncOutcomeForGatewayProblem(error)
            } catch (error: Throwable) {
                if (!error.isProvenGatewayTransportFailure()) {
                    reconcileFeedAfterBlockedMedia(userId, context)
                    updateProgress(
                        userId,
                        "WAITING_FOR_EVIDENCE",
                        completed,
                        total,
                        null,
                        null,
                        "Фото требует действия; задания обновлены по данным RWMS",
                    )
                    return DriverSyncOutcome.Failed(SAFE_EVIDENCE_UPLOAD_FAILURE_MESSAGE)
                }
                reconcileFeedAfterBlockedMedia(userId, context)
                updateProgress(
                    userId,
                    "WAITING_FOR_EVIDENCE",
                    completed,
                    total,
                    null,
                    null,
                    "Фото ожидает повторной отправки; задания обновлены по данным RWMS",
                )
                return DriverSyncOutcome.Retry(SAFE_EVIDENCE_UPLOAD_RETRY_MESSAGE)
            }
            if (mediaResult.outcome != null) {
                // An upload can be delayed by media processing or a transient
                // failure for minutes. It must not prevent an authenticated
                // full feed from removing a task another driver has completed.
                // Completion actions remain below this gate and therefore are
                // never sent before their evidence is READY.
                reconcileFeedAfterBlockedMedia(userId, context)
                updateProgress(
                    userId,
                    "WAITING_FOR_EVIDENCE",
                    mediaResult.completedUnits,
                    total,
                    null,
                    null,
                    "Фото ожидает готовности; задания обновлены по данным RWMS",
                )
                return mediaResult.outcome
            }
            completed = mediaResult.completedUnits

            completions.forEach { operation ->
                if (!completionEvidenceReady(userId, operation.entryId)) {
                    updateProgress(
                        userId,
                        "WAITING_FOR_EVIDENCE",
                        completed,
                        total,
                        null,
                        null,
                        "Завершение ждёт готовые фотографии",
                    )
                    return DriverSyncOutcome.Deferred("Завершение ждёт обработки фотографии")
                }
                applyAction(userId, operation)
                completed += 1
                updateProgress(userId, "COMMANDS", completed, total, null, null, "Завершаем задания")
            }

            lateShiftCommands.forEach { operation ->
                val pending = shiftCommandPayload(operation)
                if (pending.action == SHIFT_CLOSE) {
                    val authoritative = gateway.todayDriverShift()
                    projections.applyTodayShift(userId, authoritative)
                    if (!authoritative.closeEvidenceReady(pending.shiftId)) {
                        updateProgress(
                            userId,
                            "WAITING_FOR_EVIDENCE",
                            completed,
                            total,
                            null,
                            null,
                            "Закрытие смены ждёт готовую фотографию неисправности",
                        )
                        return DriverSyncOutcome.Deferred(
                            "Закрытие смены ждёт готовую фотографию неисправности",
                        )
                    }
                }
                applyShiftCommand(userId, operation)
                completed += 1
                updateProgress(userId, "SHIFT", completed, total, null, null, "Завершаем смену")
            }
            when (val feed = fetchFeed(userId, context)) {
                is FetchedFeed.Changed -> {
                    projections.commitContextAndFeed(
                        context,
                        feed.revision,
                        feed.serverTime,
                        feed.categories,
                        feed.etag,
                    )
                }
                is FetchedFeed.NotModified -> {
                    // Context + a validated ETag is a successful full snapshot.
                    projections.applyContext(context)
                }
            }
            completed += 1
            projections.applyTodayShift(userId, gateway.todayDriverShift())
            completed += 1
            updateProgress(userId, "IDLE", completed, total, null, null, "Синхронизировано")
            DriverSyncOutcome.Complete
        } catch (error: CancellationException) {
            throw error
        } catch (error: UserActionRequiredSyncException) {
            DriverSyncOutcome.UserActionRequired(error.message ?: "Нужно обновить доступ рабочего")
        } catch (error: GatewayProblemException) {
            driverSyncOutcomeForGatewayProblem(error)
        } catch (error: RetryableSyncException) {
            DriverSyncOutcome.Retry(error.message ?: "Сеть недоступна")
        } catch (error: TerminalSyncException) {
            DriverSyncOutcome.Failed(error.message ?: SAFE_SYNC_FAILURE_MESSAGE)
        } catch (error: Throwable) {
            driverSyncOutcomeForUnexpectedFailure(error)
        }
    }

    private suspend fun applyShiftCommand(userId: String, operation: DriverOutboxEntity) {
        val pending = shiftCommandPayload(operation)
        try {
            val today = executeShiftCommand(pending)
            projections.commitShiftCommandResult(userId, operation.operationId, today)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GatewayProblemException) {
            val userMessage = gatewayProblemUserMessage(error.problem)
            when (error.disposition) {
                GatewayFailureDisposition.AUTHENTICATION_REQUIRED -> throw error
                GatewayFailureDisposition.RETRYABLE -> {
                    localStore.markOutboxRetry(operation, userMessage)
                    throw RetryableSyncException(userMessage, error)
                }
                GatewayFailureDisposition.USER_ACTION_REQUIRED,
                GatewayFailureDisposition.CONFLICT,
                GatewayFailureDisposition.TERMINAL,
                -> {
                    resolveTerminalShiftProblem(userId, operation, error)
                    if (error.disposition == GatewayFailureDisposition.USER_ACTION_REQUIRED) {
                        throw UserActionRequiredSyncException(userMessage)
                    }
                    throw TerminalSyncException(userMessage, error)
                }
            }
        } catch (error: Throwable) {
            if (error.isProvenGatewayTransportFailure()) {
                localStore.markOutboxRetry(operation, SAFE_SHIFT_RETRY_MESSAGE)
                throw RetryableSyncException(SAFE_SHIFT_RETRY_MESSAGE, error)
            }
            localStore.markOutboxRetry(operation, SAFE_SHIFT_FAILURE_MESSAGE)
            throw TerminalSyncException(SAFE_SHIFT_FAILURE_MESSAGE, error)
        }
    }

    private suspend fun executeShiftCommand(pending: PendingShiftCommand): TodayDriverShiftDto =
        when (pending.action) {
            SHIFT_BRIEFING_SEEN -> gateway.markShiftBriefingSeen(
                pending.shiftId,
                ShiftTransitionRequestDto(pending.operationId, pending.expectedVersion),
            )
            SHIFT_MEDICAL_CHECK -> gateway.confirmShiftMedicalCheck(
                pending.shiftId,
                ConfirmMedicalCheckRequestDto(
                    operationId = pending.operationId,
                    expectedVersion = pending.expectedVersion,
                    clientCompletedAt = pending.clientCompletedAt,
                ),
            )
            SHIFT_INSPECTION_ITEM -> gateway.updateShiftInspectionItem(
                shiftId = pending.shiftId,
                itemId = requireNotNull(pending.itemId),
                request = UpdateInspectionItemRequestDto(
                    operationId = pending.operationId,
                    expectedVersion = pending.expectedVersion,
                    expectedItemVersion = requireNotNull(pending.expectedItemVersion),
                    result = requireNotNull(pending.result),
                    defectId = pending.defectId,
                    defectDescription = pending.defectDescription,
                ),
            )
            SHIFT_INSPECTION_COMPLETE -> gateway.completeShiftInspection(
                pending.shiftId,
                ShiftTransitionRequestDto(pending.operationId, pending.expectedVersion),
            )
            SHIFT_START -> gateway.startShift(
                pending.shiftId,
                ShiftTransitionRequestDto(pending.operationId, pending.expectedVersion),
            )
            SHIFT_CLOSING_START -> gateway.startShiftClosing(
                pending.shiftId,
                ShiftTransitionRequestDto(pending.operationId, pending.expectedVersion),
            )
            SHIFT_WAREHOUSE_RETURN -> gateway.confirmShiftWarehouseReturn(
                pending.shiftId,
                ReturnToWarehouseRequestDto(
                    operationId = pending.operationId,
                    expectedVersion = pending.expectedVersion,
                    confirmationType = requireNotNull(pending.confirmationType),
                ),
            )
            SHIFT_CLOSING_REPORT -> gateway.submitShiftClosingReport(
                pending.shiftId,
                SubmitClosingReportRequestDto(
                    operationId = pending.operationId,
                    expectedVersion = pending.expectedVersion,
                    vehicleCondition = requireNotNull(pending.vehicleCondition),
                    endOdometer = requireNotNull(pending.endOdometer),
                    fuelLevelPercent = requireNotNull(pending.fuelLevelPercent),
                    confirmSuspiciousOdometer = pending.confirmSuspiciousOdometer,
                    defectId = pending.defectId,
                    defectDescription = pending.defectDescription,
                ),
            )
            SHIFT_PHOTO_RESERVATION -> gateway.reserveShiftPhoto(
                pending.shiftId,
                ReserveShiftPhotoRequestDto(
                    operationId = pending.operationId,
                    expectedVersion = pending.expectedVersion,
                    clientReferenceId = requireNotNull(pending.clientReferenceId),
                    evidenceId = requireNotNull(pending.evidenceId),
                    role = requireNotNull(pending.photoRole),
                    defectId = pending.defectId,
                    inspectionItemId = pending.inspectionItemId,
                    capturedAt = requireNotNull(pending.capturedAt),
                    contentType = requireNotNull(pending.contentType),
                    sizeBytes = requireNotNull(pending.sizeBytes),
                    sha256 = requireNotNull(pending.sha256),
                ),
            )
            SHIFT_CLOSE -> gateway.closeShift(
                pending.shiftId,
                ShiftTransitionRequestDto(pending.operationId, pending.expectedVersion),
            )
            else -> throw TerminalSyncException("Неизвестная локальная команда смены")
        }

    private suspend fun resolveTerminalShiftProblem(
        userId: String,
        operation: DriverOutboxEntity,
        error: GatewayProblemException,
    ) {
        recordConflict(userId, operation, error)
        val authoritative = runCatching { gateway.todayDriverShift() }.getOrNull()
        if (authoritative != null) {
            projections.commitShiftCommandResult(
                userId = userId,
                operationId = operation.operationId,
                today = authoritative,
                preservePendingOverlay = false,
            )
        } else {
            localStore.markOutboxComplete(operation.operationId)
        }
    }

    private suspend fun applyAction(userId: String, operation: DriverOutboxEntity) {
        val pending = actionPayload(operation)
        try {
            val result = gateway.action(
                operation.entryId,
                DriverActionRequestDto(
                    operationId = pending.operationId,
                    action = pending.action,
                    expectedVersion = pending.expectedVersion,
                    driverGroupId = pending.driverGroupId,
                    evidenceId = pending.evidenceId,
                    occurredAt = pending.occurredAt,
                    offlineLeaseId = pending.offlineLeaseId,
                ),
            )
            projections.commitActionResult(userId, operation.operationId, result.entry)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GatewayProblemException) {
            val userMessage = gatewayProblemUserMessage(error.problem)
            when (error.disposition) {
                GatewayFailureDisposition.AUTHENTICATION_REQUIRED -> throw error
                GatewayFailureDisposition.RETRYABLE -> {
                    localStore.markOutboxRetry(operation, userMessage)
                    throw RetryableSyncException(userMessage, error)
                }
                GatewayFailureDisposition.USER_ACTION_REQUIRED,
                GatewayFailureDisposition.CONFLICT,
                GatewayFailureDisposition.TERMINAL,
                -> {
                    resolveTerminalActionProblem(userId, operation, error)
                    if (error.disposition == GatewayFailureDisposition.USER_ACTION_REQUIRED) {
                        throw UserActionRequiredSyncException(userMessage)
                    }
                }
            }
        } catch (error: Throwable) {
            if (error.isProvenGatewayTransportFailure()) {
                localStore.markOutboxRetry(operation, SAFE_ACTION_RETRY_MESSAGE)
                throw RetryableSyncException(SAFE_ACTION_RETRY_MESSAGE, error)
            }
            localStore.markOutboxRetry(operation, SAFE_ACTION_FAILURE_MESSAGE)
            throw TerminalSyncException(SAFE_ACTION_FAILURE_MESSAGE, error)
        }
    }

    private suspend fun reserveEvidence(userId: String, operation: DriverOutboxEntity) {
        val pending = runCatching {
            json.decodeFromString<PendingEvidenceReservation>(localStore.decryptOutboxPayload(operation))
        }.getOrElse { throw TerminalSyncException("Локальная резервная запись повреждена", it) }
        try {
            val remote = gateway.reserveEvidence(
                operation.entryId,
                EvidenceReservationRequestDto(
                    operationId = pending.operationId,
                    evidenceId = pending.evidenceId,
                    routeIndex = pending.routeIndex,
                    capturedAt = pending.capturedAt,
                    offlineLeaseId = pending.offlineLeaseId,
                    contentType = pending.contentType,
                    sizeBytes = pending.sizeBytes,
                    sha256 = pending.sha256,
                ),
            )
            database.evidenceDao().updateState(
                evidenceId = pending.evidenceId,
                state = remote.state,
                mediaId = remote.mediaId,
                generation = remote.mediaGeneration,
                reviewReason = remote.reviewReason,
                now = System.currentTimeMillis(),
            )
            localStore.markOutboxComplete(operation.operationId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GatewayProblemException) {
            val userMessage = gatewayProblemUserMessage(error.problem)
            when (error.disposition) {
                GatewayFailureDisposition.AUTHENTICATION_REQUIRED -> throw error
                GatewayFailureDisposition.RETRYABLE -> {
                    localStore.markOutboxRetry(operation, userMessage)
                    throw RetryableSyncException(userMessage, error)
                }
                GatewayFailureDisposition.USER_ACTION_REQUIRED,
                GatewayFailureDisposition.CONFLICT,
                GatewayFailureDisposition.TERMINAL,
                -> {
                    resolveTerminalEvidenceReservationProblem(userId, operation, pending, error)
                    if (error.disposition == GatewayFailureDisposition.USER_ACTION_REQUIRED) {
                        throw UserActionRequiredSyncException(userMessage)
                    }
                }
            }
        } catch (error: Throwable) {
            if (error.isProvenGatewayTransportFailure()) {
                localStore.markOutboxRetry(operation, SAFE_EVIDENCE_RESERVATION_RETRY_MESSAGE)
                throw RetryableSyncException(SAFE_EVIDENCE_RESERVATION_RETRY_MESSAGE, error)
            }
            localStore.markOutboxRetry(operation, SAFE_EVIDENCE_RESERVATION_FAILURE_MESSAGE)
            throw TerminalSyncException(SAFE_EVIDENCE_RESERVATION_FAILURE_MESSAGE, error)
        }
    }

    /**
     * Persists an authoritative terminal command response before stopping
     * replay. A 409 snapshot is applied immediately and a later full feed
     * remains the final source of truth for the driver projection.
     */
    private suspend fun resolveTerminalActionProblem(
        userId: String,
        operation: DriverOutboxEntity,
        error: GatewayProblemException,
    ) {
        recordConflict(userId, operation, error)
        val currentEntry = error.problem.currentEntry
        if (currentEntry == null) {
            if (error.problem.status == 404) {
                // A terminal command response without an entry means the
                // driver can no longer see or act on it. Durable evidence is
                // deliberately retained for an explicit recovery decision.
                projections.commitAbsentActionResult(
                    userId = userId,
                    operationId = operation.operationId,
                    entryId = operation.entryId,
                )
            } else {
                localStore.markOutboxComplete(operation.operationId)
            }
        } else {
            projections.commitActionResult(userId, operation.operationId, currentEntry)
        }
    }

    /**
     * Retains a server-rejected evidence row as review-required instead of
     * replaying a request that has already reached a terminal response.
     */
    private suspend fun resolveTerminalEvidenceReservationProblem(
        userId: String,
        operation: DriverOutboxEntity,
        pending: PendingEvidenceReservation,
        error: GatewayProblemException,
    ) {
        recordConflict(userId, operation, error)
        database.evidenceDao().updateState(
            pending.evidenceId,
            "REVIEW_REQUIRED",
            null,
            null,
            gatewayProblemUserMessage(error.problem),
            System.currentTimeMillis(),
        )
        localStore.markOutboxComplete(operation.operationId)
    }

    private suspend fun uploadEvidence(
        userId: String,
        completed: Int,
        total: Int,
        expectedMediaCount: Int,
    ): EvidenceSyncResult {
        val evidence = database.evidenceDao().pending(userId)
        evidence.forEachIndexed { index, item ->
            updateProgress(
                userId,
                "UPLOAD",
                completed + index,
                total,
                item.evidenceId,
                item.uploadPercent,
                "Загружаем фото ${index + 1} из ${evidence.size}",
            )
            when (val result = mediaUploadPipeline.uploadReservedEvidence(userId, item)) {
                EvidenceUploadResult.WaitingForReservation -> return EvidenceSyncResult(
                    DriverSyncOutcome.Deferred("Ожидается резервирование фотографии"),
                    completed + index,
                )
                EvidenceUploadResult.Processing -> return EvidenceSyncResult(
                    DriverSyncOutcome.Deferred("Фотография ещё обрабатывается"),
                    completed + index,
                )
                is EvidenceUploadResult.Ready,
                is EvidenceUploadResult.ReviewRequired,
                -> Unit
            }
            updateProgress(
                userId,
                "UPLOAD",
                completed + index + 1,
                total,
                item.evidenceId,
                100,
                "Фотография ${index + 1} обработана",
            )
        }
        return EvidenceSyncResult(outcome = null, completedUnits = completed + expectedMediaCount)
    }

    private suspend fun fetchFeed(userId: String, context: DriverContextDto): FetchedFeed {
        val session = database.sessionDao().session(userId)
        val cachedCategories = database.categoryDao().categories(userId)
        var cursor: String? = null
        var first = true
        val cachedFirstPageEtag = session.feedEtagForValidatedProjection()
            ?.takeIf {
                cachedFeedMatchesContext(
                    session = session,
                    cachedCategories = cachedCategories,
                    contextRevision = context.revision,
                    contextCategories = context.categories,
                )
            }
        var firstPageEtag: String? = cachedFirstPageEtag
        var consistency: FeedPageConsistency? = null
        val categories = linkedMapOf<String, DriverFeedCategoryDto>()
        val seenCursors = mutableSetOf<String>()
        var pageCount = 0
        do {
            if (++pageCount > MAX_FEED_PAGES) {
                throw TerminalSyncException("Пагинация ленты превысила безопасный предел")
            }
            when (val page = gateway.feed(cursor = cursor, etag = if (first) cachedFirstPageEtag else null)) {
                is DriverFeedResponse.NotModified -> {
                    // A 304 only applies to the first complete cached feed.
                    if (!first || session?.cacheHidden != false) {
                        throw TerminalSyncException("Скрытый кэш нельзя подтверждать ответом 304")
                    }
                    return FetchedFeed.NotModified(page.etag ?: cachedFirstPageEtag)
                }
                is DriverFeedResponse.Changed -> {
                    consistency = validateFeedPage(consistency, page.feed.revision, page.feed.serverTime)
                    if (first) firstPageEtag = page.etag ?: cachedFirstPageEtag
                    page.feed.categories.forEach { incoming ->
                        val current = categories[incoming.category.queueId]
                        categories[incoming.category.queueId] = if (current == null) incoming else {
                            current.copy(entries = (current.entries + incoming.entries).distinctBy { it.entryId })
                        }
                    }
                    cursor = page.feed.nextCursor
                    if (cursor != null && !seenCursors.add(cursor)) {
                        throw TerminalSyncException("Сервер вернул циклический курсор ленты")
                    }
                }
            }
            first = false
        } while (cursor != null)
        return FetchedFeed.Changed(
            revision = requireNotNull(consistency).revision,
            serverTime = consistency.serverTime,
            categories = categories.values.toList(),
            etag = firstPageEtag,
        )
    }

    /**
     * Applies an authenticated full feed after blocked media without accepting
     * the context's new offline lease. This keeps completed-by-another-driver
     * tasks from remaining IN_PROGRESS while preserving upload/completion
     * ordering and the captured encrypted evidence.
     */
    private suspend fun reconcileFeedAfterBlockedMedia(userId: String, context: DriverContextDto) {
        when (val feed = fetchFeed(userId, context)) {
            is FetchedFeed.Changed -> projections.commitFeedWithoutLeaseRenewal(
                userId = userId,
                revision = feed.revision,
                serverTime = feed.serverTime,
                categories = feed.categories,
                etag = feed.etag,
            )
            is FetchedFeed.NotModified -> Unit
        }
    }

    /**
     * A completion is never sent while the local projection knows that its
     * queue still lacks READY evidence. The server remains authoritative and
     * repeats this gate, but avoiding the request prevents a false success UI.
     */
    private suspend fun completionEvidenceReady(userId: String, entryId: String): Boolean {
        val task = database.taskDao().task(userId, entryId) ?: return false
        val ready = database.evidenceDao().readyCount(userId, entryId)
        return completionGateAllows(task.resultPhotoMinCount, ready)
    }

    private fun actionPayload(operation: DriverOutboxEntity): PendingDriverAction =
        runCatching { json.decodeFromString<PendingDriverAction>(localStore.decryptOutboxPayload(operation)) }
            .getOrElse { throw TerminalSyncException("Локальная команда повреждена", it) }

    private fun shiftCommandPayload(operation: DriverOutboxEntity): PendingShiftCommand =
        runCatching { json.decodeFromString<PendingShiftCommand>(localStore.decryptOutboxPayload(operation)) }
            .getOrElse { throw TerminalSyncException("Локальная команда смены повреждена", it) }

    private suspend fun recordConflict(
        userId: String,
        operation: DriverOutboxEntity,
        error: GatewayProblemException,
    ) {
        localStore.recordConflict(
            userId = userId,
            operationId = operation.operationId,
            entryId = operation.entryId,
            code = error.problem.code,
            message = gatewayProblemUserMessage(error.problem),
            currentVersion = error.problem.currentVersion,
            currentEntryJson = error.problem.currentEntry?.let(json::encodeToString),
        )
    }

    private suspend fun updateProgress(
        userId: String,
        stage: String,
        completed: Int,
        total: Int,
        activeEvidenceId: String?,
        activeEvidencePercent: Int?,
        message: String?,
    ) = localStore.updateProgress(
        DriverSyncProgressEntity(
            userId = userId,
            stage = stage,
            completedUnits = completed,
            totalUnits = total.coerceAtLeast(1),
            activeEvidenceId = activeEvidenceId,
            activeEvidencePercent = activeEvidencePercent,
            message = message,
            updatedAtEpochMillis = System.currentTimeMillis(),
        ),
    )

}

/**
 * Returns the typed sync outcome for a gateway Problem Details response after
 * the authenticator has had its single refresh opportunity.
 */
internal fun driverSyncOutcomeForGatewayProblem(error: GatewayProblemException): DriverSyncOutcome =
    when (error.disposition) {
        GatewayFailureDisposition.AUTHENTICATION_REQUIRED ->
            DriverSyncOutcome.AuthenticationRequired(gatewayProblemUserMessage(error.problem))
        GatewayFailureDisposition.USER_ACTION_REQUIRED ->
            DriverSyncOutcome.UserActionRequired(gatewayProblemUserMessage(error.problem))
        GatewayFailureDisposition.CONFLICT ->
            DriverSyncOutcome.Conflict(gatewayProblemUserMessage(error.problem))
        GatewayFailureDisposition.RETRYABLE ->
            DriverSyncOutcome.Retry(gatewayProblemUserMessage(error.problem))
        GatewayFailureDisposition.TERMINAL ->
            DriverSyncOutcome.Failed(gatewayProblemUserMessage(error.problem))
    }

/** Converts an untyped failure to a fixed outcome without exposing its exception text. */
internal fun driverSyncOutcomeForUnexpectedFailure(error: Throwable): DriverSyncOutcome =
    if (error.isProvenGatewayTransportFailure()) {
        DriverSyncOutcome.Retry(SAFE_SYNC_RETRY_MESSAGE)
    } else {
        DriverSyncOutcome.Failed(SAFE_SYNC_FAILURE_MESSAGE)
    }

/** A reservation response outside the narrow retry set is terminal for automatic replay. */
internal fun isTerminalEvidenceReservationStatus(status: Int): Boolean =
    gatewayFailureDisposition(status) != GatewayFailureDisposition.RETRYABLE

/**
 * Records the revision and server time from the first changed feed page so all
 * following pages can be rejected when they describe a mixed snapshot.
 */
internal data class FeedPageConsistency(val revision: Long, val serverTime: String)

internal fun validateFeedPage(
    previous: FeedPageConsistency?,
    revision: Long,
    serverTime: String,
): FeedPageConsistency {
    val current = FeedPageConsistency(revision, serverTime)
    require(previous == null || previous == current) {
        "RWMS returned a mixed-revision driver feed"
    }
    return current
}

internal fun completionGateAllows(requiredReadyEvidence: Int, actualReadyEvidence: Int): Boolean =
    actualReadyEvidence >= requiredReadyEvidence

/**
 * Allows final replay only after task-board has projected a ready photo for the exact closing
 * defect. A shift without a closing defect has no mandatory end-of-shift evidence.
 */
internal fun TodayDriverShiftDto.closeEvidenceReady(expectedShiftId: String): Boolean {
    if (shift?.id != expectedShiftId) return false
    val closingDefectId = closingReport?.defectId ?: return true
    return photos.any { photo ->
        photo.role == "END_SHIFT_DEFECT" &&
            photo.defectId == closingDefectId &&
            photo.state == "READY"
    }
}

/** A purged/hidden projection must be fetched again instead of revalidated. */
internal fun DriverSessionEntity?.feedEtagForValidatedProjection(): String? =
    this?.takeIf { !it.cacheHidden }?.feedEtag

/**
 * An ETag can validate only the same authorized projection. A new server
 * revision, changed queue binding or a v1 cache without category metadata
 * must fetch a complete feed even if an older ETag is still present.
 */
internal fun cachedFeedMatchesContext(
    session: DriverSessionEntity?,
    cachedCategories: List<DriverCategoryEntity>,
    contextRevision: Long,
    contextCategories: List<DriverCategoryDto>,
): Boolean {
    if (session == null || session.cacheHidden || session.revision != contextRevision) return false
    if (cachedCategories.size != contextCategories.size) return false
    val cachedByQueue = cachedCategories.associateBy { it.queueId }
    return contextCategories.all { remote ->
        val cached = cachedByQueue[remote.queueId] ?: return@all false
        cached.name == remote.name &&
            cached.type == remote.type &&
            cached.queuePurpose == remote.queuePurpose &&
            cached.groupIdsKey == remote.normalizedGroupIdsKey() &&
            cached.sortOrder == remote.sortOrder &&
            cached.audienceModesKey == remote.normalizedAudienceModesKey() &&
            cached.resultPhotoMinCount == remote.resultPhotoMinCount
    }
}

private const val MAX_FEED_PAGES = 100
private const val SAFE_SYNC_RETRY_MESSAGE =
    "Нет соединения с RWMS. Проверьте сеть и повторите попытку."
private const val SAFE_SYNC_FAILURE_MESSAGE =
    "Не удалось синхронизировать данные. Обновите список заданий и повторите попытку."
private const val SAFE_SHIFT_RETRY_MESSAGE =
    "Не удалось передать состояние смены. Проверьте сеть и повторите попытку."
private const val SAFE_SHIFT_FAILURE_MESSAGE =
    "Не удалось обновить смену. Синхронизируйте данные и повторите действие."
private const val SAFE_ACTION_RETRY_MESSAGE =
    "Не удалось передать действие. Проверьте сеть и повторите попытку."
private const val SAFE_ACTION_FAILURE_MESSAGE =
    "Не удалось передать действие. Синхронизируйте задание и повторите попытку."
private const val SAFE_EVIDENCE_RESERVATION_RETRY_MESSAGE =
    "Не удалось зарезервировать фотографию. Проверьте сеть и повторите попытку."
private const val SAFE_EVIDENCE_RESERVATION_FAILURE_MESSAGE =
    "Не удалось зарезервировать фотографию. Синхронизируйте задание и повторите попытку."
private const val SAFE_EVIDENCE_UPLOAD_RETRY_MESSAGE =
    "Не удалось отправить фотографию. Проверьте сеть и повторите попытку."
private const val SAFE_EVIDENCE_UPLOAD_FAILURE_MESSAGE =
    "Не удалось отправить фотографию. Проверьте файл и повторите попытку."
private const val SHIFT_BRIEFING_SEEN = "BRIEFING_SEEN"
private const val SHIFT_MEDICAL_CHECK = "MEDICAL_CHECK"
private const val SHIFT_INSPECTION_ITEM = "INSPECTION_ITEM"
private const val SHIFT_INSPECTION_COMPLETE = "INSPECTION_COMPLETE"
private const val SHIFT_START = "START"
private const val SHIFT_CLOSING_START = "CLOSING_START"
private const val SHIFT_WAREHOUSE_RETURN = "WAREHOUSE_RETURN"
private const val SHIFT_CLOSING_REPORT = "CLOSING_REPORT"
private const val SHIFT_PHOTO_RESERVATION = "PHOTO_RESERVATION"
private const val SHIFT_CLOSE = "CLOSE"
private val LATE_SHIFT_ACTIONS = setOf(SHIFT_CLOSING_REPORT, SHIFT_CLOSE)
