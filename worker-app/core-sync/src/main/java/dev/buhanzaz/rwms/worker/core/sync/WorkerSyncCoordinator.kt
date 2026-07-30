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
import dev.buhanzaz.rwms.worker.core.network.GatewayProblemException
import dev.buhanzaz.rwms.worker.core.network.WorkerActionRequestDto
import dev.buhanzaz.rwms.worker.core.network.WorkerCategoryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerContextDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedCategoryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedResponse
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface WorkerSyncOutcome {
    data object Complete : WorkerSyncOutcome
    data class Retry(val reason: String) : WorkerSyncOutcome
    data class AuthenticationRequired(val reason: String) : WorkerSyncOutcome
}

private class RetryableSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

private sealed interface FetchedFeed {
    data class Changed(
        val revision: Long,
        val serverTime: String,
        val categories: List<WorkerFeedCategoryDto>,
        val etag: String?,
    ) : FetchedFeed

    data class NotModified(val etag: String?) : FetchedFeed
}

private data class EvidenceSyncResult(
    val outcome: WorkerSyncOutcome?,
    val completedUnits: Int,
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
    suspend fun sync(userId: String): WorkerSyncOutcome {
        localStore.hideExpiredCacheIfNeeded(userId)
        return try {
            updateProgress(userId, "CONTEXT", 0, 1, null, null, "Проверяем доступ")
            val context = gateway.context()
            if (context.worker.id != userId) {
                throw SecurityException("Ответ RWMS получен для другого пользователя")
            }
            // Do not activate this new lease yet. A dropped upload or failed
            // feed must not grant another 24 offline hours.
            projections.stageContextIdentity(context)

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
                if (error.problem.status == 401) throw error
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
                return WorkerSyncOutcome.Retry(error.problem.detail ?: "Не удалось загрузить фотографию")
            } catch (error: Throwable) {
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
                    return WorkerSyncOutcome.Retry("Завершение ждёт обработки фотографии")
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
        } catch (error: GatewayProblemException) {
            if (error.problem.status == 401) {
                WorkerSyncOutcome.AuthenticationRequired(error.problem.detail ?: "Требуется повторный вход")
            } else if (error.problem.status >= 500 || error.problem.status == 429) {
                WorkerSyncOutcome.Retry(error.problem.detail ?: "RWMS временно недоступен")
            } else {
                WorkerSyncOutcome.Retry(error.problem.detail ?: "Не удалось синхронизировать данные")
            }
        } catch (error: RetryableSyncException) {
            WorkerSyncOutcome.Retry(error.message ?: "Сеть недоступна")
        } catch (error: IOException) {
            WorkerSyncOutcome.Retry(error.message ?: "Сеть недоступна")
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
                    occurredAt = pending.occurredAt,
                    offlineLeaseId = pending.offlineLeaseId,
                ),
            )
            projections.commitActionResult(userId, operation.operationId, result.entry)
        } catch (error: GatewayProblemException) {
            if (error.problem.status == 401) throw error
            if (error.isTerminalCommandProblem()) {
                recordConflict(userId, operation, error)
                val currentEntry = error.problem.currentEntry
                if (currentEntry == null) {
                    if (error.problem.status == 404) {
                        // A terminal command response without an entry means
                        // the worker can no longer see or act on it. Remove the
                        // stale optimistic task immediately; durable evidence
                        // is intentionally left in place.
                        projections.commitAbsentActionResult(
                            userId = userId,
                            operationId = operation.operationId,
                            entryId = operation.entryId,
                        )
                    } else {
                        // Authorization/validation conflicts without a current
                        // entry are not proof of absence, so retain the server
                        // projection and only stop this command replay.
                        localStore.markOutboxComplete(operation.operationId)
                    }
                } else {
                    projections.commitActionResult(userId, operation.operationId, currentEntry)
                }
            } else {
                localStore.markOutboxRetry(operation, error.problem.detail ?: error.problem.title)
                throw RetryableSyncException(error.problem.detail ?: error.problem.title, error)
            }
        } catch (error: Throwable) {
            localStore.markOutboxRetry(operation, error.message ?: "network")
            throw RetryableSyncException("Не удалось передать действие", error)
        }
    }

    private suspend fun reserveEvidence(userId: String, operation: WorkerOutboxEntity) {
        val pending = runCatching {
            json.decodeFromString<PendingEvidenceReservation>(localStore.decryptOutboxPayload(operation))
        }.getOrElse { throw RetryableSyncException("Локальная резервная запись повреждена", it) }
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
        } catch (error: GatewayProblemException) {
            if (error.problem.status == 401) throw error
            if (error.isTerminalEvidenceReservationProblem()) {
                recordConflict(userId, operation, error)
                database.evidenceDao().updateState(
                    pending.evidenceId,
                    "REVIEW_REQUIRED",
                    null,
                    null,
                    error.problem.detail ?: error.problem.title,
                    System.currentTimeMillis(),
                )
                localStore.markOutboxComplete(operation.operationId)
            } else {
                localStore.markOutboxRetry(operation, error.problem.detail ?: error.problem.title)
                throw RetryableSyncException(error.problem.detail ?: error.problem.title, error)
            }
        } catch (error: Throwable) {
            localStore.markOutboxRetry(operation, error.message ?: "network")
            throw RetryableSyncException("Не удалось зарезервировать фото", error)
        }
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
                    WorkerSyncOutcome.Retry("Ожидается резервирование фотографии"),
                    completed + index,
                )
                EvidenceUploadResult.Processing -> return EvidenceSyncResult(
                    WorkerSyncOutcome.Retry("Фотография ещё обрабатывается"),
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
                throw RetryableSyncException("Пагинация ленты превысила безопасный предел")
            }
            when (val page = gateway.feed(cursor = cursor, etag = if (first) cachedFirstPageEtag else null)) {
                is WorkerFeedResponse.NotModified -> {
                    // A 304 only applies to the first complete cached feed.
                    if (!first || session?.cacheHidden != false) {
                        throw RetryableSyncException("Скрытый кэш нельзя подтверждать ответом 304")
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
                        throw RetryableSyncException("Сервер вернул циклический курсор ленты")
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
            .getOrElse { throw RetryableSyncException("Локальная команда повреждена", it) }

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

    private fun GatewayProblemException.isTerminalCommandProblem(): Boolean =
        problem.status == 400 || problem.status == 403 || problem.status == 404 || problem.status == 409

    /**
     * A 404 can mean that a rolling deployment has not exposed the reservation
     * route yet. Keep the encrypted photo and stable outbox operation so the
     * same reservation is retried once the service recovers.
     */
    private fun GatewayProblemException.isTerminalEvidenceReservationProblem(): Boolean =
        isTerminalEvidenceReservationStatus(problem.status)
}

internal fun isTerminalEvidenceReservationStatus(status: Int): Boolean =
    status == 400 || status == 403 || status == 409

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
            cached.sortOrder == remote.sortOrder &&
            cached.audienceModesKey == remote.normalizedAudienceModesKey() &&
            cached.resultPhotoMinCount == remote.resultPhotoMinCount
    }
}

private const val MAX_FEED_PAGES = 100
