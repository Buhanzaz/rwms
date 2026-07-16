package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.QueueUsageReference;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistryService {
  private final WorkerClassRepository classes;
  private final WorkQueueRepository queues;
  private final WorkQueueClassBindingRepository bindings;
  private final QueueEntryRepository entries;
  private final TaskTimeEventRepository events;
  private final QueueUsageReferenceRepository references;
  private final WorkerGroupRepository groups;
  private final WorkerClassAssignmentRepository qualifications;
  private final JdbcTemplate jdbc;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;

  public RegistryService(
      WorkerClassRepository classes,
      WorkQueueRepository queues,
      WorkQueueClassBindingRepository bindings,
      QueueEntryRepository entries,
      TaskTimeEventRepository events,
      QueueUsageReferenceRepository references,
      WorkerGroupRepository groups,
      WorkerClassAssignmentRepository qualifications,
      JdbcTemplate jdbc,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter) {
    this.classes = classes;
    this.queues = queues;
    this.bindings = bindings;
    this.entries = entries;
    this.events = events;
    this.references = references;
    this.groups = groups;
    this.qualifications = qualifications;
    this.jdbc = jdbc;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
  }

  @Transactional(readOnly = true)
  public List<WorkerClassDto> listClasses() {
    return classes.findAllByOrderBySortOrderAscNameAsc().stream().map(this::dto).toList();
  }

  @Transactional
  public WorkerClassDto createClass(WorkerClassRequest request) {
    ensureClassCode(request.code(), null);
    var entity = new WorkerClass();
    apply(entity, request);
    entity = projectionWriter.saveAndFlush(classes, entity);
    projectionWriter.refresh(entity);
    eventSourcing.created(entity);
    return dto(entity);
  }

  @Transactional
  public WorkerClassDto updateClass(UUID id, WorkerClassRequest request) {
    var entity = requireClass(id);
    checkVersion(entity.getVersion(), request.version(), "Класс рабочего");
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER_CLASS, id);
    ensureClassCode(request.code(), id);
    apply(entity, request);
    entity.touch();
    entity = projectionWriter.save(classes, entity);
    projectionWriter.flush();
    projectionWriter.refresh(entity);
    eventSourcing.changed(entity, streamVersion);
    return dto(entity);
  }

  @Transactional
  public void deleteClass(UUID id, long expectedVersion) {
    var entity = requireClass(id);
    checkVersion(entity.getVersion(), expectedVersion, "Класс рабочего");
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER_CLASS, id);
    if (bindings.existsByWorkerClassId(id)
        || groups.existsByWorkerClassId(id)
        || qualifications.existsByWorkerClassId(id))
      throw new ConflictException("Используемый класс рабочего можно только деактивировать");
    eventSourcing.deleted(entity, streamVersion);
    projectionWriter.delete(classes, entity);
    projectionWriter.flush();
  }

  @Transactional(readOnly = true)
  public List<WorkQueueDto> listQueues(UUID warehouseId) {
    return queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseId).stream()
        .map(this::dto)
        .toList();
  }

  @Transactional
  public WorkQueueDto createQueue(UUID warehouseId, WorkQueueRequest request) {
    lockQueueOrder(warehouseId);
    var existing = queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseId);
    var streamVersions =
        eventSourcingVersions(
            TaskBoardAggregateType.WORK_QUEUE,
            existing.stream().map(WorkQueue::getId).toList());
    var orderBefore = queueOrders(existing);
    ensureQueueCode(warehouseId, request.code(), null);
    var queue = new WorkQueue();
    queue.setWarehouseId(warehouseId);
    queue.setSortOrder(nextRegularSortOrder(warehouseId));
    apply(queue, request, false);
    queue = projectionWriter.save(queues, queue);
    replaceBindings(queue, request.bindings());
    normalizeOrder(warehouseId);
    refresh(queue);
    eventSourcing.created(queue);
    appendReorderedQueues(warehouseId, queue.getId(), orderBefore, streamVersions);
    return dto(queue);
  }

  @Transactional
  public WorkQueueDto updateQueue(UUID warehouseId, UUID id, WorkQueueRequest request) {
    lockQueueOrder(warehouseId);
    var current = queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseId);
    var streamVersions =
        eventSourcingVersions(
            TaskBoardAggregateType.WORK_QUEUE,
            current.stream().map(WorkQueue::getId).toList());
    var orderBefore = queueOrders(current);
    var queue = requireQueue(warehouseId, id);
    checkVersion(queue.getVersion(), request.version(), "Очередь");
    if (!queue.getCode().equalsIgnoreCase(request.code()) && !isQueueEmpty(id))
      throw new ConflictException("Код используемой очереди менять нельзя");
    ensureQueueCode(warehouseId, request.code(), id);
    apply(queue, request, true);
    queue.touch();
    queue = projectionWriter.save(queues, queue);
    replaceBindings(queue, request.bindings());
    normalizeOrder(warehouseId);
    refresh(queue);
    eventSourcing.queueChanged(
        queue, streamVersions.get(queue.getId()), TaskBoardEventTypes.WORK_QUEUE_CHANGED);
    appendReorderedQueues(warehouseId, queue.getId(), orderBefore, streamVersions);
    return dto(queue);
  }

  @Transactional
  public List<WorkQueueDto> reorder(UUID warehouseId, QueueOrderRequest request) {
    lockQueueOrder(warehouseId);
    var current = queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseId);
    var streamVersions = eventSourcingVersions(TaskBoardAggregateType.WORK_QUEUE, current.stream()
        .map(WorkQueue::getId).toList());
    if (request.queues().size() != current.size())
      throw new ConflictException("Порядок должен содержать полный набор очередей склада");
    var requestedIds = new java.util.HashSet<UUID>();
    if (request.queues().stream().anyMatch(item -> !requestedIds.add(item.queueId())))
      throw new ConflictException("Порядок содержит повторяющуюся очередь");
    var byId = new LinkedHashMap<UUID, WorkQueue>();
    current.forEach(q -> byId.put(q.getId(), q));
    var regular = new ArrayList<WorkQueue>();
    for (var item : request.queues()) {
      var queue = byId.remove(item.queueId());
      if (queue == null)
        throw new ConflictException("Порядок содержит чужую или неизвестную очередь");
      checkVersion(queue.getVersion(), item.expectedVersion(), "Очередь");
      if (queue.getType() != QueueType.HOLDING) regular.add(queue);
    }
    if (!byId.isEmpty())
      throw new ConflictException("Порядок должен содержать полный набор очередей склада");
    var holding = current.stream().filter(q -> q.getType() == QueueType.HOLDING).toList();
    int order = 10;
    for (var q : regular) {
      q.setSortOrder(order);
      order += 10;
    }
    for (var q : holding) {
      q.setSortOrder(order);
      order += 10;
    }
    projectionWriter.saveAll(queues, current);
    projectionWriter.flush();
    projectionWriter.clear();
    var reordered = queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseId);
    reordered.forEach(queue -> eventSourcing.queueChanged(
        queue, streamVersions.get(queue.getId()), TaskBoardEventTypes.WORK_QUEUE_REORDERED));
    return reordered.stream().map(this::dto).toList();
  }

  @Transactional
  public void deleteQueue(UUID warehouseId, UUID id, long expectedVersion) {
    lockQueueOrder(warehouseId);
    var current = queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseId);
    var streamVersions =
        eventSourcingVersions(
            TaskBoardAggregateType.WORK_QUEUE,
            current.stream().map(WorkQueue::getId).toList());
    var orderBefore = queueOrders(current);
    var queue = requireQueue(warehouseId, id);
    checkVersion(queue.getVersion(), expectedVersion, "Очередь");
    if (!isQueueEmpty(id))
      throw new ConflictException("Используемую очередь можно только скрыть или деактивировать");
    eventSourcing.deleted(queue, streamVersions.get(id));
    projectionWriter.deleteAll(bindings, bindings.findAllByQueueId(id));
    projectionWriter.delete(queues, queue);
    projectionWriter.flush();
    normalizeOrder(warehouseId);
    projectionWriter.flush();
    appendReorderedQueues(warehouseId, id, orderBefore, streamVersions);
  }

  public WorkerClass requireClass(UUID id) {
    return classes
        .findById(id)
        .orElseThrow(() -> new NotFoundException("Класс рабочего не найден"));
  }

  public WorkQueue requireQueue(UUID warehouseId, UUID id) {
    var q = queues.findById(id).orElseThrow(() -> new NotFoundException("Очередь не найдена"));
    if (!q.getWarehouseId().equals(warehouseId)) throw new NotFoundException("Очередь не найдена");
    return q;
  }

  public WorkQueue requireQueue(UUID id) {
    return queues.findById(id).orElseThrow(() -> new NotFoundException("Очередь не найдена"));
  }

  public boolean isQueueEmpty(UUID id) {
    return !entries.existsByQueueId(id)
        && !events.existsByQueueEntryQueueId(id)
        && !references.existsByQueueId(id);
  }

  @Transactional
  public QueueReferenceDto registerReference(UUID queueId, QueueReferenceRequest request) {
    WorkQueue queue = requireQueue(queueId);
    String externalReferenceId = request.externalReferenceId().trim();
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "queue-reference:" + request.type() + ":" + externalReferenceId);
    var existing =
        references.findByReferenceTypeAndExternalReferenceId(request.type(), externalReferenceId);
    if (existing.isPresent()) {
      QueueUsageReference reference = existing.get();
      if (!reference.getQueue().getId().equals(queueId)) {
        throw new ConflictException("Внешняя ссылка уже закреплена за другой очередью");
      }
      return referenceDto(reference);
    }
    QueueUsageReference reference = new QueueUsageReference();
    reference.setQueue(queue);
    reference.setReferenceType(request.type());
    reference.setExternalReferenceId(externalReferenceId);
    reference = projectionWriter.save(references, reference);
    projectionWriter.flush();
    projectionWriter.refresh(reference);
    eventSourcing.created(reference);
    return referenceDto(reference);
  }

  @Transactional
  public void deleteReference(
      QueueReferenceType type, String externalReferenceId, long expectedVersion) {
    QueueUsageReference reference =
        references
            .findByReferenceTypeAndExternalReferenceId(type, externalReferenceId)
            .orElseThrow(() -> new NotFoundException("Ссылка на очередь не найдена"));
    checkVersion(reference.getVersion(), expectedVersion, "Ссылка на очередь");
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.QUEUE_USAGE_REFERENCE, reference.getId());
    eventSourcing.deleted(reference, streamVersion);
    projectionWriter.delete(references, reference);
    projectionWriter.flush();
  }

  public static void checkVersion(long actual, long expected, String resource) {
    if (actual != expected) throw new StaleVersionException(resource);
  }

  private void lockQueueOrder(UUID warehouseId) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "work-queue-order:" + warehouseId);
  }

  private java.util.Map<UUID, Long> eventSourcingVersions(
      TaskBoardAggregateType type, List<UUID> aggregateIds) {
    var versions = new LinkedHashMap<UUID, Long>();
    aggregateIds.stream().sorted().forEach(id -> versions.put(id, eventSourcing.lock(type, id)));
    return versions;
  }

  private java.util.Map<UUID, Integer> queueOrders(List<WorkQueue> values) {
    return values.stream()
        .collect(
            java.util.stream.Collectors.toMap(
                WorkQueue::getId,
                WorkQueue::getSortOrder,
                (left, right) -> left,
                LinkedHashMap::new));
  }

  private void appendReorderedQueues(
      UUID warehouseId,
      UUID excludedQueueId,
      java.util.Map<UUID, Integer> orderBefore,
      java.util.Map<UUID, Long> streamVersions) {
    projectionWriter.flush();
    for (WorkQueue value :
        queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseId)) {
      Integer previous = orderBefore.get(value.getId());
      if (!value.getId().equals(excludedQueueId)
          && previous != null
          && previous != value.getSortOrder()) {
        eventSourcing.queueChanged(
            value,
            streamVersions.get(value.getId()),
            TaskBoardEventTypes.WORK_QUEUE_REORDERED);
      }
    }
  }

  private void apply(WorkerClass entity, WorkerClassRequest r) {
    entity.setCode(r.code());
    entity.setName(r.name());
    entity.setDescription(r.description());
    entity.setComment(r.comment());
    entity.setSortOrder(r.sortOrder());
    entity.setActive(r.active());
  }

  private void apply(WorkQueue q, WorkQueueRequest r, boolean update) {
    q.setCode(r.code());
    q.setName(r.name());
    q.setDescription(r.description());
    q.setType(r.type());
    q.setActive(r.active());
    q.setHidden(r.hidden());
    q.setCollapsed(r.collapsed());
    boolean holding = r.type() == QueueType.HOLDING;
    q.setHoldingPeriodMinutes(holding ? r.holdingPeriodMinutes() : null);
    q.setNotificationThreshold(holding ? r.notificationThreshold() : null);
    q.setNotifyWhenThresholdReached(holding && r.notifyWhenThresholdReached());
  }

  private void replaceBindings(WorkQueue queue, List<QueueBindingRequest> requested) {
    projectionWriter.deleteAll(bindings, bindings.findAllByQueueId(queue.getId()));
    if (requested == null) return;
    var unique = new LinkedHashMap<UUID, QueueBindingRequest>();
    requested.forEach(r -> unique.put(r.workerClassId(), r));
    for (var r : unique.values()) {
      var b = new WorkQueueClassBinding();
      b.setQueue(queue);
      b.setWorkerClass(requireClass(r.workerClassId()));
      b.setStopTaskOnTake(r.stopTaskOnTake());
      projectionWriter.save(bindings, b);
    }
  }

  private void normalizeOrder(UUID warehouseId) {
    var ordered = queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseId);
    var result = new ArrayList<WorkQueue>();
    ordered.stream().filter(q -> q.getType() != QueueType.HOLDING).forEach(result::add);
    ordered.stream().filter(q -> q.getType() == QueueType.HOLDING).forEach(result::add);
    int i = 10;
    for (var q : result) {
      q.setSortOrder(i);
      i += 10;
    }
    projectionWriter.saveAll(queues, result);
  }

  private void refresh(WorkQueue queue) {
    projectionWriter.flush();
    projectionWriter.refresh(queue);
  }

  private int nextRegularSortOrder(UUID warehouseId) {
    return queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseId).stream()
            .filter(q -> q.getType() != QueueType.HOLDING)
            .mapToInt(WorkQueue::getSortOrder)
            .max()
            .orElse(0)
        + 10;
  }

  private void ensureClassCode(String code, UUID id) {
    if (classes.existsByCodeIgnoreCaseAndIdNot(
        code.trim().toUpperCase(Locale.ROOT), id == null ? new UUID(0, 0) : id))
      throw new ConflictException("Код класса уже используется");
  }

  private void ensureQueueCode(UUID warehouseId, String code, UUID id) {
    if (queues.existsByWarehouseIdAndCodeIgnoreCaseAndIdNot(
        warehouseId, code.trim().toUpperCase(Locale.ROOT), id == null ? new UUID(0, 0) : id))
      throw new ConflictException("Код очереди уже используется на складе");
  }

  public WorkerClassDto dto(WorkerClass e) {
    return new WorkerClassDto(
        e.getId(),
        e.getVersion(),
        e.getCode(),
        e.getName(),
        e.getDescription(),
        e.getComment(),
        e.getSortOrder(),
        e.isActive());
  }

  public WorkQueueDto dto(WorkQueue q) {
    return new WorkQueueDto(
        q.getId(),
        q.getVersion(),
        q.getWarehouseId(),
        q.getCode(),
        q.getName(),
        q.getDescription(),
        q.getType(),
        q.getSortOrder(),
        q.isActive(),
        q.isHidden(),
        q.isCollapsed(),
        q.getHoldingPeriodMinutes(),
        q.getNotificationThreshold(),
        q.isNotifyWhenThresholdReached(),
        bindings.findAllByQueueId(q.getId()).stream()
            .map(
                b ->
                    new QueueBindingDto(
                        b.getId(), b.getVersion(), dto(b.getWorkerClass()), b.isStopTaskOnTake()))
            .toList());
  }

  private QueueReferenceDto referenceDto(QueueUsageReference reference) {
    return new QueueReferenceDto(
        reference.getId(),
        reference.getVersion(),
        reference.getQueue().getId(),
        reference.getReferenceType(),
        reference.getExternalReferenceId());
  }
}
