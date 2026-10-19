package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TakeEntryRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskSourceReferenceDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.VersionCommand;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorTaskAction;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorTaskActionRequest;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorTaskActionResult;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorEvidenceOwnerType;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorEvidenceReservation;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorEvidenceReservationRequest;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorTaskExecutionSnapshot;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorTaskRouteEntry;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.TaskEvidence;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.domain.TaskSyncSource;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerEmploymentType;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskSyncSourceRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the private logistics-service execution boundary for an exact assigned contractor.
 *
 * <p>This boundary never exposes the general board or accepts an arbitrary executor. Every read
 * and command proves the immutable logistics source, exact assigned-driver audience, active
 * contractor employment and exact route membership. START and COMPLETE delegate to the existing
 * worker execution state machine; only the service authentication and presentation boundary are
 * different from DriverApp.
 */
@Service
public class ContractorTaskExecutionService {
  private static final String LOGISTICS_SOURCE_CLIENT_ID = "logistics-service";
  private static final Set<AssignmentStatus> LIVE_ASSIGNMENTS =
      Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED);

  private final BoardTaskRepository tasks;
  private final QueueEntryRepository entries;
  private final WorkerRepository workers;
  private final TaskSyncSourceRepository sources;
  private final TaskAssignmentRepository assignments;
  private final TaskBoardReadProjectionService readProjections;
  private final TaskBoardWorkerExecutionService workerExecutions;
  private final TaskBoardCompletionEvidenceService completionEvidence;
  private final WorkerTaskBoardService workerBoard;
  private final WorkerActionReceiptStore actionReceipts;
  private final JdbcTemplate jdbc;

  /** Creates the exact-assignment service from task-board-owned repositories and transitions. */
  public ContractorTaskExecutionService(
      BoardTaskRepository tasks,
      QueueEntryRepository entries,
      WorkerRepository workers,
      TaskSyncSourceRepository sources,
      TaskAssignmentRepository assignments,
      TaskBoardReadProjectionService readProjections,
      TaskBoardWorkerExecutionService workerExecutions,
      TaskBoardCompletionEvidenceService completionEvidence,
      WorkerTaskBoardService workerBoard,
      WorkerActionReceiptStore actionReceipts,
      JdbcTemplate jdbc) {
    this.tasks = tasks;
    this.entries = entries;
    this.workers = workers;
    this.sources = sources;
    this.assignments = assignments;
    this.readProjections = readProjections;
    this.workerExecutions = workerExecutions;
    this.completionEvidence = completionEvidence;
    this.workerBoard = workerBoard;
    this.actionReceipts = actionReceipts;
    this.jdbc = jdbc;
  }

  /** Returns the complete ordered worker-visible route for one exact active contractor task. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ContractorTaskExecutionSnapshot snapshot(UUID workerId, UUID externalTaskId) {
    return snapshot(requireContext(workerId, externalTaskId, null, false));
  }

  /**
   * Applies one version-fenced START or COMPLETE transition and returns its immutable replay form.
   *
   * <p>The worker row is locked before the receipt check, so deactivation cannot race an accepted
   * replay or mutation. The task proof is repeated by the existing worker state machine before it
   * mutates entry state.
   */
  @Transactional
  public ContractorTaskActionResult apply(
      UUID workerId,
      UUID externalTaskId,
      UUID entryId,
      UUID idempotencyKey,
      ContractorTaskActionRequest request) {
    if (!idempotencyKey.equals(request.operationId())) {
      throw new IllegalArgumentException(
          "Idempotency-Key должен совпадать с operationId команды");
    }
    lockExternalTask(externalTaskId);
    ContractorExecutionContext context =
        requireContext(workerId, externalTaskId, entryId, true);
    Optional<ContractorTaskActionResult> replay =
        actionReceipts.lockAndReplayContractor(
            workerId,
            context.task().getWarehouseId(),
            externalTaskId,
            entryId,
            request);
    if (replay.isPresent()) return replay.get();

    QueueEntry entry = requireEntry(context, entryId);
    String previousCorrelation = MDC.get(CorrelationIdFilter.MDC_KEY);
    MDC.put(CorrelationIdFilter.MDC_KEY, request.operationId().toString());
    try {
      if (request.action() == ContractorTaskAction.START) {
        if (request.evidenceId() != null) {
          throw new IllegalArgumentException("START не принимает фотографию завершения");
        }
        workerExecutions.take(
            context.task().getWarehouseId(),
            entryId,
            new TakeEntryRequest(request.expectedVersion(), null, workerId),
            workerId,
            false);
      } else {
        completionEvidence.requireAndSelectForContractor(
            entryId,
            workerId,
            entry.getQueue().getResultPhotoMinCount(),
            request.evidenceId());
        workerExecutions.complete(
            context.task().getWarehouseId(),
            entryId,
            new VersionCommand(request.expectedVersion()),
            workerId);
      }
    } finally {
      if (previousCorrelation == null) {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
      } else {
        MDC.put(CorrelationIdFilter.MDC_KEY, previousCorrelation);
      }
    }

    ContractorTaskExecutionSnapshot changed =
        snapshot(requireContext(workerId, externalTaskId, entryId, false));
    long currentVersion =
        changed.route().stream()
            .filter(candidate -> candidate.entryId().equals(entryId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Изменённый этап отсутствует в маршруте"))
            .version();
    return actionReceipts.saveContractor(
        workerId,
        context.task().getWarehouseId(),
        externalTaskId,
        entryId,
        request,
        new ContractorTaskActionResult("APPLIED", currentVersion, changed));
  }

  /**
   * Reserves one exact route-entry evidence identity through task-board's native media pipeline.
   *
   * <p>The external-task advisory fence serializes source, audience and assignment changes. The
   * worker evidence service then locks the entry, repeats the exact active assignment and
   * IN_PROGRESS proof, applies the existing declaration limits and publishes the standard entry
   * media-owner proof. No native offline lease or media bearer path crosses this boundary.
   */
  @Transactional
  public ContractorEvidenceReservation reserveEvidence(
      UUID workerId,
      UUID externalTaskId,
      UUID entryId,
      UUID idempotencyKey,
      ContractorEvidenceReservationRequest request) {
    if (!idempotencyKey.equals(request.operationId())) {
      throw new IllegalArgumentException(
          "Idempotency-Key должен совпадать с operationId резервирования");
    }
    lockExternalTask(externalTaskId);
    ContractorExecutionContext context =
        requireContext(workerId, externalTaskId, entryId, true);
    QueueEntry entry = requireEntry(context, entryId);
    String previousCorrelation = MDC.get(CorrelationIdFilter.MDC_KEY);
    MDC.put(CorrelationIdFilter.MDC_KEY, request.operationId().toString());
    try {
      TaskEvidence evidence =
          workerBoard.reserveContractorEvidence(
              workerId,
              context.task().getWarehouseId(),
              context.task().getId(),
              entryId,
              entry.getRouteIndex(),
              context.source().getSourceType().name(),
              context.source().getSourceId(),
              idempotencyKey,
              request);
      return new ContractorEvidenceReservation(
          evidence.evidenceId(),
          evidence.version(),
          evidence.state(),
          entryId,
          ContractorEvidenceOwnerType.TASK_BOARD_ENTRY,
          entryId,
          context.task().getWarehouseId(),
          evidence.evidenceId(),
          evidence.capturedAt(),
          evidence.contentType(),
          request.sizeBytes(),
          request.sha256());
    } finally {
      if (previousCorrelation == null) {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
      } else {
        MDC.put(CorrelationIdFilter.MDC_KEY, previousCorrelation);
      }
    }
  }

  /**
   * Serializes assignment proof with source-owned audience, route and cancellation mutations.
   *
   * <p>{@link TaskBoardExternalMutationService} acquires the same key before changing those facts,
   * so a command cannot retain a stale exact-contractor proof while another transaction reassigns
   * the task.
   */
  private void lockExternalTask(UUID externalTaskId) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "external-task:" + externalTaskId);
  }

  private ContractorExecutionContext requireContext(
      UUID workerId, UUID externalTaskId, UUID entryId, boolean lockWorker) {
    BoardTask task =
        tasks
            .findByExternalTaskId(externalTaskId)
            .orElseThrow(() -> new NotFoundException("Задание наёмного водителя не найдено"));
    TaskSyncSource source = sources.findById(task.getId()).orElse(null);
    boolean exactLogisticsSource =
        source != null
            && externalTaskId.equals(source.getExternalTaskId())
            && LOGISTICS_SOURCE_CLIENT_ID.equals(source.getSourceClientId())
            && source.getSourceType() == TaskSourceType.LOGISTICS_DRIVER_TASK
            && source.getSourceId() != null;
    boolean exactAudience =
        task.getDriverAudienceMode() == DriverTaskAudienceMode.ASSIGNED_DRIVER
            && workerId.equals(task.getPlannedDriverWorkerId());
    if (!exactLogisticsSource || !exactAudience) {
      throw new NotFoundException("Задание наёмного водителя не найдено");
    }

    Worker worker =
        (lockWorker ? workers.findByIdForUpdate(workerId) : workers.findById(workerId))
            .orElseThrow(() -> new NotFoundException("Задание наёмного водителя не найдено"));
    if (worker.getEmploymentType() != WorkerEmploymentType.CONTRACTOR) {
      throw new ConflictException("Задание назначено штатному водителю");
    }
    if (!worker.isActive()) {
      throw new ConflictException("Наёмный водитель неактивен");
    }

    List<QueueEntry> route = entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId());
    if (route.isEmpty()
        || route.stream()
            .anyMatch(
                candidate ->
                    candidate.getQueue() == null
                        || candidate.getQueue().getPurpose() != QueuePurpose.LOGISTICS_DRIVER)) {
      throw new NotFoundException("Задание наёмного водителя не найдено");
    }
    if (entryId != null && route.stream().noneMatch(candidate -> entryId.equals(candidate.getId()))) {
      throw new NotFoundException("Этап задания наёмного водителя не найден");
    }
    return new ContractorExecutionContext(task, worker, source, List.copyOf(route));
  }

  private QueueEntry requireEntry(ContractorExecutionContext context, UUID entryId) {
    return context.route().stream()
        .filter(candidate -> candidate.getId().equals(entryId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Этап задания наёмного водителя не найден"));
  }

  private ContractorTaskExecutionSnapshot snapshot(ContractorExecutionContext context) {
    BoardTask task = context.task();
    List<QueueEntry> route = context.route();
    List<ContractorTaskRouteEntry> routeSnapshots =
        java.util.stream.IntStream.range(0, route.size())
            .mapToObj(
                stepIndex -> {
                  QueueEntry entry = route.get(stepIndex);
                  var content =
                      readProjections.workerContent(task.getWarehouseId(), entry.getId());
                  return new ContractorTaskRouteEntry(
                      entry.getId(),
                      entry.getVersion(),
                      entry.getRouteIndex(),
                      stepIndex,
                      route.size(),
                      entry.getQueue().getName(),
                      entry.getTaskText(),
                      entry.getStatus(),
                      entry.getPlannedDurationMinutes(),
                      content.works(),
                      content.materials(),
                      content.comments(),
                      content.sourceMedia(),
                      entry.getQueue().getResultPhotoMinCount(),
                      completionEvidence.contractorEvidence(
                          entry.getId(), context.worker().getId()),
                      completionAllowed(entry, context.worker().getId()));
                })
            .toList();
    return new ContractorTaskExecutionSnapshot(
        context.worker().getId(),
        task.getExternalTaskId(),
        task.getId(),
        task.getVersion(),
        task.getWarehouseId(),
        task.getTitle(),
        task.getDescription(),
        task.getUnitNumber(),
        task.getScheduledDate(),
        task.getDeadlineAt(),
        task.getPriority(),
        task.getStatus(),
        new TaskSourceReferenceDto(
            context.source().getSourceType(), context.source().getSourceId()),
        routeSnapshots);
  }

  private boolean completionAllowed(QueueEntry entry, UUID workerId) {
    if (entry.getStatus() != EntryStatus.IN_PROGRESS) return false;
    boolean assigned =
        assignments.findAllByQueueEntryIdAndStatusIn(entry.getId(), LIVE_ASSIGNMENTS).stream()
            .anyMatch(
                assignment ->
                    assignment.getWorker() != null
                        && workerId.equals(assignment.getWorker().getId()));
    if (!assigned) return false;
    int requiredEvidence = Math.max(1, entry.getQueue().getResultPhotoMinCount());
    return completionEvidence.readyCount(entry.getId(), workerId) >= requiredEvidence;
  }

  /** Exact task, contractor and source proof retained while one transaction builds a response. */
  private record ContractorExecutionContext(
      BoardTask task, Worker worker, TaskSyncSource source, List<QueueEntry> route) {}
}
