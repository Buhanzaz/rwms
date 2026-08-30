package dev.buhanzaz.rwms.driver.core.sync

import android.os.SystemClock
import androidx.room.withTransaction
import dev.buhanzaz.rwms.driver.core.database.DriverAssignmentEntity
import dev.buhanzaz.rwms.driver.core.database.DriverCategoryEntity
import dev.buhanzaz.rwms.driver.core.database.DriverDatabase
import dev.buhanzaz.rwms.driver.core.database.DriverGroupEntity
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.database.DriverSessionEntity
import dev.buhanzaz.rwms.driver.core.database.DriverShiftSnapshotEntity
import dev.buhanzaz.rwms.driver.core.database.DriverTaskDetailEntity
import dev.buhanzaz.rwms.driver.core.database.DriverTaskEntity
import dev.buhanzaz.rwms.driver.core.network.DriverAssignmentDto
import dev.buhanzaz.rwms.driver.core.network.DriverCategoryDto
import dev.buhanzaz.rwms.driver.core.network.DriverContextDto
import dev.buhanzaz.rwms.driver.core.network.DriverFeedCategoryDto
import dev.buhanzaz.rwms.driver.core.network.DriverFeedEntryDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskDetailDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Writes only authorization-filtered snapshots received from the public driver
 * API. A full feed replaces visible projection rows, which prevents a changed
 * audience from leaving a stale task visible in Room.
 */
@Singleton
class DriverProjectionWriter @Inject constructor(
    private val database: DriverDatabase,
    private val json: Json,
) {
    /** Stores a complete server-owned shift aggregate without touching its durable command queue. */
    suspend fun applyTodayShift(userId: String, today: TodayDriverShiftDto) {
        database.withTransaction {
            applyTodayShiftInTransaction(userId, today)
        }
    }

    /**
     * Atomically accepts a shift command response and removes only that replay-safe operation.
     * A successful partial replay retains newer inspection results and photos that still have a
     * durable outbox command, so process restart cannot visually roll back unfinished offline work.
     */
    suspend fun commitShiftCommandResult(
        userId: String,
        operationId: String,
        today: TodayDriverShiftDto,
        preservePendingOverlay: Boolean = true,
    ) {
        database.withTransaction {
            val committedToday = if (preservePendingOverlay) {
                mergePendingShiftOverlay(userId, operationId, today)
            } else {
                today
            }
            applyTodayShiftInTransaction(userId, committedToday)
            database.outboxDao().delete(operationId)
        }
    }

    /**
     * Reapplies only optimistic facts proven by a still-pending command for the same shift.
     * Authoritative status and timestamps are never overlaid; terminal-rejection commits bypass
     * this recovery path explicitly.
     */
    private suspend fun mergePendingShiftOverlay(
        userId: String,
        committedOperationId: String,
        authoritative: TodayDriverShiftDto,
    ): TodayDriverShiftDto {
        val authoritativeShift = authoritative.shift ?: return authoritative
        val localSnapshot = database.shiftSnapshotDao().snapshot(userId) ?: return authoritative
        if (localSnapshot.shiftId != authoritativeShift.id) return authoritative
        val local = runCatching {
            json.decodeFromString<TodayDriverShiftDto>(localSnapshot.serializedTodayShift)
        }.getOrNull() ?: return authoritative
        val localShift = local.shift?.takeIf { it.id == authoritativeShift.id } ?: return authoritative
        val pendingOperationIds = database.outboxDao().pending(userId)
            .asSequence()
            .filter {
                it.kind == DriverLocalStore.OUTBOX_SHIFT_COMMAND &&
                    it.entryId == authoritativeShift.id &&
                    it.operationId != committedOperationId
            }
            .mapTo(linkedSetOf()) { it.operationId }
        if (pendingOperationIds.isEmpty()) return authoritative

        val authoritativePhotoIds = authoritative.photos.mapTo(hashSetOf()) { it.evidenceId }
        val pendingPhotos = local.photos.filter {
            it.evidenceId in pendingOperationIds && it.evidenceId !in authoritativePhotoIds
        }
        val mergedPhotos = authoritative.photos + pendingPhotos

        val authoritativeInspection = authoritative.inspection
        val localInspection = local.inspection
        val canOverlayInspection = authoritative.nextRequiredAction == "COMPLETE_VEHICLE_INSPECTION" &&
            local.nextRequiredAction == "COMPLETE_VEHICLE_INSPECTION" &&
            authoritativeShift.status == "VEHICLE_INSPECTION_REQUIRED" &&
            localShift.status == "VEHICLE_INSPECTION_REQUIRED" &&
            authoritativeInspection != null &&
            localInspection != null &&
            authoritativeInspection.id == localInspection.id
        val mergedInspection = if (canOverlayInspection) {
            val confirmedAuthoritativeInspection = requireNotNull(authoritativeInspection)
            val confirmedLocalInspection = requireNotNull(localInspection)
            val localItems = confirmedLocalInspection.items.associateBy { it.id }
            val mergedItems = confirmedAuthoritativeInspection.items.map { authoritativeItem ->
                val localItem = localItems[authoritativeItem.id] ?: return@map authoritativeItem
                if (localItem.version > authoritativeItem.version) {
                    localItem
                } else {
                    val authoritativeDefect = authoritativeItem.defect
                    val localDefect = localItem.defect
                    if (authoritativeDefect != null && localDefect?.id == authoritativeDefect.id) {
                        val pendingPhotoIds = localDefect.photoIds.filter { it in pendingOperationIds }
                        authoritativeItem.copy(
                            defect = authoritativeDefect.copy(
                                photoIds = (authoritativeDefect.photoIds + pendingPhotoIds).distinct(),
                            ),
                        )
                    } else {
                        authoritativeItem
                    }
                }
            }
            if (mergedItems != confirmedAuthoritativeInspection.items) {
                confirmedAuthoritativeInspection.copy(
                    version = maxOf(confirmedAuthoritativeInspection.version, confirmedLocalInspection.version),
                    checkedRequired = mergedItems.count { it.required && it.state != "NOT_CHECKED" },
                    blockingDefectCount = mergedItems.count { item ->
                        item.defect?.let { defect ->
                            defect.severity == "BLOCKING" && defect.status == "OPEN"
                        } == true
                    },
                    items = mergedItems,
                )
            } else {
                confirmedAuthoritativeInspection
            }
        } else {
            authoritativeInspection
        }
        val hasOverlay = pendingPhotos.isNotEmpty() || mergedInspection != authoritativeInspection
        if (!hasOverlay) return authoritative
        return authoritative.copy(
            shift = authoritativeShift.copy(version = maxOf(authoritativeShift.version, localShift.version)),
            inspection = mergedInspection,
            photos = mergedPhotos,
        )
    }

    private suspend fun applyTodayShiftInTransaction(userId: String, today: TodayDriverShiftDto) {
        val now = System.currentTimeMillis()
        database.shiftSnapshotDao().upsert(
            DriverShiftSnapshotEntity(
                userId = userId,
                shiftId = today.shift?.id,
                workDate = today.shift?.workDate,
                enabled = today.enabled,
                nextRequiredAction = today.nextRequiredAction,
                serializedTodayShift = json.encodeToString(today),
                serverTime = today.serverTime,
                updatedAtEpochMillis = now,
            ),
        )
        today.photos.forEach { photo ->
            val local = database.evidenceDao().evidence(userId, photo.evidenceId) ?: return@forEach
            database.evidenceDao().updateState(
                evidenceId = local.evidenceId,
                state = photo.state,
                mediaId = photo.mediaId,
                generation = photo.mediaGeneration,
                reviewReason = if (photo.state == "REVIEW_REQUIRED") {
                    "Фотография требует проверки"
                } else {
                    null
                },
                now = now,
            )
        }
        val shiftId = today.shift?.id
        if (shiftId != null && (today.closingReport != null || today.nextRequiredAction == "SHIFT_CLOSED")) {
            database.shiftDraftDao().delete(userId, shiftId)
        }
    }

    /**
     * Stores only identity/warehouse information needed by an online sync.
     * Crucially it retains the previous lease anchor: a response from
     * /context is not a successful full sync by itself.
     */
    suspend fun stageContextIdentity(context: DriverContextDto) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val previous = database.sessionDao().session(context.driver.id)
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
    suspend fun applyContext(context: DriverContextDto): DriverSessionEntity {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val previous = database.sessionDao().session(context.driver.id)
            val activated = activatedSession(
                previous = previous,
                context = context,
                now = now,
                kpiPaletteJson = context.kpiPalette?.let { json.encodeToString(it) },
            )
            database.sessionDao().upsert(activated)
            replaceGroups(context)
            reconcileCompletedActionsFromDetails(context.driver.id, now)
            database.conflictDao().resolveOpenForUser(context.driver.id, now)
            activated
        }
    }

    /**
     * Commits the new lease and its complete authorized feed in one Room
     * transaction. Process death can therefore leave the prior lease intact,
     * but can never activate a lease without the matching full projection.
     */
    suspend fun commitContextAndFeed(
        context: DriverContextDto,
        revision: Long,
        serverTime: String,
        categories: List<DriverFeedCategoryDto>,
        etag: String?,
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val userId = context.driver.id
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
        categories: List<DriverFeedCategoryDto>,
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
        categories: List<DriverFeedCategoryDto>,
        etag: String?,
        now: Long,
        makeCacheVisible: Boolean,
    ) {
            val pendingExpectedVersions = database.outboxDao().pending(userId)
                .filter { it.kind == "ACTION" }
                .mapNotNull { operation -> operation.expectedVersion?.let { operation.entryId to it } }
                .groupBy({ it.first }, { it.second })
            val session = requireNotNull(database.sessionDao().session(userId)) {
                "Driver context must be present before its feed"
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
            // action left by a driver who was superseded/completed by another
            // driver must never keep a ghost IN_PROGRESS task alive or replay
            // against a task that is no longer actionable. Evidence rows and
            // their encrypted files are deliberately untouched.
            database.outboxDao().pending(userId)
                .asSequence()
                .filter { it.kind == DriverLocalStore.OUTBOX_ACTION }
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

    private suspend fun replaceGroups(context: DriverContextDto) {
        database.groupDao().deleteForUser(context.driver.id)
        database.groupDao().upsertAll(
            context.groups.map { group ->
                DriverGroupEntity(
                    localId = "${context.driver.id}:${group.id}",
                    userId = context.driver.id,
                    groupId = group.id,
                    name = group.name,
                    driverClassId = group.driverClassId,
                    driverClassName = group.driverClassName,
                )
            },
        )
    }

    private fun DriverCategoryDto.toEntity(userId: String, revision: Long) =
        DriverCategoryEntity(
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

    suspend fun applyDetail(userId: String, detail: DriverTaskDetailDto) {
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
        detail: DriverTaskDetailDto,
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            applyAuthoritativeDetailToProjection(userId, detail, now)
            database.outboxDao().delete(operationId)
        }
    }

    /**
     * A 404 command response is authoritative absence for this driver entry.
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
        detail: DriverTaskDetailDto,
        now: Long,
    ) {
        database.detailDao().upsert(
            DriverTaskDetailEntity(
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
        detail: DriverTaskDetailDto,
        now: Long,
    ) {
        writeDetailAndAssignments(userId, detail, now)
        val existing = database.taskDao().task(userId, detail.entryId) ?: return
        if (detail.status.isTerminalDriverStatus()) {
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
                        runCatching { json.decodeFromString<DriverTaskDetailDto>(encoded) }.getOrNull()
                    }
                    ?: return@forEach
                if (detail.status.isTerminalDriverStatus()) {
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

    private fun DriverFeedEntryDto.toEntity(
        userId: String,
        category: DriverFeedCategoryDto,
        revision: Long,
        now: Long,
    ) = DriverTaskEntity(
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
        driverAudienceMode = driverAudience?.mode,
    )

    private fun DriverAssignmentDto.toEntity(userId: String, entryId: String) =
        DriverAssignmentEntity(
            localId = "$userId:$entryId:$id",
            userId = userId,
            entryId = entryId,
            assignmentId = id,
            driverId = driverId,
            driverName = driverName,
            driverGroupId = driverGroupId,
            driverGroupName = driverGroupName,
            status = status,
            assignedAt = assignedAt,
            startedAt = startedAt,
            pausedAt = pausedAt,
            finishedAt = finishedAt,
        )

    private fun DriverTaskEntity.withAuthoritativeDetail(
        detail: DriverTaskDetailDto,
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
    )

    private fun String.toEpochMillis(field: String): Long =
        runCatching { Instant.parse(this).toEpochMilli() }
            .getOrElse { throw IllegalArgumentException("Invalid $field from RWMS gateway", it) }

    private fun String.isTerminalDriverStatus(): Boolean = this == "DONE" || this == "CANCELLED"
}

internal fun stagedSession(
    previous: DriverSessionEntity?,
    context: DriverContextDto,
    now: Long,
    kpiPaletteJson: String? = context.kpiPalette?.let { Json.encodeToString(it) },
): DriverSessionEntity = DriverSessionEntity(
    userId = context.driver.id,
    displayName = context.driver.displayName,
    login = context.driver.login,
    warehouseId = context.driver.warehouseId,
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
    previous: DriverSessionEntity?,
    context: DriverContextDto,
    now: Long,
    elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
    kpiPaletteJson: String? = context.kpiPalette?.let { Json.encodeToString(it) },
): DriverSessionEntity = DriverSessionEntity(
    userId = context.driver.id,
    displayName = context.driver.displayName,
    login = context.driver.login,
    warehouseId = context.driver.warehouseId,
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

internal fun DriverCategoryDto.normalizedAudienceModesKey(): String =
    audienceModes.asSequence().distinct().sorted().joinToString("\u001F")

internal fun DriverCategoryDto.normalizedGroupIdsKey(): String =
    groupIds.asSequence().distinct().sorted().joinToString("\u001F")
