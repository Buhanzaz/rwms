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
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskDetailDto
import dev.buhanzaz.rwms.worker.core.network.gatewayFailureDisposition
import dev.buhanzaz.rwms.worker.core.network.gatewayProblemUserMessage
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

    /** A pending evidence dependency gets a bounded background follow-up, even with the app closed. */
    data class Deferred(val reason: String) : WorkerSyncOutcome

    /** The gateway's token refresh opportunity was exhausted and login is required. */
    data class AuthenticationRequired(val reason: String) : WorkerSyncOutcome

    /** A valid session lacks the current grant and must not be retried in the background. */
    data class UserActionRequired(val reason: String) : WorkerSyncOutcome

    /** A conflict and any server snapshot are persisted until the worker explicitly acknowledges them. */
    data class Conflict(val reason: String) : WorkerSyncOutcome

    /** A non-recoverable local or protocol failure stopped this sync attempt safely. */
    data class Failed(val reason: String) : WorkerSyncOutcome
}

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

/**
 * Reports whether one durable operation was retired and whether later work for
 * the same entry must wait. Independent entries always remain eligible.
 */
private data class EntryOperationResult(
    val completedUnits: Int,
    val blocksEntry: Boolean,
    val outcome: WorkerSyncOutcome? = null,
)

/** Captures one independently uploaded evidence outcome without cancelling sibling transfers. */
private data class EvidenceUploadAttempt(
    val index: Int,
    val evidenceId: String,
    val result: EvidenceUploadResult?,
    val error: Throwable?,
)

/**
 * Executes the only permitted per-entry ordering for offline work:
 * prerequisite actions, evidence reservations, media upload/finalization,
 * then completion actions. Room is the UI source of truth during every stage;
 * a blocked entry never stalls another entry owned by the same worker.
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
     * Runs one authenticated recovery pass in per-entry dependency order, then
     * refreshes the feed and returns a durable aggregate outcome. A partial
     * pass never activates a new offline lease.
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
            val pendingEvidence = database.evidenceDao().pending(userId)
            // Captured rows already exist before their reservation outbox row is
            // applied, so count media once up front for a stable progress total.
            val mediaCount = pendingEvidence.size
            val total = outbox.size + mediaCount + 2
            var completed = 1
            val entryOutcomes = mutableListOf<WorkerSyncOutcome>()
            val entryIds = (
                outbox.map { it.entryId to it.createdAtEpochMillis } +
                    pendingEvidence.map { it.entryId to it.createdAtEpochMillis }
                )
                .sortedBy { it.second }
                .map { it.first }
                .distinct()

            entryIds.forEach { entryId ->
                val entryActions = actions.filter { it.entryId == entryId }
                val prerequisites = entryActions.filter { actionPayload(it).action != "COMPLETE" }
                val completions = entryActions.filter { actionPayload(it).action == "COMPLETE" }
                val reservations = outbox.filter {
                    it.entryId == entryId && it.kind == WorkerLocalStore.OUTBOX_EVIDENCE_RESERVATION
                }
                var entryBlocked = false

                for (operation in prerequisites) {
                    val result = applyAction(userId, operation)
                    completed += result.completedUnits
                    result.outcome?.let(entryOutcomes::add)
                    updateProgress(userId, "COMMANDS", completed, total, null, null, "Передаём действия")
                    if (result.blocksEntry) {
                        entryBlocked = true
                        break
                    }
                }
                if (entryBlocked) return@forEach

                for (operation in reservations) {
                    val result = reserveEvidence(userId, operation)
                    completed += result.completedUnits
                    result.outcome?.let(entryOutcomes::add)
                    updateProgress(userId, "EVIDENCE", completed, total, null, null, "Резервируем фото")
                    if (result.blocksEntry) {
                        entryBlocked = true
                        break
                    }
                }
                if (entryBlocked) return@forEach

                val entryEvidence = database.evidenceDao().pending(userId).filter { it.entryId == entryId }
                val mediaResult = uploadEvidence(userId, entryEvidence, completed, total)
                completed = mediaResult.completedUnits
                mediaResult.outcome?.let {
                    entryOutcomes += it
                    return@forEach
                }

                for (operation in completions) {
                    if (!completionEvidenceReady(userId, operation.entryId)) {
                        entryOutcomes += WorkerSyncOutcome.Deferred(
                            "Завершение ${operation.entryId} ждёт обработки фотографии",
                        )
                        break
                    }
                    val result = applyAction(userId, operation)
                    completed += result.completedUnits
                    result.outcome?.let(entryOutcomes::add)
                    updateProgress(userId, "COMMANDS", completed, total, null, null, "Завершаем задания")
                    if (result.blocksEntry) break
                }
            }

            val passOutcome = highestPriorityOutcome(entryOutcomes)
            when (val feed = fetchFeed(userId, context)) {
                is FetchedFeed.Changed -> {
                    if (passOutcome == null) {
                        projections.commitContextAndFeed(
                            context,
                            feed.revision,
                            feed.serverTime,
                            feed.categories,
                            feed.etag,
                        )
                    } else {
                        projections.commitFeedWithoutLeaseRenewal(
                            userId = userId,
                            revision = feed.revision,
                            serverTime = feed.serverTime,
                            categories = feed.categories,
                            etag = feed.etag,
                        )
                    }
                }
                is FetchedFeed.NotModified -> {
                    // Context + a validated ETag is a successful full snapshot.
                    if (passOutcome == null) projections.applyContext(context)
                }
            }
            completed += 1
            if (passOutcome == null) {
                updateProgress(userId, "IDLE", completed, total, null, null, "Синхронизировано")
                WorkerSyncOutcome.Complete
            } else {
                updateProgress(
                    userId = userId,
                    stage = passOutcome.progressStage(),
                    completed = completed,
                    total = total,
                    activeEvidenceId = null,
                    activeEvidencePercent = null,
                    message = passOutcome.progressMessage(),
                )
                passOutcome
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: UserActionRequiredSyncException) {
            WorkerSyncOutcome.UserActionRequired(error.message ?: "Нужно обновить доступ рабочего")
        } catch (error: GatewayProblemException) {
            if (error.disposition == GatewayFailureDisposition.CONFLICT) {
                recordSyncConflict(userId, error)
            }
            workerSyncOutcomeForGatewayProblem(error)
        } catch (error: TerminalSyncException) {
            WorkerSyncOutcome.Failed(error.message ?: SAFE_SYNC_FAILURE_MESSAGE)
        } catch (error: Throwable) {
            workerSyncOutcomeForUnexpectedFailure(error)
        }
    }

    private suspend fun applyAction(userId: String, operation: WorkerOutboxEntity): EntryOperationResult {
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
            return EntryOperationResult(completedUnits = 1, blocksEntry = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GatewayProblemException) {
            val userMessage = gatewayProblemUserMessage(error.problem)
            when (error.disposition) {
                GatewayFailureDisposition.AUTHENTICATION_REQUIRED -> throw error
                GatewayFailureDisposition.RETRYABLE -> {
                    localStore.markOutboxRetry(operation, userMessage)
                    return EntryOperationResult(
                        completedUnits = 0,
                        blocksEntry = true,
                        outcome = WorkerSyncOutcome.Retry(userMessage),
                    )
                }
                GatewayFailureDisposition.USER_ACTION_REQUIRED,
                GatewayFailureDisposition.CONFLICT,
                GatewayFailureDisposition.TERMINAL,
                -> {
                    resolveTerminalActionProblem(userId, operation, error)
                    if (error.disposition == GatewayFailureDisposition.USER_ACTION_REQUIRED) {
                        return EntryOperationResult(
                            completedUnits = 1,
                            blocksEntry = true,
                            outcome = WorkerSyncOutcome.UserActionRequired(userMessage),
                        )
                    }
                    return EntryOperationResult(
                        completedUnits = 1,
                        blocksEntry = true,
                        outcome = if (error.disposition == GatewayFailureDisposition.CONFLICT) {
                            WorkerSyncOutcome.Conflict(userMessage)
                        } else {
                            null
                        },
                    )
                }
            }
        } catch (error: Throwable) {
            if (error.isProvenGatewayTransportFailure()) {
                localStore.markOutboxRetry(operation, SAFE_ACTION_RETRY_MESSAGE)
                return EntryOperationResult(
                    completedUnits = 0,
                    blocksEntry = true,
                    outcome = WorkerSyncOutcome.Retry(SAFE_ACTION_RETRY_MESSAGE),
                )
            }
            localStore.markOutboxRetry(operation, SAFE_ACTION_FAILURE_MESSAGE)
            return EntryOperationResult(
                completedUnits = 0,
                blocksEntry = true,
                outcome = WorkerSyncOutcome.Failed(SAFE_ACTION_FAILURE_MESSAGE),
            )
        }
    }

    private suspend fun reserveEvidence(userId: String, operation: WorkerOutboxEntity): EntryOperationResult {
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
            return EntryOperationResult(completedUnits = 1, blocksEntry = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GatewayProblemException) {
            val userMessage = gatewayProblemUserMessage(error.problem)
            when (error.disposition) {
                GatewayFailureDisposition.AUTHENTICATION_REQUIRED -> throw error
                GatewayFailureDisposition.RETRYABLE -> {
                    localStore.markOutboxRetry(operation, userMessage)
                    return EntryOperationResult(
                        completedUnits = 0,
                        blocksEntry = true,
                        outcome = WorkerSyncOutcome.Retry(userMessage),
                    )
                }
                GatewayFailureDisposition.USER_ACTION_REQUIRED,
                GatewayFailureDisposition.CONFLICT,
                GatewayFailureDisposition.TERMINAL,
                -> {
                    resolveTerminalEvidenceReservationProblem(userId, operation, pending, error)
                    if (error.disposition == GatewayFailureDisposition.USER_ACTION_REQUIRED) {
                        return EntryOperationResult(
                            completedUnits = 1,
                            blocksEntry = true,
                            outcome = WorkerSyncOutcome.UserActionRequired(userMessage),
                        )
                    }
                    return EntryOperationResult(
                        completedUnits = 1,
                        blocksEntry = true,
                        outcome = if (error.disposition == GatewayFailureDisposition.CONFLICT) {
                            WorkerSyncOutcome.Conflict(userMessage)
                        } else {
                            null
                        },
                    )
                }
            }
        } catch (error: Throwable) {
            if (error.isProvenGatewayTransportFailure()) {
                localStore.markOutboxRetry(operation, SAFE_EVIDENCE_RESERVATION_RETRY_MESSAGE)
                return EntryOperationResult(
                    completedUnits = 0,
                    blocksEntry = true,
                    outcome = WorkerSyncOutcome.Retry(SAFE_EVIDENCE_RESERVATION_RETRY_MESSAGE),
                )
            }
            localStore.markOutboxRetry(operation, SAFE_EVIDENCE_RESERVATION_FAILURE_MESSAGE)
            return EntryOperationResult(
                completedUnits = 0,
                blocksEntry = true,
                outcome = WorkerSyncOutcome.Failed(SAFE_EVIDENCE_RESERVATION_FAILURE_MESSAGE),
            )
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
            gatewayProblemUserMessage(error.problem),
            System.currentTimeMillis(),
        )
        localStore.markOutboxComplete(operation.operationId)
    }

    private suspend fun uploadEvidence(
        userId: String,
        evidence: List<TaskEvidenceEntity>,
        completed: Int,
        total: Int,
    ): EvidenceSyncResult = supervisorScope {
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

        val completedUploads = attempts.count { attempt ->
            attempt.result is EvidenceUploadResult.Ready ||
                attempt.result is EvidenceUploadResult.ReviewRequired
        }
        attempts.firstOrNull { it.error != null }?.let { failed ->
            val error = requireNotNull(failed.error)
            if (error is GatewayProblemException &&
                error.disposition == GatewayFailureDisposition.AUTHENTICATION_REQUIRED
            ) {
                throw error
            }
            if (error is GatewayProblemException &&
                error.disposition == GatewayFailureDisposition.CONFLICT
            ) {
                val item = evidence.first { it.evidenceId == failed.evidenceId }
                localStore.recordConflict(
                    userId = userId,
                    operationId = item.uploadOperationId,
                    entryId = item.entryId,
                    code = error.problem.code,
                    message = gatewayProblemUserMessage(error.problem),
                    currentVersion = error.problem.currentVersion,
                    currentEntryJson = error.problem.currentEntry?.let(json::encodeToString),
                )
            }
            val outcome = if (error is GatewayProblemException) {
                workerSyncOutcomeForGatewayProblem(error)
            } else if (error.isProvenGatewayTransportFailure()) {
                WorkerSyncOutcome.Retry(SAFE_EVIDENCE_UPLOAD_RETRY_MESSAGE)
            } else {
                WorkerSyncOutcome.Failed(SAFE_EVIDENCE_UPLOAD_FAILURE_MESSAGE)
            }
            return@supervisorScope EvidenceSyncResult(
                outcome = outcome,
                completedUnits = completed + completedUploads,
            )
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
        EvidenceSyncResult(outcome = null, completedUnits = completed + evidence.size)
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
     * A completion is never sent while the merged server/local projection
     * still lacks READY evidence. The task count covers a feed-only snapshot;
     * cached detail IDs and local finalized IDs are unioned to avoid both stale
     * feed undercounting and double-counting the same evidence.
     */
    private suspend fun completionEvidenceReady(userId: String, entryId: String): Boolean {
        val task = database.taskDao().task(userId, entryId) ?: return false
        val localReadyIds = database.evidenceDao().readyIds(userId, entryId)
        val detailReadyIds = cachedReadyEvidenceIds(userId, entryId)
        val ready = combinedReadyEvidenceCount(
            serverReadyEvidenceCount = task.readyEvidenceCount,
            localReadyEvidenceIds = localReadyIds,
            detailReadyEvidenceIds = detailReadyIds,
        )
        return completionGateAllows(task.resultPhotoMinCount, ready)
    }

    private suspend fun cachedReadyEvidenceIds(userId: String, entryId: String): List<String> =
        database.detailDao().detail(userId, entryId)
            ?.sanitizedDetailJson
            ?.let { encoded ->
                runCatching { json.decodeFromString<WorkerTaskDetailDto>(encoded) }
                    .getOrNull()
                    ?.evidence
                    ?.filter { it.state == "READY" }
                    ?.map { it.evidenceId }
            }
            .orEmpty()

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
            message = gatewayProblemUserMessage(error.problem),
            currentVersion = error.problem.currentVersion,
            currentEntryJson = error.problem.currentEntry?.let(json::encodeToString),
        )
    }

    /** Persists a non-command 409 (for example a feed revision race) for explicit acknowledgement. */
    private suspend fun recordSyncConflict(userId: String, error: GatewayProblemException) {
        val currentEntry = error.problem.currentEntry
        localStore.recordConflict(
            userId = userId,
            operationId = "sync:$userId:${error.problem.code}:${error.problem.currentVersion ?: "unknown"}",
            entryId = currentEntry?.entryId ?: SYNC_CONFLICT_ENTRY_ID,
            code = error.problem.code,
            message = gatewayProblemUserMessage(error.problem),
            currentVersion = error.problem.currentVersion,
            currentEntryJson = currentEntry?.let(json::encodeToString),
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
            WorkerSyncOutcome.AuthenticationRequired(gatewayProblemUserMessage(error.problem))
        GatewayFailureDisposition.USER_ACTION_REQUIRED ->
            WorkerSyncOutcome.UserActionRequired(gatewayProblemUserMessage(error.problem))
        GatewayFailureDisposition.CONFLICT ->
            WorkerSyncOutcome.Conflict(gatewayProblemUserMessage(error.problem))
        GatewayFailureDisposition.RETRYABLE ->
            WorkerSyncOutcome.Retry(gatewayProblemUserMessage(error.problem))
        GatewayFailureDisposition.TERMINAL ->
            WorkerSyncOutcome.Failed(gatewayProblemUserMessage(error.problem))
    }

/** Converts an untyped failure to a fixed outcome without exposing its internal exception text. */
internal fun workerSyncOutcomeForUnexpectedFailure(error: Throwable): WorkerSyncOutcome =
    if (error.isProvenGatewayTransportFailure()) {
        WorkerSyncOutcome.Retry(SAFE_SYNC_RETRY_MESSAGE)
    } else {
        WorkerSyncOutcome.Failed(SAFE_SYNC_FAILURE_MESSAGE)
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
        "Лента заданий содержит несогласованные данные"
    }
    return current
}

internal fun completionGateAllows(requiredReadyEvidence: Int, actualReadyEvidence: Int): Boolean =
    actualReadyEvidence >= requiredReadyEvidence

/**
 * Merges a count-only feed snapshot with exact READY IDs known from detail and
 * local finalized evidence without counting an ID twice.
 */
internal fun combinedReadyEvidenceCount(
    serverReadyEvidenceCount: Int,
    localReadyEvidenceIds: List<String>,
    detailReadyEvidenceIds: List<String>,
): Int = maxOf(
    serverReadyEvidenceCount,
    (localReadyEvidenceIds + detailReadyEvidenceIds).distinct().size,
)

/** Selects the durable result for a pass after every independent entry had a chance to progress. */
private fun highestPriorityOutcome(outcomes: List<WorkerSyncOutcome>): WorkerSyncOutcome? =
    outcomes.firstOrNull { it is WorkerSyncOutcome.UserActionRequired } ?:
        outcomes.firstOrNull { it is WorkerSyncOutcome.Failed } ?:
        outcomes.firstOrNull { it is WorkerSyncOutcome.Retry } ?:
        outcomes.firstOrNull { it is WorkerSyncOutcome.Conflict } ?:
        outcomes.firstOrNull { it is WorkerSyncOutcome.Deferred }

/** Returns the durable progress stage associated with an incomplete sync pass. */
private fun WorkerSyncOutcome.progressStage(): String = when (this) {
    WorkerSyncOutcome.Complete -> "IDLE"
    is WorkerSyncOutcome.Deferred -> "WAITING_FOR_EVIDENCE"
    is WorkerSyncOutcome.Retry -> "RETRY"
    is WorkerSyncOutcome.AuthenticationRequired -> "AUTHENTICATION_REQUIRED"
    is WorkerSyncOutcome.UserActionRequired -> "USER_ACTION_REQUIRED"
    is WorkerSyncOutcome.Conflict -> "CONFLICT"
    is WorkerSyncOutcome.Failed -> "FAILED"
}

/** Returns the worker-facing reason retained with an incomplete sync pass. */
private fun WorkerSyncOutcome.progressMessage(): String? = when (this) {
    WorkerSyncOutcome.Complete -> null
    is WorkerSyncOutcome.Deferred -> reason
    is WorkerSyncOutcome.Retry -> reason
    is WorkerSyncOutcome.AuthenticationRequired -> reason
    is WorkerSyncOutcome.UserActionRequired -> reason
    is WorkerSyncOutcome.Conflict -> reason
    is WorkerSyncOutcome.Failed -> reason
}

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
private const val SYNC_CONFLICT_ENTRY_ID = "worker-feed"
private const val SAFE_SYNC_RETRY_MESSAGE =
    "Нет соединения с RWMS. Проверьте сеть и повторите попытку."
private const val SAFE_SYNC_FAILURE_MESSAGE =
    "Не удалось синхронизировать данные. Обновите список заданий и повторите попытку."
private const val SAFE_ACTION_RETRY_MESSAGE =
    "Не удалось передать действие. Проверьте сеть и повторите попытку."
private const val SAFE_ACTION_FAILURE_MESSAGE =
    "Не удалось передать действие. Обновите список заданий и повторите попытку."
private const val SAFE_EVIDENCE_RESERVATION_RETRY_MESSAGE =
    "Не удалось зарезервировать фотографию. Проверьте сеть и повторите попытку."
private const val SAFE_EVIDENCE_RESERVATION_FAILURE_MESSAGE =
    "Не удалось зарезервировать фотографию. Обновите задание и повторите попытку."
private const val SAFE_EVIDENCE_UPLOAD_RETRY_MESSAGE =
    "Не удалось загрузить фотографию. Проверьте сеть и повторите попытку."
private const val SAFE_EVIDENCE_UPLOAD_FAILURE_MESSAGE =
    "Не удалось загрузить фотографию. Повторите отправку вручную."

/** Returns whether task-board proved that no further worker evidence can be attached. */
private fun String.isTerminalWorkerTaskStatus(): Boolean = this == "DONE" || this == "CANCELLED"
