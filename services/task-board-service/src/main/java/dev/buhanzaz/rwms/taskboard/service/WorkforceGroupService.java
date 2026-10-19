package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.*;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns worker-group membership, operational availability, and current-group transitions.
 *
 * <p>Availability and current-group interval rows are written in the same transaction as their
 * versioned aggregate event. Profile and credential lifecycle code cannot mutate this state.
 */
@Service
public class WorkforceGroupService {
  private final WorkerRepository workers;
  private final WorkerClassAssignmentRepository qualifications;
  private final WorkerGroupRepository groups;
  private final WorkerGroupMemberRepository members;
  private final TaskAssignmentRepository taskAssignments;
  private final RegistryService registry;
  private final TransactionTemplate tx;
  private final JdbcTemplate jdbc;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;
  private final GroupKpiEvidenceService kpiEvidence;
  private final WorkforceReadProjectionService projections;

  public WorkforceGroupService(
      WorkerRepository workers,
      WorkerClassAssignmentRepository qualifications,
      WorkerGroupRepository groups,
      WorkerGroupMemberRepository members,
      TaskAssignmentRepository taskAssignments,
      RegistryService registry,
      PlatformTransactionManager transactionManager,
      JdbcTemplate jdbc,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter,
      GroupKpiEvidenceService kpiEvidence,
      WorkforceReadProjectionService projections) {
    this.workers = workers;
    this.qualifications = qualifications;
    this.groups = groups;
    this.members = members;
    this.taskAssignments = taskAssignments;
    this.registry = registry;
    this.tx = new TransactionTemplate(transactionManager);
    this.jdbc = jdbc;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
    this.kpiEvidence = kpiEvidence;
    this.projections = projections;
  }

  WorkerDto setCurrentGroup(UUID warehouseId, UUID workerId, SetCurrentGroupRequest request) {
    return tx.execute(
        transaction -> {
          Worker worker = requireWorker(warehouseId, workerId);
          checkVersion(worker.getVersion(), request.expectedVersion(), "Рабочий");
          WorkerGroup selected =
              request.workerGroupId() == null
                  ? null
                  : requireGroup(warehouseId, request.workerGroupId());
          if (selected != null) {
            if (!selected.isActive()
                || selected.getOperationalStatus() != GroupOperationalStatus.AVAILABLE) {
              throw new ConflictException("Выбранная группа недоступна");
            }
            boolean member =
                members.findAllByWorkerGroupIdAndActiveTrue(selected.getId()).stream()
                    .anyMatch(value -> value.getWorker().getId().equals(workerId));
            if (!member) {
              throw new ConflictException("Рабочий не состоит в выбранной группе");
            }
          }
          return changeCurrentGroup(warehouseId, worker, selected);
        });
  }

  WorkerGroupDto disableGroupState(
      UUID warehouseId, UUID groupId, GroupAvailabilityRequest request) {
    return tx.execute(
        transaction -> {
          WorkerGroup group = requireGroup(warehouseId, groupId);
          checkVersion(group.getVersion(), request.expectedVersion(), "Группа");
          if (group.getOperationalStatus() == GroupOperationalStatus.DISABLED) {
            throw new ConflictException("Группа уже отключена");
          }
          long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER_GROUP, groupId);
          OffsetDateTime changedAt =
              jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
          closeAvailabilityInterval(groupId, changedAt);
          jdbc.update(
              """
              insert into worker_group_availability_interval(
                id,worker_group_id,status,reason,started_at,ended_at)
              values (?,?,'DISABLED',?,?,null)
              """,
              UUID.randomUUID(),
              groupId,
              normalize(request.reason()),
              changedAt);
          group.disable(changedAt, request.reason());
          group = projectionWriter.saveAndFlush(groups, group);
          projectionWriter.refresh(group);
          eventSourcing.groupChanged(
              group, streamVersion, TaskBoardEventTypes.WORKER_GROUP_CHANGED);
          return projections.groupDto(group);
        });
  }

  WorkerGroupDto enableGroupState(
      UUID warehouseId, UUID groupId, GroupAvailabilityRequest request) {
    return tx.execute(
        transaction -> {
          WorkerGroup group = requireGroup(warehouseId, groupId);
          checkVersion(group.getVersion(), request.expectedVersion(), "Группа");
          if (group.getOperationalStatus() == GroupOperationalStatus.AVAILABLE) {
            throw new ConflictException("Группа уже доступна");
          }
          long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER_GROUP, groupId);
          OffsetDateTime changedAt =
              jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
          closeAvailabilityInterval(groupId, changedAt);
          jdbc.update(
              """
              insert into worker_group_availability_interval(
                id,worker_group_id,status,reason,started_at,ended_at)
              values (?,?,'AVAILABLE',null,?,null)
              """,
              UUID.randomUUID(),
              groupId,
              changedAt);
          group.enable();
          group = projectionWriter.saveAndFlush(groups, group);
          projectionWriter.refresh(group);
          eventSourcing.groupChanged(
              group, streamVersion, TaskBoardEventTypes.WORKER_GROUP_CHANGED);
          return projections.groupDto(group);
        });
  }

  List<WorkerGroupDto> listGroups(UUID warehouseId) {
    return tx.execute(
        ignored ->
            groups.findAllByWarehouseIdOrderByNameAsc(warehouseId).stream()
                .map(projections::groupDto)
                .toList());
  }

  WorkerGroupDto createGroup(UUID warehouseId, WorkerGroupRequest request) {
    return tx.execute(
        transaction -> {
          var group = new WorkerGroup();
          group.setWarehouseId(warehouseId);
          apply(group, request);
          group = projectionWriter.save(groups, group);
          replaceMembers(group, request);
          projectionWriter.flush();
          applyCurrentGroupChanges(group, request);
          projectionWriter.flush();
          projectionWriter.refresh(group);
          jdbc.update(
              """
              insert into worker_group_availability_interval(
                id,worker_group_id,status,reason,started_at,ended_at)
              values (?,?,'AVAILABLE',null,clock_timestamp(),null)
              """,
              UUID.randomUUID(),
              group.getId());
          eventSourcing.created(group);
          return projections.groupDto(group);
        });
  }

  WorkerGroupDto updateGroup(UUID warehouseId, UUID id, WorkerGroupRequest request) {
    return tx.execute(
        transaction -> {
          var group = requireGroup(warehouseId, id);
          checkVersion(group.getVersion(), request.version(), "Группа");
          long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER_GROUP, id);
          apply(group, request);
          group.touch();
          group = projectionWriter.save(groups, group);
          replaceMembers(group, request);
          projectionWriter.flush();
          applyCurrentGroupChanges(group, request);
          projectionWriter.flush();
          projectionWriter.refresh(group);
          eventSourcing.groupChanged(group, streamVersion, TaskBoardEventTypes.WORKER_GROUP_CHANGED);
          return projections.groupDto(group);
        });
  }

  void deleteGroup(UUID warehouseId, UUID id, long expectedVersion) {
    tx.executeWithoutResult(
        transaction -> {
          var group = requireGroup(warehouseId, id);
          checkVersion(group.getVersion(), expectedVersion, "Группа");
          long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER_GROUP, id);
          if (!taskAssignments.findAllByWorkerGroupIdAndStatusIn(
              id, java.util.Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED)).isEmpty()) {
            throw new ConflictException("Перед удалением группы завершите или отмените её задания");
          }
          workers.findAllByCurrentGroupId(id).stream()
              .sorted(java.util.Comparator.comparing(worker -> worker.getId().toString()))
              .forEach(worker -> changeCurrentGroup(warehouseId, worker, null));
          projectionWriter.deleteAll(members, members.findAllByWorkerGroupId(id));
          closeAvailabilityInterval(id,
              jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class));
          group.archive();
          projectionWriter.saveAndFlush(groups, group);
          projectionWriter.refresh(group);
          eventSourcing.deleted(group, streamVersion);
          projectionWriter.flush();
        });
  }

  List<WorkerGroup> eligibleGroups(WorkQueue queue, List<WorkQueueClassBinding> queueBindings) {
    var active = groups.findAllByWarehouseIdAndActiveTrueOrderByNameAsc(queue.getWarehouseId());
    if (queueBindings.isEmpty()) {
      return active;
    }
    var classIds =
        queueBindings.stream()
            .map(binding -> binding.getWorkerClass().getId())
            .collect(java.util.stream.Collectors.toSet());
    return active.stream()
        .filter(group -> classIds.contains(group.getWorkerClass().getId()))
        .toList();
  }

  WorkerGroup requireGroup(UUID warehouseId, UUID id) {
    var group = groups.findById(id).orElseThrow(() -> new NotFoundException("Группа не найдена"));
    if (!group.getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Группа не найдена");
    }
    return group;
  }

  private void replaceMembers(WorkerGroup group, WorkerGroupRequest request) {
    List<GroupMemberRequest> requested = request.members();
    Map<UUID, GroupMemberRequest> requestedByWorker = new LinkedHashMap<>();
    if (requested != null) {
      requested.forEach(value -> requestedByWorker.put(value.workerId(), value));
    }
    java.util.Set<UUID> plannedCurrentGroupClears =
        request.currentGroupChanges() == null
            ? java.util.Set.of()
            : request.currentGroupChanges().stream()
                .filter(change -> !change.current())
                .map(CurrentGroupChangeRequest::workerId)
                .collect(java.util.stream.Collectors.toSet());
    for (Worker currentWorker : workers.findAllByCurrentGroupId(group.getId())) {
      GroupMemberRequest retained = requestedByWorker.get(currentWorker.getId());
      if ((retained == null || !retained.active())
          && !plannedCurrentGroupClears.contains(currentWorker.getId())) {
        throw new ConflictException(
            "Нельзя исключить рабочего, пока эта группа назначена ему текущей");
      }
    }
    var existing = members.findAllByWorkerGroupId(group.getId());
    projectionWriter.deleteAll(members, existing);
    // Hibernate orders entity inserts before deletes during the final flush.
    // Flush removals first so an unchanged member can be recreated without
    // violating the natural (worker_group_id, worker_id) identity.
    if (!existing.isEmpty()) {
      projectionWriter.flush();
    }
    if (requested == null) {
      return;
    }
    var unique = new LinkedHashMap<UUID, GroupMemberRequest>();
    requested.forEach(memberRequest -> unique.put(memberRequest.workerId(), memberRequest));
    for (var memberRequest : unique.values()) {
      var worker = requireWorker(group.getWarehouseId(), memberRequest.workerId());
      boolean qualified =
          qualifications.findAllByWorkerId(worker.getId()).stream()
              .anyMatch(
                  qualification ->
                      qualification.isActive()
                          && qualification.getWorkerClass().equals(group.getWorkerClass()));
      if (!qualified) {
        throw new ConflictException(
            "Рабочий "
                + worker.getDisplayName()
                + " не имеет квалификации "
                + group.getWorkerClass().getName());
      }
      var member = new WorkerGroupMember();
      member.setWorkerGroup(group);
      member.setWorker(worker);
      member.setActive(memberRequest.active());
      projectionWriter.save(members, member);
    }
  }

  private void applyCurrentGroupChanges(WorkerGroup group, WorkerGroupRequest request) {
    if (request.currentGroupChanges() == null || request.currentGroupChanges().isEmpty()) {
      return;
    }
    Map<UUID, GroupMemberRequest> requestedMembers = new LinkedHashMap<>();
    if (request.members() != null) {
      request.members().forEach(member -> requestedMembers.put(member.workerId(), member));
    }
    Map<UUID, CurrentGroupChangeRequest> unique = new LinkedHashMap<>();
    for (CurrentGroupChangeRequest change : request.currentGroupChanges()) {
      if (unique.put(change.workerId(), change) != null) {
        throw new ConflictException("Изменение текущей группы рабочего указано повторно");
      }
    }
    unique.values().stream()
        .sorted(java.util.Comparator.comparing(change -> change.workerId().toString()))
        .forEach(
            change -> {
              Worker worker = requireWorker(group.getWarehouseId(), change.workerId());
              checkVersion(worker.getVersion(), change.expectedVersion(), "Рабочий");
              if (change.current()) {
                GroupMemberRequest membership = requestedMembers.get(worker.getId());
                if (membership == null || !membership.active()) {
                  throw new ConflictException(
                      "Текущим можно назначить только активного участника бригады");
                }
                if (!group.isActive()
                    || group.getOperationalStatus() != GroupOperationalStatus.AVAILABLE) {
                  throw new ConflictException("Выбранная группа недоступна");
                }
                changeCurrentGroup(group.getWarehouseId(), worker, group);
              } else if (worker.getCurrentGroup() != null
                  && worker.getCurrentGroup().getId().equals(group.getId())) {
                changeCurrentGroup(group.getWarehouseId(), worker, null);
              }
            });
  }

  private WorkerDto changeCurrentGroup(
      UUID warehouseId, Worker worker, WorkerGroup selected) {
    UUID workerId = worker.getId();
    UUID previousId =
        worker.getCurrentGroup() == null ? null : worker.getCurrentGroup().getId();
    UUID selectedId = selected == null ? null : selected.getId();
    if (java.util.Objects.equals(previousId, selectedId)) {
      return projections.workerDto(worker);
    }
    if (!taskAssignments
        .findAllByWorkerIdAndStatusIn(
            workerId, java.util.Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED))
        .isEmpty()) {
      throw new ConflictException("Нельзя менять текущую группу во время активного задания");
    }
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, workerId);
    OffsetDateTime changedAt =
        jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    jdbc.update(
        """
        update worker_current_group_interval
           set ended_at=?
         where worker_id=? and ended_at is null
        """,
        changedAt,
        workerId);
    if (selected != null) {
      jdbc.update(
          """
          insert into worker_current_group_interval(
            id,worker_id,worker_group_id,started_at,ended_at)
          values (?,?,?, ?,null)
          """,
          UUID.randomUUID(),
          workerId,
          selected.getId(),
          changedAt);
    }
    worker.setCurrentGroup(selected);
    worker.touch();
    worker = projectionWriter.saveAndFlush(workers, worker);
    projectionWriter.refresh(worker);
    eventSourcing.workerChanged(worker, streamVersion, TaskBoardEventTypes.WORKER_CHANGED);
    if (previousId != null) {
      kpiEvidence.refreshGroup(warehouseId, previousId, changedAt);
    }
    if (selectedId != null) {
      kpiEvidence.refreshGroup(warehouseId, selectedId, changedAt);
    }
    return projections.workerDto(worker);
  }

  private Worker requireWorker(UUID warehouseId, UUID id) {
    var worker = workers.findById(id).orElseThrow(() -> new NotFoundException("Рабочий не найден"));
    if (!worker.getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Рабочий не найден");
    }
    return worker;
  }

  private void apply(WorkerGroup group, WorkerGroupRequest request) {
    group.setWorkerClass(registry.requireClass(request.workerClassId()));
    group.setName(request.name().trim());
    group.setDescription(request.description());
    group.setActive(request.active());
  }

  private String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private void closeAvailabilityInterval(UUID groupId, OffsetDateTime endedAt) {
    int updated =
        jdbc.update(
            """
            update worker_group_availability_interval
               set ended_at=?
             where worker_group_id=? and ended_at is null
            """,
            endedAt,
            groupId);
    if (updated != 1) {
      throw new IllegalStateException(
          "Открытый интервал доступности группы отсутствует или неоднозначен");
    }
  }
}
