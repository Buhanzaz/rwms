package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.PauseOrigin;
import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.domain.TimeEventType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class TaskBoardEventPayloads {
  public record WorkerClassFact(UUID workerClassId, UUID revisionMarker, String code,
      int sortOrder, boolean active, boolean deleted) {}

  public record QualificationFact(UUID assignmentId, long version, UUID workerClassId, boolean active) {}

  public record WorkerFact(UUID workerId, UUID warehouseId, boolean active, UUID profileRevision,
      List<QualificationFact> qualifications, boolean deleted) {
    public WorkerFact {
      qualifications = qualifications.stream()
          .sorted(java.util.Comparator.comparing(QualificationFact::assignmentId))
          .toList();
    }
  }

  public record GroupMemberFact(UUID membershipId, long version, UUID workerId, boolean active) {}

  public record WorkerGroupFact(UUID workerGroupId, UUID revisionMarker, UUID warehouseId,
      UUID workerClassId, boolean active,
      List<GroupMemberFact> members, boolean deleted) {
    public WorkerGroupFact {
      members = List.copyOf(members);
    }
  }

  public record QueueBindingFact(UUID bindingId, long version, UUID workerClassId,
      boolean stopTaskOnTake) {}

  public record WorkQueueFact(UUID workQueueId, UUID revisionMarker, UUID warehouseId, String code,
      QueueType queueType, int sortOrder, boolean active, boolean hidden,
      boolean collapsed, Integer holdingPeriodMinutes, Integer notificationThreshold,
      boolean notifyWhenThresholdReached, List<QueueBindingFact> classBindings, boolean deleted) {
    public WorkQueueFact {
      classBindings = List.copyOf(classBindings);
    }
  }

  public record QueueUsageReferenceFact(UUID queueUsageReferenceId, UUID revisionMarker,
      UUID queueId, QueueReferenceType referenceType, String externalReferenceHash, boolean deleted) {}

  public record BoardTaskFact(UUID boardTaskId, UUID warehouseId, UUID externalTaskId,
      TaskStatus status, Integer plannedDurationMinutes, OffsetDateTime deadlineAt,
      OffsetDateTime doneAt, boolean deleted) {}

  public record AssignmentFact(UUID assignmentId, long version, UUID workerGroupId, UUID workerId,
      AssignmentStatus status, OffsetDateTime assignedAt, OffsetDateTime startedAt,
      OffsetDateTime pausedAt, OffsetDateTime finishedAt) {}

  public record TimeEventFact(UUID timeEventId, long version, UUID workerId, TimeEventType eventType,
      OffsetDateTime createdAt, UUID relatedEntryId) {}

  public record InterruptionFact(UUID interruptionId, long version, UUID workerId, UUID interruptedEntryId,
      UUID interruptingEntryId, boolean active, OffsetDateTime createdAt, OffsetDateTime resolvedAt) {}

  public record QueueEntryFact(UUID queueEntryId, UUID taskId, UUID queueId, String queueCode,
      int routeIndex, int queuePosition, EntryType entryType, EntryStatus status,
      Integer plannedDurationMinutes, OffsetDateTime activeStartedAt, OffsetDateTime pausedAt,
      OffsetDateTime doneAt, long activeWorkSeconds, PauseOrigin pauseOrigin,
      List<AssignmentFact> assignments, List<TimeEventFact> timeEvents,
      List<InterruptionFact> interruptions, boolean deleted) {
    public QueueEntryFact {
      assignments = List.copyOf(assignments);
      timeEvents = List.copyOf(timeEvents);
      interruptions = List.copyOf(interruptions);
    }
  }

  private TaskBoardEventPayloads() {}
}
