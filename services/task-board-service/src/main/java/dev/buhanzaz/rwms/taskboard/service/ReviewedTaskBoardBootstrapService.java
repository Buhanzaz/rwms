package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.ReviewedBootstrapCounts;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.ReviewedBootstrapResponse;
import dev.buhanzaz.rwms.taskboard.domain.CredentialStatus;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClassAssignment;
import dev.buhanzaz.rwms.taskboard.domain.WorkerGroup;
import dev.buhanzaz.rwms.taskboard.domain.WorkerGroupMember;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueClassBindingRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerClassAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerClassRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerGroupMemberRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerGroupRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import dev.buhanzaz.rwms.taskboard.service.ReviewedTaskBoardManifest.GroupSeed;
import dev.buhanzaz.rwms.taskboard.service.ReviewedTaskBoardManifest.ManifestCounts;
import dev.buhanzaz.rwms.taskboard.service.ReviewedTaskBoardManifest.QueueBindingSeed;
import dev.buhanzaz.rwms.taskboard.service.ReviewedTaskBoardManifest.QueueSeed;
import dev.buhanzaz.rwms.taskboard.service.ReviewedTaskBoardManifest.WorkerClassSeed;
import dev.buhanzaz.rwms.taskboard.service.ReviewedTaskBoardManifest.WorkerSeed;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the reviewed old-panel workforce and queue registries through the authoritative
 * PostgreSQL projections and event store. It deliberately never provisions authentication
 * credentials.
 */
@Service
@RequiredArgsConstructor
public class ReviewedTaskBoardBootstrapService {
  private static final String LOCK_NAME = "task-board-reviewed-bootstrap:v1";

  private final ReviewedTaskBoardManifest manifest;
  private final WorkerClassRepository classes;
  private final WorkQueueRepository queues;
  private final WorkQueueClassBindingRepository bindings;
  private final WorkerRepository workers;
  private final WorkerClassAssignmentRepository qualifications;
  private final WorkerGroupRepository groups;
  private final WorkerGroupMemberRepository members;
  private final JdbcTemplate jdbc;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;

  @Transactional
  public ReviewedBootstrapResponse bootstrap(UUID warehouseId) {
    manifest.requireCanonicalWarehouse(warehouseId);
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        LOCK_NAME);

    Stats stats = new Stats();
    Map<UUID, WorkerClass> classById = new LinkedHashMap<>();
    for (WorkerClassSeed seed : manifest.workerClasses()) {
      WorkerClass workerClass = ensureWorkerClass(seed, stats);
      classById.put(workerClass.getId(), workerClass);
    }
    for (QueueSeed seed : manifest.queues(warehouseId)) {
      ensureQueue(seed, classById, stats);
    }

    Map<UUID, Worker> workerById = new LinkedHashMap<>();
    for (WorkerSeed seed : manifest.workers(warehouseId)) {
      Worker worker = ensureWorker(seed, classById, stats);
      workerById.put(worker.getId(), worker);
    }
    for (GroupSeed seed : manifest.groups(warehouseId)) {
      ensureGroup(seed, classById, workerById, stats);
    }

    ManifestCounts counts = manifest.counts(warehouseId);
    if (stats.created + stats.reused != counts.total()) {
      throw new IllegalStateException("Reviewed task-board bootstrap count invariant failed");
    }
    return new ReviewedBootstrapResponse(
        warehouseId,
        manifest.sourceSha256(),
        stats.created,
        stats.reused,
        0,
        new ReviewedBootstrapCounts(
            counts.workerClasses(),
            counts.workQueues(),
            counts.queueBindings(),
            counts.workers(),
            counts.qualifications(),
            counts.workerGroups(),
            counts.memberships()));
  }

  private WorkerClass ensureWorkerClass(WorkerClassSeed seed, Stats stats) {
    WorkerClass byId = classes.findById(seed.id()).orElse(null);
    WorkerClass byCode = classes.findByCodeIgnoreCase(seed.code()).orElse(null);
    WorkerClass existing = resolveRoot("worker class", seed.id(), byId, byCode);
    if (existing != null) {
      if (!workerClassMatches(existing, seed)) {
        throw conflict("worker class", seed.id());
      }
      stats.reused++;
      return existing;
    }

    WorkerClass value = new WorkerClass();
    value.assignReviewedId(seed.id());
    value.setCode(seed.code());
    value.setName(seed.name());
    value.setDescription(seed.description());
    value.setComment(seed.comment());
    value.setSortOrder(seed.sortOrder());
    value.setActive(seed.active());
    projectionWriter.persistAndFlush(value);
    projectionWriter.refresh(value);
    eventSourcing.created(value);
    stats.created++;
    return value;
  }

  private void ensureQueue(
      QueueSeed seed, Map<UUID, WorkerClass> classById, Stats stats) {
    WorkQueue byId = queues.findById(seed.id()).orElse(null);
    WorkQueue byCode =
        queues.findByWarehouseIdAndCodeIgnoreCase(seed.warehouseId(), seed.code()).orElse(null);
    WorkQueue queue = resolveRoot("work queue", seed.id(), byId, byCode);
    boolean created = queue == null;
    Long streamVersion = null;
    if (created) {
      queue = new WorkQueue();
      queue.assignReviewedId(seed.id());
      apply(queue, seed);
      projectionWriter.persistAndFlush(queue);
      stats.created++;
    } else {
      if (!queueMatches(queue, seed)) {
        throw conflict("work queue", seed.id());
      }
      streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORK_QUEUE, queue.getId());
      stats.reused++;
    }

    boolean childCreated = reconcileQueueBinding(queue, seed.binding(), classById, stats);
    if (created) {
      projectionWriter.flush();
      projectionWriter.refresh(queue);
      eventSourcing.created(queue);
    } else if (childCreated) {
      queue.touch();
      projectionWriter.saveAndFlush(queues, queue);
      projectionWriter.refresh(queue);
      eventSourcing.queueChanged(
          queue, streamVersion, TaskBoardEventTypes.WORK_QUEUE_CHANGED);
    }
  }

  private boolean reconcileQueueBinding(
      WorkQueue queue,
      QueueBindingSeed seed,
      Map<UUID, WorkerClass> classById,
      Stats stats) {
    List<WorkQueueClassBinding> existing = bindings.findAllByQueueId(queue.getId());
    if (seed == null) {
      if (!existing.isEmpty()) {
        throw conflict("work queue binding", queue.getId());
      }
      return false;
    }
    if (existing.size() > 1) {
      throw conflict("work queue binding", queue.getId());
    }
    if (existing.size() == 1) {
      WorkQueueClassBinding value = existing.getFirst();
      if (!seed.workerClassId().equals(value.getWorkerClass().getId())
          || value.isStopTaskOnTake() != seed.stopTaskOnTake()) {
        throw conflict("work queue binding", queue.getId());
      }
      stats.reused++;
      return false;
    }

    WorkerClass workerClass = requireReferenced(classById, seed.workerClassId(), "worker class");
    UUID id = reviewedChildId("queue-binding", queue.getId(), seed.workerClassId());
    if (bindings.existsById(id)) {
      throw conflict("work queue binding", id);
    }
    WorkQueueClassBinding value = new WorkQueueClassBinding();
    value.assignReviewedId(id);
    value.setQueue(queue);
    value.setWorkerClass(workerClass);
    value.setStopTaskOnTake(seed.stopTaskOnTake());
    projectionWriter.persistAndFlush(value);
    stats.created++;
    return true;
  }

  private Worker ensureWorker(
      WorkerSeed seed, Map<UUID, WorkerClass> classById, Stats stats) {
    Worker byId = workers.findById(seed.id()).orElse(null);
    Worker byLogin = workers.findByAppLoginIgnoreCase(seed.appLogin()).orElse(null);
    Worker worker = resolveRoot("worker", seed.id(), byId, byLogin);
    boolean created = worker == null;
    Long streamVersion = null;
    if (created) {
      worker = new Worker();
      worker.assignReviewedId(seed.id());
      apply(worker, seed);
      projectionWriter.persistAndFlush(worker);
      stats.created++;
    } else {
      if (!workerMatches(worker, seed)) {
        throw conflict("worker", seed.id());
      }
      streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, worker.getId());
      stats.reused++;
    }

    boolean childCreated = reconcileQualifications(worker, seed, classById, stats);
    if (created) {
      projectionWriter.flush();
      projectionWriter.refresh(worker);
      eventSourcing.created(worker);
    } else if (childCreated) {
      worker.touch();
      projectionWriter.saveAndFlush(workers, worker);
      projectionWriter.refresh(worker);
      eventSourcing.workerChanged(
          worker, streamVersion, TaskBoardEventTypes.WORKER_QUALIFICATIONS_CHANGED);
    }
    return worker;
  }

  private boolean reconcileQualifications(
      Worker worker,
      WorkerSeed seed,
      Map<UUID, WorkerClass> classById,
      Stats stats) {
    Map<UUID, WorkerClassAssignment> existing =
        qualifications.findAllByWorkerId(worker.getId()).stream()
            .collect(
                Collectors.toMap(
                    assignment -> assignment.getWorkerClass().getId(),
                    Function.identity(),
                    (left, right) -> {
                      throw conflict("worker qualification", worker.getId());
                    },
                    LinkedHashMap::new));
    Set<UUID> expected = Set.copyOf(seed.qualifications());
    if (!expected.containsAll(existing.keySet())) {
      throw conflict("worker qualification", worker.getId());
    }

    boolean created = false;
    for (UUID classId : seed.qualifications()) {
      WorkerClassAssignment value = existing.get(classId);
      if (value != null) {
        if (!value.isActive() || value.getComment() != null) {
          throw conflict("worker qualification", value.getId());
        }
        stats.reused++;
        continue;
      }
      WorkerClass workerClass = requireReferenced(classById, classId, "worker class");
      UUID id = reviewedChildId("worker-qualification", worker.getId(), classId);
      if (qualifications.existsById(id)) {
        throw conflict("worker qualification", id);
      }
      value = new WorkerClassAssignment();
      value.assignReviewedId(id);
      value.setWorker(worker);
      value.setWorkerClass(workerClass);
      value.setActive(true);
      value.setComment(null);
      projectionWriter.persistAndFlush(value);
      stats.created++;
      created = true;
    }
    return created;
  }

  private void ensureGroup(
      GroupSeed seed,
      Map<UUID, WorkerClass> classById,
      Map<UUID, Worker> workerById,
      Stats stats) {
    WorkerGroup byId = groups.findById(seed.id()).orElse(null);
    WorkerGroup byName =
        groups.findByWarehouseIdAndNameIgnoreCase(seed.warehouseId(), seed.name()).orElse(null);
    WorkerGroup group = resolveRoot("worker group", seed.id(), byId, byName);
    boolean created = group == null;
    Long streamVersion = null;
    if (created) {
      group = new WorkerGroup();
      group.assignReviewedId(seed.id());
      apply(group, seed, requireReferenced(classById, seed.workerClassId(), "worker class"));
      projectionWriter.persistAndFlush(group);
      stats.created++;
    } else {
      if (!groupMatches(group, seed)) {
        throw conflict("worker group", seed.id());
      }
      streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER_GROUP, group.getId());
      stats.reused++;
    }

    boolean childCreated = reconcileMembers(group, seed, workerById, stats);
    if (created) {
      projectionWriter.flush();
      projectionWriter.refresh(group);
      eventSourcing.created(group);
    } else if (childCreated) {
      group.touch();
      projectionWriter.saveAndFlush(groups, group);
      projectionWriter.refresh(group);
      eventSourcing.groupChanged(
          group, streamVersion, TaskBoardEventTypes.WORKER_GROUP_MEMBERS_CHANGED);
    }
  }

  private boolean reconcileMembers(
      WorkerGroup group, GroupSeed seed, Map<UUID, Worker> workerById, Stats stats) {
    Map<UUID, WorkerGroupMember> existing =
        members.findAllByWorkerGroupId(group.getId()).stream()
            .collect(
                Collectors.toMap(
                    member -> member.getWorker().getId(),
                    Function.identity(),
                    (left, right) -> {
                      throw conflict("worker group membership", group.getId());
                    },
                    LinkedHashMap::new));
    Set<UUID> expected = Set.copyOf(seed.members());
    if (!expected.containsAll(existing.keySet())) {
      throw conflict("worker group membership", group.getId());
    }

    boolean created = false;
    for (UUID workerId : seed.members()) {
      WorkerGroupMember value = existing.get(workerId);
      if (value != null) {
        if (!value.isActive() || value.getRoleInGroup() != null) {
          throw conflict("worker group membership", value.getId());
        }
        stats.reused++;
        continue;
      }
      Worker worker = requireReferenced(workerById, workerId, "worker");
      UUID id = reviewedChildId("worker-group-member", group.getId(), workerId);
      if (members.existsById(id)) {
        throw conflict("worker group membership", id);
      }
      value = new WorkerGroupMember();
      value.assignReviewedId(id);
      value.setWorkerGroup(group);
      value.setWorker(worker);
      value.setRoleInGroup(null);
      value.setActive(true);
      projectionWriter.persistAndFlush(value);
      stats.created++;
      created = true;
    }
    return created;
  }

  private static <T extends dev.buhanzaz.rwms.taskboard.domain.AbstractVersionedEntity>
      T resolveRoot(String kind, UUID expectedId, T byId, T byNaturalKey) {
    if (byNaturalKey != null && !expectedId.equals(byNaturalKey.getId())) {
      throw conflict(kind, expectedId);
    }
    if (byId != null
        && byNaturalKey != null
        && !Objects.equals(byId.getId(), byNaturalKey.getId())) {
      throw conflict(kind, expectedId);
    }
    return byId != null ? byId : byNaturalKey;
  }

  private static boolean workerClassMatches(WorkerClass value, WorkerClassSeed seed) {
    return seed.id().equals(value.getId())
        && Objects.equals(seed.code(), value.getCode())
        && Objects.equals(seed.name(), value.getName())
        && Objects.equals(seed.description(), value.getDescription())
        && Objects.equals(seed.comment(), value.getComment())
        && seed.sortOrder() == value.getSortOrder()
        && seed.active() == value.isActive();
  }

  private static boolean queueMatches(WorkQueue value, QueueSeed seed) {
    return seed.id().equals(value.getId())
        && seed.warehouseId().equals(value.getWarehouseId())
        && Objects.equals(seed.code(), value.getCode())
        && Objects.equals(seed.name(), value.getName())
        && Objects.equals(seed.description(), value.getDescription())
        && seed.type() == value.getType()
        && seed.sortOrder() == value.getSortOrder()
        && seed.active() == value.isActive()
        && seed.hidden() == value.isHidden()
        && seed.collapsed() == value.isCollapsed()
        && Objects.equals(seed.holdingPeriodMinutes(), value.getHoldingPeriodMinutes())
        && Objects.equals(seed.notificationThreshold(), value.getNotificationThreshold())
        && seed.notifyWhenThresholdReached() == value.isNotifyWhenThresholdReached();
  }

  private static boolean workerMatches(Worker value, WorkerSeed seed) {
    return seed.id().equals(value.getId())
        && seed.warehouseId().equals(value.getWarehouseId())
        && Objects.equals(seed.displayName(), value.getDisplayName())
        && Objects.equals(seed.firstName(), value.getFirstName())
        && Objects.equals(seed.lastName(), value.getLastName())
        && Objects.equals(seed.middleName(), value.getMiddleName())
        && seed.active() == value.isActive()
        && Objects.equals(seed.comment(), value.getComment())
        && Objects.equals(seed.appLogin(), value.getAppLogin())
        && seed.credentialStatus() == value.getCredentialStatus()
        && value.getCredentialStatus() == CredentialStatus.NOT_CONFIGURED
        && value.getCredentialError() == null
        && value.getCredentialOperationId() == null
        && value.getCredentialOperationType() == null
        && value.getCredentialOperationStartedAt() == null;
  }

  private static boolean groupMatches(WorkerGroup value, GroupSeed seed) {
    return seed.id().equals(value.getId())
        && seed.warehouseId().equals(value.getWarehouseId())
        && seed.workerClassId().equals(value.getWorkerClass().getId())
        && Objects.equals(seed.name(), value.getName())
        && Objects.equals(seed.description(), value.getDescription())
        && seed.active() == value.isActive();
  }

  private static void apply(WorkQueue value, QueueSeed seed) {
    value.setWarehouseId(seed.warehouseId());
    value.setCode(seed.code());
    value.setName(seed.name());
    value.setDescription(seed.description());
    value.setType(seed.type());
    value.setSortOrder(seed.sortOrder());
    value.setActive(seed.active());
    value.setHidden(seed.hidden());
    value.setCollapsed(seed.collapsed());
    value.setHoldingPeriodMinutes(seed.holdingPeriodMinutes());
    value.setNotificationThreshold(seed.notificationThreshold());
    value.setNotifyWhenThresholdReached(seed.notifyWhenThresholdReached());
  }

  private static void apply(Worker value, WorkerSeed seed) {
    value.setWarehouseId(seed.warehouseId());
    value.setDisplayName(seed.displayName());
    value.setFirstName(seed.firstName());
    value.setLastName(seed.lastName());
    value.setMiddleName(seed.middleName());
    value.setActive(seed.active());
    value.setComment(seed.comment());
    value.setAppLogin(seed.appLogin());
    value.setCredentialStatus(seed.credentialStatus());
    value.setCredentialError(null);
    value.setCredentialOperationId(null);
    value.setCredentialOperationType(null);
    value.setCredentialOperationStartedAt(null);
  }

  private static void apply(WorkerGroup value, GroupSeed seed, WorkerClass workerClass) {
    value.setWarehouseId(seed.warehouseId());
    value.setWorkerClass(workerClass);
    value.setName(seed.name());
    value.setDescription(seed.description());
    value.setActive(seed.active());
  }

  private static <T> T requireReferenced(Map<UUID, T> values, UUID id, String kind) {
    T value = values.get(id);
    if (value == null) {
      throw new IllegalStateException("Reviewed task-board manifest references unknown " + kind);
    }
    return value;
  }

  private static UUID reviewedChildId(String kind, UUID left, UUID right) {
    return UUID.nameUUIDFromBytes(
        ("task-board-reviewed-bootstrap:v1\u0000" + kind + "\u0000" + left + "\u0000" + right)
            .getBytes(StandardCharsets.UTF_8));
  }

  private static ConflictException conflict(String kind, UUID id) {
    return new ConflictException(
        "Reviewed task-board bootstrap conflicts with existing " + kind + " " + id);
  }

  private static final class Stats {
    private int created;
    private int reused;
  }
}
