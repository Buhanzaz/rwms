package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QualificationDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerGroupDto;

import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves the authoritative worker, group, qualification and queue audience of native task
 * surfaces.
 *
 * <p>Worker feed/detail checks and task-entry media proofs use this same resolver so a worker who
 * can open a task receives read-only media access without becoming an authorized result uploader.
 */
@Service
class WorkerTaskAccessService {
  private final WorkforceService workforce;
  private final RegistryService registry;
  private final MobileTaskSurfacePolicy surfacePolicy;
  private final DriverTaskAudienceService driverAudiences;

  WorkerTaskAccessService(
      WorkforceService workforce,
      RegistryService registry,
      MobileTaskSurfacePolicy surfacePolicy,
      DriverTaskAudienceService driverAudiences) {
    this.workforce = workforce;
    this.registry = registry;
    this.surfacePolicy = surfacePolicy;
    this.driverAudiences = driverAudiences;
  }

  /** Returns the active worker's exact categories and class-bearing groups for one native app. */
  WorkerAccess require(MobileTaskSurface surface, UUID workerId, UUID warehouseId) {
    List<WorkerDto> workers = workforce.listWorkers(warehouseId);
    WorkerDto worker =
        workers.stream()
            .filter(candidate -> candidate.id().equals(workerId) && candidate.active())
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Рабочий не найден"));
    List<WorkerGroupDto> groups = groupsFor(workerId, workforce.listGroups(warehouseId));
    List<QualificationDto> qualifications = activeQualifications(worker);
    return new WorkerAccess(
        worker,
        groups,
        qualifications,
        categoriesFor(surface, warehouseId, groups, qualifications));
  }

  /**
   * Returns every active worker who can open the entry on WorkerApp or DriverApp right now.
   *
   * <p>Ordinary entries inherit their visible queue audience. Driver-logistics entries additionally
   * apply the planned-driver and secondary-slinger rules used by native detail reads.
   */
  List<UUID> readerWorkerIds(QueueEntry entry) {
    if (entry.getQueue() == null) return List.of();
    UUID warehouseId = entry.getTask().getWarehouseId();
    WorkQueueDto queue =
        registry.listQueues(warehouseId).stream()
            .filter(candidate -> candidate.id().equals(entry.getQueue().getId()))
            .filter(candidate -> candidate.active() && !candidate.hidden())
            .findFirst()
            .orElse(null);
    if (queue == null) return List.of();

    List<WorkerGroupDto> warehouseGroups = workforce.listGroups(warehouseId);
    List<UUID> result = new ArrayList<>();
    for (WorkerDto worker : workforce.listWorkers(warehouseId)) {
      if (!worker.active()) continue;
      List<WorkerGroupDto> groups = groupsFor(worker.id(), warehouseGroups);
      List<QualificationDto> qualifications = activeQualifications(worker);
      Set<UUID> classIds = classIds(groups, qualifications);
      boolean workerSurface =
          surfacePolicy.includesQueue(MobileTaskSurface.WORKER, queue, classIds);
      boolean driverSurface =
          surfacePolicy.includesQueue(MobileTaskSurface.DRIVER, queue, classIds);
      if (queue.purpose() == QueuePurpose.LOGISTICS_DRIVER) {
        workerSurface =
            workerSurface && driverAudiences.isVisibleToMobileWorker(entry, worker.id());
        driverSurface = driverSurface && driverAudiences.isVisibleTo(entry, worker.id());
      }
      if (workerSurface || driverSurface) result.add(worker.id());
    }
    return result.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
  }

  private List<WorkerGroupDto> groupsFor(UUID workerId, List<WorkerGroupDto> groups) {
    return groups.stream()
        .filter(WorkerGroupDto::active)
        .filter(
            group ->
                group.members().stream()
                    .anyMatch(member -> member.active() && member.workerId().equals(workerId)))
        .toList();
  }

  private List<QualificationDto> activeQualifications(WorkerDto worker) {
    return worker.qualifications().stream().filter(QualificationDto::active).toList();
  }

  private List<WorkQueueDto> categoriesFor(
      MobileTaskSurface surface,
      UUID warehouseId,
      List<WorkerGroupDto> groups,
      List<QualificationDto> qualifications) {
    Set<UUID> classIds = classIds(groups, qualifications);
    return registry.listQueues(warehouseId).stream()
        .filter(queue -> queue.active() && !queue.hidden())
        .filter(queue -> surfacePolicy.includesQueue(surface, queue, classIds))
        .sorted(Comparator.comparingInt(WorkQueueDto::sortOrder))
        .toList();
  }

  private Set<UUID> classIds(
      List<WorkerGroupDto> groups, List<QualificationDto> qualifications) {
    Set<UUID> result = new LinkedHashSet<>();
    qualifications.forEach(value -> result.add(value.workerClass().id()));
    groups.forEach(value -> result.add(value.workerClass().id()));
    return result;
  }

  /** Immutable access snapshot consumed by the native worker task facade. */
  record WorkerAccess(
      WorkerDto worker,
      List<WorkerGroupDto> groups,
      List<QualificationDto> qualifications,
      List<WorkQueueDto> categories) {}
}
