package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.GroupOperationalStatus;
import dev.buhanzaz.rwms.taskboard.domain.GroupKpiOpenState;
import dev.buhanzaz.rwms.taskboard.domain.PauseOrigin;
import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.TimeEventType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Framework-local immutable payload records for the canonical task-board event families. */
public final class TaskBoardEventPayloads {
  public record WorkerClassFact(
      UUID workerClassId, UUID revisionMarker, int sortOrder, boolean active, boolean deleted) {}

  public record QualificationFact(UUID assignmentId, long version, UUID workerClassId, boolean active) {}

  public record WorkerFact(
      UUID workerId,
      UUID warehouseId,
      boolean active,
      UUID profileRevision,
      UUID currentGroupId,
      List<QualificationFact> qualifications,
      boolean deleted) {
    public WorkerFact(
        UUID workerId,
        UUID warehouseId,
        boolean active,
        UUID profileRevision,
        List<QualificationFact> qualifications,
        boolean deleted) {
      this(
          workerId,
          warehouseId,
          active,
          profileRevision,
          null,
          qualifications,
          deleted);
    }

    public WorkerFact {
      qualifications = qualifications.stream()
          .sorted(java.util.Comparator.comparing(QualificationFact::assignmentId))
          .toList();
    }
  }

  public record GroupMemberFact(UUID membershipId, long version, UUID workerId, boolean active) {}

  public record WorkerGroupFact(
      UUID workerGroupId,
      UUID revisionMarker,
      UUID warehouseId,
      UUID workerClassId,
      boolean active,
      GroupOperationalStatus operationalStatus,
      List<GroupMemberFact> members,
      boolean deleted) {
    public WorkerGroupFact(
        UUID workerGroupId,
        UUID revisionMarker,
        UUID warehouseId,
        UUID workerClassId,
        boolean active,
        List<GroupMemberFact> members,
        boolean deleted) {
      this(
          workerGroupId,
          revisionMarker,
          warehouseId,
          workerClassId,
          active,
          GroupOperationalStatus.AVAILABLE,
          members,
          deleted);
    }

    public WorkerGroupFact {
      members = List.copyOf(members);
    }
  }

  public record QueueBindingFact(
      UUID bindingId,
      long version,
      UUID workerClassId,
      int bindingOrder,
      boolean stopTaskOnTake,
      ParticipationPolicy participationPolicy,
      boolean notifyOnPrimaryTake) {}

  public record WorkQueueFact(
      UUID workQueueId,
      UUID revisionMarker,
      UUID warehouseId,
      UUID queueDefinitionId,
      QueueType queueType, QueuePurpose queuePurpose, int sortOrder, boolean active, boolean hidden,
      boolean collapsed, Integer holdingPeriodMinutes, Integer notificationThreshold,
      boolean notifyWhenThresholdReached, int resultPhotoMinCount,
      List<QueueBindingFact> classBindings, boolean deleted) {
    public WorkQueueFact {
      classBindings = List.copyOf(classBindings);
    }
  }

  public record QueueUsageReferenceFact(UUID queueUsageReferenceId, UUID revisionMarker,
      UUID queueId, QueueReferenceType referenceType, String externalReferenceHash, boolean deleted) {}

  public record BoardTaskFact(UUID boardTaskId, UUID warehouseId, UUID externalTaskId,
      TaskStatus status, LocalDate scheduledDate, TaskLane lane, int priority, boolean pinned,
      Integer plannedDurationMinutes, OffsetDateTime deadlineAt, OffsetDateTime doneAt,
      boolean deleted) {
    public BoardTaskFact {
      if (scheduledDate == null) {
        throw new IllegalArgumentException("Task schedule date is required");
      }
      if (priority < 1 || priority > 5) {
        throw new IllegalArgumentException("Task priority must be between 1 and 5");
      }
    }
  }

  public record AssignmentFact(UUID assignmentId, long version, UUID workerGroupId, UUID workerId,
      AssignmentStatus status, OffsetDateTime assignedAt, OffsetDateTime startedAt,
      OffsetDateTime pausedAt, OffsetDateTime finishedAt) {}

  public record TimeEventFact(UUID timeEventId, long version, UUID workerId, TimeEventType eventType,
      OffsetDateTime createdAt, UUID relatedEntryId) {}

  public record InterruptionFact(UUID interruptionId, long version, UUID workerId, UUID interruptedEntryId,
      UUID interruptingEntryId, boolean active, OffsetDateTime createdAt, OffsetDateTime resolvedAt) {}

  public record QueueEntryFact(UUID queueEntryId, UUID taskId, UUID queueId,
      int routeIndex, int queuePosition, EntryType entryType, EntryStatus status,
      Integer plannedDurationMinutes, OffsetDateTime activeStartedAt, OffsetDateTime pausedAt,
      OffsetDateTime doneAt, long activeWorkSeconds, Long originalBudgetSeconds,
      Long currentBudgetSeconds, PauseOrigin pauseOrigin,
      List<AssignmentFact> assignments, List<TimeEventFact> timeEvents,
      List<InterruptionFact> interruptions, boolean deleted) {
    public QueueEntryFact {
      assignments = List.copyOf(assignments);
      timeEvents = List.copyOf(timeEvents);
      interruptions = List.copyOf(interruptions);
    }
  }

  public record OwnerMediaReferenceFact(UUID mediaId, long generation) {}

  public record EntryOwnerProofFact(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      int routeIndex,
      boolean active,
      List<UUID> allowedWorkerIds,
      List<OwnerMediaReferenceFact> sourceMediaReferences) {
    public EntryOwnerProofFact {
      allowedWorkerIds =
          allowedWorkerIds.stream()
              .distinct()
              .sorted(java.util.Comparator.comparing(UUID::toString))
              .toList();
      sourceMediaReferences =
          sourceMediaReferences.stream()
              .distinct()
              .sorted(java.util.Comparator.comparing(value -> value.mediaId().toString()))
              .toList();
    }
  }

  public record TaskEvidenceFact(
      UUID evidenceId,
      UUID entryId,
      UUID taskId,
      int routeIndex,
      UUID warehouseId,
      UUID workerId,
      UUID workerGroupId,
      UUID mediaId,
      long mediaGeneration,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt,
      String state,
      String sourceType,
      UUID sourceId) {}

  public record GroupKpiDayFact(
      UUID evidenceId,
      UUID warehouseId,
      UUID workerGroupId,
      LocalDate localDate,
      LocalDate dataAvailableFrom,
      String formulaVersion,
      long completedBudgetSeconds,
      long earnedRemainingSeconds,
      long activeSeconds,
      long penalizedIdleSeconds,
      long completedTaskCount,
      GroupKpiOpenState openState,
      OffsetDateTime openStateStartedAt,
      OffsetDateTime penaltyStartsAt,
      OffsetDateTime nextTransitionAt,
      OffsetDateTime asOf) {}

  private TaskBoardEventPayloads() {}
}
