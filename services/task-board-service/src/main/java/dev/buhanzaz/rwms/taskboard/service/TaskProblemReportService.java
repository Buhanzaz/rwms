package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.*;

import dev.buhanzaz.rwms.taskboard.api.ProblemReportApiModels.TaskProblemReportAttachment;
import dev.buhanzaz.rwms.taskboard.api.ProblemReportApiModels.TaskProblemReportPage;
import dev.buhanzaz.rwms.taskboard.api.ProblemReportApiModels.TaskProblemReportSummary;
import dev.buhanzaz.rwms.taskboard.domain.TaskProblemReport;
import dev.buhanzaz.rwms.taskboard.domain.TaskProblemReportReadReceipt;
import dev.buhanzaz.rwms.taskboard.mapper.TaskProblemReportMapper;
import dev.buhanzaz.rwms.taskboard.repository.TaskProblemReportReadReceiptRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskProblemReportRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns immutable worker problem reports and each manager's personal read receipt.
 *
 * <p>The service delegates task-entry and media-reservation authorization to the existing worker
 * task surface. This preserves the established media owner proof and inbox flow without making a
 * report photo completion evidence or an externally exported task-evidence fact.
 */
@Service
public class TaskProblemReportService {
  private static final int MAX_PAGE_LIMIT = 50;

  private final TaskProblemReportRepository reports;
  private final TaskProblemReportReadReceiptRepository readReceipts;
  private final TaskProblemReportMapper mapper;
  private final WorkerTaskBoardService workerBoard;
  private final JdbcTemplate jdbc;
  private final TaskRequirementService requirements;
  private final TaskBoardWorkerExecutionService execution;

  public TaskProblemReportService(
      TaskProblemReportRepository reports,
      TaskProblemReportReadReceiptRepository readReceipts,
      TaskProblemReportMapper mapper,
      WorkerTaskBoardService workerBoard,
      JdbcTemplate jdbc,
      TaskRequirementService requirements,
      TaskBoardWorkerExecutionService execution) {
    this.execution = execution;
    this.requirements = requirements;
    this.reports = reports;
    this.readReceipts = readReceipts;
    this.mapper = mapper;
    this.workerBoard = workerBoard;
    this.jdbc = jdbc;
  }

  /** Creates all report reservations atomically or returns the byte-equivalent durable replay. */
  @Transactional
  public CreatedWorkerProblemReport create(
      UUID workerId,
      UUID homeWarehouseId,
      UUID entryId,
      String idempotencyKey,
      WorkerProblemReportRequest request) {
    requireIdempotencyKey(idempotencyKey, request.operationId());
    String comment = normalizedComment(request.comment());
    requireDistinctAttachmentIdentities(request);
    workerBoard.requireProblemReportAuthorHome(workerId, homeWarehouseId);
    lockProblemReportOperation(request.operationId());

    Optional<TaskProblemReport> existing = reports.findForUpdate(request.operationId());
    if (existing.isPresent()) {
      TaskProblemReport report = existing.get();
      requireSameReport(report, workerId, entryId, comment, request);
      return new CreatedWorkerProblemReport(workerResponse(report), false);
    }

    requirements.lockEntryMutation(entryId);
    WorkerTaskBoardService.ProblemReportTarget target =
        workerBoard.prepareProblemReportTarget(
            workerId,
            homeWarehouseId,
            entryId,
            request.occurredAt(),
            request.offlineLeaseId());
    TaskProblemReport report =
        TaskProblemReport.create(
            request.operationId(),
            target.entryId(),
            target.taskId(),
            target.warehouseId(),
            workerId,
            target.workerName(),
            target.entryTitle(),
            target.routeIndex(),
            comment,
            request.occurredAt().truncatedTo(ChronoUnit.MICROS),
            databaseNow());
    var missingItems = requirements.report(target.warehouseId(), target.taskId(), target.entryId(),
        request.expectedVersion(), request.missingItemIds());
    report.recordMissingItems(requirements.write(request.missingItemIds().stream().sorted().toList()),
        requirements.write(missingItems),
        requirements.requireTask(target.warehouseId(), target.taskId()).getUnitNumber(), request.expectedVersion());
    reports.saveAndFlush(report);
    for (EvidenceReservationRequest attachment : request.attachments()) {
      if (!request.offlineLeaseId().equals(attachment.offlineLeaseId())) {
        throw new ConflictException("Все фотографии сообщения должны использовать тот же offline lease");
      }
      workerBoard.reserveProblemReportEvidence(
          workerId, homeWarehouseId, report.getId(), target, attachment);
    }
    return new CreatedWorkerProblemReport(workerResponse(report), true);
  }

  /** Returns a durable author-owned report without requiring its original task to remain active. */
  @Transactional(readOnly = true)
  public WorkerProblemReport own(UUID workerId, UUID homeWarehouseId, UUID reportId) {
    workerBoard.requireProblemReportAuthorHome(workerId, homeWarehouseId);
    TaskProblemReport report =
        reports.findById(reportId).orElseThrow(() -> new NotFoundException("Сообщение о проблеме не найдено"));
    if (!workerId.equals(report.getWorkerId())) {
      throw new NotFoundException("Сообщение о проблеме не найдено");
    }
    return workerResponse(report);
  }

  /** Returns one newest-first manager page and the requester's full personal unread count. */
  @Transactional(readOnly = true)
  public TaskProblemReportPage page(
      UUID warehouseId, UUID managerId, String cursor, int requestedLimit) {
    int limit = Math.min(Math.max(1, requestedLimit), MAX_PAGE_LIMIT);
    PageCursor pageCursor = cursor == null ? null : decodeCursor(cursor);
    List<PageRow> rows = pageRows(warehouseId, managerId, pageCursor, limit + 1);
    boolean hasNext = rows.size() > limit;
    if (hasNext) rows = rows.subList(0, limit);
    Map<UUID, TaskProblemReport> reportsById = new LinkedHashMap<>();
    reports.findAllById(rows.stream().map(PageRow::reportId).toList())
        .forEach(report -> reportsById.put(report.getId(), report));
    List<dev.buhanzaz.rwms.taskboard.api.ProblemReportApiModels.TaskProblemReport> page =
        rows.stream()
            .map(
                row -> {
                  TaskProblemReport report = reportsById.get(row.reportId());
                  if (report == null) throw new IllegalStateException("Problem report disappeared during read");
                  return managerResponse(report, row.readAt());
                })
            .toList();
    String nextCursor =
        hasNext && !rows.isEmpty()
            ? encodeCursor(rows.getLast().recordedAt(), rows.getLast().reportId())
            : null;
    Long unread =
        jdbc.queryForObject(
            """
            select count(*)
              from task_problem_report report
             where report.warehouse_id=?
               and not exists (
                 select 1
                   from task_problem_report_read_receipt receipt
                  where receipt.report_id=report.id and receipt.manager_id=?)
            """,
            Long.class,
            warehouseId,
            managerId);
    return new TaskProblemReportPage(page, nextCursor, Math.toIntExact(unread == null ? 0 : unread));
  }

  /** Persists one manager-local read marker without mutating the report or another manager's view. */
  @Transactional
  public void markRead(UUID warehouseId, UUID managerId, UUID reportId) {
    TaskProblemReport report =
        reports
            .findForUpdate(reportId)
            .orElseThrow(() -> new NotFoundException("Сообщение о проблеме не найдено"));
    if (!warehouseId.equals(report.getWarehouseId())) {
      throw new NotFoundException("Сообщение о проблеме не найдено");
    }
    if (readReceipts.findByReportIdAndManagerId(reportId, managerId).isEmpty()) {
      readReceipts.save(
          TaskProblemReportReadReceipt.create(UUID.randomUUID(), reportId, managerId, databaseNow()));
    }
  }

  /** Replays the same warehouse-scoped bulk command without repeating external effects. */
  @Transactional
  public dev.buhanzaz.rwms.taskboard.api.TaskRequirementApiModels.AppliedToAll applyToAll(
      UUID warehouseId, UUID managerId, UUID reportId, String idempotencyKey, UUID operationId) {
    requireIdempotencyKey(idempotencyKey, operationId);
    lockProblemReportOperation(operationId);
    var replay = jdbc.query("select report_id,warehouse_id,manager_id,affected_task_ids::text from task_problem_bulk_receipt where operation_id=?",
        (row, index) -> {
          if (!reportId.equals(row.getObject("report_id", UUID.class))
              || !warehouseId.equals(row.getObject("warehouse_id", UUID.class))
              || !managerId.equals(row.getObject("manager_id", UUID.class))) {
            throw new ConflictException("operationId уже использован другой командой");
          }
          return new dev.buhanzaz.rwms.taskboard.api.TaskRequirementApiModels.AppliedToAll(
              requirements.read(row.getString("affected_task_ids"), new tools.jackson.core.type.TypeReference<>() {}));
        }, operationId);
    if (!replay.isEmpty()) return replay.getFirst();
    TaskProblemReport report = reports.findForUpdate(reportId)
        .filter(value -> warehouseId.equals(value.getWarehouseId()))
        .orElseThrow(() -> new NotFoundException("Сообщение о проблеме не найдено"));
    List<dev.buhanzaz.rwms.taskboard.api.TaskRequirementApiModels.MissingItem> missing = requirements.read(
        report.getMissingItems(), new tools.jackson.core.type.TypeReference<>() {});
    if (missing.isEmpty()) throw new ConflictException("Сообщение не содержит отсутствующих позиций");
    List<UUID> affected = requirements.applyToAll(warehouseId, report.getTaskId(),
        missing.stream().map(dev.buhanzaz.rwms.taskboard.api.TaskRequirementApiModels.MissingItem::itemId).toList());
    affected.forEach(taskId -> execution.blockForMissingRequirements(warehouseId, taskId));
    report.appliedToAll();
    reports.saveAndFlush(report);
    jdbc.update("insert into task_problem_bulk_receipt(operation_id,report_id,warehouse_id,manager_id,affected_task_ids) values (?,?,?,?,?::jsonb)",
        operationId, reportId, warehouseId, managerId, requirements.write(affected));
    return new dev.buhanzaz.rwms.taskboard.api.TaskRequirementApiModels.AppliedToAll(affected);
  }

  private WorkerProblemReport workerResponse(TaskProblemReport report) {
    TaskProblemReportSummary summary = mapper.summary(report);
    return new WorkerProblemReport(
        summary.reportId(),
        summary.entryId(),
        summary.taskId(),
        summary.routeIndex(),
        summary.entryTitle(),
        summary.comment(),
        summary.occurredAt(),
        summary.recordedAt(),
        workerBoard.problemReportEvidence(summary.reportId()),
        requirements.read(report.getMissingItems(), new tools.jackson.core.type.TypeReference<>() {}),
        report.getUnitNumber());
  }

  private dev.buhanzaz.rwms.taskboard.api.ProblemReportApiModels.TaskProblemReport managerResponse(
      TaskProblemReport report, OffsetDateTime readAt) {
    TaskProblemReportSummary summary = mapper.summary(report);
    List<TaskProblemReportAttachment> attachments =
        workerBoard.problemReportEvidence(summary.reportId()).stream()
            .map(
                evidence ->
                    new TaskProblemReportAttachment(
                        evidence.evidenceId(),
                        evidence.state(),
                        evidence.capturedAt(),
                        evidence.recordedAt(),
                        evidence.mediaId(),
                        evidence.mediaGeneration(),
                        evidence.reviewReason(),
                        evidence.contentType(),
                        evidence.readPath(),
                        evidence.thumbnailPath()))
            .toList();
    return new dev.buhanzaz.rwms.taskboard.api.ProblemReportApiModels.TaskProblemReport(
        summary.reportId(),
        summary.entryId(),
        summary.taskId(),
        summary.routeIndex(),
        summary.entryTitle(),
        summary.workerName(),
        summary.comment(),
        summary.occurredAt(),
        summary.recordedAt(),
        readAt,
        attachments,
        requirements.read(report.getMissingItems(), new tools.jackson.core.type.TypeReference<>() {}),
        report.getUnitNumber(), report.isAppliedToAll());
  }

  private List<PageRow> pageRows(
      UUID warehouseId, UUID managerId, PageCursor cursor, int fetchLimit) {
    if (cursor == null) {
      return jdbc.query(
          """
          select report.id,report.recorded_at,receipt.read_at
            from task_problem_report report
            left join task_problem_report_read_receipt receipt
              on receipt.report_id=report.id and receipt.manager_id=?
           where report.warehouse_id=?
           order by report.recorded_at desc,report.id desc
           limit ?
          """,
          (result, row) ->
              new PageRow(
                  result.getObject("id", UUID.class),
                  result.getObject("recorded_at", OffsetDateTime.class),
                  result.getObject("read_at", OffsetDateTime.class)),
          managerId,
          warehouseId,
          fetchLimit);
    }
    return jdbc.query(
        """
        select report.id,report.recorded_at,receipt.read_at
          from task_problem_report report
          left join task_problem_report_read_receipt receipt
            on receipt.report_id=report.id and receipt.manager_id=?
         where report.warehouse_id=?
           and (report.recorded_at,report.id) < (?,?)
         order by report.recorded_at desc,report.id desc
         limit ?
        """,
        (result, row) ->
            new PageRow(
                result.getObject("id", UUID.class),
                result.getObject("recorded_at", OffsetDateTime.class),
                result.getObject("read_at", OffsetDateTime.class)),
        managerId,
        warehouseId,
        cursor.recordedAt(),
        cursor.reportId(),
        fetchLimit);
  }

  private void requireSameReport(
      TaskProblemReport report,
      UUID workerId,
      UUID entryId,
      String comment,
      WorkerProblemReportRequest request) {
    boolean same =
        workerId.equals(report.getWorkerId())
            && entryId.equals(report.getEntryId())
            && comment.equals(report.getComment())
            && java.util.Objects.equals(report.getExpectedEntryVersion(), request.expectedVersion())
            && requirements.read(report.getRequestedMissingItemIds(),
                new tools.jackson.core.type.TypeReference<List<UUID>>() {})
                .equals(request.missingItemIds().stream().sorted().toList())
            && Duration.between(report.getOccurredAt().toInstant(), request.occurredAt().toInstant())
                    .abs()
                    .compareTo(Duration.ofNanos(1_000))
                <= 0
            && evidenceFingerprints(report.getId()).equals(requestFingerprints(request.attachments()));
    if (!same) {
      throw new ConflictException("operationId уже использован другим сообщением о проблеме");
    }
  }

  private List<EvidenceFingerprint> evidenceFingerprints(UUID reportId) {
    return jdbc.query(
            """
            select operation_id,evidence_id,route_index,captured_at,content_type,size_bytes,sha256
              from worker_task_evidence
             where problem_report_id=?
            """,
            (result, row) ->
                new EvidenceFingerprint(
                    result.getObject("operation_id", UUID.class),
                    result.getObject("evidence_id", UUID.class),
                    result.getInt("route_index"),
                    result.getObject("captured_at", OffsetDateTime.class).toInstant(),
                    result.getString("content_type"),
                    result.getLong("size_bytes"),
                    result.getString("sha256")),
            reportId)
        .stream()
        .sorted(Comparator.comparing(EvidenceFingerprint::evidenceId))
        .toList();
  }

  private List<EvidenceFingerprint> requestFingerprints(List<EvidenceReservationRequest> attachments) {
    return attachments.stream()
        .map(
            request ->
                new EvidenceFingerprint(
                    request.operationId(),
                    request.evidenceId(),
                    request.routeIndex(),
                    request.capturedAt().truncatedTo(ChronoUnit.MICROS).toInstant(),
                    request.contentType().toLowerCase(java.util.Locale.ROOT),
                    request.sizeBytes(),
                    request.sha256()))
        .sorted(Comparator.comparing(EvidenceFingerprint::evidenceId))
        .toList();
  }

  private void requireDistinctAttachmentIdentities(WorkerProblemReportRequest request) {
    Set<UUID> operationIds = new HashSet<>();
    Set<UUID> evidenceIds = new HashSet<>();
    operationIds.add(request.operationId());
    evidenceIds.add(request.operationId());
    for (EvidenceReservationRequest attachment : request.attachments()) {
      if (!operationIds.add(attachment.operationId()) || !evidenceIds.add(attachment.evidenceId())) {
        throw new IllegalArgumentException("Идентификаторы фотографий в сообщении должны быть уникальны");
      }
      if (request.operationId().equals(attachment.evidenceId())) {
        throw new IllegalArgumentException(
            "Идентификатор сообщения нельзя использовать как идентификатор фотографии");
      }
    }
  }

  private String normalizedComment(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 2000) {
      throw new IllegalArgumentException("Комментарий должен содержать от 1 до 2000 символов");
    }
    return normalized;
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

  private OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (value == null) throw new IllegalStateException("Database clock is unavailable");
    return value;
  }

  private void lockProblemReportOperation(UUID operationId) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "task-problem-report:" + operationId);
  }

  private String encodeCursor(OffsetDateTime recordedAt, UUID reportId) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((recordedAt + "|" + reportId).getBytes(StandardCharsets.UTF_8));
  }

  private PageCursor decodeCursor(String value) {
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
      String[] parts = decoded.split("\\|", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      return new PageCursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Некорректный cursor сообщения о проблеме", exception);
    }
  }

  /** Report-create result distinguishes the only valid replay response status. */
  public record CreatedWorkerProblemReport(WorkerProblemReport report, boolean created) {}

  private record EvidenceFingerprint(
      UUID operationId,
      UUID evidenceId,
      int routeIndex,
      java.time.Instant capturedAt,
      String contentType,
      long sizeBytes,
      String sha256) {}

  private record PageRow(UUID reportId, OffsetDateTime recordedAt, OffsetDateTime readAt) {}

  private record PageCursor(OffsetDateTime recordedAt, UUID reportId) {}
}
