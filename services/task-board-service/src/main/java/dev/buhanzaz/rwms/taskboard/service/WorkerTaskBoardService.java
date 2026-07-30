package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.*;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.taskboard.domain.GroupOperationalStatus;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerTaskBoardService {
  private static final String AVAILABLE = "AVAILABLE";
  private static final String MANDATORY = "MANDATORY";
  private static final String SECONDARY_PENDING = "SECONDARY_PENDING";
  private static final int MAX_LIMIT = 50;

  private final TaskBoardService taskBoard;
  private final WorkforceService workforce;
  private final RegistryService registry;
  private final JdbcTemplate jdbc;
  private final WorkerOfflineLeaseCodec leases;
  private final WorkerInvalidationHub invalidations;
  private final TaskBoardEntryOwnerProofService ownerProofs;

  public WorkerTaskBoardService(
      TaskBoardService taskBoard,
      WorkforceService workforce,
      RegistryService registry,
      JdbcTemplate jdbc,
      WorkerOfflineLeaseCodec leases,
      WorkerInvalidationHub invalidations,
      TaskBoardEntryOwnerProofService ownerProofs) {
    this.taskBoard = taskBoard;
    this.workforce = workforce;
    this.registry = registry;
    this.jdbc = jdbc;
    this.leases = leases;
    this.invalidations = invalidations;
    this.ownerProofs = ownerProofs;
  }

  public WorkerContext context(UUID workerId, UUID warehouseId) {
    WorkerAccess access = access(workerId, warehouseId);
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
        worker.operationalAvailability().name(),
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
        access.categories().stream().map(queue -> category(queue, access)).toList(),
        now,
        revision,
        leases.issue(workerId, warehouseId, revision, now));
  }

  public FeedPage feed(
      UUID workerId, UUID warehouseId, String encodedCursor, int requestedLimit) {
    int limit = Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
    WorkerAccess access = access(workerId, warehouseId);
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
        .forEach(queue -> categories.put(queue.id(), category(queue, access)));
    TaskBoardSnapshot snapshot = taskBoard.snapshot(warehouseId, null, false);
    List<CategoryEntry> visible = new ArrayList<>();
    for (BoardColumnDto column : snapshot.columns()) {
      WorkerCategory workerCategory = categories.get(column.queueId());
      if (workerCategory == null) continue;
      for (BoardEntryDto entry : column.entries()) {
        visible.add(new CategoryEntry(workerCategory, feedEntry(entry, workerCategory)));
      }
    }
    int start = Math.min(cursor.offset(), visible.size());
    int end = Math.min(start + limit, visible.size());
    Map<UUID, List<WorkerFeedEntry>> selected = new LinkedHashMap<>();
    for (CategoryEntry item : visible.subList(start, end)) {
      selected.computeIfAbsent(item.category().queueId(), ignored -> new ArrayList<>())
          .add(item.entry());
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

  public WorkerTaskDetail detail(UUID workerId, UUID warehouseId, UUID entryId) {
    WorkerAccess access = access(workerId, warehouseId);
    BoardEntryDto entry = taskBoard.entry(warehouseId, entryId);
    WorkQueueDto queue =
        access.categories().stream()
            .filter(candidate -> candidate.id().equals(entry.queueId()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Задание не найдено"));
    BoardTaskRegistrationDto task = registration(warehouseId, entry);
    int photoMinimum = resultPhotoMinimum(queue);
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
    TaskWorkerContentDto workerContent = taskBoard.workerContent(warehouseId, entryId);
    List<WorkerWork> works =
        workerContent.works().stream()
            .map(
                work ->
                    new WorkerWork(
                        work.id(),
                        work.name(),
                        work.quantity(),
                        work.unit(),
                        work.durationMinutes(),
                        work.comment()))
            .toList();
    List<WorkerMaterial> materials =
        workerContent.materials().stream()
            .map(
                material ->
                    new WorkerMaterial(
                        material.id(),
                        material.name(),
                        material.quantity(),
                        material.unit()))
            .toList();
    List<WorkerVisibleComment> comments =
        workerContent.comments().stream()
            .map(
                comment ->
                    new WorkerVisibleComment(
                        comment.id(),
                        comment.text(),
                        comment.authorDisplayName(),
                        comment.createdAt()))
            .toList();
    List<WorkerMediaReference> sourceMedia =
        workerContent.sourceMedia().stream()
            .map(
                reference ->
                    new WorkerMediaReference(
                        reference.mediaId(),
                        reference.generation(),
                        "SOURCE",
                        reference.contentType() == null
                            ? "application/octet-stream"
                            : reference.contentType(),
                        mediaReadPath(
                            reference.mediaId(),
                            entry.id(),
                            warehouseId,
                            reference.generation(),
                            null),
                        mediaReadPath(
                            reference.mediaId(),
                            entry.id(),
                            warehouseId,
                            reference.generation(),
                            "SMALL"),
                        reference.capturedAt(),
                        reference.recordedAt()))
            .toList();
    List<TaskEvidence> evidence = evidence(entry.id());
    long readyEvidenceCount =
        evidence.stream().filter(item -> "READY".equals(item.state())).count();
    WorkerCategory workerCategory = category(queue, access);
    List<AudienceSelector> audienceSelectors = new ArrayList<>();
    queue.bindings().stream()
        .map(
            binding ->
                new AudienceSelector(
                    "WORKER_CLASS",
                    binding.workerClass().id(),
                    binding.primary()
                        ? AVAILABLE
                        : binding.notifyUrgent() ? MANDATORY : SECONDARY_PENDING,
                    binding.stopTaskOnTake()))
        .forEach(audienceSelectors::add);
    return new WorkerTaskDetail(
        entry.id(),
        entry.version(),
        entry.taskId(),
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
        entry.plannedDurationMinutes(),
        entry.activeStartedAt(),
        entry.activeWorkSeconds(),
        entry.timerSnapshot(),
        List.copyOf(audienceSelectors),
        assignments(entry),
        works,
        materials,
        comments,
        sourceMedia,
        evidence,
        related,
        photoMinimum,
        entry.status().name().equals("IN_PROGRESS") && readyEvidenceCount >= photoMinimum);
  }

  @Transactional
  public TaskEvidence reserveEvidence(
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      String idempotencyKey,
      EvidenceReservationRequest request) {
    requireIdempotencyKey(idempotencyKey, request.operationId());
    WorkerTaskDetail current = detail(workerId, warehouseId, entryId);
    if (request.routeIndex() != current.routeIndex()) {
      throw new ConflictException("Фотография относится к другому шагу задания");
    }
    if (!"image/jpeg".equalsIgnoreCase(request.contentType())) {
      throw new IllegalArgumentException("Для результата поддерживаются только JPEG-фотографии");
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

    BoardEntryDto entry = taskBoard.entry(warehouseId, entryId);
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
          request.contentType().toLowerCase(java.util.Locale.ROOT),
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
    invalidations.actionApplied(workerId, entryId, revision());
    return reserved;
  }

  @Transactional
  public DeviceRegistrationResult registerDevice(
      UUID workerId,
      UUID warehouseId,
      String installationId,
      WorkerDeviceRegistrationRequest request) {
    access(workerId, warehouseId);
    String normalizedInstallationId = requireInstallationId(installationId);
    if (!"FCM".equals(request.provider())) {
      throw new IllegalArgumentException("Поддерживается только provider FCM");
    }
    List<DeviceOwner> existing =
        jdbc.query(
            """
            select worker_id,warehouse_id
              from worker_device_registration
             where installation_id=?
             for update
            """,
            (result, row) ->
                new DeviceOwner(
                    result.getObject("worker_id", UUID.class),
                    result.getObject("warehouse_id", UUID.class)),
            normalizedInstallationId);
    if (!existing.isEmpty()
        && (!existing.getFirst().workerId().equals(workerId)
            || !existing.getFirst().warehouseId().equals(warehouseId))) {
      throw new ConflictException("Установка приложения принадлежит другому рабочему");
    }
    boolean created = existing.isEmpty();
    try {
      jdbc.update(
          """
          insert into worker_device_registration(
              installation_id,worker_id,warehouse_id,provider,provider_token,status,
              app_version,sdk_int,locale,registered_at,updated_at)
          values (?,?,?,?,?,'ACTIVE',?,?,?,clock_timestamp(),clock_timestamp())
          on conflict (installation_id) do update
             set provider=excluded.provider,
                 provider_token=excluded.provider_token,
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
          request.appVersion(),
          request.sdkInt(),
          request.locale());
    } catch (DuplicateKeyException exception) {
      throw new ConflictException("FCM token уже зарегистрирован другой установкой");
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

  @Transactional
  public void unregisterDevice(
      UUID workerId, UUID warehouseId, String installationId) {
    access(workerId, warehouseId);
    int deleted =
        jdbc.update(
            """
            delete from worker_device_registration
             where installation_id=? and worker_id=? and warehouse_id=?
            """,
            requireInstallationId(installationId),
            workerId,
            warehouseId);
    if (deleted != 1) {
      throw new NotFoundException("Регистрация устройства не найдена");
    }
  }

  public WorkerActionAppliedResult applyAction(
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      String idempotencyKey,
      WorkerActionRequest request) {
    requireIdempotencyKey(idempotencyKey, request.operationId());
    WorkerTaskDetail current = detail(workerId, warehouseId, entryId);
    boolean primaryTakeTriggersUrgency =
        request.action() == WorkerAction.TAKE && "WAITING".equals(current.status());
    ActionReplay replay = replay(request.operationId());
    if (replay != null) {
      if (!replay.entryId().equals(entryId)
          || !replay.eventType().equals(eventType(request.action()))) {
        throw new ConflictException("operationId уже использован другой командой");
      }
      return new WorkerActionAppliedResult("REPLAYED", current.version(), current);
    }

    WorkerAccess access = access(workerId, warehouseId);
    UUID currentGroupId = access.worker().currentGroupId();
    if (request.action() == WorkerAction.TAKE) {
      if (currentGroupId == null
          || access.worker().operationalAvailability()
              != GroupOperationalStatus.AVAILABLE) {
        throw new ConflictException("Рабочему не назначена доступная текущая группа");
      }
      if (request.workerGroupId() != null
          && !currentGroupId.equals(request.workerGroupId())) {
        throw new ConflictException(
            "Задачу можно взять только текущей группой рабочего");
      }
    }
    leases.requireValid(
        request.offlineLeaseId(), workerId, warehouseId, request.occurredAt(), now());
    String previousCorrelation = MDC.get(CorrelationIdFilter.MDC_KEY);
    MDC.put(CorrelationIdFilter.MDC_KEY, request.operationId().toString());
    try {
      switch (request.action()) {
        case TAKE ->
            taskBoard.take(
                warehouseId,
                entryId,
                new TakeEntryRequest(
                    request.expectedVersion(), currentGroupId, workerId),
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
    WorkerTaskDetail changed = detail(workerId, warehouseId, entryId);
    long changedRevision = revision();
    Set<UUID> urgentWorkerIds =
        primaryTakeTriggersUrgency
            ? urgentWorkerIds(warehouseId, current.audienceSelectors())
            : Set.of();
    invalidations.actionApplied(
        workerId, entryId, changedRevision, urgentWorkerIds);
    return new WorkerActionAppliedResult("APPLIED", changed.version(), changed);
  }

  public long revision() {
    Long value =
        jdbc.queryForObject(
            "select coalesce(sum(current_version + 1), 0)::bigint from event_stream_head",
            Long.class);
    return value == null ? 0 : value;
  }

  private WorkerAccess access(UUID workerId, UUID warehouseId) {
    WorkerDto worker =
        workforce.listWorkers(warehouseId).stream()
            .filter(candidate -> candidate.id().equals(workerId) && candidate.active())
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Рабочий не найден"));
    List<QualificationDto> qualifications =
        worker.qualifications().stream().filter(QualificationDto::active).toList();
    List<WorkerGroupDto> groups =
        workforce.listGroups(warehouseId).stream()
            .filter(WorkerGroupDto::active)
            .filter(
                group ->
                    group.members().stream()
                        .anyMatch(member -> member.active() && member.workerId().equals(workerId)))
            .toList();
    Set<UUID> classIds = new LinkedHashSet<>();
    qualifications.forEach(value -> classIds.add(value.workerClass().id()));
    groups.forEach(value -> classIds.add(value.workerClass().id()));
    List<WorkQueueDto> categories =
        registry.listQueues(warehouseId).stream()
            .filter(queue -> queue.active() && !queue.hidden())
            .filter(
                queue ->
                    queue.bindings().stream()
                        .anyMatch(binding -> classIds.contains(binding.workerClass().id())))
            .sorted(Comparator.comparingInt(WorkQueueDto::sortOrder))
            .toList();
    return new WorkerAccess(worker, groups, qualifications, categories);
  }

  private WorkerCategory category(WorkQueueDto queue, WorkerAccess access) {
    Set<UUID> workerClassIds = new LinkedHashSet<>();
    access
        .qualifications()
        .forEach(value -> workerClassIds.add(value.workerClass().id()));
    access.groups().forEach(value -> workerClassIds.add(value.workerClass().id()));
    Set<String> modes = new LinkedHashSet<>();
    queue.bindings().stream()
        .filter(binding -> workerClassIds.contains(binding.workerClass().id()))
        .forEach(
            binding -> {
              if (binding.primary()) {
                modes.add(AVAILABLE);
              } else {
                modes.add(SECONDARY_PENDING);
                if (binding.notifyUrgent()) modes.add(MANDATORY);
              }
            });
    return new WorkerCategory(
        queue.id(),
        queue.name(),
        queue.type().name(),
        queue.sortOrder(),
        List.copyOf(modes),
        resultPhotoMinimum(queue));
  }

  private WorkerFeedEntry feedEntry(BoardEntryDto entry, WorkerCategory category) {
    return new WorkerFeedEntry(
        entry.id(),
        entry.version(),
        entry.taskId(),
        entry.routeIndex(),
        entry.title(),
        entry.unitNumber(),
        entry.taskText(),
        entry.scheduledDate(),
        null,
        entry.priority(),
        entry.queuePosition(),
        entry.status().name(),
        availabilityMode(category, entry.status().name()),
        entry.plannedDurationMinutes(),
        entry.activeStartedAt(),
        entry.activeWorkSeconds(),
        entry.timerSnapshot(),
        assignments(entry),
        readyEvidenceCount(entry.id()),
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

  private int resultPhotoMinimum(WorkQueueDto queue) {
    return queue.resultPhotoMinCount();
  }

  private static String availabilityMode(WorkerCategory category, String entryStatus) {
    if ("IN_PROGRESS".equals(entryStatus) && category.audienceModes().contains(MANDATORY)) {
      return MANDATORY;
    }
    if ("WAITING".equals(entryStatus)
        && !category.audienceModes().contains(AVAILABLE)
        && category.audienceModes().contains(SECONDARY_PENDING)) {
      return SECONDARY_PENDING;
    }
    return AVAILABLE;
  }

  private Set<UUID> urgentWorkerIds(
      UUID warehouseId, List<AudienceSelector> audienceSelectors) {
    Set<UUID> urgentClassIds =
        audienceSelectors.stream()
            .filter(selector -> "WORKER_CLASS".equals(selector.kind()))
            .filter(selector -> MANDATORY.equals(selector.mode()))
            .map(AudienceSelector::id)
            .collect(java.util.stream.Collectors.toSet());
    if (urgentClassIds.isEmpty()) return Set.of();
    return workforce.listWorkers(warehouseId).stream()
        .filter(WorkerDto::active)
        .filter(
            worker ->
                worker.qualifications().stream()
                    .anyMatch(
                        qualification ->
                            qualification.active()
                                && urgentClassIds.contains(
                                    qualification.workerClass().id())))
        .map(WorkerDto::id)
        .collect(
            java.util.stream.Collectors.toCollection(LinkedHashSet::new));
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

  private int readyEvidenceCount(UUID entryId) {
    Integer value =
        jdbc.queryForObject(
            """
            select count(*)::integer
              from worker_task_evidence
             where entry_id=? and state='READY'
            """,
            Integer.class,
            entryId);
    return value == null ? 0 : value;
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
      case TAKE -> TaskBoardEventTypes.QUEUE_ENTRY_TAKEN;
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

  private record CategoryEntry(WorkerCategory category, WorkerFeedEntry entry) {}

  private record ActionReplay(UUID entryId, String eventType) {}

  private record EvidenceRow(
      TaskEvidence dto,
      UUID operationId,
      UUID warehouseId,
      String contentType,
      long sizeBytes,
      String sha256) {}

  private record DeviceOwner(UUID workerId, UUID warehouseId) {}
}
