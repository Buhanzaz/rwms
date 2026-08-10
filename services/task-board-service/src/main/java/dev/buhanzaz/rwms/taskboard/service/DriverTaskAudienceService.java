package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.DriverTaskAudienceDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskSourceReferenceDto;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.mapper.DriverTaskAudienceMapper;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueClassBindingRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Owns validation and worker visibility for logistics driver audiences.
 *
 * <p>The planned worker identifier is an opaque task-board worker identity rather than a foreign
 * key from logistics and exists only for assigned work. A shared audience stays identity-free and
 * visible to qualified drivers until one of them takes the task.
 */
@Service
class DriverTaskAudienceService {
  private static final String LOGISTICS_SOURCE_CLIENT_ID = "logistics-service";
  private static final Set<AssignmentStatus> LIVE_ASSIGNMENTS =
      Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED);
  private static final Set<AssignmentStatus> EXECUTOR_ASSIGNMENTS =
      Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED, AssignmentStatus.DONE);

  private final WorkforceService workforce;
  private final WorkQueueClassBindingRepository bindings;
  private final TaskAssignmentRepository assignments;
  private final DriverTaskAudienceMapper mapper;

  DriverTaskAudienceService(
      WorkforceService workforce,
      WorkQueueClassBindingRepository bindings,
      TaskAssignmentRepository assignments,
      DriverTaskAudienceMapper mapper) {
    this.workforce = workforce;
    this.bindings = bindings;
    this.assignments = assignments;
    this.mapper = mapper;
  }

  /**
   * Resolves the registration default and rejects audience data outside the logistics driver
   * source boundary. Worker display names are deliberately ignored until authoritative validation.
   */
  DriverTaskAudienceDto normalizeRegistration(
      String sourceClientId,
      TaskSourceReferenceDto source,
      DriverTaskAudienceDto requested) {
    boolean logisticsDriverSource =
        LOGISTICS_SOURCE_CLIENT_ID.equals(sourceClientId)
            && source != null
            && source.type() == TaskSourceType.LOGISTICS_DRIVER_TASK;
    if (!logisticsDriverSource) {
      if (requested != null) {
        throw new IllegalArgumentException(
            "Аудитория водителя разрешена только логистическому заданию водителей");
      }
      return null;
    }
    return normalizeShape(
        requested == null
            ? new DriverTaskAudienceDto(
                DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null)
            : requested);
  }

  /** Validates and persists an audience for a newly registered driver task. */
  void applyNewTask(
      BoardTask task, WorkQueue driverQueue, DriverTaskAudienceDto normalizedAudience) {
    if (driverQueue.getPurpose() != QueuePurpose.LOGISTICS_DRIVER
        || normalizedAudience == null) {
      throw new IllegalArgumentException(
          "Аудитория водителя требует логистическую очередь водителей");
    }
    applyValidated(task, driverQueue, normalizedAudience);
  }

  /** Replaces the audience under the caller's existing task-version and transaction fence. */
  void replace(BoardTask task, WorkQueue driverQueue, DriverTaskAudienceDto requested) {
    if (requested == null) return;
    if (task.getDriverAudienceMode() == null
        || driverQueue.getPurpose() != QueuePurpose.LOGISTICS_DRIVER) {
      throw new IllegalArgumentException(
          "Аудиторию можно изменить только у логистического задания водителей");
    }
    applyValidated(task, driverQueue, normalizeShape(requested));
  }

  /**
   * Verifies that a retained audience remains valid when source-owned work moves warehouses.
   * Assigned workers are never silently detached or reinterpreted at the target.
   */
  void requireCompatibleWarehouse(
      UUID targetWarehouseId, WorkQueue targetQueue, DriverTaskAudienceDto audience) {
    if (audience == null) return;
    if (targetQueue.getPurpose() != QueuePurpose.LOGISTICS_DRIVER) {
      throw new ConflictException(
          "Логистическое задание водителей требует очередь водителей целевого склада");
    }
    if (audience.workerId() != null) {
      requireQualifiedDriver(targetWarehouseId, targetQueue, audience.workerId());
    }
  }

  /** Returns the persisted dispatcher-facing audience projection. */
  DriverTaskAudienceDto dto(BoardTask task) {
    return task.getDriverAudienceMode() == null
        ? null
        : mapper.toDto(task);
  }

  /**
   * Tests whether the authenticated worker may discover this driver entry.
   *
   * <p>A shared waiting task is visible to every qualified warehouse driver. Once claimed, only
   * its actual assignee retains access.
   */
  boolean isVisibleTo(QueueEntry entry, UUID workerId) {
    if (entry.getQueue() == null
        || entry.getQueue().getPurpose() != QueuePurpose.LOGISTICS_DRIVER
        || workerId == null) {
      return false;
    }
    BoardTask task = entry.getTask();
    DriverTaskAudienceMode mode = task.getDriverAudienceMode();
    if (mode == null || mode == DriverTaskAudienceMode.UNASSIGNED) return false;
    if (mode == DriverTaskAudienceMode.ASSIGNED_DRIVER) {
      return workerId.equals(task.getPlannedDriverWorkerId())
          && isQualifiedDriver(task.getWarehouseId(), entry.getQueue(), workerId);
    }
    boolean hasLiveAssignment =
        !assignments
            .findAllByQueueEntryIdAndStatusIn(entry.getId(), LIVE_ASSIGNMENTS)
            .isEmpty();
    if (entry.getStatus() == EntryStatus.WAITING && !hasLiveAssignment) {
      return isQualifiedDriver(entry.getTask().getWarehouseId(), entry.getQueue(), workerId);
    }
    return assignments.findAllByQueueEntryIdAndStatusIn(entry.getId(), EXECUTOR_ASSIGNMENTS).stream()
        .anyMatch(
            assignment ->
                assignment.getWorker() != null
                    && workerId.equals(assignment.getWorker().getId()));
  }

  /** Rejects an execution attempt that is outside the persisted driver audience. */
  void requireExecutableBy(QueueEntry entry, Worker worker) {
    if (worker == null
        || !isQualifiedDriver(
            entry.getTask().getWarehouseId(), entry.getQueue(), worker.getId())
        || !isVisibleTo(entry, worker.getId())) {
      throw new ConflictException("Логистическое задание недоступно выбранному водителю");
    }
  }

  /** Stores only an authoritative worker snapshot after all queue and warehouse checks succeed. */
  private void applyValidated(
      BoardTask task, WorkQueue driverQueue, DriverTaskAudienceDto normalizedAudience) {
    UUID workerId = normalizedAudience.workerId();
    String workerName = null;
    if (workerId != null) {
      Worker worker = requireQualifiedDriver(task.getWarehouseId(), driverQueue, workerId);
      workerName = worker.getDisplayName();
    }
    task.setDriverAudienceMode(normalizedAudience.mode());
    task.setPlannedDriverWorkerId(workerId);
    task.setPlannedDriverNameSnapshot(workerName);
  }

  /** Validates mode/identity shape and discards any source-supplied display-name snapshot. */
  private DriverTaskAudienceDto normalizeShape(DriverTaskAudienceDto requested) {
    if (requested == null || requested.mode() == null) {
      throw new IllegalArgumentException("Укажите режим аудитории водителя");
    }
    UUID workerId = requested.workerId();
    if (requested.mode() == DriverTaskAudienceMode.UNASSIGNED && workerId != null) {
      throw new IllegalArgumentException(
          "Неназначенное задание не может содержать ответственного водителя");
    }
    if (requested.mode() == DriverTaskAudienceMode.ASSIGNED_DRIVER && workerId == null) {
      throw new IllegalArgumentException("Для персонального задания выберите водителя");
    }
    if (requested.mode() == DriverTaskAudienceMode.WAREHOUSE_DRIVERS && workerId != null) {
      throw new IllegalArgumentException(
          "Общее задание водителей не может содержать ответственного водителя");
    }
    if (workerId == null
        && requested.workerName() != null
        && !requested.workerName().isBlank()) {
      throw new IllegalArgumentException("Имя водителя нельзя передать без его идентификатора");
    }
    return new DriverTaskAudienceDto(requested.mode(), workerId, null);
  }

  /** Tests current active membership in the queue's primary driver qualification. */
  private boolean isQualifiedDriver(UUID warehouseId, WorkQueue queue, UUID workerId) {
    Worker worker;
    try {
      worker = workforce.requireWorker(warehouseId, workerId);
    } catch (NotFoundException ignored) {
      return false;
    }
    if (!worker.isActive()) return false;
    Set<UUID> activeClassIds =
        workforce.activeQualifications(workerId).stream()
            .map(qualification -> qualification.getWorkerClass().getId())
            .collect(java.util.stream.Collectors.toSet());
    return bindings.findAllByQueueIdOrderByBindingOrderAscIdAsc(queue.getId()).stream()
        .filter(binding -> binding.getBindingOrder() == 0)
        .filter(binding -> binding.getParticipationPolicy() == ParticipationPolicy.PRIMARY)
        .anyMatch(binding -> activeClassIds.contains(binding.getWorkerClass().getId()));
  }

  /** Resolves one active, same-warehouse worker with the queue's primary driver qualification. */
  private Worker requireQualifiedDriver(UUID warehouseId, WorkQueue queue, UUID workerId) {
    Worker worker;
    try {
      worker = workforce.requireWorker(warehouseId, workerId);
    } catch (NotFoundException ignored) {
      throw new ConflictException("Выбранный водитель не относится к складу задания");
    }
    if (!worker.isActive()) {
      throw new ConflictException("Выбранный водитель неактивен");
    }
    if (!isQualifiedDriver(warehouseId, queue, workerId)) {
      throw new ConflictException(
          "Выбранный рабочий не имеет активной квалификации водителя этой очереди");
    }
    return worker;
  }
}
