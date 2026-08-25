package dev.buhanzaz.rwms.worker.core.sync

import dev.buhanzaz.rwms.worker.core.database.PendingEvidenceReservation
import dev.buhanzaz.rwms.worker.core.database.PendingWorkerAction
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerOutboxEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerSessionEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerSyncProgressEntity
import dev.buhanzaz.rwms.worker.core.media.EvidenceUploadResult
import dev.buhanzaz.rwms.worker.core.media.WorkerEvidenceUploader
import dev.buhanzaz.rwms.worker.core.network.EvidenceReservationRequestDto
import dev.buhanzaz.rwms.worker.core.network.GatewayFailureDisposition
import dev.buhanzaz.rwms.worker.core.network.GatewayProblemException
import dev.buhanzaz.rwms.worker.core.network.WorkerActionRequestDto
import dev.buhanzaz.rwms.worker.core.network.WorkerCategoryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerContextDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedCategoryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedResponse
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.network.gatewayFailureDisposition
import dev.buhanzaz.rwms.worker.core.network.isProvenGatewayTransportFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Reports one sync pass to the bounded worker job. Only an explicitly
 * classified temporary gateway or transport failure can return [Retry].
 */
sealed interface WorkerSyncOutcome {
    /** All pending work and the authoritative worker projection are synchronized. */
    data object Complete : WorkerSyncOutcome

    /** A bounded automatic retry is permitted for a transient dependency or transport failure. */
    data class Retry(val reason: String) : WorkerSyncOutcome

    /** A server-confirmed evidence dependency is pending until a later explicit sync trigger. */
    data class Deferred(val reason: String) : WorkerSyncOutcome

    /** The gateway's token refresh opportunity was exhausted and login is required. */
    data class AuthenticationRequired(val reason: String) : WorkerSyncOutcome

    /** A valid session lacks the current grant and must not be retried in the background. */
    data class UserActionRequired(val reason: String) : WorkerSyncOutcome

    /** A command conflict has been persisted and requires authoritative state before another action. */
    data class Conflict(val reason: String) : WorkerSyncOutcome

    /** A non-recoverable local or protocol failure stopped this sync attempt safely. */
    data class Failed(val reason: String) : WorkerSyncOutcome
}

/**
 * Carries only an explicitly classified temporary failure to the bounded
 * WorkManager recovery loop.
 */
private class RetryableSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Stops background replay when the current worker grant cannot perform the requested action. */
private class UserActionRequiredSyncException(message: String) : Exception(message)

/** Stops automatic recovery for an invalid local state or terminal gateway/protocol response. */
private class TerminalSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Represents one complete authoritative feed fetch. A changed result contains
 * every consistent page; a not-modified result is the validated first-page
 * cache response.
 */
private sealed interface FetchedFeed {
    data class Changed(
        val revision: Long,
        val serverTime: String,
        val categories: List<WorkerFeedCategoryDto>,
        val etag: String?,
    ) : FetchedFeed

    data class NotModified(val etag: String?) : FetchedFeed
}

/**
 * Carries media-phase progress and an optional blocked-evidence outcome so the
 * coordinator can reconcile the authoritative feed before returning.
 */
private data class EvidenceSyncResult(
    val outcome: WorkerSyncOutcome?,
    val completedUnits: Int,
)

/** Captures one independently uploaded evidence outcome without cancelling sibling transfers. */
private data class EvidenceUploadAttempt(
    val index: Int,
    val evidenceId: String,
    val result: EvidenceUploadResult?,
    val error: Throwable?,
)

/**
 * Executes the only permitted ordering for offline work: prerequisite actions,
 * evidence reservations, media upload/finalization, then completion actions.
 * Room is the UI source of truth during every stage.
 */
@Singleton
class WorkerSyncCoordinator @Inject constructor(
    private val gateway: WorkerGatewayClient,
    private val database: WorkerDatabase,
    private val localStore: WorkerLocalStore,
    private val projections: WorkerProjectionWriter,
    private val mediaUploadPipeline: WorkerEvidenceUploader,
    private val json: Json,
) {
    private val evidenceUploadPermits = Semaphore(MAX_PARALLEL_EVIDENCE_UPLOADS)

    /**
     * Runs one authenticated recovery pass in the fixed command/evidence/feed order and returns a
     * durable outcome; it never activates a new offline lease from a partial sync.
     */
    suspend fun sync(userId: String): WorkerSyncOutcome {
        localStore.hideExpiredCacheIfNeeded(userId)
        return try {
            updateProgress(userId, "CONTEXT", 0, 1, null, null, "Проверяем доступ")
            val context = gateway.context()
            if (context.worker.id != userId) {
                throw UserActionRequiredSyncException("Ответ RWMS получен для другого пользователя")
            }
            // Do not activate this new lease yet. A dropped upload or failed
            // feed must not grant another 24 offline hours.
            projections.stageContextIdentity(context)
            // Older WorkerApp versions terminally retained a result photo when
            // its original offline lease elapsed. The current authenticated
            // context can safely reconstruct only that exact durable request;
            // all other review-required photos remain manual recovery cases.
            localStore.requeueExpiredLeaseEvidenceReservations(userId, context.offlineLease.id)

            val outbox = localStore.pendingOutbox(userId)
            val actions = outbox.filter { it.kind == WorkerLocalStore.OUTBOX_ACTION }
            val prerequisites = actions.filter { actionPayload(it).action != "COMPLETE" }
            val completions = actions.filter { actionPayload(it).action == "COMPLETE" }
            val reservations = outbox.filter { it.kind == WorkerLocalStore.OUTBOX_EVIDENCE_RESERVATION }
            // Captured rows already exist before their reservation outbox row is
            // applied, so count media once up front for a stable progress total.
            val mediaCount = database.evidenceDao().pending(userId).size
            val total = prerequisites.size + reservations.size + mediaCount + completions.size + 2
            var completed = 1

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
                    return WorkerSyncOutcome.UserActionRequired(
                        error.problem.detail ?: "Нужно обновить доступ рабочего",
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
                return workerSyncOutcomeForGatewayProblem(error)
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
                    return WorkerSyncOutcome.Failed(error.message ?: "Не удалось загрузить фотографию")
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
                return WorkerSyncOutcome.Retry(error.message ?: "Не удалось загрузить фотографию")
            }
            if (mediaResult.outcome != null) {
                // An upload can be delayed by media processing or a transient
                // failure for minutes. It must not prevent an authenticated
                // full feed from removing a task another worker has completed.
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
                    return WorkerSyncOutcome.Deferred("Завершение ждёт обработки фотографии")
                }
                applyAction(userId, operation)
                completed += 1
                updateProgress(userId, "COMMANDS", completed, total, null, null, "Завершаем задания")
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
            updateProgress(userId, "IDLE", completed, total, null, null, "Синхронизировано")
            WorkerSyncOutcome.Complete
        } catch (error: CancellationException) {
            throw error
        } catch (error: UserActionRequiredSyncException) {
            WorkerSyncOutcome.UserActionRequired(error.message ?: "Нужно обновить доступ рабочего")
        } catch (error: GatewayProblemException) {
            workerSyncOutcomeForGatewayProblem(error)
        } catch (error: RetryableSyncException) {
            WorkerSyncOutcome.Retry(error.message ?: "Сеть недоступна")
        } catch (error: TerminalSyncException) {
            WorkerSyncOutcome.Failed(error.message ?: "Не удалось синхронизировать данные")
        } catch (error: Throwable) {
            if (error.isProvenGatewayTransportFailure()) {
                WorkerSyncOutcome.Retry(error.message ?: "Сеть недоступна")
            } else {
                WorkerSyncOutcome.Failed(error.message ?: "Не удалось синхронизировать данные")
            }
        }
    }

    private suspend fun applyAction(userId: String, operation: WorkerOutboxEntity) {
        val pending = actionPayload(operation)
        try {
            val result = gateway.action(
                operation.entryId,
                WorkerActionRequestDto(
                    operationId = pending.operationId,
                    action = pending.action,
                    expectedVersion = pending.expectedVersion,
                    workerGroupId = pending.workerGroupId,
                    evidenceId = pending.evidenceId,
                    occurredAt = pending.occurredAt,
                    offlineLeaseId = pending.offlineLeaseId,
                ),
            )
            projections.commitActionResult(userId, operation.operationId, result.entry)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GatewayProblemException) {
            when (error.disposition) {
                GatewayFailureDisposition.AUTHENTICATION_REQUIRED -> throw error
                GatewayFailureDisposition.RETRYABLE -> {
                    localStore.markOutboxRetry(operation, error.problem.detail ?: error.problem.title)
                    throw RetryableSyncException(error.problem.detail ?: error.problem.title, error)
                }
                GatewayFailureDisposition.USER_ACTION_REQUIRED,
                GatewayFailureDisposition.CONFLICT,
                GatewayFailureDisposition.TERMINAL,
                -> {
                    resolveTerminalActionProblem(userId, operation, error)
                    if (error.disposition == GatewayFailureDisposition.USER_ACTION_REQUIRED) {
                        throw UserActionRequiredSyncException(
                            error.problem.detail ?: "Нужно обновить доступ рабочего",
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            if (error.isProvenGatewayTransportFailure()) {
                localStore.markOutboxRetry(operation, error.message ?: "network")
                throw RetryableSyncException("Не удалось передать действие", error)
            }
            localStore.markOutboxRetry(operation, error.message ?: "network")
            throw TerminalSyncException("Не удалось передать действие", error)
        }
    }

    private suspend fun reserveEvidence(userId: String, operation: WorkerOutboxEntity) {
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
            when (error.disposition) {
                GatewayFailureDisposition.AUTHENTICATION_REQUIRED -> throw error
                GatewayFailureDisposition.RETRYABLE -> {
                    localStore.markOutboxRetry(operation, error.problem.detail ?: error.problem.title)
                    throw RetryableSyncException(error.problem.detail ?: error.problem.title, error)
                }
                GatewayFailureDisposition.USER_ACTION_REQUIRED,
                GatewayFailureDisposition.CONFLICT,
                GatewayFailureDisposition.TERMINAL,
                -> {
                    resolveTerminalEvidenceReservationProblem(userId, operation, pending, error)
                    if (error.disposition == GatewayFailureDisposition.USER_ACTION_REQUIRED) {
                        throw UserActionRequiredSyncException(
                            error.problem.detail ?: "Нужно обновить доступ рабочего",
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            if (error.isProvenGatewayTransportFailure()) {
                localStore.markOutboxRetry(operation, error.message ?: "network")
                throw RetryableSyncException("Не удалось зарезервировать фото", error)
            }
            localStore.markOutboxRetry(operation, error.message ?: "network")
            throw TerminalSyncException("Не удалось зарезервировать фото", error)
        }
    }

    /**
     * Persists an authoritative terminal command response before stopping
     * replay. A 409 snapshot is applied immediately and a later full feed
     * remains the final source of truth for the worker projection.
     */
    private suspend fun resolveTerminalActionProblem(
        userId: String,
        operation: WorkerOutboxEntity,
        error: GatewayProblemException,
    ) {
        recordConflict(userId, operation, error)
        val currentEntry = error.problem.currentEntry
        if (currentEntry == null) {
            if (error.problem.status == 404) {
                // A terminal command response without an entry means the
                // worker can no longer see or act on it. Durable evidence is
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
     * Reconciles a terminal reservation response with the owning task. A photo that can no longer
     * be attached to a server-confirmed completed/cancelled task is archived locally without
     * deleting its encrypted bytes; every other rejection remains visible for worker review.
     */
    private suspend fun resolveTerminalEvidenceReservationProblem(
        userId: String,
        operation: WorkerOutboxEntity,
        pending: PendingEvidenceReservation,
        error: GatewayProblemException,
    ) {
        recordConflict(userId, operation, error)
        val terminalDetail = try {
            gateway.detail(operation.entryId).takeIf { it.status.isTerminalWorkerTaskStatus() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        if (terminalDetail != null) {
            projections.applyDetail(userId, terminalDetail)
            localStore.supersedeTerminalTaskEvidence(
                userId = userId,
                evidenceId = pending.evidenceId,
                reservationOperationId = operation.operationId,
                reason = TERMINAL_TASK_EVIDENCE_REASON,
            )
            return
        }
        database.evidenceDao().updateState(
            pending.evidenceId,
            "REVIEW_REQUIRED",
            null,
            null,
            error.problem.detail ?: error.problem.title,
            System.currentTimeMillis(),
        )
        localStore.markOutboxComplete(operation.operationId)
    }

    private suspend fun uploadEvidence(
        userId: String,
        completed: Int,
        total: Int,
        expectedMediaCount: Int,
    ): EvidenceSyncResult = supervisorScope {
        val evidence = database.evidenceDao().pending(userId)
        val attempts = evidence.mapIndexed { index, item ->
            async {
                evidenceUploadPermits.withPermit {
                    updateProgress(
                        userId,
                        "UPLOAD",
                        completed + index,
                        total,
                        item.evidenceId,
                        item.uploadPercent,
                        "Загружаем фото ${index + 1} из ${evidence.size}",
                    )
                    try {
                        val result = mediaUploadPipeline.uploadReservedEvidence(userId, item)
                        if (result is EvidenceUploadResult.Ready ||
                            result is EvidenceUploadResult.ReviewRequired
                        ) {
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
                        EvidenceUploadAttempt(index, item.evidenceId, result, null)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        EvidenceUploadAttempt(index, item.evidenceId, null, error)
                    }
                }
            }
        }.awaitAll().sortedBy(EvidenceUploadAttempt::index)

        attempts.firstOrNull { it.error != null }?.let { failed ->
            throw requireNotNull(failed.error)
        }
        val completedUploads = attempts.count { attempt ->
            attempt.result is EvidenceUploadResult.Ready ||
                attempt.result is EvidenceUploadResult.ReviewRequired
        }
        attempts.firstOrNull { it.result is EvidenceUploadResult.WaitingForReservation }?.let {
            return@supervisorScope EvidenceSyncResult(
                WorkerSyncOutcome.Deferred("Ожидается резервирование фотографии"),
                completed + completedUploads,
            )
        }
        attempts.firstOrNull { it.result is EvidenceUploadResult.Processing }?.let {
            return@supervisorScope EvidenceSyncResult(
                WorkerSyncOutcome.Deferred("Фотография ещё обрабатывается"),
                completed + completedUploads,
            )
        }
        EvidenceSyncResult(outcome = null, completedUnits = completed + expectedMediaCount)
    }

    private suspend fun fetchFeed(userId: String, context: WorkerContextDto): FetchedFeed {
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
        val categories = linkedMapOf<String, WorkerFeedCategoryDto>()
        val seenCursors = mutableSetOf<String>()
        var pageCount = 0
        do {
            if (++pageCount > MAX_FEED_PAGES) {
                throw TerminalSyncException("Пагинация ленты превысила безопасный предел")
            }
            when (val page = gateway.feed(cursor = cursor, etag = if (first) cachedFirstPageEtag else null)) {
                is WorkerFeedResponse.NotModified -> {
                    // A 304 only applies to the first complete cached feed.
                    if (!first || session?.cacheHidden != false) {
                        throw TerminalSyncException("Скрытый кэш нельзя подтверждать ответом 304")
                    }
                    return FetchedFeed.NotModified(page.etag ?: cachedFirstPageEtag)
                }
                is WorkerFeedResponse.Changed -> {
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
     * the context's new offline lease. This keeps completed-by-another-worker
     * tasks from remaining IN_PROGRESS while preserving upload/completion
     * ordering and the captured encrypted evidence.
     */
    private suspend fun reconcileFeedAfterBlockedMedia(userId: String, context: WorkerContextDto) {
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

    private fun actionPayload(operation: WorkerOutboxEntity): PendingWorkerAction =
        runCatching { json.decodeFromString<PendingWorkerAction>(localStore.decryptOutboxPayload(operation)) }
            .getOrElse { throw TerminalSyncException("Локальная команда повреждена", it) }

    private suspend fun recordConflict(
        userId: String,
        operation: WorkerOutboxEntity,
        error: GatewayProblemException,
    ) {
        localStore.recordConflict(
            userId = userId,
            operationId = operation.operationId,
            entryId = operation.entryId,
            code = error.problem.code,
            message = error.problem.detail ?: error.problem.title,
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
        WorkerSyncProgressEntity(
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
internal fun workerSyncOutcomeForGatewayProblem(error: GatewayProblemException): WorkerSyncOutcome =
    when (error.disposition) {
        GatewayFailureDisposition.AUTHENTICATION_REQUIRED ->
            WorkerSyncOutcome.AuthenticationRequired(error.problem.detail ?: "Требуется повторный вход")
        GatewayFailureDisposition.USER_ACTION_REQUIRED ->
            WorkerSyncOutcome.UserActionRequired(error.problem.detail ?: "Нужно обновить доступ рабочего")
        GatewayFailureDisposition.CONFLICT ->
            WorkerSyncOutcome.Conflict(
                error.problem.detail ?: "Данные задания изменились на RWMS. Обновите список задач.",
            )
        GatewayFailureDisposition.RETRYABLE ->
            WorkerSyncOutcome.Retry(error.problem.detail ?: "RWMS временно недоступен")
        GatewayFailureDisposition.TERMINAL ->
            WorkerSyncOutcome.Failed(error.problem.detail ?: "Не удалось синхронизировать данные")
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
        "RWMS returned a mixed-revision worker feed"
    }
    return current
}

internal fun completionGateAllows(requiredReadyEvidence: Int, actualReadyEvidence: Int): Boolean =
    actualReadyEvidence >= requiredReadyEvidence

/** A purged/hidden projection must be fetched again instead of revalidated. */
internal fun WorkerSessionEntity?.feedEtagForValidatedProjection(): String? =
    this?.takeIf { !it.cacheHidden }?.feedEtag

/**
 * An ETag can validate only the same authorized projection. A new server
 * revision, changed queue binding or a v1 cache without category metadata
 * must fetch a complete feed even if an older ETag is still present.
 */
internal fun cachedFeedMatchesContext(
    session: WorkerSessionEntity?,
    cachedCategories: List<WorkerCategoryEntity>,
    contextRevision: Long,
    contextCategories: List<WorkerCategoryDto>,
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
private const val MAX_PARALLEL_EVIDENCE_UPLOADS = 2
private const val TERMINAL_TASK_EVIDENCE_REASON =
    "Задание уже завершено; локальная фотография сохранена на устройстве"

/** Returns whether task-board proved that no further worker evidence can be attached. */
private fun String.isTerminalWorkerTaskStatus(): Boolean = this == "DONE" || this == "CANCELLED"
