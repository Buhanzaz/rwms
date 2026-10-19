package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.GroupKpiDayState;
import dev.buhanzaz.rwms.taskboard.domain.GroupKpiOpenState;
import dev.buhanzaz.rwms.taskboard.domain.GroupKpiResponsibilitySegment;
import dev.buhanzaz.rwms.taskboard.domain.GroupKpiSegmentDay;
import dev.buhanzaz.rwms.taskboard.domain.GroupKpiSegmentOutcome;
import dev.buhanzaz.rwms.taskboard.domain.GroupOperationalStatus;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import dev.buhanzaz.rwms.taskboard.domain.WorkerGroup;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.repository.GroupKpiDayStateRepository;
import dev.buhanzaz.rwms.taskboard.repository.GroupKpiResponsibilitySegmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.GroupKpiSegmentDayRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueClassBindingRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerGroupRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the replaceable per-group/per-day raw KPI evidence published to analytics-service.
 *
 * <p>The projection keeps incomplete responsibility segments reversible: their active seconds are
 * visible provisionally, but every affected day is corrected if the whole task is returned to the
 * queue. Speed credit is written only after a segment is completed.
 */
@Service
public class GroupKpiEvidenceService {
  private static final Duration IDLE_GRACE = Duration.ofMinutes(5);

  private final GroupKpiDayStateRepository days;
  private final GroupKpiResponsibilitySegmentRepository segments;
  private final GroupKpiSegmentDayRepository segmentDays;
  private final WorkerGroupRepository groups;
  private final WorkerRepository workers;
  private final WorkQueueClassBindingRepository bindings;
  private final QueueEntryRepository entries;
  private final TaskAssignmentRepository assignments;
  private final WarehouseKpiClock clock;
  private final TaskBoardEventSourcing eventSourcing;

  public GroupKpiEvidenceService(
      GroupKpiDayStateRepository days,
      GroupKpiResponsibilitySegmentRepository segments,
      GroupKpiSegmentDayRepository segmentDays,
      WorkerGroupRepository groups,
      WorkerRepository workers,
      WorkQueueClassBindingRepository bindings,
      QueueEntryRepository entries,
      TaskAssignmentRepository assignments,
      WarehouseKpiClock clock,
      TaskBoardEventSourcing eventSourcing) {
    this.days = days;
    this.segments = segments;
    this.segmentDays = segmentDays;
    this.groups = groups;
    this.workers = workers;
    this.bindings = bindings;
    this.entries = entries;
    this.assignments = assignments;
    this.clock = clock;
    this.eventSourcing = eventSourcing;
  }

  @Transactional
  public void refreshWarehouse(UUID warehouseId, OffsetDateTime at) {
    groups.findAllByWarehouseIdAndActiveTrueOrderByNameAsc(warehouseId).stream()
        .map(WorkerGroup::getId)
        .forEach(groupId -> refreshGroup(warehouseId, groupId, at));
  }

  @Transactional
  public void refreshGroup(UUID warehouseId, UUID groupId, OffsetDateTime at) {
    LocalDate dataAvailableFrom = clock.dataAvailableFrom(warehouseId).orElse(null);
    if (dataAvailableFrom == null) return;
    LocalDate localDate = clock.localDate(warehouseId, at.toInstant());
    if (localDate.isBefore(dataAvailableFrom)) return;
    WorkerGroup group =
        groups
            .findById(groupId)
            .filter(value -> value.getWarehouseId().equals(warehouseId))
            .orElse(null);
    if (group == null) return;

    reconcileOpenAssignments(warehouseId, group, at);
    DayHandle handle = day(warehouseId, groupId, localDate, dataAvailableFrom, at);
    settle(handle.value(), at);
    transition(handle.value(), desiredState(group, at), at);
    publish(handle.value(), handle.created());
  }

  @Transactional
  public void refreshDueGroup(UUID warehouseId, UUID groupId, OffsetDateTime at) {
    for (int guard = 0; guard < 32; guard++) {
      GroupKpiDayState current =
          days
              .findFirstByWarehouseIdAndWorkerGroupIdOrderByLocalDateDesc(
                  warehouseId, groupId)
              .orElse(null);
      if (current == null
          || current.getNextTransitionAt() == null
          || current.getNextTransitionAt().isAfter(at)) {
        break;
      }
      refreshGroup(warehouseId, groupId, current.getNextTransitionAt());
    }
    refreshGroup(warehouseId, groupId, at);
  }

  @Transactional
  public void beginSegment(
      UUID warehouseId, UUID groupId, QueueEntry entry, OffsetDateTime at) {
    beginSegment(warehouseId, groupId, entry, at, entry.getCurrentBudgetSeconds());
  }

  /**
   * Opens responsibility for an execution representative with an explicit combined package
   * budget. A secondary group still receives zero budget when another open segment already owns
   * it.
   */
  @Transactional
  void beginSegment(
      UUID warehouseId,
      UUID groupId,
      QueueEntry entry,
      OffsetDateTime at,
      Long responsibilityBudgetSeconds) {
    if (responsibilityBudgetSeconds != null && responsibilityBudgetSeconds < 0) {
      throw new IllegalArgumentException("Бюджет ответственности не может быть отрицательным");
    }
    LocalDate dataAvailableFrom = clock.dataAvailableFrom(warehouseId).orElse(null);
    if (dataAvailableFrom == null
        || clock.localDate(warehouseId, at.toInstant()).isBefore(dataAvailableFrom)) {
      return;
    }
    if (segments
        .findByQueueEntryIdAndWorkerGroupIdAndOutcome(
            entry.getId(), groupId, GroupKpiSegmentOutcome.OPEN)
        .isEmpty()) {
      boolean firstResponsibility =
          segments
              .findAllByQueueEntryIdAndOutcome(
                  entry.getId(), GroupKpiSegmentOutcome.OPEN)
              .isEmpty();
      long budget =
          firstResponsibility && responsibilityBudgetSeconds != null
              ? responsibilityBudgetSeconds
              : 0;
      segments.saveAndFlush(
          new GroupKpiResponsibilitySegment(
              warehouseId, groupId, entry.getId(), budget, at));
    }
    refreshGroup(warehouseId, groupId, at);
  }

  private void reconcileOpenAssignments(
      UUID warehouseId, WorkerGroup group, OffsetDateTime at) {
    Map<UUID, QueueEntry> assignedEntries = new LinkedHashMap<>();
    assignments
        .findAllByWorkerGroupIdAndStatusIn(
            group.getId(), Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED))
        .forEach(
            assignment -> {
              QueueEntry entry = assignment.getQueueEntry();
              if (entry.getTask().getWarehouseId().equals(warehouseId)
                  && (entry.getStatus() == EntryStatus.IN_PROGRESS
                      || entry.getStatus() == EntryStatus.PAUSED)) {
                assignedEntries.putIfAbsent(entry.getId(), entry);
              }
            });

    for (QueueEntry entry : assignedEntries.values()) {
      if (segments
          .findByQueueEntryIdAndWorkerGroupIdAndOutcome(
              entry.getId(), group.getId(), GroupKpiSegmentOutcome.OPEN)
          .isPresent()) {
        continue;
      }
      boolean budgetAlreadyOwned =
          segments
              .findAllByQueueEntryIdAndOutcome(
                  entry.getId(), GroupKpiSegmentOutcome.OPEN)
              .stream()
              .anyMatch(value -> value.getBudgetSeconds() > 0);
      UUID budgetOwner = budgetOwner(entry);
      long budget =
          !budgetAlreadyOwned && group.getId().equals(budgetOwner)
              ? remainingBudgetAt(entry, at)
              : 0;
      segments.saveAndFlush(
          new GroupKpiResponsibilitySegment(
              warehouseId, group.getId(), entry.getId(), budget, at));
    }
  }

  private UUID budgetOwner(QueueEntry entry) {
    return assignments
        .findAllByQueueEntryIdAndStatusIn(
            entry.getId(), Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED))
        .stream()
        .map(assignment -> assignment.getWorkerGroup())
        .filter(Objects::nonNull)
        .distinct()
        .min(
            Comparator.comparingInt(
                    (WorkerGroup value) ->
                        bindings
                            .findByQueueIdAndWorkerClassId(
                                entry.getQueue().getId(), value.getWorkerClass().getId())
                            .map(WorkQueueClassBinding::getBindingOrder)
                            .orElse(Integer.MAX_VALUE))
                .thenComparing(value -> value.getId().toString()))
        .map(WorkerGroup::getId)
        .orElse(null);
  }

  private long remainingBudgetAt(QueueEntry entry, OffsetDateTime at) {
    if (entry.getCurrentBudgetSeconds() == null) return 0;
    long spent = entry.getActiveWorkSeconds();
    if (entry.getActiveStartedAt() != null
        && entry.getActiveStartedAt().isBefore(at)) {
      spent =
          Math.addExact(
              spent,
              clock.countedSeconds(
                  entry.getTask().getWarehouseId(),
                  entry.getActiveStartedAt().toInstant(),
                  at.toInstant()));
    }
    return Math.max(0, entry.getCurrentBudgetSeconds() - spent);
  }

  @Transactional
  public Set<UUID> completeSegment(UUID warehouseId, QueueEntry entry, OffsetDateTime at) {
    return completeSegment(warehouseId, entry, at, null);
  }

  @Transactional
  public Set<UUID> completeSegment(UUID warehouseId, QueueEntry entry, OffsetDateTime at,
      Long completedBudget) {
    List<GroupKpiResponsibilitySegment> completed =
        segments.findAllByQueueEntryIdAndOutcome(
            entry.getId(), GroupKpiSegmentOutcome.OPEN);
    if (completed.isEmpty()) return Set.of();
    Set<UUID> groupIds =
        completed.stream()
            .map(GroupKpiResponsibilitySegment::getWorkerGroupId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    groupIds.forEach(groupId -> refreshGroup(warehouseId, groupId, at));
    LocalDate localDate = clock.localDate(warehouseId, at.toInstant());
    LocalDate dataAvailableFrom = clock.dataAvailableFrom(warehouseId).orElseThrow();
    for (GroupKpiResponsibilitySegment segment : completed) {
      if (completedBudget == null) segment.complete(at);
      else segment.completePortion(segment.getBudgetSeconds() == 0 ? 0 : completedBudget, at);
      segments.saveAndFlush(segment);
      if (segment.getBudgetSeconds() > 0) {
        DayHandle handle =
            day(
                warehouseId,
                segment.getWorkerGroupId(),
                localDate,
                dataAvailableFrom,
                at);
        handle
            .value()
            .addCompletedSegment(segment.getBudgetSeconds(), segment.getActiveSeconds(), completedBudget == null);
        publish(handle.value(), handle.created());
      }
    }
    return Set.copyOf(groupIds);
  }

  @Transactional
  public Set<UUID> returnSegment(UUID warehouseId, QueueEntry entry, OffsetDateTime at) {
    List<GroupKpiResponsibilitySegment> returned =
        segments.findAllByQueueEntryIdAndOutcome(
            entry.getId(), GroupKpiSegmentOutcome.OPEN);
    if (returned.isEmpty()) return Set.of();
    Set<UUID> groupIds =
        returned.stream()
            .map(GroupKpiResponsibilitySegment::getWorkerGroupId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    groupIds.forEach(groupId -> refreshGroup(warehouseId, groupId, at));
    for (GroupKpiResponsibilitySegment segment : returned) {
      for (GroupKpiSegmentDay allocation : segmentDays.findAllBySegmentId(segment.getId())) {
        GroupKpiDayState evidence = days.findById(allocation.getEvidenceId()).orElseThrow();
        evidence.subtractActiveSeconds(allocation.getActiveSeconds());
        publish(evidence, false);
      }
      segment.returnToQueue(at);
      segments.saveAndFlush(segment);
    }
    return Set.copyOf(groupIds);
  }

  @Transactional
  public void refreshEntryGroup(UUID warehouseId, UUID entryId, OffsetDateTime at) {
    segments
        .findAllByQueueEntryIdAndOutcome(entryId, GroupKpiSegmentOutcome.OPEN)
        .stream()
        .map(GroupKpiResponsibilitySegment::getWorkerGroupId)
        .distinct()
        .forEach(groupId -> refreshGroup(warehouseId, groupId, at));
  }

  @Transactional(readOnly = true)
  public Set<UUID> dueGroups(OffsetDateTime at) {
    Set<UUID> result = new LinkedHashSet<>();
    for (GroupKpiDayState value :
        days.findAllByNextTransitionAtLessThanEqualOrderByNextTransitionAtAsc(at)) {
      result.add(value.getWorkerGroupId());
    }
    return Set.copyOf(result);
  }

  @Transactional(readOnly = true)
  public boolean hasDay(UUID warehouseId, UUID groupId, OffsetDateTime at) {
    LocalDate available = clock.dataAvailableFrom(warehouseId).orElse(null);
    if (available == null) return true;
    LocalDate date = clock.localDate(warehouseId, at.toInstant());
    return date.isBefore(available)
        || days.findByWarehouseIdAndWorkerGroupIdAndLocalDate(warehouseId, groupId, date).isPresent();
  }

  private DayHandle day(
      UUID warehouseId,
      UUID groupId,
      LocalDate localDate,
      LocalDate dataAvailableFrom,
      OffsetDateTime at) {
    GroupKpiDayState existing =
        days
            .findByWarehouseIdAndWorkerGroupIdAndLocalDate(warehouseId, groupId, localDate)
            .orElse(null);
    if (existing != null) return new DayHandle(existing, false);

    closePreviousDay(warehouseId, groupId, localDate, at);
    UUID evidenceId =
        UUID.nameUUIDFromBytes(
            ("rwms:kpi-v1:" + warehouseId + ":" + groupId + ":" + localDate)
                .getBytes(StandardCharsets.UTF_8));
    return new DayHandle(
        GroupKpiDayState.create(
            evidenceId, warehouseId, groupId, localDate, dataAvailableFrom, at),
        true);
  }

  private void closePreviousDay(
      UUID warehouseId, UUID groupId, LocalDate localDate, OffsetDateTime at) {
    GroupKpiDayState previous =
        days
            .findFirstByWarehouseIdAndWorkerGroupIdOrderByLocalDateDesc(warehouseId, groupId)
            .filter(value -> value.getLocalDate().isBefore(localDate))
            .orElse(null);
    if (previous == null || previous.getOpenState() == null) return;
    settle(previous, at);
    previous.transition(null, null, null, null, previous.getAsOf());
    publish(previous, false);
  }

  private void settle(GroupKpiDayState state, OffsetDateTime requestedAt) {
    OffsetDateTime settledAt = requestedAt;
    if (state.getNextTransitionAt() != null
        && state.getNextTransitionAt().isBefore(settledAt)) {
      settledAt = state.getNextTransitionAt();
    }
    if (!settledAt.isAfter(state.getAsOf()) || state.getOpenState() == null) return;

    if (state.getOpenState() == GroupKpiOpenState.WORKING) {
      long elapsed = Duration.between(state.getAsOf(), settledAt).toSeconds();
      if (elapsed > 0) allocateActive(state, elapsed);
    } else if (state.getOpenState() == GroupKpiOpenState.IDLE_PENALIZED) {
      state.addPenalizedIdleSeconds(
          Duration.between(state.getAsOf(), settledAt).toSeconds());
    } else if (state.getOpenState() == GroupKpiOpenState.IDLE_GRACE
        && state.getPenaltyStartsAt() != null
        && settledAt.isAfter(state.getPenaltyStartsAt())) {
      OffsetDateTime penaltyStart =
          state.getAsOf().isAfter(state.getPenaltyStartsAt())
              ? state.getAsOf()
              : state.getPenaltyStartsAt();
      state.addPenalizedIdleSeconds(Duration.between(penaltyStart, settledAt).toSeconds());
    }
    state.transition(
        state.getOpenState(),
        state.getOpenStateStartedAt(),
        state.getPenaltyStartsAt(),
        state.getNextTransitionAt(),
        settledAt);
  }

  private void allocateActive(GroupKpiDayState state, long seconds) {
    List<GroupKpiResponsibilitySegment> open =
        segments.findAllByWorkerGroupIdAndOutcome(
            state.getWorkerGroupId(), GroupKpiSegmentOutcome.OPEN);
    GroupKpiResponsibilitySegment segment =
        open.stream()
            .filter(
                value ->
                    entries
                        .findById(value.getQueueEntryId())
                        .map(entry -> entry.getStatus() == EntryStatus.IN_PROGRESS)
                        .orElse(false))
            .findFirst()
            .orElse(open.isEmpty() ? null : open.getFirst());
    if (segment == null) return;
    segment.addActiveSeconds(seconds);
    segments.save(segment);
    GroupKpiSegmentDay allocation =
        segmentDays
            .findBySegmentIdAndEvidenceId(segment.getId(), state.getId())
            .orElseGet(() -> new GroupKpiSegmentDay(segment.getId(), state.getId()));
    allocation.addActiveSeconds(seconds);
    segmentDays.save(allocation);
    state.addActiveSeconds(seconds);
  }

  private void transition(
      GroupKpiDayState state, DesiredState desired, OffsetDateTime at) {
    OffsetDateTime stateStartedAt = at;
    OffsetDateTime penaltyStartsAt = null;
    GroupKpiOpenState nextState = desired.state();
    boolean continuingIdle =
        isIdle(state.getOpenState()) && isIdle(nextState);
    if (continuingIdle) {
      stateStartedAt = state.getOpenStateStartedAt();
      penaltyStartsAt = state.getPenaltyStartsAt();
    } else if (isIdle(nextState)) {
      penaltyStartsAt = at.plus(IDLE_GRACE);
    } else if (state.getOpenState() == nextState && state.getOpenStateStartedAt() != null) {
      stateStartedAt = state.getOpenStateStartedAt();
    }

    if (isIdle(nextState) && penaltyStartsAt != null) {
      nextState =
          !at.isBefore(penaltyStartsAt)
              ? GroupKpiOpenState.IDLE_PENALIZED
              : GroupKpiOpenState.IDLE_GRACE;
    }
    OffsetDateTime nextTransition = desired.scheduleTransitionAt();
    if (nextState == GroupKpiOpenState.IDLE_GRACE
        && penaltyStartsAt != null
        && (nextTransition == null || penaltyStartsAt.isBefore(nextTransition))) {
      nextTransition = penaltyStartsAt;
    }
    state.transition(nextState, stateStartedAt, penaltyStartsAt, nextTransition, at);
  }

  private DesiredState desiredState(WorkerGroup group, OffsetDateTime at) {
    var moment = clock.moment(group.getWarehouseId(), at.toInstant());
    if (moment.state() != WarehouseKpiClock.ScheduleState.WORKING
        || !group.isActive()
        || group.getOperationalStatus() != GroupOperationalStatus.AVAILABLE
        || !workers.existsByCurrentGroupIdAndActiveTrue(group.getId())) {
      return new DesiredState(GroupKpiOpenState.EXCLUDED, moment.nextTransitionAt());
    }

    GroupKpiResponsibilitySegment segment =
        segments
            .findAllByWorkerGroupIdAndOutcome(group.getId(), GroupKpiSegmentOutcome.OPEN)
            .stream()
            .sorted(
                java.util.Comparator.comparingInt(
                    value ->
                        entries
                            .findById(value.getQueueEntryId())
                            .map(entry -> entry.getStatus() == EntryStatus.IN_PROGRESS ? 0 : 1)
                            .orElse(2)))
            .findFirst()
            .orElse(null);
    if (segment != null) {
      QueueEntry entry = entries.findById(segment.getQueueEntryId()).orElse(null);
      if (entry != null && entry.getStatus() == EntryStatus.IN_PROGRESS) {
        return new DesiredState(GroupKpiOpenState.WORKING, moment.nextTransitionAt());
      }
      if (entry != null && entry.getStatus() == EntryStatus.PAUSED) {
        return new DesiredState(GroupKpiOpenState.IDLE_GRACE, moment.nextTransitionAt());
      }
    }
    return new DesiredState(
        hasLegallyAvailableTask(
                group, clock.localDate(group.getWarehouseId(), at.toInstant()))
            ? GroupKpiOpenState.IDLE_GRACE
            : GroupKpiOpenState.EXCLUDED,
        moment.nextTransitionAt());
  }

  private boolean hasLegallyAvailableTask(WorkerGroup group, LocalDate localDate) {
    return bindings.findAllByWorkerClassId(group.getWorkerClass().getId()).stream()
        .filter(binding -> binding.getQueue().isActive())
        .anyMatch(
            binding -> {
              if (binding.getBindingOrder() == 0) {
                return entries
                    .findAllByQueueIdAndStatusInOrderByQueuePositionAsc(
                        binding.getQueue().getId(), Set.of(EntryStatus.WAITING))
                    .stream()
                    .anyMatch(
                        value ->
                            value.getEntryType() == EntryType.REAL
                                && !value.getTask().getScheduledDate().isAfter(localDate));
              }
              if (!binding.isNotifyOnPrimaryTake()) return false;
              return entries
                  .findAllByQueueIdAndStatusInOrderByQueuePositionAsc(
                      binding.getQueue().getId(), Set.of(EntryStatus.IN_PROGRESS))
                  .stream()
                  .filter(
                      value ->
                          value.getEntryType() == EntryType.REAL
                              && !value.getTask().getScheduledDate().isAfter(localDate))
                  .anyMatch(
                      value ->
                          assignments
                              .findAllByQueueEntryIdAndStatusIn(
                                  value.getId(),
                                  Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED))
                              .stream()
                              .noneMatch(
                                  assignment ->
                                      group.equals(assignment.getWorkerGroup())));
            });
  }

  private void publish(GroupKpiDayState value, boolean created) {
    if (created) {
      value = days.saveAndFlush(value);
      eventSourcing.groupKpiDayCreated(value);
      return;
    }
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.GROUP_KPI_DAY, value.getId());
    value = days.saveAndFlush(value);
    eventSourcing.groupKpiDayChanged(value, streamVersion);
  }

  private static boolean isIdle(GroupKpiOpenState value) {
    return value == GroupKpiOpenState.IDLE_GRACE
        || value == GroupKpiOpenState.IDLE_PENALIZED;
  }

  private record DayHandle(GroupKpiDayState value, boolean created) {}

  private record DesiredState(
      GroupKpiOpenState state, OffsetDateTime scheduleTransitionAt) {}
}
