package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.*;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.KpiPaletteDto;
import dev.buhanzaz.rwms.taskboard.domain.GroupOperationalStatus;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.push.WorkerPushOutbox;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds worker-scoped task views and applies replay-safe worker commands.
 *
 * <p>All reads and mutations are bounded by the authenticated worker and warehouse. Offline work
 * uses a short-lived lease, a client operation ID and the observed entry version so reconnecting a
 * device cannot silently replay a stale action against a changed task.
 */
@Service
public class WorkerTaskBoardService {
  private static final String AVAILABLE = "AVAILABLE";
  private static final String REQUIRED_JOIN = "REQUIRED_JOIN";
  private static final String OPTIONAL_JOIN = "OPTIONAL_JOIN";
  private static final String SECONDARY_PENDING = "SECONDARY_PENDING";
  private static final int MAX_LIMIT = 50;
  private static final String LEGACY_EVIDENCE_CONTENT_TYPE = "image/jpeg";
  private static final String BUNDLE_EVIDENCE_CONTENT_TYPE = "image/webp";
  private static final long LEGACY_EVIDENCE_MAX_BYTES = 15_728_640;
  private static final long BUNDLE_EVIDENCE_MAX_BYTES = 1_048_576;

  private final TaskBoardService taskBoard;
  private final WorkerFeedCountProjection feedCounts;
  private final WorkforceService workforce;
  private final RegistryService registry;
  private final WorkerTaskAccessService taskAccess;
  private final MaintenanceTaskExecutionPackageService executionPackages;
  private final JdbcTemplate jdbc;
  private final WorkerOfflineLeaseCodec leases;
  private final WorkerInvalidationHub invalidations;
  private final TaskBoardEntryOwnerProofService ownerProofs;
  private final KpiSettingsService kpiSettings;
  private final MobileTaskSurfacePolicy surfacePolicy;
  private final WorkerPushOutbox pushOutbox;

  public WorkerTaskBoardService(
      TaskBoardService taskBoard,
      WorkerFeedCountProjection feedCounts,
      WorkforceService workforce,
      RegistryService registry,
      WorkerTaskAccessService taskAccess,
      MaintenanceTaskExecutionPackageService executionPackages,
      JdbcTemplate jdbc,
      WorkerOfflineLeaseCodec leases,
      WorkerInvalidationHub invalidations,
      TaskBoardEntryOwnerProofService ownerProofs,
      KpiSettingsService kpiSettings,
      MobileTaskSurfacePolicy surfacePolicy,
      WorkerPushOutbox pushOutbox) {
    this.taskBoard = taskBoard;
    this.feedCounts = feedCounts;
    this.workforce = workforce;
    this.registry = registry;
    this.taskAccess = taskAccess;
    this.executionPackages = executionPackages;
    this.jdbc = jdbc;
    this.leases = leases;
    this.invalidations = invalidations;
    this.ownerProofs = ownerProofs;
    this.kpiSettings = kpiSettings;
    this.surfacePolicy = surfacePolicy;
    this.pushOutbox = pushOutbox;
  }

  /** Returns the WorkerApp-compatible access context. */
  public WorkerContext context(UUID workerId, UUID warehouseId) {
    return context(MobileTaskSurface.WORKER, workerId, warehouseId);
  }

  /** Returns the selected native surface's access context, revision and offline lease. */
  public WorkerContext context(
      MobileTaskSurface surface, UUID workerId, UUID warehouseId) {
    WorkerAccess access = access(surface, workerId, warehouseId);
    OffsetDateTime now = now();
    long revision = revision();
    WorkerDto worker = access.worker();
    return new WorkerContext(
        new WorkerIdentity(
            worker.id(), worker.warehouseId(), requireLogin(worker), worker.displayName()),
        access.groups().stream()
            .filter(group -> group.id().equals(worker.currentGroupId()))
            .findFirst()
            .map(
                group ->
                    new WorkerGroupSummary(
                        group.id(),
                        group.name(),
                        group.workerClass().id(),
                        group.workerClass().name()))
            .orElse(null),
        operationalAvailability(surface, access),
        access.groups().stream()
            .map(
                group ->
                    new WorkerGroupSummary(
                        group.id(),
                        group.name(),
                        group.workerClass().id(),
                        group.workerClass().name()))
            .toList(),
        access.qualifications().stream()
            .map(
                qualification ->
                    new WorkerQualificationSummary(
                        qualification.workerClass().id(),
                        qualification.workerClass().name()))
            .toList(),
        access.categories().stream().map(queue -> category(surface, queue, access)).toList(),
        workerKpiPalette(warehouseId),
        now,
        revision,
        leases.issue(workerId, warehouseId, revision, now));
  }

  /**
   * Returns one worker-authorized feed page for a fixed revision.
   *
   * <p>A cursor from another revision is rejected rather than serving a mixed snapshot.
   */
  public FeedPage feed(
      UUID workerId, UUID warehouseId, String encodedCursor, int requestedLimit) {
    return feed(
        MobileTaskSurface.WORKER,
        workerId,
        warehouseId,
        encodedCursor,
        requestedLimit);
  }

  /** Returns one authorized feed page for the selected native task surface. */
  public FeedPage feed(
      MobileTaskSurface surface,
      UUID workerId,
      UUID warehouseId,
      String encodedCursor,
      int requestedLimit) {
    int limit = Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
    WorkerAccess access = access(surface, workerId, warehouseId);
    long currentRevision = revision();
    Cursor cursor =
        encodedCursor == null
            ? new Cursor(currentRevision, 0, now().toInstant().toEpochMilli())
            : decodeCursor(encodedCursor);
    if (cursor.revision() != currentRevision) {
      throw new ConflictException("Лента изменилась во время постраничной загрузки");
    }

    Map<UUID, WorkerCategory> categories = new LinkedHashMap<>();
    access
        .categories()
        .forEach(queue -> categories.put(queue.id(), category(surface, queue, access)));
    TaskBoardSnapshot snapshot = taskBoard.workerSnapshot(surface, warehouseId, workerId);
    List<VisibleEntry> visible = new ArrayList<>();
    for (BoardColumnDto column : snapshot.columns()) {
      WorkerCategory workerCategory = categories.get(column.queueId());
      if (workerCategory == null) continue;
      for (BoardEntryDto entry : column.entries()) {
        WorkQueueDto queue =
            access.categories().stream()
                .filter(candidate -> candidate.id().equals(column.queueId()))
                .findFirst()
                .orElseThrow();
        if (!surfacePolicy.includesFeedEntry(surface, queue, entry)) continue;
        visible.add(new VisibleEntry(workerCategory, entry));
      }
    }
    int start = Math.min(cursor.offset(), visible.size());
    int end = Math.min(start + limit, visible.size());
    List<VisibleEntry> page = visible.subList(start, end);
    Map<UUID, UUID> taskIdsByEntry = new LinkedHashMap<>();
    page.forEach(item -> taskIdsByEntry.put(item.entry().id(), item.entry().taskId()));
    Map<UUID, WorkerFeedCountProjection.Counts> countsByEntry =
        feedCounts.load(taskIdsByEntry);
    Map<UUID, List<WorkerFeedEntry>> selected = new LinkedHashMap<>();
    for (VisibleEntry item : page) {
      WorkerFeedCountProjection.Counts counts = countsByEntry.get(item.entry().id());
      if (counts == null || counts.routeStepCount() < 1) {
        throw new IllegalStateException(
            "Маршрут задания не содержит текущий шаг " + item.entry().id());
      }
      selected.computeIfAbsent(item.category().queueId(), ignored -> new ArrayList<>())
          .add(feedEntry(item.entry(), item.category(), counts));
    }

    List<WorkerFeedCategory> pageCategories = new ArrayList<>();
    for (WorkerCategory workerCategory : categories.values()) {
      List<WorkerFeedEntry> entries = selected.get(workerCategory.queueId());
      if (start == 0 || entries != null) {
        pageCategories.add(
            new WorkerFeedCategory(
                workerCategory, entries == null ? List.of() : List.copyOf(entries)));
      }
    }
    String nextCursor =
        end < visible.size()
            ? encodeCursor(new Cursor(currentRevision, end, cursor.serverTimeMillis()))
            : null;
    OffsetDateTime serverTime =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(cursor.serverTimeMillis()), ZoneOffset.UTC);
    String etag = etag(currentRevision, cursor.offset(), limit);
    return new FeedPage(
        new WorkerFeed(
            currentRevision, serverTime, List.copyOf(pageCategories), nextCursor),
        etag);
  }

  /** Returns WorkerApp-compatible task detail. */
  public WorkerTaskDetail detail(UUID workerId, UUID warehouseId, UUID entryId) {
    return detail(MobileTaskSurface.WORKER, workerId, warehouseId, entryId);
  }

  /**
   * Returns task detail after enforcing the selected surface and worker audience.
   *
   * <p>A maintenance representative presents the content and remaining timer of its consecutive
   * same-queue execution package; other sources remain entry-scoped.
   */
  public WorkerTaskDetail detail(
      MobileTaskSurface surface, UUID workerId, UUID warehouseId, UUID entryId) {
    WorkerAccess access = access(surface, workerId, warehouseId);
    BoardEntryDto entry = taskBoard.workerEntry(surface, warehouseId, entryId, workerId);
    WorkQueueDto queue =
        access.categories().stream()
            .filter(candidate -> candidate.id().equals(entry.queueId()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Задание не найдено"));
    surfacePolicy.requireDetailVisible(surface, queue, entry, workerId);
    BoardTaskRegistrationDto task = registration(warehouseId, entry);
    int photoMinimum = surfacePolicy.resultPhotoMinimum(queue);
    List<WorkerRelatedStep> related =
        task == null
            ? List.of()
            : task.route().stream()
                .map(
                    step ->
                        new WorkerRelatedStep(
                            step.entryId(),
                            step.routeIndex(),
                            step.queueName(),
                            step.taskText(),
                            step.status().name()))
                .toList();
    WorkerTaskObject object =
        entry.unitNumber() == null || entry.unitNumber().isBlank()
            ? null
            : new WorkerTaskObject("CABIN", null, entry.unitNumber());
    List<RegisteredRouteStepDto> packageSteps = executionPackages.detailSteps(entry, task);
    Map<UUID, TaskWorkerContentDto> contentByEntry = new LinkedHashMap<>();
    if (packageSteps.isEmpty()) {
      contentByEntry.put(entry.id(), taskBoard.workerContent(warehouseId, entry.id()));
    } else {
      packageSteps.forEach(
          step ->
              contentByEntry.put(
                  step.entryId(), taskBoard.workerContent(warehouseId, step.entryId())));
    }

    Map<UUID, WorkerWork> workById = new LinkedHashMap<>();
    Map<UUID, WorkerMaterial> materialById = new LinkedHashMap<>();
    Map<UUID, WorkerVisibleComment> commentById = new LinkedHashMap<>();
    contentByEntry.values().forEach(
        content -> {
          content.works().forEach(
              work ->
                  workById.putIfAbsent(
                      work.id(),
                      new WorkerWork(
                          work.id(),
                          work.name(),
                          work.quantity(),
                          work.unit(),
                          work.durationMinutes(),
                          work.comment(),
                          work.sourceMediaIds())));
          content.materials().forEach(
              material ->
                  materialById.putIfAbsent(
                      material.id(),
                      new WorkerMaterial(
                          material.id(),
                          material.name(),
                          material.quantity(),
                          material.unit())));
          content.comments().forEach(
              comment ->
                  commentById.putIfAbsent(
                      comment.id(),
                      new WorkerVisibleComment(
                          comment.id(),
                          comment.text(),
                          comment.authorDisplayName(),
                          comment.createdAt())));
        });

    List<UUID> mediaEntryOrder = new ArrayList<>();
    mediaEntryOrder.add(entry.id());
    contentByEntry.keySet().stream()
        .filter(candidate -> !candidate.equals(entry.id()))
        .forEach(mediaEntryOrder::add);
    Map<UUID, WorkerMediaReference> sourceMediaById = new LinkedHashMap<>();
    for (UUID contentEntryId : mediaEntryOrder) {
      TaskWorkerContentDto content = contentByEntry.get(contentEntryId);
      if (content == null) continue;
      content.sourceMedia().forEach(
          reference ->
              sourceMediaById.putIfAbsent(
                  reference.mediaId(),
                  new WorkerMediaReference(
                      reference.mediaId(),
                      reference.generation(),
                      "SOURCE",
                      reference.contentType() == null
                          ? "application/octet-stream"
                          : reference.contentType(),
                      mediaReadPath(
                          reference.mediaId(),
                          contentEntryId,
                          warehouseId,
                          reference.generation(),
                          null),
                      mediaReadPath(
                          reference.mediaId(),
                          contentEntryId,
                          warehouseId,
                          reference.generation(),
                          "SMALL"),
                      reference.capturedAt(),
                      reference.recordedAt())));
    }
    List<WorkerWork> works = List.copyOf(workById.values());
    List<WorkerMaterial> materials = List.copyOf(materialById.values());
    List<WorkerVisibleComment> comments = List.copyOf(commentById.values());
    List<WorkerMediaReference> sourceMedia = List.copyOf(sourceMediaById.values());
    Integer packageDurationMinutes =
        executionPackages.plannedDurationMinutes(entry, packageSteps);
    TaskTimerSnapshot packageTimer =
        executionPackages.timerSnapshot(entry, packageSteps, packageDurationMinutes);
    List<TaskEvidence> evidence = evidence(entry.id());
    List<WorkerAssignmentSnapshot> assignmentSnapshots = assignments(entry);
    long readyEvidenceCount =
        evidence.stream().filter(item -> "READY".equals(item.state())).count();
    WorkerCategory workerCategory = category(surface, queue, access);
    List<AudienceSelector> audienceSelectors = new ArrayList<>();
    surfaceBindings(surface, queue, access).stream()
        .map(
            binding ->
                new AudienceSelector(
                    "WORKER_CLASS",
                    binding.workerClass().id(),
                    binding.primary()
                        ? AVAILABLE
                        : binding.participationPolicy() == ParticipationPolicy.REQUIRED
                            ? REQUIRED_JOIN
                            : OPTIONAL_JOIN,
                    binding.stopTaskOnTake(),
                    binding.notifyOnPrimaryTake()))
        .forEach(audienceSelectors::add);
    return new WorkerTaskDetail(
        entry.id(),
        entry.version(),
        entry.taskId(),
        entry.source(),
        entry.routeIndex(),
        entry.title(),
        task == null ? null : task.description(),
        object,
        entry.taskText(),
        entry.scheduledDate(),
        task == null ? null : task.deadlineAt(),
        entry.priority(),
        entry.queuePosition(),
        entry.status().name(),
        availabilityMode(workerCategory, entry.status().name()),
        packageDurationMinutes,
        entry.activeStartedAt(),
        entry.activeWorkSeconds(),
        packageTimer,
        List.copyOf(audienceSelectors),
        assignmentSnapshots,
        works,
        materials,
        comments,
        sourceMedia,
        evidence,
        related,
        photoMinimum,
        entry.status().name().equals("IN_PROGRESS")
            && readyEvidenceCount >= photoMinimum
            && surfacePolicy.isActiveParticipant(
                surface, queue.purpose(), workerId, assignmentSnapshots));
  }

  /**
   * Reserves WorkerApp-compatible JPEG evidence or one logical WebP client bundle under the
   * surface-neutral limits enforced by the native reservation path.
   */
  @Transactional
  public TaskEvidence reserveEvidence(
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      String idempotencyKey,
      EvidenceReservationRequest request) {
    return reserveEvidence(
        MobileTaskSurface.WORKER,
        workerId,
        warehouseId,
        entryId,
        idempotencyKey,
        request);
  }

  /**
   * Reserves an idempotent media-evidence upload for an authorized native surface.
   *
   * <p>Legacy JPEG declarations are limited to 15 MiB. Logical WebP bundle declarations are
   * limited to 1 MiB and carry the deterministic bundle-manifest checksum in {@code sha256}.
   */
  @Transactional
  public TaskEvidence reserveEvidence(
      MobileTaskSurface surface,
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      String idempotencyKey,
      EvidenceReservationRequest request) {
    requireIdempotencyKey(idempotencyKey, request.operationId());
    requireSupportedEvidenceDeclaration(request);
    WorkerTaskDetail current = detail(surface, workerId, warehouseId, entryId);
    if (request.routeIndex() != current.routeIndex()) {
      throw new ConflictException("Фотография относится к другому шагу задания");
    }
    leases.requireValid(
        request.offlineLeaseId(), workerId, warehouseId, request.capturedAt(), now());

    jdbc.queryForObject(
        "select id from queue_entry where id=? for update", UUID.class, entryId);
    List<EvidenceRow> existing =
        jdbc.query(
            """
            select *
              from worker_task_evidence
             where evidence_id=? or operation_id=?
             order by evidence_id
            """,
            this::evidenceRow,
            request.evidenceId(),
            request.operationId());
    if (!existing.isEmpty()) {
      if (existing.size() != 1) {
        throw new ConflictException("Идентификаторы фотографии уже использованы");
      }
      requireSameReservation(
          existing.getFirst(), workerId, warehouseId, entryId, request);
      ownerProofs.publish(warehouseId, entryId, true);
      return existing.getFirst().dto();
    }
    if (!"IN_PROGRESS".equals(current.status())) {
      throw new ConflictException(
          "Добавить новую фотографию можно только к заданию в работе");
    }

    BoardEntryDto entry = taskBoard.workerEntry(surface, warehouseId, entryId, workerId);
    surfacePolicy.requireActiveParticipant(
        surface, entry.queuePurpose(), workerId, current.assignments());
    UUID workerGroupId =
        current.assignments().stream()
            .filter(assignment -> workerId.equals(assignment.workerId()))
            .filter(
                assignment ->
                    "ACTIVE".equals(assignment.status())
                        || "PAUSED".equals(assignment.status()))
            .map(WorkerAssignmentSnapshot::workerGroupId)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    OffsetDateTime recordedAt = databaseNow();
    String sourceType = entry.source() == null ? null : entry.source().type().name();
    UUID sourceId = entry.source() == null ? null : entry.source().sourceId();
    try {
      jdbc.update(
          """
          insert into worker_task_evidence(
              evidence_id,version,operation_id,entry_id,task_id,route_index,
              warehouse_id,worker_id,worker_group_id,captured_at,recorded_at,state,
              media_id,media_generation,review_reason,content_type,size_bytes,sha256,
              source_type,source_id,updated_at)
          values (?,0,?,?,?,?,?,?,?,?,?,'RESERVED',null,null,null,?,?,?,?,?,?)
          """,
          request.evidenceId(),
          request.operationId(),
          entryId,
          entry.taskId(),
          entry.routeIndex(),
          warehouseId,
          workerId,
          workerGroupId,
          request.capturedAt().truncatedTo(ChronoUnit.MICROS),
          recordedAt,
          request.contentType().toLowerCase(Locale.ROOT),
          request.sizeBytes(),
          request.sha256(),
          sourceType,
          sourceId,
          recordedAt);
    } catch (DuplicateKeyException exception) {
      EvidenceRow replay =
          findEvidence(request.evidenceId(), request.operationId())
              .orElseThrow(() -> exception);
      requireSameReservation(replay, workerId, warehouseId, entryId, request);
      ownerProofs.publish(warehouseId, entryId, true);
      return replay.dto();
    }
    ownerProofs.publish(warehouseId, entryId, true);
    TaskEvidence reserved =
        findEvidence(request.evidenceId(), request.operationId())
            .orElseThrow(() -> new IllegalStateException("Резервирование фотографии не сохранено"))
            .dto();
    invalidations.actionApplied(surface, workerId, entryId, revision());
    return reserved;
  }

  /** Registers one WorkerApp installation for backward-compatible callers. */
  @Transactional
  public DeviceRegistrationResult registerDevice(
      UUID workerId,
      UUID warehouseId,
      String installationId,
      WorkerDeviceRegistrationRequest request) {
    return registerDevice(
        MobileTaskSurface.WORKER, workerId, warehouseId, installationId, request);
  }

  /** Registers one authenticated native-app installation on its server-fixed surface. */
  @Transactional
  public DeviceRegistrationResult registerDevice(
      MobileTaskSurface surface,
      UUID workerId,
      UUID warehouseId,
      String installationId,
      WorkerDeviceRegistrationRequest request) {
    access(surface, workerId, warehouseId);
    String normalizedInstallationId = requireInstallationId(installationId);
    if (!"FCM".equals(request.provider())) {
      throw new IllegalArgumentException("Поддерживается только provider FCM");
    }
    List<DeviceOwner> existing =
        jdbc.query(
            """
            select worker_id,warehouse_id,app_surface
              from worker_device_registration
             where installation_id=?
             for update
            """,
            (result, row) ->
                new DeviceOwner(
                    result.getObject("worker_id", UUID.class),
                    result.getObject("warehouse_id", UUID.class),
                    result.getString("app_surface")),
            normalizedInstallationId);
    if (!existing.isEmpty()
        && (!existing.getFirst().workerId().equals(workerId)
            || !existing.getFirst().warehouseId().equals(warehouseId))) {
      throw new ConflictException("Установка приложения принадлежит другому рабочему");
    }
    if (!existing.isEmpty()
        && !existing.getFirst().appSurface().equals(surface.name())) {
      throw new ConflictException("Установка зарегистрирована другим приложением");
    }
    boolean created = existing.isEmpty();
    String targetKind = normalizedTargetKind(request.targetKind());
    try {
      jdbc.update(
          """
          insert into worker_device_registration(
              installation_id,worker_id,warehouse_id,provider,provider_token,target_kind,
              app_surface,status,
              app_version,sdk_int,locale,registered_at,updated_at)
          values (?,?,?,?,?,?,?,'ACTIVE',?,?,?,clock_timestamp(),clock_timestamp())
          on conflict (installation_id) do update
             set provider=excluded.provider,
                 provider_token=excluded.provider_token,
                 target_kind=excluded.target_kind,
                 status='ACTIVE',
                 app_version=excluded.app_version,
                 sdk_int=excluded.sdk_int,
                 locale=excluded.locale,
                 updated_at=clock_timestamp()
          """,
          normalizedInstallationId,
          workerId,
          warehouseId,
          request.provider(),
          request.token().trim(),
          targetKind,
          surface.name(),
          request.appVersion(),
          request.sdkInt(),
          request.locale());
    } catch (DuplicateKeyException exception) {
      throw new ConflictException("FCM-адресат уже зарегистрирован другой установкой");
    }
    WorkerDeviceRegistration registration =
        jdbc.queryForObject(
            """
            select installation_id,provider,status,registered_at,updated_at
              from worker_device_registration
             where installation_id=? and worker_id=? and warehouse_id=?
            """,
            (result, row) ->
                new WorkerDeviceRegistration(
                    result.getString("installation_id"),
                    result.getString("provider"),
                    result.getString("status"),
                    result.getObject("registered_at", OffsetDateTime.class),
                    result.getObject("updated_at", OffsetDateTime.class)),
            normalizedInstallationId,
            workerId,
            warehouseId);
    if (registration == null) {
      throw new IllegalStateException("Регистрация устройства не сохранена");
    }
    return new DeviceRegistrationResult(registration, created);
  }

  /** Removes one WorkerApp installation for backward-compatible callers. */
  @Transactional
  public void unregisterDevice(
      UUID workerId, UUID warehouseId, String installationId) {
    unregisterDevice(MobileTaskSurface.WORKER, workerId, warehouseId, installationId);
  }

  /** Removes an installation only from its authenticated worker, warehouse and app surface. */
  @Transactional
  public void unregisterDevice(
      MobileTaskSurface surface,
      UUID workerId,
      UUID warehouseId,
      String installationId) {
    access(surface, workerId, warehouseId);
    int deleted =
        jdbc.update(
            """
            delete from worker_device_registration
             where installation_id=? and worker_id=? and warehouse_id=?
               and app_surface=?
            """,
            requireInstallationId(installationId),
            workerId,
            warehouseId,
            surface.name());
    if (deleted != 1) {
      throw new NotFoundException("Регистрация устройства не найдена");
    }
  }

  /**
   * Applies a worker task action exactly once for its idempotency key and offline lease.
   *
   * <p>A replay returns the already-applied outcome; a divergent replay or stale observed version
   * is a conflict rather than an implicit overwrite.
   */
  @Transactional
  public WorkerActionAppliedResult applyAction(
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      String idempotencyKey,
      WorkerActionRequest request) {
    return applyAction(
        MobileTaskSurface.WORKER,
        workerId,
        warehouseId,
        entryId,
        idempotencyKey,
        request);
  }

  /** Applies a replay-safe command after enforcing the selected native surface capability. */
  @Transactional
  public WorkerActionAppliedResult applyAction(
      MobileTaskSurface surface,
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      String idempotencyKey,
      WorkerActionRequest request) {
    requireIdempotencyKey(idempotencyKey, request.operationId());
    WorkerTaskDetail current = detail(surface, workerId, warehouseId, entryId);
    boolean primaryTakeTriggersNotification =
        request.action() == WorkerAction.TAKE && "WAITING".equals(current.status());
    ActionReplay replay = replay(request.operationId());
    if (replay != null) {
      if (!replay.entryId().equals(entryId)
          || !replay.eventType().equals(eventType(request.action()))) {
        throw new ConflictException("operationId уже использован другой командой");
      }
      return new WorkerActionAppliedResult("REPLAYED", current.version(), current);
    }

    WorkerAccess access = access(surface, workerId, warehouseId);
    UUID currentGroupId = access.worker().currentGroupId();
    BoardEntryDto commandEntry =
        taskBoard.workerEntry(surface, warehouseId, entryId, workerId);
    boolean individualLogistics =
        commandEntry.queuePurpose() == QueuePurpose.LOGISTICS_DRIVER;
    surfacePolicy.requireActionAllowed(surface, commandEntry.queuePurpose(), request.action());
    if (request.action() != WorkerAction.TAKE && request.action() != WorkerAction.JOIN) {
      surfacePolicy.requireActiveParticipant(
          surface, commandEntry.queuePurpose(), workerId, current.assignments());
    }
    if (request.action() == WorkerAction.TAKE) {
      if (!individualLogistics
          && (currentGroupId == null
              || access.worker().operationalAvailability()
                  != GroupOperationalStatus.AVAILABLE)) {
        throw new ConflictException("Рабочему не назначена доступная текущая группа");
      }
      if (!individualLogistics
          && request.workerGroupId() != null
          && !currentGroupId.equals(request.workerGroupId())) {
        throw new ConflictException(
            "Задачу можно взять только текущей группой рабочего");
      }
      if (individualLogistics && request.workerGroupId() != null) {
        throw new ConflictException("Водитель берёт логистическое задание без бригады");
      }
    }
    if (request.action() == WorkerAction.JOIN && !"IN_PROGRESS".equals(current.status())) {
      throw new ConflictException("Присоединиться можно только к заданию в работе");
    }
    if (request.action() == WorkerAction.JOIN && individualLogistics) {
      if (currentGroupId == null) {
        throw new ConflictException("Стропальщику не назначена текущая группа");
      }
      if (request.workerGroupId() != null
          && !currentGroupId.equals(request.workerGroupId())) {
        throw new ConflictException(
            "К логистическому заданию можно присоединиться только текущей группой");
      }
    }
    leases.requireValid(
        request.offlineLeaseId(), workerId, warehouseId, request.occurredAt(), now());
    String previousCorrelation = MDC.get(CorrelationIdFilter.MDC_KEY);
    MDC.put(CorrelationIdFilter.MDC_KEY, request.operationId().toString());
    try {
      switch (request.action()) {
        case TAKE, JOIN ->
            taskBoard.take(
                warehouseId,
                entryId,
                new TakeEntryRequest(
                    request.expectedVersion(),
                    request.action() == WorkerAction.TAKE && !individualLogistics
                        ? currentGroupId
                        : request.action() == WorkerAction.JOIN && individualLogistics
                            ? currentGroupId
                            : null,
                    workerId),
                workerId);
        case PAUSE ->
            taskBoard.pause(
                warehouseId,
                entryId,
                new PauseEntryRequest(request.expectedVersion(), null),
                workerId);
        case RESUME ->
            taskBoard.resume(
                warehouseId,
                entryId,
                new VersionCommand(request.expectedVersion()),
                workerId);
        case COMPLETE -> {
          long readyEvidence =
              current.evidence().stream()
                  .filter(item -> "READY".equals(item.state()))
                  .count();
          if (current.resultPhotoMinCount() > readyEvidence) {
            throw new ConflictException("Для завершения не хватает готовых фотографий");
          }
          if (commandEntry.queuePurpose() == QueuePurpose.LOGISTICS_DRIVER) {
            selectCompletionEvidence(entryId, request.evidenceId());
          }
          taskBoard.complete(
              warehouseId,
              entryId,
              new VersionCommand(request.expectedVersion()),
              workerId);
        }
      }
    } finally {
      if (previousCorrelation == null) {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
      } else {
        MDC.put(CorrelationIdFilter.MDC_KEY, previousCorrelation);
      }
    }
    WorkerTaskDetail changed = detail(surface, workerId, warehouseId, entryId);
    long changedRevision = revision();
    Set<UUID> notifiedWorkerIds =
        primaryTakeTriggersNotification
            ? notifiedWorkerIds(warehouseId, commandEntry.queueId(), workerId)
            : Set.of();
    pushOutbox.enqueueJoinAvailable(
        notifiedWorkerIds, warehouseId, entryId, changedRevision);
    invalidations.actionApplied(
        surface, workerId, entryId, changedRevision, notifiedWorkerIds);
    return new WorkerActionAppliedResult("APPLIED", changed.version(), changed);
  }

  /** Returns the current worker-feed revision used to fence pagination and invalidations. */
  public long revision() {
    Long value =
        jdbc.queryForObject(
            "select coalesce(sum(current_version + 1), 0)::bigint from event_stream_head",
            Long.class);
    return value == null ? 0 : value;
  }

  private WorkerAccess access(
      MobileTaskSurface surface, UUID workerId, UUID warehouseId) {
    var resolved = taskAccess.require(surface, workerId, warehouseId);
    return new WorkerAccess(
        resolved.worker(),
        resolved.groups(),
        resolved.qualifications(),
        resolved.categories());
  }

  private static String operationalAvailability(
      MobileTaskSurface surface, WorkerAccess access) {
    if (surface == MobileTaskSurface.DRIVER
        && access.worker().currentGroupId() == null
        && access.categories().stream()
            .anyMatch(
                queue ->
                    queue.purpose() == QueuePurpose.LOGISTICS_DRIVER
                        && queue.bindings().stream()
                            .anyMatch(
                                binding ->
                                    binding.primary()
                                        && access.qualifications().stream()
                                            .anyMatch(
                                                qualification ->
                                                    qualification.workerClass().id()
                                                        .equals(
                                                            binding.workerClass().id()))))) {
      return GroupOperationalStatus.AVAILABLE.name();
    }
    return access.worker().operationalAvailability().name();
  }

  private WorkerCategory category(
      MobileTaskSurface surface, WorkQueueDto queue, WorkerAccess access) {
    Set<UUID> workerClassIds = new LinkedHashSet<>();
    access
        .qualifications()
        .forEach(value -> workerClassIds.add(value.workerClass().id()));
    access.groups().forEach(value -> workerClassIds.add(value.workerClass().id()));
    Set<String> modes = new LinkedHashSet<>();
    List<QueueBindingDto> visibleBindings =
        surfacePolicy.bindings(surface, queue, workerClassIds);
    visibleBindings.stream()
        .forEach(
            binding -> {
              if (binding.primary()) {
                modes.add(AVAILABLE);
              } else {
                modes.add(SECONDARY_PENDING);
                modes.add(
                    binding.participationPolicy() == ParticipationPolicy.REQUIRED
                        ? REQUIRED_JOIN
                        : OPTIONAL_JOIN);
              }
            });
    Set<UUID> boundWorkerClassIds =
        visibleBindings.stream()
            .map(binding -> binding.workerClass().id())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<UUID> groupIds =
        access.groups().stream()
            .filter(group -> boundWorkerClassIds.contains(group.workerClass().id()))
            .map(WorkerGroupDto::id)
            .toList();
    return new WorkerCategory(
        queue.id(),
        queue.name(),
        queue.type().name(),
        queue.purpose().name(),
        queue.sortOrder(),
        List.copyOf(modes),
        groupIds,
        surfacePolicy.resultPhotoMinimum(queue));
  }

  private List<QueueBindingDto> surfaceBindings(
      MobileTaskSurface surface, WorkQueueDto queue, WorkerAccess access) {
    Set<UUID> workerClassIds = new LinkedHashSet<>();
    access
        .qualifications()
        .forEach(value -> workerClassIds.add(value.workerClass().id()));
    access.groups().forEach(value -> workerClassIds.add(value.workerClass().id()));
    return surfacePolicy.bindings(surface, queue, workerClassIds);
  }

  private WorkerKpiPalette workerKpiPalette(UUID warehouseId) {
    KpiPaletteDto palette = kpiSettings.get(warehouseId).palette();
    if (palette == null) return null;
    return new WorkerKpiPalette(
        palette.ranges().stream()
            .map(
                range ->
                    new WorkerKpiPaletteRange(
                        range.fromPercent(), range.toPercent(), range.color()))
            .toList(),
        palette.overdueColor());
  }

  private WorkerFeedEntry feedEntry(
      BoardEntryDto entry,
      WorkerCategory category,
      WorkerFeedCountProjection.Counts counts) {
    return new WorkerFeedEntry(
        entry.id(),
        entry.version(),
        entry.taskId(),
        entry.routeIndex(),
        counts.routeStepCount(),
        entry.title(),
        entry.unitNumber(),
        entry.taskText(),
        entry.scheduledDate(),
        null,
        entry.priority(),
        entry.queuePosition(),
        entry.entryType().name(),
        entry.pinned(),
        entry.status().name(),
        availabilityMode(category, entry.status().name()),
        entry.driverAudience(),
        entry.plannedDurationMinutes(),
        entry.activeStartedAt(),
        entry.activeWorkSeconds(),
        entry.timerSnapshot(),
        assignments(entry),
        counts.readyEvidenceCount(),
        category.resultPhotoMinCount());
  }

  private List<WorkerAssignmentSnapshot> assignments(BoardEntryDto entry) {
    return entry.assignments().stream()
        .map(
            assignment ->
                new WorkerAssignmentSnapshot(
                    assignment.id(),
                    assignment.workerId(),
                    assignment.workerName(),
                    assignment.workerGroupId(),
                    assignment.workerGroupName(),
                    assignment.status().name(),
                    assignment.assignedAt(),
                    assignment.startedAt(),
                    assignment.pausedAt(),
                    assignment.finishedAt()))
        .toList();
  }

  private BoardTaskRegistrationDto registration(UUID warehouseId, BoardEntryDto entry) {
    if (entry.externalTaskId() == null) return null;
    try {
      return taskBoard.registration(warehouseId, entry.externalTaskId());
    } catch (NotFoundException ignored) {
      return null;
    }
  }

  private static String availabilityMode(WorkerCategory category, String entryStatus) {
    if ("IN_PROGRESS".equals(entryStatus)
        && category.audienceModes().contains(REQUIRED_JOIN)) {
      return REQUIRED_JOIN;
    }
    if ("IN_PROGRESS".equals(entryStatus)
        && category.audienceModes().contains(OPTIONAL_JOIN)) {
      return OPTIONAL_JOIN;
    }
    if ("WAITING".equals(entryStatus)
        && !category.audienceModes().contains(AVAILABLE)
        && category.audienceModes().contains(SECONDARY_PENDING)) {
      return SECONDARY_PENDING;
    }
    return AVAILABLE;
  }

  private Set<UUID> notifiedWorkerIds(
      UUID warehouseId, UUID queueId, UUID primaryWorkerId) {
    WorkQueueDto queue =
        registry.listQueues(warehouseId).stream()
            .filter(candidate -> candidate.id().equals(queueId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Очередь не найдена"));
    Set<UUID> notifiedClassIds =
        queue.bindings().stream()
            .filter(binding -> !binding.primary())
            .filter(
                binding ->
                    queue.purpose() == QueuePurpose.LOGISTICS_DRIVER
                        || binding.notifyOnPrimaryTake())
            .map(binding -> binding.workerClass().id())
            .collect(java.util.stream.Collectors.toSet());
    if (notifiedClassIds.isEmpty()) return Set.of();
    List<WorkerDto> activeWorkers =
        workforce.listWorkers(warehouseId).stream()
            .filter(WorkerDto::active)
            .filter(worker -> worker.currentGroupId() != null)
            .filter(
                worker ->
                    worker.operationalAvailability() == GroupOperationalStatus.AVAILABLE)
            .toList();
    Set<UUID> activeWorkerIds =
        activeWorkers.stream()
            .map(WorkerDto::id)
            .collect(java.util.stream.Collectors.toSet());
    Set<UUID> result =
        activeWorkers.stream()
            .filter(worker -> !worker.id().equals(primaryWorkerId))
            .filter(
                worker ->
                    worker.qualifications().stream()
                        .anyMatch(
                            qualification ->
                                qualification.active()
                                    && notifiedClassIds.contains(
                                        qualification.workerClass().id())))
            .map(WorkerDto::id)
            .collect(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    workforce.listGroups(warehouseId).stream()
        .filter(WorkerGroupDto::active)
        .filter(group -> group.operationalStatus() == GroupOperationalStatus.AVAILABLE)
        .filter(group -> notifiedClassIds.contains(group.workerClass().id()))
        .flatMap(group -> group.members().stream())
        .filter(GroupMemberDto::active)
        .map(GroupMemberDto::workerId)
        .filter(activeWorkerIds::contains)
        .filter(workerId -> !workerId.equals(primaryWorkerId))
        .forEach(result::add);
    return Set.copyOf(result);
  }

  private void selectCompletionEvidence(UUID entryId, UUID evidenceId) {
    if (evidenceId == null) {
      throw new ConflictException(
          "Для завершения логистического задания выберите фотографию результата");
    }
    Integer ready =
        jdbc.queryForObject(
            """
            select count(*)::integer
              from worker_task_evidence
             where entry_id=? and evidence_id=? and state='READY' and media_id is not null
            """,
            Integer.class,
            entryId,
            evidenceId);
    if (ready == null || ready != 1) {
      throw new ConflictException("Выбранная фотография результата ещё не готова");
    }
    jdbc.update(
        "update worker_task_evidence set selected_for_completion=false where entry_id=?",
        entryId);
    jdbc.update(
        """
        update worker_task_evidence
           set selected_for_completion=true, updated_at=clock_timestamp()
         where entry_id=? and evidence_id=?
        """,
        entryId,
        evidenceId);
  }

  private List<TaskEvidence> evidence(UUID entryId) {
    return jdbc.query(
        """
        select *
          from worker_task_evidence
         where entry_id=?
         order by recorded_at,evidence_id
        """,
        (result, row) -> evidenceRow(result, row).dto(),
        entryId);
  }

  private Optional<EvidenceRow> findEvidence(UUID evidenceId, UUID operationId) {
    List<EvidenceRow> rows =
        jdbc.query(
            """
            select *
              from worker_task_evidence
             where evidence_id=? or operation_id=?
             order by evidence_id
            """,
            this::evidenceRow,
            evidenceId,
            operationId);
    return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
  }

  private EvidenceRow evidenceRow(ResultSet result, int row) throws SQLException {
    Long mediaGeneration =
        result.getObject("media_generation") == null
            ? null
            : result.getLong("media_generation");
    UUID mediaId = result.getObject("media_id", UUID.class);
    UUID entryId = result.getObject("entry_id", UUID.class);
    UUID warehouseId = result.getObject("warehouse_id", UUID.class);
    String contentType = result.getString("content_type");
    String readPath =
        mediaId == null || mediaGeneration == null
            ? null
            : mediaReadPath(mediaId, entryId, warehouseId, mediaGeneration, null);
    String thumbnailPath =
        mediaId == null || mediaGeneration == null
            ? null
            : mediaReadPath(mediaId, entryId, warehouseId, mediaGeneration, "SMALL");
    UUID workerGroupId = result.getObject("worker_group_id", UUID.class);
    TaskEvidence dto =
        new TaskEvidence(
            result.getObject("evidence_id", UUID.class),
            result.getLong("version"),
            entryId,
            result.getInt("route_index"),
            result.getObject("worker_id", UUID.class),
            workerGroupId,
            result.getObject("captured_at", OffsetDateTime.class),
            result.getObject("recorded_at", OffsetDateTime.class),
            result.getString("state"),
            mediaId,
            mediaGeneration,
            result.getString("review_reason"),
            contentType,
            readPath,
            thumbnailPath);
    return new EvidenceRow(
        dto,
        result.getObject("operation_id", UUID.class),
        warehouseId,
        contentType,
        result.getLong("size_bytes"),
        result.getString("sha256"));
  }

  private void requireSameReservation(
      EvidenceRow existing,
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      EvidenceReservationRequest request) {
    TaskEvidence evidence = existing.dto();
    boolean same =
        evidence.evidenceId().equals(request.evidenceId())
            && existing.operationId().equals(request.operationId())
            && evidence.entryId().equals(entryId)
            && evidence.workerId().equals(workerId)
            && existing.warehouseId().equals(warehouseId)
            && evidence.routeIndex() == request.routeIndex()
            && java.time.Duration
                    .between(evidence.capturedAt().toInstant(), request.capturedAt().toInstant())
                    .abs()
                    .compareTo(java.time.Duration.ofNanos(1_000))
                <= 0
            && existing.contentType().equalsIgnoreCase(request.contentType())
            && existing.sizeBytes() == request.sizeBytes()
            && existing.sha256().equals(request.sha256());
    if (!same) {
      throw new ConflictException(
          "operationId или evidenceId уже использован другой фотографией");
    }
  }

  /** Enforces the media declaration limits before any evidence reservation is persisted. */
  private void requireSupportedEvidenceDeclaration(EvidenceReservationRequest request) {
    long maximumBytes;
    if (LEGACY_EVIDENCE_CONTENT_TYPE.equalsIgnoreCase(request.contentType())) {
      maximumBytes = LEGACY_EVIDENCE_MAX_BYTES;
    } else if (BUNDLE_EVIDENCE_CONTENT_TYPE.equalsIgnoreCase(request.contentType())) {
      maximumBytes = BUNDLE_EVIDENCE_MAX_BYTES;
    } else {
      throw new IllegalArgumentException(
          "Для результата поддерживаются только image/jpeg и image/webp");
    }
    if (request.sizeBytes() < 1 || request.sizeBytes() > maximumBytes) {
      throw new IllegalArgumentException(
          "Размер "
              + request.contentType().toLowerCase(Locale.ROOT)
              + " должен быть от 1 до "
              + maximumBytes
              + " байт");
    }
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime value =
        jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    return value == null ? now() : value;
  }

  private String mediaReadPath(
      UUID mediaId, UUID entryId, UUID warehouseId, long generation, String variant) {
    String suffix =
        variant == null
            ? "/original"
            : "/variants/" + variant + "/content";
    return "/api/media/v1/assets/"
        + mediaId
        + suffix
        + "?ownerType=TASK_BOARD_ENTRY&ownerId="
        + entryId
        + "&warehouseId="
        + warehouseId
        + "&context=WORK_RESULT&generation="
        + generation;
  }

  private ActionReplay replay(UUID operationId) {
    List<ActionReplay> events =
        jdbc.query(
            """
            select aggregate_id,event_type
              from domain_event
             where correlation_id=?
               and aggregate_type='QUEUE_ENTRY'
             order by recorded_at,event_id
            """,
            (result, row) ->
                new ActionReplay(
                    UUID.fromString(result.getString("aggregate_id")),
                    result.getString("event_type")),
            operationId);
    return events.isEmpty() ? null : events.get(events.size() - 1);
  }

  private String eventType(WorkerAction action) {
    return switch (action) {
      case TAKE, JOIN -> TaskBoardEventTypes.QUEUE_ENTRY_TAKEN;
      case PAUSE -> TaskBoardEventTypes.QUEUE_ENTRY_PAUSED;
      case RESUME -> TaskBoardEventTypes.QUEUE_ENTRY_RESUMED;
      case COMPLETE -> TaskBoardEventTypes.QUEUE_ENTRY_COMPLETED;
    };
  }

  private void requireIdempotencyKey(String value, UUID operationId) {
    try {
      if (!UUID.fromString(value).equals(operationId)) {
        throw new IllegalArgumentException("Idempotency-Key должен совпадать с operationId");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Idempotency-Key должен быть UUID и совпадать с operationId", exception);
    }
  }

  private String requireInstallationId(String value) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("Некорректный installationId");
    }
    return value.trim();
  }

  private String normalizedTargetKind(String value) {
    String normalized = value == null || value.isBlank() ? "TOKEN" : value.trim();
    if (!Set.of("TOKEN", "FID").contains(normalized)) {
      throw new IllegalArgumentException("targetKind должен быть TOKEN или FID");
    }
    return normalized;
  }

  private String requireLogin(WorkerDto worker) {
    if (worker.appLogin() == null || worker.appLogin().isBlank()) {
      throw new ConflictException("У рабочего не настроен логин");
    }
    return worker.appLogin();
  }

  private String etag(long revision, int offset, int limit) {
    return "W/\"worker-" + revision + "-" + offset + "-" + limit + "\"";
  }

  private String encodeCursor(Cursor cursor) {
    String value =
        cursor.revision() + ":" + cursor.offset() + ":" + cursor.serverTimeMillis();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.US_ASCII));
  }

  private Cursor decodeCursor(String value) {
    try {
      String decoded =
          new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
      String[] fields = decoded.split(":", -1);
      if (fields.length != 3) throw new IllegalArgumentException();
      long revision = Long.parseLong(fields[0]);
      int offset = Integer.parseInt(fields[1]);
      long serverTimeMillis = Long.parseLong(fields[2]);
      if (revision < 0 || offset < 0 || serverTimeMillis < 0) {
        throw new IllegalArgumentException();
      }
      return new Cursor(revision, offset, serverTimeMillis);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Некорректный cursor ленты", exception);
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public record FeedPage(WorkerFeed feed, String etag) {}

  public record DeviceRegistrationResult(
      WorkerDeviceRegistration registration, boolean created) {}

  private record WorkerAccess(
      WorkerDto worker,
      List<WorkerGroupDto> groups,
      List<QualificationDto> qualifications,
      List<WorkQueueDto> categories) {}

  private record Cursor(long revision, int offset, long serverTimeMillis) {}

  /** One authorized entry retained until feed pagination has selected its page. */
  private record VisibleEntry(WorkerCategory category, BoardEntryDto entry) {}

  private record ActionReplay(UUID entryId, String eventType) {}

  private record EvidenceRow(
      TaskEvidence dto,
      UUID operationId,
      UUID warehouseId,
      String contentType,
      long sizeBytes,
      String sha256) {}

  /** Persisted owner and native surface fence for one registered installation. */
  private record DeviceOwner(UUID workerId, UUID warehouseId, String appSurface) {}
}
