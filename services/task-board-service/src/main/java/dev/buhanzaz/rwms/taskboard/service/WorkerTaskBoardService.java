package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.*;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorEvidenceReservationRequest;
import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.KpiPaletteDto;
import dev.buhanzaz.rwms.taskboard.domain.GroupOperationalStatus;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.eventing.WorkerFeedRevisionStore;
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
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Builds worker-scoped task views and applies replay-safe worker commands.
 *
 * <p>All reads and mutations are bounded by the authenticated worker and JWT home warehouse. An
 * exact DriverApp assignment may execute at another physical warehouse, which is always resolved
 * from the server-owned entry rather than client input. Offline work uses a short-lived home
 * warehouse lease, a client operation ID and the observed entry version so reconnecting a device
 * cannot silently replay a stale action against a changed task.
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
  private final KpiPaletteService kpiPalettes;
  private final MobileTaskSurfacePolicy surfacePolicy;
  private final WorkerPushOutbox pushOutbox;
  private final WorkerFeedRevisionStore feedRevisions;
  private final WorkerActionReceiptStore actionReceipts;
  private final TaskBoardCompletionEvidenceService completionEvidence;

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
      KpiPaletteService kpiPalettes,
      MobileTaskSurfacePolicy surfacePolicy,
      WorkerPushOutbox pushOutbox,
      WorkerFeedRevisionStore feedRevisions,
      WorkerActionReceiptStore actionReceipts,
      TaskBoardCompletionEvidenceService completionEvidence) {
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
    this.kpiPalettes = kpiPalettes;
    this.surfacePolicy = surfacePolicy;
    this.pushOutbox = pushOutbox;
    this.feedRevisions = feedRevisions;
    this.actionReceipts = actionReceipts;
    this.completionEvidence = completionEvidence;
  }

  /** Returns the WorkerApp-compatible access context. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public WorkerContext context(UUID workerId, UUID warehouseId) {
    return context(MobileTaskSurface.WORKER, workerId, warehouseId);
  }

  /** Returns the selected native surface's access context, revision and offline lease. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public WorkerContext context(
      MobileTaskSurface surface, UUID workerId, UUID warehouseId) {
    WorkerAccess access = access(surface, workerId, warehouseId);
    List<WorkQueueDto> visibleCategories =
        visibleCategories(surface, workerId, warehouseId, access);
    OffsetDateTime now = now();
    long revision = revision(surface, warehouseId);
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
        visibleCategories.stream().map(queue -> category(surface, queue, access)).toList(),
        workerKpiPalette(),
        now,
        revision,
        leases.issue(workerId, warehouseId, revision, now));
  }

  /**
   * Returns one worker-authorized feed page for a fixed revision.
   *
   * <p>A cursor from another revision is rejected rather than serving a mixed snapshot.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public FeedPage feed(
      MobileTaskSurface surface,
      UUID workerId,
      UUID warehouseId,
      String encodedCursor,
      int requestedLimit) {
    int limit = Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
    WorkerAccess access = access(surface, workerId, warehouseId);
    long currentRevision = revision(surface, warehouseId);
    Cursor cursor =
        encodedCursor == null
            ? new Cursor(currentRevision, 0, now().toInstant().toEpochMilli())
            : decodeCursor(encodedCursor);
    if (cursor.revision() != currentRevision) {
      throw new ConflictException("Лента изменилась во время постраничной загрузки");
    }

    Map<UUID, WorkerCategory> categories = new LinkedHashMap<>();
    Map<UUID, WorkQueueDto> queuesById = new LinkedHashMap<>();
    visibleCategories(surface, workerId, warehouseId, access)
        .forEach(
            queue -> {
              queuesById.put(queue.id(), queue);
              categories.put(queue.id(), category(surface, queue, access));
            });
    TaskBoardSnapshot snapshot = taskBoard.workerSnapshot(surface, warehouseId, workerId);
    List<VisibleEntry> visible = new ArrayList<>();
    for (BoardColumnDto column : snapshot.columns()) {
      WorkerCategory workerCategory = categories.get(column.queueId());
      WorkQueueDto queue = queuesById.get(column.queueId());
      if (workerCategory == null && surface == MobileTaskSurface.DRIVER) {
        queue = driverQueue(column.queueId(), access);
        if (queue != null) {
          workerCategory = category(surface, queue, access);
          if (!workerCategory.audienceModes().isEmpty()) {
            queuesById.put(queue.id(), queue);
            categories.put(queue.id(), workerCategory);
          } else {
            workerCategory = null;
          }
        }
      }
      if (workerCategory == null) continue;
      for (BoardEntryDto entry : column.entries()) {
        if (queue == null) throw new IllegalStateException("Очередь ленты не найдена");
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
      if (counts == null
          || counts.routeStepIndex() < 0
          || counts.routeStepIndex() >= counts.routeStepCount()) {
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
    String etag =
        etag(surface, workerId, warehouseId, currentRevision, cursor.offset(), limit);
    return new FeedPage(
        new WorkerFeed(
            currentRevision, serverTime, List.copyOf(pageCategories), nextCursor),
        etag);
  }

  /** Returns WorkerApp-compatible task detail. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public WorkerTaskDetail detail(UUID workerId, UUID warehouseId, UUID entryId) {
    return detail(MobileTaskSurface.WORKER, workerId, warehouseId, entryId);
  }

  /**
   * Returns task detail after enforcing the selected surface and worker audience.
   *
   * <p>A repeatable-read snapshot keeps the raw route identity, package ordinal and aggregated
   * detail content consistent. A maintenance representative presents the content and remaining
   * timer of its consecutive same-queue execution package; other sources remain entry-scoped.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public WorkerTaskDetail detail(
      MobileTaskSurface surface, UUID workerId, UUID warehouseId, UUID entryId) {
    WorkerAccess access = access(surface, workerId, warehouseId);
    TaskLocation location = taskLocation(entryId);
    BoardEntryDto entry =
        taskBoard.workerEntry(surface, location.warehouseId(), entryId, workerId);
    WorkQueueDto queue = visibleQueue(surface, access, location);
    surfacePolicy.requireDetailVisible(surface, queue, entry, workerId);
    BoardTaskRegistrationDto task = registration(location.warehouseId(), entry);
    WorkerFeedCountProjection.Counts routeCoordinates = routeCoordinates(entry);
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
      contentByEntry.put(
          entry.id(), taskBoard.workerContent(location.warehouseId(), entry.id()));
    } else {
      packageSteps.forEach(
          step ->
              contentByEntry.put(
                  step.entryId(),
                  taskBoard.workerContent(location.warehouseId(), step.entryId())));
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
                          location.warehouseId(),
                          reference.generation(),
                          null),
                      mediaReadPath(
                          reference.mediaId(),
                          contentEntryId,
                          location.warehouseId(),
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
        routeCoordinates.routeStepIndex(),
        routeCoordinates.routeStepCount(),
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
    EvidenceDeclaration declaration = evidenceDeclaration(request);
    requireSupportedEvidenceDeclaration(declaration);
    TaskLocation location = taskLocation(entryId);
    WorkerTaskDetail current = detail(surface, workerId, warehouseId, entryId);
    if (request.routeIndex() != current.routeIndex()) {
      throw new ConflictException("Фотография относится к другому шагу задания");
    }
    if (surface == MobileTaskSurface.WORKER) {
      leases.requireDeferredCompletionValid(
          request.offlineLeaseId(), workerId, warehouseId, request.capturedAt(), now());
    } else {
      leases.requireValid(
          request.offlineLeaseId(), workerId, warehouseId, request.capturedAt(), now());
    }

    EvidenceReservationResult result =
        reserveEvidenceRecord(
            workerId,
            location.warehouseId(),
            entryId,
            declaration,
            false,
            () -> {
              if (!"IN_PROGRESS".equals(current.status())) {
                throw new ConflictException(
                    "Добавить новую фотографию можно только к заданию в работе");
              }
              BoardEntryDto entry =
                  taskBoard.workerEntry(surface, location.warehouseId(), entryId, workerId);
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
              String sourceType =
                  entry.source() == null ? null : entry.source().type().name();
              UUID sourceId = entry.source() == null ? null : entry.source().sourceId();
              return new EvidenceReservationTarget(
                  entry.taskId(), entry.routeIndex(), workerGroupId, sourceType, sourceId);
            });
    if (result.created()) {
      long changedRevision = revision(surface, warehouseId);
      afterCommit(
          () ->
              invalidations.actionApplied(
                  warehouseId, surface, workerId, entryId, changedRevision));
    }
    return result.evidence();
  }

  /**
   * Reserves exact-contractor evidence without issuing or accepting a native offline lease.
   *
   * <p>The caller has already proven the immutable logistics source and exact active contractor
   * audience under the external-task fence. This method locks the route entry and independently
   * requires its server-derived task, route index, IN_PROGRESS state and exact live worker
   * assignment before both a first reservation and an idempotent replay.
   */
  @Transactional
  TaskEvidence reserveContractorEvidence(
      UUID workerId,
      UUID warehouseId,
      UUID taskId,
      UUID entryId,
      int routeIndex,
      String sourceType,
      UUID sourceId,
      UUID idempotencyKey,
      ContractorEvidenceReservationRequest request) {
    requireIdempotencyKey(idempotencyKey.toString(), request.operationId());
    EvidenceDeclaration declaration =
        new EvidenceDeclaration(
            request.operationId(),
            request.evidenceId(),
            routeIndex,
            request.capturedAt(),
            request.contentType(),
            request.sizeBytes(),
            request.sha256());
    requireSupportedEvidenceDeclaration(declaration);
    if (request.capturedAt().toInstant().isAfter(databaseNow().toInstant())) {
      throw new IllegalArgumentException("Время съёмки фотографии не может быть в будущем");
    }
    return reserveEvidenceRecord(
            workerId,
            warehouseId,
            entryId,
            declaration,
            true,
            () -> {
              Boolean exactActiveAssignment =
                  jdbc.queryForObject(
                      """
                      select exists(
                        select 1
                          from queue_entry entry
                          join board_task task on task.id=entry.task_id
                          join task_assignment assignment on assignment.queue_entry_id=entry.id
                         where entry.id=?
                           and task.id=?
                           and task.warehouse_id=?
                           and entry.route_index=?
                           and entry.status='IN_PROGRESS'
                           and assignment.worker_id=?
                           and assignment.status in ('ACTIVE','PAUSED')
                      )
                      """,
                      Boolean.class,
                      entryId,
                      taskId,
                      warehouseId,
                      routeIndex,
                      workerId);
              if (!Boolean.TRUE.equals(exactActiveAssignment)) {
                throw new ConflictException(
                    "Фотографию можно добавить только к текущему этапу наёмного водителя");
              }
              return new EvidenceReservationTarget(
                  taskId, routeIndex, null, sourceType, sourceId);
            })
        .evidence();
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
    Optional<WorkerActionAppliedResult> replay =
        actionReceipts.lockAndReplay(
            surface, workerId, warehouseId, entryId, request);
    if (replay.isPresent()) return replay.get();
    TaskLocation location = taskLocation(entryId);
    WorkerTaskDetail current = detail(surface, workerId, warehouseId, entryId);
    boolean primaryTakeTriggersNotification =
        request.action() == WorkerAction.TAKE && "WAITING".equals(current.status());
    WorkerAccess access = access(surface, workerId, warehouseId);
    UUID currentGroupId = access.worker().currentGroupId();
    BoardEntryDto commandEntry =
        taskBoard.workerEntry(surface, location.warehouseId(), entryId, workerId);
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
    if (surface == MobileTaskSurface.WORKER && request.action() == WorkerAction.COMPLETE) {
      leases.requireDeferredCompletionValid(
          request.offlineLeaseId(), workerId, warehouseId, request.occurredAt(), now());
    } else {
      leases.requireValid(
          request.offlineLeaseId(), workerId, warehouseId, request.occurredAt(), now());
    }
    String previousCorrelation = MDC.get(CorrelationIdFilter.MDC_KEY);
    MDC.put(CorrelationIdFilter.MDC_KEY, request.operationId().toString());
    try {
      switch (request.action()) {
        case TAKE, JOIN ->
            taskBoard.takeFromMobile(
                surface,
                location.warehouseId(),
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
                location.warehouseId(),
                entryId,
                new PauseEntryRequest(request.expectedVersion(), null),
                workerId);
        case RESUME ->
            taskBoard.resume(
                location.warehouseId(),
                entryId,
                new VersionCommand(request.expectedVersion()),
                workerId);
        case COMPLETE -> {
          completionEvidence.requireAndSelect(
              entryId,
              current.resultPhotoMinCount(),
              commandEntry.queuePurpose() == QueuePurpose.LOGISTICS_DRIVER,
              request.evidenceId());
          taskBoard.complete(
              location.warehouseId(),
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
    long changedRevision = revision(surface, warehouseId);
    Set<UUID> notifiedWorkerIds =
        primaryTakeTriggersNotification
            ? notifiedWorkerIds(location.warehouseId(), commandEntry.queueId(), workerId)
            : Set.of();
    pushOutbox.enqueueJoinAvailable(
        notifiedWorkerIds, location.warehouseId(), entryId, changedRevision);
    WorkerActionAppliedResult response =
        actionReceipts.save(
            surface,
            workerId,
            warehouseId,
            entryId,
            request,
            new WorkerActionAppliedResult("APPLIED", changed.version(), changed));
    afterCommit(
        () ->
            invalidations.actionApplied(
                warehouseId,
                surface,
                workerId,
                entryId,
                changedRevision,
                notifiedWorkerIds));
    return response;
  }

  /** Returns one warehouse's current worker-feed revision for pagination and invalidations. */
  public long revision(UUID warehouseId) {
    return feedRevisions.current(warehouseId);
  }

  /**
   * Returns the opaque feed fence for one native audience.
   *
   * <p>The existing SSE contract carries only one home warehouse and cannot signal an externally
   * assigned task that is added to, removed from or reassigned at another warehouse. DriverApp
   * therefore uses the global task-board revision as a conservative REST-polling fence. This may
   * trigger an extra refresh for unrelated warehouse work, but it cannot leak that work and it
   * guarantees convergence without changing the native contract.
   */
  private long revision(MobileTaskSurface surface, UUID warehouseId) {
    if (surface != MobileTaskSurface.DRIVER) return revision(warehouseId);
    Long value =
        jdbc.queryForObject(
            """
            select coalesce(max(revision),0)
              from worker_feed_revision
            """,
            Long.class);
    return value == null ? 0 : value;
  }

  /** Resolves an entry's physical task warehouse and queue only from authoritative rows. */
  private TaskLocation taskLocation(UUID entryId) {
    List<TaskLocation> values =
        jdbc.query(
            """
            select task.warehouse_id,entry.queue_id
              from queue_entry entry
              join board_task task on task.id=entry.task_id
             where entry.id=?
            """,
            (result, row) ->
                new TaskLocation(
                    result.getObject("warehouse_id", UUID.class),
                    result.getObject("queue_id", UUID.class)),
            entryId);
    if (values.size() != 1 || values.getFirst().queueId() == null) {
      throw new NotFoundException("Задание не найдено");
    }
    return values.getFirst();
  }

  /** Resolves the queue capability used by an already audience-authorized entry detail. */
  private WorkQueueDto visibleQueue(
      MobileTaskSurface surface, WorkerAccess access, TaskLocation location) {
    WorkQueueDto queue =
        access.categories().stream()
            .filter(candidate -> candidate.id().equals(location.queueId()))
            .findFirst()
            .orElse(null);
    if (queue == null && surface == MobileTaskSurface.DRIVER) {
      queue = driverQueue(location.queueId(), access);
    }
    if (queue == null || category(surface, queue, access).audienceModes().isEmpty()) {
      throw new NotFoundException("Задание не найдено");
    }
    return queue;
  }

  /** Resolves an active remote driver queue while retaining the home worker's qualifications. */
  private WorkQueueDto driverQueue(UUID queueId, WorkerAccess access) {
    List<UUID> warehouses =
        jdbc.query(
            "select warehouse_id from work_queue where id=?",
            (result, row) -> result.getObject("warehouse_id", UUID.class),
            queueId);
    if (warehouses.size() != 1) return null;
    return registry.listQueues(warehouses.getFirst()).stream()
        .filter(queue -> queue.id().equals(queueId))
        .filter(WorkQueueDto::active)
        .filter(queue -> !queue.hidden())
        .filter(queue -> queue.purpose() == QueuePurpose.LOGISTICS_DRIVER)
        .filter(queue -> !category(MobileTaskSurface.DRIVER, queue, access).audienceModes().isEmpty())
        .findFirst()
        .orElse(null);
  }

  /**
   * Returns home queue capabilities plus remote queues that currently contain exact driver work.
   *
   * <p>The remote lookup never exposes an identity-free pool task. It exists so DriverApp context
   * and feed publish the same queue capabilities while the worker identity and offline lease stay
   * anchored to the JWT home warehouse.
   */
  private List<WorkQueueDto> visibleCategories(
      MobileTaskSurface surface,
      UUID workerId,
      UUID homeWarehouseId,
      WorkerAccess access) {
    Map<UUID, WorkQueueDto> result = new LinkedHashMap<>();
    access.categories().forEach(queue -> result.put(queue.id(), queue));
    if (surface != MobileTaskSurface.DRIVER) return List.copyOf(result.values());
    List<UUID> remoteQueueIds =
        jdbc.query(
            """
            select distinct queue.id
              from queue_entry entry
              join board_task task on task.id=entry.task_id
              join work_queue queue on queue.id=entry.queue_id
              join queue_definition definition on definition.id=queue.definition_id
             where task.warehouse_id<>?
               and task.status='ACTIVE'
               and entry.status in ('WAITING','IN_PROGRESS','PAUSED')
               and entry.entry_type='REAL'
               and queue.warehouse_id=task.warehouse_id
               and queue.active
               and not queue.hidden
               and definition.active
               and definition.queue_purpose='LOGISTICS_DRIVER'
               and (
                 (task.driver_audience_mode='ASSIGNED_DRIVER'
                  and task.planned_driver_worker_id=?)
                 or exists (
                   select 1
                     from task_assignment assignment
                    where assignment.queue_entry_id=entry.id
                      and assignment.worker_id=?
                      and assignment.status in ('ACTIVE','PAUSED')
                 )
               )
             order by queue.id
            """,
            (queryResult, row) -> queryResult.getObject("id", UUID.class),
            homeWarehouseId,
            workerId,
            workerId);
    remoteQueueIds.forEach(
        queueId -> {
          WorkQueueDto queue = driverQueue(queueId, access);
          if (queue != null) result.putIfAbsent(queue.id(), queue);
        });
    return List.copyOf(result.values());
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

  private WorkerKpiPalette workerKpiPalette() {
    KpiPaletteDto palette = kpiPalettes.get().palette();
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
        counts.routeStepIndex(),
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

  private WorkerFeedCountProjection.Counts routeCoordinates(BoardEntryDto entry) {
    WorkerFeedCountProjection.Counts coordinates =
        feedCounts.load(Map.of(entry.id(), entry.taskId())).get(entry.id());
    if (coordinates == null
        || coordinates.routeStepIndex() < 0
        || coordinates.routeStepIndex() >= coordinates.routeStepCount()) {
      throw new IllegalStateException(
          "Маршрут задания не содержит текущий шаг " + entry.id());
    }
    return coordinates;
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

  /**
   * Persists or exactly replays one reservation after its native or contractor access gate.
   *
   * <p>The route-entry lock serializes both evidence identity checks and execution-state checks.
   * Native reconnects preserve their established replay-after-completion behavior, while the
   * private contractor boundary deliberately repeats its live-assignment gate before replay.
   */
  private EvidenceReservationResult reserveEvidenceRecord(
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      EvidenceDeclaration request,
      boolean requireTargetBeforeReplay,
      Supplier<EvidenceReservationTarget> targetSupplier) {
    jdbc.queryForObject(
        "select id from queue_entry where id=? for update", UUID.class, entryId);
    EvidenceReservationTarget target =
        requireTargetBeforeReplay ? targetSupplier.get() : null;
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
      requireSameReservation(existing.getFirst(), workerId, warehouseId, entryId, request);
      ownerProofs.publish(warehouseId, entryId, true);
      return new EvidenceReservationResult(existing.getFirst().dto(), false);
    }
    if (target == null) target = targetSupplier.get();

    OffsetDateTime recordedAt = databaseNow();
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
          target.taskId(),
          target.routeIndex(),
          warehouseId,
          workerId,
          target.workerGroupId(),
          request.capturedAt().truncatedTo(ChronoUnit.MICROS),
          recordedAt,
          request.contentType().toLowerCase(Locale.ROOT),
          request.sizeBytes(),
          request.sha256(),
          target.sourceType(),
          target.sourceId(),
          recordedAt);
    } catch (DuplicateKeyException exception) {
      EvidenceRow replay =
          findEvidence(request.evidenceId(), request.operationId())
              .orElseThrow(() -> exception);
      requireSameReservation(replay, workerId, warehouseId, entryId, request);
      ownerProofs.publish(warehouseId, entryId, true);
      return new EvidenceReservationResult(replay.dto(), false);
    }
    ownerProofs.publish(warehouseId, entryId, true);
    TaskEvidence reserved =
        findEvidence(request.evidenceId(), request.operationId())
            .orElseThrow(() -> new IllegalStateException("Резервирование фотографии не сохранено"))
            .dto();
    return new EvidenceReservationResult(reserved, true);
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
      EvidenceDeclaration request) {
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
  private void requireSupportedEvidenceDeclaration(EvidenceDeclaration request) {
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

  private EvidenceDeclaration evidenceDeclaration(EvidenceReservationRequest request) {
    return new EvidenceDeclaration(
        request.operationId(),
        request.evidenceId(),
        request.routeIndex(),
        request.capturedAt(),
        request.contentType(),
        request.sizeBytes(),
        request.sha256());
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

  private String etag(
      MobileTaskSurface surface,
      UUID workerId,
      UUID warehouseId,
      long revision,
      int offset,
      int limit) {
    return "W/\"worker-"
        + surface.name()
        + "-"
        + warehouseId
        + "-"
        + workerId
        + "-"
        + revision
        + "-"
        + offset
        + "-"
        + limit
        + "\"";
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

  /** Dispatches volatile invalidations only after the authoritative transaction has committed. */
  private void afterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
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

  private record EvidenceRow(
      TaskEvidence dto,
      UUID operationId,
      UUID warehouseId,
      String contentType,
      long sizeBytes,
      String sha256) {}

  /** Server-derived immutable facts persisted with one new evidence reservation. */
  private record EvidenceReservationTarget(
      UUID taskId,
      int routeIndex,
      UUID workerGroupId,
      String sourceType,
      UUID sourceId) {}

  /** Surface-neutral evidence declaration after any native lease proof has been completed. */
  private record EvidenceDeclaration(
      UUID operationId,
      UUID evidenceId,
      int routeIndex,
      OffsetDateTime capturedAt,
      String contentType,
      long sizeBytes,
      String sha256) {}

  /** Exact reservation or replay plus whether this transaction created the row. */
  private record EvidenceReservationResult(TaskEvidence evidence, boolean created) {}

  /** Server-owned physical location of one route entry. */
  private record TaskLocation(UUID warehouseId, UUID queueId) {}

  /** Persisted owner and native surface fence for one registered installation. */
  private record DeviceOwner(UUID workerId, UUID warehouseId, String appSurface) {}
}
