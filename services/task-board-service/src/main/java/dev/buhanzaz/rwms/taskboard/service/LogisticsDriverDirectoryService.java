package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAvailabilityKind;
import dev.buhanzaz.rwms.taskboard.api.LogisticsDriverIdentityResponse;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerEmploymentType;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignment;
import dev.buhanzaz.rwms.taskboard.repository.WorkerOperationalAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the least-privilege logistics driver directory derived from task-board qualifications.
 *
 * <p>The directory never exposes credentials, login, groups, or ordinary queue memberships.
 * Driver eligibility and operational warehouse placement stay task-board-owned and are recalculated
 * for every read.
 */
@Service
@RequiredArgsConstructor
public class LogisticsDriverDirectoryService {
  private final WorkerRepository workers;
  private final WorkerOperationalAssignmentRepository assignments;
  private final WorkerOperationalAvailabilityPolicy availability =
      new WorkerOperationalAvailabilityPolicy();

  /** Returns currently available qualified drivers in canonical display-name and UUID order. */
  @Transactional(readOnly = true)
  public List<LogisticsDriverIdentityResponse> list(UUID warehouseId) {
    return list(warehouseId, OffsetDateTime.now(ZoneOffset.UTC), false);
  }

  /**
   * Returns drivers operationally available at one instant, optionally including planned arrivals
   * whose buffered availability has already begun.
   */
  @Transactional(readOnly = true)
  public List<LogisticsDriverIdentityResponse> list(
      UUID warehouseId, OffsetDateTime at, boolean includeIncoming) {
    Objects.requireNonNull(warehouseId, "warehouseId");
    OffsetDateTime moment = at == null ? OffsetDateTime.now(ZoneOffset.UTC) : at;
    List<Worker> candidates =
        workers.findAllActiveLogisticsDrivers(
            WorkerEmploymentType.STAFF,
            WorkerEmploymentType.CONTRACTOR,
            QueuePurpose.LOGISTICS_DRIVER,
            ParticipationPolicy.PRIMARY);
    Map<UUID, List<WorkerOperationalAssignment>> byWorker = historyByWorker(candidates);

    return candidates.stream()
        .map(
            worker ->
                project(
                    worker,
                    byWorker.getOrDefault(worker.getId(), List.of()),
                    warehouseId,
                    moment,
                    includeIncoming))
        .filter(Objects::nonNull)
        .sorted(
            Comparator.comparing(
                    LogisticsDriverIdentityResponse::displayName,
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing(LogisticsDriverIdentityResponse::workerId))
        .toList();
  }

  private LogisticsDriverIdentityResponse project(
      Worker worker,
      List<WorkerOperationalAssignment> history,
      UUID warehouseId,
      OffsetDateTime at,
      boolean includeIncoming) {
    var current = availability.resolve(worker, history, at);
    if (current.available()
        && current.warehouseId().equals(warehouseId)
        && worker.contractCovers(at)) {
      return response(
          worker,
          warehouseId,
          later(current.availableFrom(), worker.getContractAvailableFrom()),
          earlier(current.availableUntil(), worker.getContractAvailableUntil()),
          current.kind());
    }
    if (!includeIncoming || !worker.contractCovers(at)) {
      return null;
    }
    return history.stream()
        .filter(assignment -> assignment.getDestinationWarehouseId().equals(warehouseId))
        .filter(assignment -> availability.incomingCovers(assignment, at))
        .min(Comparator.comparing(WorkerOperationalAssignment::getEffectiveFrom))
        .map(
            assignment ->
                response(
                    worker,
                    warehouseId,
                    later(assignment.getEffectiveFrom(), worker.getContractAvailableFrom()),
                    earlier(assignment.getEffectiveUntil(), worker.getContractAvailableUntil()),
                    LogisticsDriverAvailabilityKind.INCOMING))
        .orElse(null);
  }

  private Map<UUID, List<WorkerOperationalAssignment>> historyByWorker(List<Worker> workers) {
    if (workers.isEmpty()) {
      return Map.of();
    }
    var result = new HashMap<UUID, List<WorkerOperationalAssignment>>();
    assignments
        .findAllByWorkerIdIn(workers.stream().map(Worker::getId).toList())
        .forEach(
            assignment ->
                result
                    .computeIfAbsent(
                        assignment.getWorker().getId(), ignored -> new ArrayList<>())
                    .add(assignment));
    return result;
  }

  private LogisticsDriverIdentityResponse response(
      Worker worker,
      UUID warehouseId,
      OffsetDateTime availableFrom,
      OffsetDateTime availableUntil,
      LogisticsDriverAvailabilityKind kind) {
    return new LogisticsDriverIdentityResponse(
        worker.getId(),
        worker.getDisplayName(),
        worker.getEmploymentType(),
        worker.getPhone(),
        warehouseId,
        availableFrom,
        availableUntil,
        kind);
  }

  private OffsetDateTime later(OffsetDateTime first, OffsetDateTime second) {
    if (first == null) return second;
    if (second == null) return first;
    return first.isAfter(second) ? first : second;
  }

  private OffsetDateTime earlier(OffsetDateTime first, OffsetDateTime second) {
    if (first == null) return second;
    if (second == null) return first;
    return first.isBefore(second) ? first : second;
  }
}
