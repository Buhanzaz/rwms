package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.GroupKpiDayState;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueueUsageReference;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import dev.buhanzaz.rwms.taskboard.domain.WorkerGroup;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.AssignmentFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.BoardTaskFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.GroupMemberFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.GroupKpiDayFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.InterruptionFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.QualificationFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.QueueBindingFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.QueueEntryFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.QueueUsageReferenceFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.TimeEventFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkQueueFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerClassFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerGroupFact;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskAutoInterruptionRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskTimeEventRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueClassBindingRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerClassAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerGroupMemberRepository;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconstructs sanitized fact payloads from authoritative task-board state for baseline and replay.
 *
 * <p>It centralizes payload meaning so live publication and recovery do not produce competing
 * representations of the same aggregate version.
 */
@Service
@RequiredArgsConstructor
public class TaskBoardEventFactFactory {
  private final WorkerClassAssignmentRepository qualifications;
  private final WorkerGroupMemberRepository members;
  private final WorkQueueClassBindingRepository bindings;
  private final TaskAssignmentRepository assignments;
  private final TaskTimeEventRepository timeEvents;
  private final TaskAutoInterruptionRepository interruptions;

  public WorkerClassFact workerClass(WorkerClass value, boolean deleted) {
    return new WorkerClassFact(
        value.getId(), value.getRevisionMarker(), value.getSortOrder(), value.isActive(), deleted);
  }

  @Transactional(readOnly = true)
  public WorkerFact worker(Worker value, boolean deleted) {
    var facts = qualifications.findAllByWorkerId(value.getId()).stream()
        .sorted(Comparator.comparing(q -> q.getId().toString()))
        .map(q -> new QualificationFact(
            q.getId(), q.getVersion(), q.getWorkerClass().getId(), q.isActive()))
        .toList();
    return new WorkerFact(
        value.getId(),
        value.getWarehouseId(),
        value.isActive(),
        value.getRevisionMarker(),
        value.getCurrentGroup() == null ? null : value.getCurrentGroup().getId(),
        facts,
        deleted);
  }

  @Transactional(readOnly = true)
  public WorkerGroupFact workerGroup(WorkerGroup value, boolean deleted) {
    var memberFacts = members.findAllByWorkerGroupId(value.getId()).stream()
        .sorted(Comparator.comparing(m -> m.getId().toString()))
        .map(m -> new GroupMemberFact(
            m.getId(), m.getVersion(), m.getWorker().getId(), m.isActive()))
        .toList();
    return new WorkerGroupFact(
        value.getId(), value.getRevisionMarker(), value.getWarehouseId(),
        value.getWorkerClass().getId(), value.isActive(), value.getOperationalStatus(),
        memberFacts, deleted);
  }

  @Transactional(readOnly = true)
  public WorkQueueFact workQueue(WorkQueue value, boolean deleted) {
    var bindingFacts = bindings.findAllByQueueIdOrderByBindingOrderAscIdAsc(value.getId()).stream()
        .map(binding -> new QueueBindingFact(
            binding.getId(), binding.getVersion(), binding.getWorkerClass().getId(),
            binding.getBindingOrder(), binding.isStopTaskOnTake(),
            binding.getParticipationPolicy(), binding.isNotifyOnPrimaryTake()))
        .toList();
    return new WorkQueueFact(
        value.getId(),
        value.getRevisionMarker(),
        value.getWarehouseId(),
        value.getDefinition().getId(),
        value.getType(),
        value.getPurpose(),
        value.getSortOrder(), value.isActive(), value.isHidden(),
        value.isCollapsed(), value.getHoldingPeriodMinutes(), value.getNotificationThreshold(),
        value.isNotifyWhenThresholdReached(), value.getResultPhotoMinCount(),
        value.getAvailableTaskLimit(), value.isWorkerFeedEnabled(),
        bindingFacts, deleted);
  }

  public QueueUsageReferenceFact queueUsageReference(QueueUsageReference value, boolean deleted) {
    return new QueueUsageReferenceFact(
        value.getId(),
        value.getRevisionMarker(),
        value.getDefinition().getId(),
        value.getReferenceType(),
        TaskBoardEventStore.sha256(
            ("task-board-queue-reference:v1\u0000" + value.getExternalReferenceId())
                .getBytes(StandardCharsets.UTF_8)),
        deleted);
  }

  public BoardTaskFact boardTask(BoardTask value, boolean deleted) {
    return new BoardTaskFact(
        value.getId(), value.getWarehouseId(), value.getExternalTaskId(), value.getStatus(),
        value.getScheduledDate(), value.getLane(), value.getPriority(), value.isPinned(),
        value.getPlannedDurationMinutes(), value.getDeadlineAt(), value.getDoneAt(),
        value.getDriverAudienceMode(), value.getPlannedDriverWorkerId(), deleted);
  }

  @Transactional(readOnly = true)
  public QueueEntryFact queueEntry(QueueEntry value, boolean deleted) {
    var assignmentFacts = assignments.findAllByQueueEntryId(value.getId()).stream()
        .sorted(Comparator.comparing(assignment -> assignment.getId().toString()))
        .map(assignment -> new AssignmentFact(
            assignment.getId(),
            assignment.getVersion(),
            assignment.getWorkerGroup() == null ? null : assignment.getWorkerGroup().getId(),
            assignment.getWorker() == null ? null : assignment.getWorker().getId(),
            assignment.getStatus(),
            assignment.getAssignedAt(),
            assignment.getStartedAt(),
            assignment.getPausedAt(),
            assignment.getFinishedAt()))
        .toList();
    var timeEventFacts = timeEvents.findAllByQueueEntryIdOrderByCreatedAtAsc(value.getId()).stream()
        .sorted(Comparator.comparing(event -> event.getId().toString()))
        .map(event -> new TimeEventFact(
            event.getId(), event.getVersion(),
            event.getWorker() == null ? null : event.getWorker().getId(), event.getEventType(),
            event.getCreatedAt(), event.getRelatedEntryId()))
        .toList();
    var interruptionFacts = interruptions
        .findAllByInterruptedEntryIdOrInterruptingEntryId(value.getId(), value.getId()).stream()
        .sorted(Comparator.comparing(interruption -> interruption.getId().toString()))
        .map(interruption -> new InterruptionFact(
            interruption.getId(), interruption.getVersion(), interruption.getWorker().getId(),
            interruption.getInterruptedEntry().getId(), interruption.getInterruptingEntry().getId(),
            interruption.isActive(), interruption.getCreatedAt(), interruption.getResolvedAt()))
        .toList();
    return new QueueEntryFact(
        value.getId(), value.getTask().getId(), value.getQueue() == null ? null : value.getQueue().getId(),
        value.getRouteIndex(), value.getQueuePosition(), value.getEntryType(),
        value.getStatus(), value.getPlannedDurationMinutes(),
        value.getActiveStartedAt(), value.getPausedAt(), value.getDoneAt(), value.getActiveWorkSeconds(),
        value.getOriginalBudgetSeconds(), value.getCurrentBudgetSeconds(), value.getPauseOrigin(),
        assignmentFacts, timeEventFacts, interruptionFacts, deleted);
  }

  public GroupKpiDayFact groupKpiDay(GroupKpiDayState value) {
    return new GroupKpiDayFact(
        value.getId(),
        value.getWarehouseId(),
        value.getWorkerGroupId(),
        value.getLocalDate(),
        value.getDataAvailableFrom(),
        value.getFormulaVersion(),
        value.getCompletedBudgetSeconds(),
        value.getEarnedRemainingSeconds(),
        value.getActiveSeconds(),
        value.getPenalizedIdleSeconds(),
        value.getCompletedTaskCount(),
        value.getOpenState(),
        value.getOpenStateStartedAt(),
        value.getPenaltyStartsAt(),
        value.getNextTransitionAt(),
        value.getAsOf());
  }
}
