package dev.buhanzaz.rwms.worker.core.sync

import android.os.SystemClock
import androidx.room.withTransaction
import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.database.WorkerGroupEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerSessionEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskDetailEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerAssignmentDto
import dev.buhanzaz.rwms.worker.core.network.WorkerCategoryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerContextDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedCategoryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedEntryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskDetailDto
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Writes only authorization-filtered snapshots received from the public worker
 * API. A full feed replaces visible projection rows, which prevents a changed
 * audience from leaving a stale task visible in Room.
 */
@Singleton
class WorkerProjectionWriter @Inject constructor(
    private val database: WorkerDatabase,
    private val json: Json,
) {
    /**
     * Stores only identity/warehouse information needed by an online sync.
     * Crucially it retains the previous lease anchor: a response from
     * /context is not a successful full sync by itself.
     */
    suspend fun stageContextIdentity(context: WorkerContextDto) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val previous = database.sessionDao().session(context.worker.id)
            database.sessionDao().upsert(
                stagedSession(
                    previous = previous,
                    context = context,
                    now = now,
                    kpiPaletteJson = context.kpiPalette?.let { json.encodeToString(it) },
                ),
            )
            replaceGroups(context)
        }
    }

    /** Activates a server-issued lease only after commands, media and feed succeed. */
    suspend fun applyContext(context: WorkerContextDto): WorkerSessionEntity {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val previous = database.sessionDao().session(context.worker.id)
            val activated = activatedSession(
                previous = previous,
                context = context,
                now = now,
                kpiPaletteJson = context.kpiPalette?.let { json.encodeToString(it) },
            )
            database.sessionDao().upsert(activated)
            replaceGroups(context)
            reconcileCompletedActionsFromDetails(context.worker.id, now)
            database.conflictDao().resolveOpenForUser(context.worker.id, now)
            activated
        }
    }

    /**
     * Commits the new lease and its complete authorized feed in one Room
     * transaction. Process death can therefore leave the prior lease intact,
     * but can never activate a lease without the matching full projection.
     */
    suspend fun commitContextAndFeed(
        context: WorkerContextDto,
        revision: Long,
        serverTime: String,
        categories: List<WorkerFeedCategoryDto>,
        etag: String?,
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val userId = context.worker.id
            val previous = database.sessionDao().session(userId)
            val activated = activatedSession(
                previous = previous,
                context = context,
                now = now,
                kpiPaletteJson = context.kpiPalette?.let { json.encodeToString(it) },
            )
            database.sessionDao().upsert(activated)
            replaceGroups(context)
            applyFeedInTransaction(
                userId = userId,
                revision = revision,
                serverTime = serverTime,
                categories = categories,
                etag = etag,
                now = now,
                makeCacheVisible = true,
            )
            database.conflictDao().resolveOpenForUser(userId, now)
        }
    }

    /**
     * Persists an authenticated, complete feed when media work is still
     * pending or failed. The server snapshot is still authoritative for
     * visible tasks, but the newly issued lease is deliberately not activated
     * until media and completion ordering have succeeded.
     */
    suspend fun commitFeedWithoutLeaseRenewal(
        userId: String,
        revision: Long,
        serverTime: String,
        categories: List<WorkerFeedCategoryDto>,
        etag: String?,
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            applyFeedInTransaction(
                userId = userId,
                revision = revision,
                serverTime = serverTime,
                categories = categories,
                etag = etag,
                now = now,
                // A failed upload must not turn a freshly staged lease into a
                // reusable offline cache. Retain the previous visibility bit.
                makeCacheVisible = false,
            )
        }
    }

    private suspend fun applyFeedInTransaction(
        userId: String,
        revision: Long,
        serverTime: String,
        categories: List<WorkerFeedCategoryDto>,
        etag: String?,
        now: Long,
        makeCacheVisible: Boolean,
    ) {
            val pendingExpectedVersions = database.outboxDao().pending(userId)
                .filter { it.kind == "ACTION" }
                .mapNotNull { operation -> operation.expectedVersion?.let { operation.entryId to it } }
                .groupBy({ it.first }, { it.second })
            val session = requireNotNull(database.sessionDao().session(userId)) {
                "Worker context must be present before its feed"
            }
            val existing = database.taskDao().tasks(userId).associateBy { it.entryId }
            val flattened = categories.flatMap { category ->
                category.entries.map { entry ->
                    entry.toEntity(userId, category, revision, now).let { incoming ->
                        val local = existing[entry.entryId]
                        // A queued action may be replayed only while its expected
                        // server version is still current. Otherwise the server
                        // snapshot wins and sync will expose a conflict.
                        if (local?.locallyPending == true &&
                            pendingExpectedVersions[entry.entryId]?.contains(entry.version) == true
                        ) {
                            incoming.copy(
                                status = local.status,
                                version = local.version,
                                locallyPending = true,
                            )
                        } else {
                            incoming
                        }
                    }
                }
            }
            database.categoryDao().deleteForUser(userId)
            database.categoryDao().upsertAll(
                categories.map { category -> category.category.toEntity(userId, revision) },
            )
            database.taskDao().deleteForUser(userId)
            database.assignmentDao().deleteForUser(userId)
            // Keep sanitized details for entries still present in this
            // authorization-filtered feed. Deleting only absent IDs remains
            // fail-closed on an audience change without blanking an open card.
            val visibleEntryIds = flattened.map { it.entryId }.distinct()
            if (visibleEntryIds.isEmpty()) {
                database.detailDao().deleteForUser(userId)
            } else {
                database.detailDao().deleteNotVisible(userId, visibleEntryIds)
            }
            // A complete server feed is final for its visible entries. An
            // action left by a worker who was superseded/completed by another
            // worker must never keep a ghost IN_PROGRESS task alive or replay
            // against a task that is no longer actionable. Evidence rows and
            // their encrypted files are deliberately untouched.
            database.outboxDao().pending(userId)
                .asSequence()
                .filter { it.kind == WorkerLocalStore.OUTBOX_ACTION }
                .filter { it.entryId !in visibleEntryIds }
                .forEach { database.outboxDao().delete(it.operationId) }
            database.taskDao().upsertAll(flattened)
            database.assignmentDao().upsertAll(
                categories.flatMap { category ->
                    category.entries.flatMap { entry ->
                        entry.assignments.map { assignment -> assignment.toEntity(userId, entry.entryId) }
                    }
                },
            )
            database.sessionDao().upsert(
                session.copy(
                    revision = revision,
                    serverEpochMillis = serverTime.toEpochMillis("feed.serverTime"),
                    elapsedRealtimeAtSyncMillis = SystemClock.elapsedRealtime(),
                    feedEtag = etag ?: session.feedEtag,
                    cacheHidden = if (makeCacheVisible) false else session.cacheHidden,
                    updatedAtEpochMillis = now,
                ),
            )
    }

    private suspend fun replaceGroups(context: WorkerContextDto) {
        database.groupDao().deleteForUser(context.worker.id)
        database.groupDao().upsertAll(
            context.groups.map { group ->
                WorkerGroupEntity(
                    localId = "${context.worker.id}:${group.id}",
                    userId = context.worker.id,
                    groupId = group.id,
                    name = group.name,
                    workerClassId = group.workerClassId,
                    workerClassName = group.workerClassName,
                )
            },
        )
    }

    private fun WorkerCategoryDto.toEntity(userId: String, revision: Long) =
        WorkerCategoryEntity(
            localId = "$userId:$queueId",
            userId = userId,
            queueId = queueId,
            name = name,
            type = type,
            queuePurpose = queuePurpose,
            groupIdsKey = normalizedGroupIdsKey(),
            sortOrder = sortOrder,
            audienceModesKey = normalizedAudienceModesKey(),
            resultPhotoMinCount = resultPhotoMinCount,
            lastServerRevision = revision,
        )

    suspend fun applyDetail(userId: String, detail: WorkerTaskDetailDto) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            applyAuthoritativeDetailToProjection(userId, detail, now)
        }
    }

    /**
     * A command response is an authoritative entry snapshot. Persisting it
     * together with deleting its outbox row prevents a replayed tap and also
     * restores an optimistic projection when the following feed is a 304.
     */
    suspend fun commitActionResult(
        userId: String,
        operationId: String,
        detail: WorkerTaskDetailDto,
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            applyAuthoritativeDetailToProjection(userId, detail, now)
            database.outboxDao().delete(operationId)
        }
    }

    /**
     * A 404 command response is authoritative absence for this worker entry.
     * Remove only the reusable task projection and action; captured evidence
     * remains durable so it can be reviewed/reconciled separately.
     */
    suspend fun commitAbsentActionResult(
        userId: String,
        operationId: String,
        entryId: String,
    ) {
        database.withTransaction {
            database.taskDao().deleteForEntry(userId, entryId)
            database.assignmentDao().deleteForEntry(userId, entryId)
            database.detailDao().deleteForEntry(userId, entryId)
            database.outboxDao().delete(operationId)
        }
    }

    private suspend fun writeDetailAndAssignments(
        userId: String,
        detail: WorkerTaskDetailDto,
        now: Long,
    ) {
        database.detailDao().upsert(
            WorkerTaskDetailEntity(
                localId = "$userId:${detail.entryId}",
                userId = userId,
                entryId = detail.entryId,
                version = detail.version,
                sanitizedDetailJson = json.encodeToString(detail),
                fetchedAtEpochMillis = now,
            ),
        )
        database.assignmentDao().deleteForEntry(userId, detail.entryId)
        database.assignmentDao().upsertAll(
            detail.assignments.map { assignment -> assignment.toEntity(userId, detail.entryId) },
        )
    }

    /** Applies an individual server snapshot without allowing a terminal task back onto the board. */
    private suspend fun applyAuthoritativeDetailToProjection(
        userId: String,
        detail: WorkerTaskDetailDto,
        now: Long,
    ) {
        writeDetailAndAssignments(userId, detail, now)
        val existing = database.taskDao().task(userId, detail.entryId) ?: return
        if (detail.status.isTerminalWorkerStatus()) {
            database.taskDao().deleteForEntry(userId, detail.entryId)
        } else {
            database.taskDao().upsertAll(listOf(existing.withAuthoritativeDetail(detail, now)))
        }
    }

    /**
     * First-release APKs removed terminal outbox rows before restoring the
     * optimistic task. Their stored Problem Details still contain the current
     * entry, so a validated 304 can safely repair those rows in place.
     */
    private suspend fun reconcileCompletedActionsFromDetails(userId: String, now: Long) {
        val entriesWithPendingActions = database.outboxDao().pending(userId)
            .asSequence()
            .filter { it.kind == "ACTION" }
            .mapTo(mutableSetOf()) { it.entryId }
        database.taskDao().tasks(userId)
            .asSequence()
            .filter { it.locallyPending && it.entryId !in entriesWithPendingActions }
            .forEach { task ->
                val detail = database.detailDao().detail(userId, task.entryId)
                    ?.sanitizedDetailJson
                    ?.let { encoded ->
                        runCatching { json.decodeFromString<WorkerTaskDetailDto>(encoded) }.getOrNull()
                    }
                    ?: return@forEach
                if (detail.status.isTerminalWorkerStatus()) {
                    database.taskDao().deleteForEntry(userId, task.entryId)
                } else {
                    database.taskDao().upsertAll(listOf(task.withAuthoritativeDetail(detail, now)))
                }
                database.assignmentDao().deleteForEntry(userId, task.entryId)
                database.assignmentDao().upsertAll(
                    detail.assignments.map { assignment -> assignment.toEntity(userId, task.entryId) },
                )
            }
    }

    private fun WorkerFeedEntryDto.toEntity(
        userId: String,
        category: WorkerFeedCategoryDto,
        revision: Long,
        now: Long,
    ) = WorkerTaskEntity(
        localId = "$userId:$entryId",
        userId = userId,
        entryId = entryId,
        taskId = taskId,
        version = version,
        categoryId = category.category.queueId,
        categoryName = category.category.name,
        categorySortOrder = category.category.sortOrder,
        title = title,
        unitNumber = unitNumber,
        taskText = taskText,
        scheduledDate = scheduledDate,
        deadlineAt = deadlineAt,
        priority = priority,
        queuePosition = queuePosition,
        status = status,
        availabilityMode = availabilityMode,
        plannedDurationMinutes = plannedDurationMinutes,
        activeStartedAt = activeStartedAt,
        activeWorkSeconds = activeWorkSeconds,
        readyEvidenceCount = readyEvidenceCount,
        resultPhotoMinCount = resultPhotoMinCount,
        lastServerRevision = revision,
        locallyPending = false,
        updatedAtEpochMillis = now,
        timerCountedActiveSeconds = timerSnapshot?.countedActiveSeconds,
        timerRemainingSeconds = timerSnapshot?.remainingSeconds,
        timerRemainingPercent = timerSnapshot?.remainingPercent,
        timerState = timerSnapshot?.timerState,
        timerNextTransitionAt = timerSnapshot?.nextTransitionAt,
        timerServerTime = timerSnapshot?.serverTime,
        routeIndex = routeIndex,
        routeStepCount = routeStepCount,
    )

    private fun WorkerAssignmentDto.toEntity(userId: String, entryId: String) =
        WorkerAssignmentEntity(
            localId = "$userId:$entryId:$id",
            userId = userId,
            entryId = entryId,
            assignmentId = id,
            workerId = workerId,
            workerName = workerName,
            workerGroupId = workerGroupId,
            workerGroupName = workerGroupName,
            status = status,
            assignedAt = assignedAt,
            startedAt = startedAt,
            pausedAt = pausedAt,
            finishedAt = finishedAt,
        )

    private fun WorkerTaskEntity.withAuthoritativeDetail(
        detail: WorkerTaskDetailDto,
        now: Long,
    ) = copy(
        version = detail.version,
        title = detail.title,
        taskText = detail.taskText,
        scheduledDate = detail.scheduledDate,
        deadlineAt = detail.deadlineAt,
        priority = detail.priority,
        queuePosition = detail.queuePosition,
        status = detail.status,
        availabilityMode = detail.availabilityMode,
        plannedDurationMinutes = detail.plannedDurationMinutes,
        activeStartedAt = detail.activeStartedAt,
        activeWorkSeconds = detail.activeWorkSeconds,
        readyEvidenceCount = detail.evidence.count { it.state == "READY" },
        resultPhotoMinCount = detail.resultPhotoMinCount,
        locallyPending = false,
        updatedAtEpochMillis = now,
        timerCountedActiveSeconds = detail.timerSnapshot?.countedActiveSeconds,
        timerRemainingSeconds = detail.timerSnapshot?.remainingSeconds,
        timerRemainingPercent = detail.timerSnapshot?.remainingPercent,
        timerState = detail.timerSnapshot?.timerState,
        timerNextTransitionAt = detail.timerSnapshot?.nextTransitionAt,
        timerServerTime = detail.timerSnapshot?.serverTime,
        routeIndex = detail.routeIndex,
    )

    private fun String.toEpochMillis(field: String): Long =
        runCatching { Instant.parse(this).toEpochMilli() }
            .getOrElse { throw IllegalArgumentException("Invalid $field from RWMS gateway", it) }

    private fun String.isTerminalWorkerStatus(): Boolean = this == "DONE" || this == "CANCELLED"
}

internal fun stagedSession(
    previous: WorkerSessionEntity?,
    context: WorkerContextDto,
    now: Long,
    kpiPaletteJson: String? = context.kpiPalette?.let { Json.encodeToString(it) },
): WorkerSessionEntity = WorkerSessionEntity(
    userId = context.worker.id,
    displayName = context.worker.displayName,
    login = context.worker.login,
    warehouseId = context.worker.warehouseId,
    leaseId = previous?.leaseId,
    leaseExpiresAtEpochMillis = previous?.leaseExpiresAtEpochMillis,
    serverEpochMillis = previous?.serverEpochMillis,
    elapsedRealtimeAtSyncMillis = previous?.elapsedRealtimeAtSyncMillis,
    revision = previous?.revision ?: 0L,
    feedEtag = previous?.feedEtag,
    cacheHidden = previous?.cacheHidden ?: true,
    updatedAtEpochMillis = now,
    currentGroupId = context.currentGroup?.id,
    currentGroupName = context.currentGroup?.name,
    operationalAvailability = context.operationalAvailability,
    kpiPaletteJson = kpiPaletteJson,
)

internal fun activatedSession(
    previous: WorkerSessionEntity?,
    context: WorkerContextDto,
    now: Long,
    elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
    kpiPaletteJson: String? = context.kpiPalette?.let { Json.encodeToString(it) },
): WorkerSessionEntity = WorkerSessionEntity(
    userId = context.worker.id,
    displayName = context.worker.displayName,
    login = context.worker.login,
    warehouseId = context.worker.warehouseId,
    leaseId = context.offlineLease.id,
    leaseExpiresAtEpochMillis = context.offlineLease.expiresAt.toEpochMillisForProjection("offlineLease.expiresAt"),
    serverEpochMillis = context.serverTime.toEpochMillisForProjection("serverTime"),
    elapsedRealtimeAtSyncMillis = elapsedRealtimeMillis,
    revision = context.revision,
    feedEtag = previous?.feedEtag,
    cacheHidden = false,
    updatedAtEpochMillis = now,
    currentGroupId = context.currentGroup?.id,
    currentGroupName = context.currentGroup?.name,
    operationalAvailability = context.operationalAvailability,
    kpiPaletteJson = kpiPaletteJson,
)

private fun String.toEpochMillisForProjection(field: String): Long =
    runCatching { Instant.parse(this).toEpochMilli() }
        .getOrElse { throw IllegalArgumentException("Invalid $field from RWMS gateway", it) }

internal fun WorkerCategoryDto.normalizedAudienceModesKey(): String =
    audienceModes.asSequence().distinct().sorted().joinToString("\u001F")

internal fun WorkerCategoryDto.normalizedGroupIdsKey(): String =
    groupIds.asSequence().distinct().sorted().joinToString("\u001F")
