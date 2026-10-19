package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.CreateWorkerOperationalAssignmentRequest;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.WorkerOperationalAssignmentResponse;

import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerEmploymentType;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignment;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignmentMode;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignmentStatus;
import dev.buhanzaz.rwms.taskboard.repository.WorkerOperationalAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns transfer-backed operational warehouse assignment commands.
 *
 * <p>Every assignment command locks the worker row before checking assignment history, so two
 * transfers cannot reserve overlapping operational intervals for the same driver.
 */
@Service
@RequiredArgsConstructor
public class WorkerOperationalAssignmentService {
  private final WorkerRepository workers;
  private final WorkerOperationalAssignmentRepository assignments;
  private final WorkerOperationalAvailabilityPolicy availability =
      new WorkerOperationalAvailabilityPolicy();

  /**
   * Creates a planned transfer assignment idempotently by transfer and worker identity.
   *
   * <p>The worker's home warehouse is only snapshotted; it is never overwritten.
   */
  @Transactional
  public WorkerOperationalAssignmentResponse create(
      CreateWorkerOperationalAssignmentRequest request, String actor) {
    validateAssignmentDefinition(request);
    Worker worker =
        workers
            .findByIdForUpdate(request.workerId())
            .orElseThrow(() -> new NotFoundException("Водитель не найден"));
    WorkerOperationalAssignment replay =
        assignments.findByTransferIdAndWorkerId(request.transferId(), request.workerId()).orElse(null);
    if (replay != null) {
      if (!replay.hasDefinition(
          request.sourceWarehouseId(),
          request.destinationWarehouseId(),
          request.mode(),
          request.travelStartsAt(),
          request.effectiveFrom(),
          request.effectiveUntil())) {
        throw new ConflictException(
            "Перемещение уже связано с другим оперативным назначением водителя");
      }
      return response(replay);
    }

    ensureEligibleDriver(worker);
    List<WorkerOperationalAssignment> history =
        assignments.findAllByWorkerIdOrderByEffectiveFromAscCreatedAtAscIdAsc(worker.getId());
    boolean overlaps =
        history.stream()
            .filter(WorkerOperationalAssignment::isNonTerminal)
            .anyMatch(
                assignment ->
                    assignment.overlaps(request.travelStartsAt(), request.effectiveUntil()));
    if (overlaps) {
      throw new ConflictException(
          "Водитель уже имеет пересекающееся оперативное назначение");
    }

    var current = availability.resolve(worker, history, request.travelStartsAt());
    if (!current.available()
        || !current.warehouseId().equals(request.sourceWarehouseId())) {
      throw new ConflictException(
          "Водитель недоступен на указанном складе отправления к началу назначения");
    }

    OffsetDateTime now = now();
    WorkerOperationalAssignment assignment =
        WorkerOperationalAssignment.planned(
            worker,
            request.transferId(),
            request.sourceWarehouseId(),
            request.destinationWarehouseId(),
            request.mode(),
            request.travelStartsAt(),
            request.effectiveFrom(),
            request.effectiveUntil(),
            now,
            actor);
    return response(assignments.saveAndFlush(assignment));
  }

  /** Returns one operational assignment by its task-board identity. */
  @Transactional(readOnly = true)
  public WorkerOperationalAssignmentResponse get(UUID assignmentId) {
    return response(requireAssignment(assignmentId));
  }

  /** Lists assignment history by exactly one transfer or worker filter. */
  @Transactional(readOnly = true)
  public List<WorkerOperationalAssignmentResponse> list(UUID transferId, UUID workerId) {
    if ((transferId == null) == (workerId == null)) {
      throw new IllegalArgumentException("Укажите ровно один фильтр transferId или workerId");
    }
    List<WorkerOperationalAssignment> result =
        transferId != null
            ? assignments.findAllByTransferIdOrderByCreatedAtAscIdAsc(transferId)
            : assignments.findAllByWorkerIdOrderByEffectiveFromAscCreatedAtAscIdAsc(workerId);
    return result.stream().map(WorkerOperationalAssignmentService::response).toList();
  }

  /** Applies one expected-version lifecycle transition and safely replays the current target. */
  @Transactional
  public WorkerOperationalAssignmentResponse transition(
      UUID assignmentId,
      long expectedVersion,
      WorkerOperationalAssignmentStatus target,
      String actor) {
    WorkerOperationalAssignment assignment =
        assignments
            .findByIdForUpdate(assignmentId)
            .orElseThrow(() -> new NotFoundException("Оперативное назначение не найдено"));
    if (assignment.getStatus() == target) {
      return response(assignment);
    }
    if (assignment.getVersion() != expectedVersion) {
      throw new ConflictException(
          "Версия оперативного назначения устарела: ожидалась "
              + expectedVersion
              + ", текущая "
              + assignment.getVersion());
    }
    workers
        .findByIdForUpdate(assignment.getWorker().getId())
        .orElseThrow(() -> new NotFoundException("Водитель не найден"));
    OffsetDateTime transitionAt = now();
    if (target == WorkerOperationalAssignmentStatus.COMPLETED
        && assignment.getMode() != WorkerOperationalAssignmentMode.TRIP_ONLY
        && transitionAt.isBefore(assignment.getEffectiveFrom())) {
      throw new ConflictException(
          "Нельзя завершить оперативное назначение до начала его доступности");
    }
    try {
      assignment.transitionTo(target, transitionAt, actor);
    } catch (IllegalStateException exception) {
      throw new ConflictException(exception.getMessage());
    }
    return response(assignments.saveAndFlush(assignment));
  }

  private void ensureEligibleDriver(Worker worker) {
    boolean eligible =
        workers
            .findAllActiveLogisticsDrivers(
                WorkerEmploymentType.STAFF,
                WorkerEmploymentType.CONTRACTOR,
                QueuePurpose.LOGISTICS_DRIVER,
                ParticipationPolicy.PRIMARY)
            .stream()
            .anyMatch(candidate -> candidate.getId().equals(worker.getId()));
    if (!eligible) {
      throw new ConflictException("Рабочий не является доступным логистическим водителем");
    }
  }

  private WorkerOperationalAssignment requireAssignment(UUID assignmentId) {
    return assignments
        .findById(assignmentId)
        .orElseThrow(() -> new NotFoundException("Оперативное назначение не найдено"));
  }

  private static void validateAssignmentDefinition(
      CreateWorkerOperationalAssignmentRequest request) {
    if (request.sourceWarehouseId().equals(request.destinationWarehouseId())) {
      throw new IllegalArgumentException("Склад отправления совпадает со складом назначения");
    }
    if (!request.travelStartsAt().isBefore(request.effectiveFrom())) {
      throw new IllegalArgumentException(
          "Начало дороги должно быть раньше доступности после прибытия");
    }
    if (request.mode() == WorkerOperationalAssignmentMode.TEMPORARY
        && (request.effectiveUntil() == null
            || !request.effectiveUntil().isAfter(request.effectiveFrom()))) {
      throw new IllegalArgumentException(
          "Временное назначение должно заканчиваться после начала");
    }
    if (request.mode() == WorkerOperationalAssignmentMode.PERMANENT
        && request.effectiveUntil() != null) {
      throw new IllegalArgumentException("Постоянное назначение не может иметь дату окончания");
    }
    if (request.mode() == WorkerOperationalAssignmentMode.TRIP_ONLY
        && (request.effectiveUntil() == null
            || !request.effectiveUntil().isEqual(request.effectiveFrom()))) {
      throw new IllegalArgumentException(
          "Разовый рейс должен завершаться в момент окончания поездки");
    }
  }

  private static WorkerOperationalAssignmentResponse response(
      WorkerOperationalAssignment assignment) {
    return new WorkerOperationalAssignmentResponse(
        assignment.getId(),
        assignment.getVersion(),
        assignment.getTransferId(),
        assignment.getWorker().getId(),
        assignment.getHomeWarehouseId(),
        assignment.getSourceWarehouseId(),
        assignment.getDestinationWarehouseId(),
        assignment.getMode(),
        assignment.getStatus(),
        assignment.getTravelStartsAt(),
        assignment.getEffectiveFrom(),
        assignment.getEffectiveUntil(),
        assignment.getCreatedAt(),
        assignment.getUpdatedAt(),
        assignment.getCreatedBy(),
        assignment.getUpdatedBy());
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
