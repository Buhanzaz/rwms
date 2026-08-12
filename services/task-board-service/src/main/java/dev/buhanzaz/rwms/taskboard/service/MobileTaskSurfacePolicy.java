package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.BoardEntryDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueBindingDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueDto;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerAction;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerAssignmentSnapshot;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Defines the non-overlapping queue and action capabilities of DriverApp and WorkerApp.
 *
 * <p>The policy is applied to context, feed, detail and commands so a client cannot recover a
 * removed capability by calling a route directly. Driver-logistics secondary bindings are treated
 * as required slinger bindings even while an older stored projection is being upgraded.
 */
@Component
public class MobileTaskSurfacePolicy {

  /** Returns whether a queue has a binding usable by the authenticated surface and worker classes. */
  public boolean includesQueue(
      MobileTaskSurface surface, WorkQueueDto queue, Set<UUID> workerClassIds) {
    return queue.bindings().stream()
        .anyMatch(binding -> includesBinding(surface, queue, binding, workerClassIds));
  }

  /** Returns the effective matching bindings exposed in a surface-specific task category. */
  public List<QueueBindingDto> bindings(
      MobileTaskSurface surface, WorkQueueDto queue, Set<UUID> workerClassIds) {
    return queue.bindings().stream()
        .filter(binding -> includesBinding(surface, queue, binding, workerClassIds))
        .map(binding -> effectiveBinding(queue, binding))
        .toList();
  }

  /**
   * Returns whether an entry belongs in a feed after the lower-level worker audience check.
   * WorkerApp never receives a waiting or historical driver task.
   */
  public boolean includesFeedEntry(
      MobileTaskSurface surface, WorkQueueDto queue, BoardEntryDto entry) {
    if (surface == MobileTaskSurface.WORKER
        && queue.purpose() == QueuePurpose.LOGISTICS_DRIVER) {
      return entry.status() == EntryStatus.IN_PROGRESS || entry.status() == EntryStatus.PAUSED;
    }
    return true;
  }

  /** Rejects a direct detail lookup that is outside the selected native-client capability. */
  public void requireDetailVisible(
      MobileTaskSurface surface, WorkQueueDto queue, BoardEntryDto entry, UUID workerId) {
    if (surface != MobileTaskSurface.WORKER
        || queue.purpose() != QueuePurpose.LOGISTICS_DRIVER) {
      return;
    }
    if (entry.status() == EntryStatus.IN_PROGRESS || entry.status() == EntryStatus.PAUSED) {
      return;
    }
    boolean previouslyAssigned =
        entry.assignments().stream()
            .anyMatch(assignment -> workerId.equals(assignment.workerId()));
    if (!previouslyAssigned) {
      throw new NotFoundException("Задание не найдено");
    }
  }

  /** Enforces that driver and slinger transition families cannot be crossed by a crafted request. */
  public void requireActionAllowed(
      MobileTaskSurface surface, QueuePurpose purpose, WorkerAction action) {
    if (surface == MobileTaskSurface.DRIVER && action == WorkerAction.JOIN) {
      throw new ConflictException("Водитель не может присоединяться как вторичный исполнитель");
    }
    if (surface == MobileTaskSurface.WORKER
        && purpose == QueuePurpose.LOGISTICS_DRIVER
        && action == WorkerAction.TAKE) {
      throw new ConflictException("Водительское задание сначала должен взять водитель");
    }
  }

  /** Returns whether a live logistics assignment belongs to the selected native role. */
  public boolean isActiveParticipant(
      MobileTaskSurface surface,
      QueuePurpose purpose,
      UUID workerId,
      List<WorkerAssignmentSnapshot> assignments) {
    return assignments.stream()
        .filter(assignment -> workerId.equals(assignment.workerId()))
        .filter(
            assignment ->
                "ACTIVE".equals(assignment.status()) || "PAUSED".equals(assignment.status()))
        .anyMatch(
            assignment ->
                purpose != QueuePurpose.LOGISTICS_DRIVER
                    || (surface == MobileTaskSurface.DRIVER)
                        == (assignment.workerGroupId() == null));
  }

  /** Rejects a crafted command or evidence reservation from the other native role. */
  public void requireActiveParticipant(
      MobileTaskSurface surface,
      QueuePurpose purpose,
      UUID workerId,
      List<WorkerAssignmentSnapshot> assignments) {
    if (purpose == QueuePurpose.LOGISTICS_DRIVER
        && !isActiveParticipant(surface, purpose, workerId, assignments)) {
      throw new ConflictException(
          surface == MobileTaskSurface.DRIVER
              ? "Действие доступно только назначенному водителю"
              : "Сначала присоединитесь к заданию как стропальщик");
    }
  }

  /** Returns the completion photo minimum, including the invariant for driver logistics. */
  public int resultPhotoMinimum(WorkQueueDto queue) {
    return queue.purpose() == QueuePurpose.LOGISTICS_DRIVER
        ? Math.max(1, queue.resultPhotoMinCount())
        : queue.resultPhotoMinCount();
  }

  private boolean includesBinding(
      MobileTaskSurface surface,
      WorkQueueDto queue,
      QueueBindingDto binding,
      Set<UUID> workerClassIds) {
    if (!workerClassIds.contains(binding.workerClass().id())) return false;
    if (surface == MobileTaskSurface.DRIVER) return binding.primary();
    return queue.purpose() != QueuePurpose.LOGISTICS_DRIVER || !binding.primary();
  }

  private QueueBindingDto effectiveBinding(WorkQueueDto queue, QueueBindingDto binding) {
    if (queue.purpose() != QueuePurpose.LOGISTICS_DRIVER || binding.primary()) return binding;
    return new QueueBindingDto(
        binding.id(),
        binding.version(),
        binding.workerClass(),
        binding.order(),
        false,
        true,
        ParticipationPolicy.REQUIRED,
        true);
  }
}
