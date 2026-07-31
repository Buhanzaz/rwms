package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.domain.QueueDefinition;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.QueueUsageReference;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.mapper.QueueRegistryMapper;
import dev.buhanzaz.rwms.taskboard.repository.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistryService {
  private final WorkerClassRepository classes;
  private final QueueDefinitionRepository definitions;
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
  private final QueueRegistryMapper mapper;

  public RegistryService(
      WorkerClassRepository classes,
      QueueDefinitionRepository definitions,
      WorkQueueRepository queues,
      WorkQueueClassBindingRepository bindings,
      QueueEntryRepository entries,
      TaskTimeEventRepository events,
      QueueUsageReferenceRepository references,
      WorkerGroupRepository groups,
      WorkerClassAssignmentRepository qualifications,
      JdbcTemplate jdbc,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter,
      QueueRegistryMapper mapper) {
    this.classes = classes;
    this.definitions = definitions;
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
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public List<WorkerClassDto> listClasses() {
    return classes.findAllByOrderBySortOrderAscNameAscIdAsc().stream().map(this::dto).toList();
  }

  @Transactional
  public WorkerClassDto createClass(WorkerClassRequest request) {
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
    return queues.findAllOrderedByWarehouseId(warehouseId).stream()
        .map(this::dto)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<QueueDefinitionDto> listQueueDefinitions() {
    return definitions.findAllByOrderByNameAscTypeAscIdAsc().stream()
        .map(this::dto)
        .toList();
  }

  @Transactional
  public QueueDefinitionDto createQueueDefinition(QueueDefinitionRequest request) {
    String normalizedName = QueueDefinition.normalizeName(request.name());
    lockQueueDefinitionIdentity(normalizedName, request.type());
    if (definitions.existsByNormalizedNameAndType(normalizedName, request.type())) {
      throw new ConflictException("Общая очередь с таким названием и типом уже существует");
    }
    var definition = new QueueDefinition();
    apply(definition, request);
    definition = projectionWriter.saveAndFlush(definitions, definition);
    projectionWriter.refresh(definition);
    return dto(definition);
  }

  @Transactional
  public QueueDefinitionDto updateQueueDefinition(
      UUID id, QueueDefinitionRequest request) {
    QueueDefinition definition = requireQueueDefinition(id);
    checkVersion(definition.getVersion(), request.version(), "Общая очередь");
    String normalizedName = QueueDefinition.normalizeName(request.name());
    lockQueueDefinitionIdentity(normalizedName, request.type());
    definitions
        .findByNormalizedNameAndType(normalizedName, request.type())
        .filter(existing -> !existing.getId().equals(id))
        .ifPresent(
            ignored -> {
              throw new ConflictException(
                  "Общая очередь с таким названием и типом уже существует");
            });

    QueueType previousType = definition.getType();
    List<WorkQueue> connected = queues.findAllByDefinitionIdOrderByWarehouseIdAscIdAsc(id);
    var streamVersions =
        previousType == request.type()
            ? java.util.Map.<UUID, Long>of()
            : eventSourcingVersions(
                TaskBoardAggregateType.WORK_QUEUE,
                connected.stream().map(WorkQueue::getId).toList());
    apply(definition, request);
    definition.touch();
    definition = projectionWriter.saveAndFlush(definitions, definition);

    if (previousType != request.type()) {
      for (WorkQueue queue : connected) {
        if (request.type() != QueueType.HOLDING) {
          queue.setHoldingPeriodMinutes(null);
          queue.setNotificationThreshold(null);
          queue.setNotifyWhenThresholdReached(false);
        }
        queue.touch();
        projectionWriter.save(queues, queue);
      }
      projectionWriter.flush();
      for (WorkQueue queue : connected) {
        eventSourcing.queueChanged(
            queue,
            streamVersions.get(queue.getId()),
            TaskBoardEventTypes.WORK_QUEUE_CHANGED);
      }
    }
    return dto(definition);
  }

  @Transactional
  public void deleteQueueDefinition(UUID id, long expectedVersion) {
    QueueDefinition definition = requireQueueDefinition(id);
    checkVersion(definition.getVersion(), expectedVersion, "Общая очередь");
    if (queues.existsByDefinitionId(id)) {
      throw new ConflictException(
          "Подключённую к складу общую очередь удалить нельзя");
    }
    if (references.existsByDefinitionId(id)) {
      throw new ConflictException(
          "Общая очередь используется каталогом и не может быть удалена");
    }
    projectionWriter.delete(definitions, definition);
    projectionWriter.flush();
  }

  @Transactional(readOnly = true)
  public WarehouseQueueCapabilities queueCapabilities(UUID warehouseId) {
    List<MovementQueueCapability> movementQueues =
        queues.findAllActiveOrderedByWarehouseId(warehouseId).stream()
            .filter(queue -> !queue.isHidden())
            .filter(queue -> queue.getPurpose() == QueuePurpose.LOGISTICS_DRIVER)
            .map(
                queue ->
                    new MovementQueueCapability(
                        queue.getDefinition().getId(), queue.getId()))
            .toList();
    return new WarehouseQueueCapabilities(
        warehouseId, !movementQueues.isEmpty(), movementQueues);
  }

  @Transactional
  public WorkQueueDto createQueue(UUID warehouseId, WorkQueueRequest request) {
    lockQueueOrder(warehouseId);
    QueueDefinition definition = requireQueueDefinition(request.definitionId());
    if (queues.existsByWarehouseIdAndDefinitionId(warehouseId, definition.getId())) {
      throw new ConflictException("Общая очередь уже подключена к этому складу");
    }
    var existing = queues.findAllOrderedByWarehouseId(warehouseId);
    var streamVersions =
        eventSourcingVersions(
            TaskBoardAggregateType.WORK_QUEUE,
            existing.stream().map(WorkQueue::getId).toList());
    var orderBefore = queueOrders(existing);
    var queue = new WorkQueue();
    queue.setWarehouseId(warehouseId);
    queue.setDefinition(definition);
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
    var current = queues.findAllOrderedByWarehouseId(warehouseId);
    var streamVersions =
        eventSourcingVersions(
            TaskBoardAggregateType.WORK_QUEUE,
            current.stream().map(WorkQueue::getId).toList());
    var orderBefore = queueOrders(current);
    var queue = requireQueue(warehouseId, id);
    checkVersion(queue.getVersion(), request.version(), "Очередь");
    if (!queue.getDefinition().getId().equals(request.definitionId())) {
      throw new ConflictException(
          "Общее определение существующего складского подключения изменять нельзя");
    }
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
    var current = queues.findAllOrderedByWarehouseId(warehouseId);
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
    var reordered = queues.findAllOrderedByWarehouseId(warehouseId);
    reordered.forEach(queue -> eventSourcing.queueChanged(
        queue, streamVersions.get(queue.getId()), TaskBoardEventTypes.WORK_QUEUE_REORDERED));
    return reordered.stream().map(this::dto).toList();
  }

  @Transactional
  public void deleteQueue(UUID warehouseId, UUID id, long expectedVersion) {
    lockQueueOrder(warehouseId);
    var current = queues.findAllOrderedByWarehouseId(warehouseId);
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

  public QueueDefinition requireQueueDefinition(UUID id) {
    return definitions
        .findById(id)
        .orElseThrow(() -> new NotFoundException("Общая очередь не найдена"));
  }

  public WorkQueue requireWarehouseBinding(UUID warehouseId, UUID definitionId) {
    return queues
        .findByWarehouseIdAndDefinitionId(warehouseId, definitionId)
        .orElseThrow(
            () ->
                new ConflictException(
                    "Общая очередь не подключена к целевому складу"));
  }

  public boolean isQueueEmpty(UUID id) {
    return !entries.existsByQueueId(id)
        && !events.existsByQueueEntryQueueId(id);
  }

  @Transactional
  public QueueReferenceDto registerReference(
      UUID queueDefinitionId, QueueReferenceRequest request) {
    QueueDefinition definition = requireQueueDefinition(queueDefinitionId);
    String externalReferenceId = request.externalReferenceId().trim();
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "queue-reference:" + request.type() + ":" + externalReferenceId);
    var existing =
        references.findByReferenceTypeAndExternalReferenceId(request.type(), externalReferenceId);
    if (existing.isPresent()) {
      QueueUsageReference reference = existing.get();
      if (!reference.getDefinition().getId().equals(queueDefinitionId)) {
        throw new ConflictException(
            "Внешняя ссылка уже закреплена за другой общей очередью");
      }
      return referenceDto(reference);
    }
    QueueUsageReference reference = new QueueUsageReference();
    reference.setDefinition(definition);
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

  private void lockQueueDefinitionIdentity(String normalizedName, QueueType type) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "queue-definition:" + type + ":" + normalizedName);
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
        queues.findAllOrderedByWarehouseId(warehouseId)) {
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
    entity.setName(r.name());
    entity.setDescription(r.description());
    entity.setComment(r.comment());
    entity.setSortOrder(r.sortOrder());
    entity.setActive(r.active());
  }

  private void apply(QueueDefinition entity, QueueDefinitionRequest request) {
    if (request.purpose() == QueuePurpose.LOGISTICS_DRIVER
        && request.type() != QueueType.MOVEMENT) {
      throw new IllegalArgumentException(
          "Логистическая очередь водителей должна иметь тип MOVEMENT");
    }
    entity.setName(request.name());
    entity.setDescription(request.description());
    entity.setType(request.type());
    entity.setPurpose(request.purpose());
  }

  private void apply(WorkQueue q, WorkQueueRequest r, boolean update) {
    q.setActive(r.active());
    q.setHidden(r.hidden());
    q.setCollapsed(r.collapsed());
    boolean holding = q.getType() == QueueType.HOLDING;
    q.setHoldingPeriodMinutes(holding ? r.holdingPeriodMinutes() : null);
    q.setNotificationThreshold(holding ? r.notificationThreshold() : null);
    q.setNotifyWhenThresholdReached(holding && r.notifyWhenThresholdReached());
    if (r.resultPhotoMinCount() != null) {
      q.setResultPhotoMinCount(r.resultPhotoMinCount());
    } else if (!update) {
      q.setResultPhotoMinCount(holding ? 0 : 1);
    }
  }

  private void replaceBindings(WorkQueue queue, List<QueueBindingRequest> requested) {
    var existing = bindings.findAllByQueueId(queue.getId());
    projectionWriter.deleteAll(bindings, existing);
    if (!existing.isEmpty()) {
      projectionWriter.flush();
    }
    if (requested == null) return;
    var unique = new LinkedHashMap<UUID, QueueBindingRequest>();
    requested.stream()
        .sorted(
            java.util.Comparator.comparingInt(QueueBindingRequest::order)
                .thenComparing(request -> request.workerClassId().toString()))
        .forEach(
            request -> {
              if (unique.putIfAbsent(request.workerClassId(), request) != null) {
                throw new ConflictException("Класс рабочего указан в очереди повторно");
              }
            });
    int order = 0;
    for (var r : unique.values()) {
      var b = new WorkQueueClassBinding();
      b.setQueue(queue);
      b.setWorkerClass(requireClass(r.workerClassId()));
      b.setBindingOrder(order);
      b.setStopTaskOnTake(r.stopTaskOnTake());
      ParticipationPolicy policy =
          order == 0
              ? ParticipationPolicy.PRIMARY
              : r.participationPolicy() == null
                  ? ParticipationPolicy.OPTIONAL
                  : r.participationPolicy();
      if (order > 0 && policy == ParticipationPolicy.PRIMARY) {
        throw new ConflictException("Вторичный класс не может иметь PRIMARY-политику");
      }
      b.setParticipationPolicy(policy);
      b.setNotifyOnPrimaryTake(order > 0 && r.notifyOnPrimaryTake());
      projectionWriter.save(bindings, b);
      order++;
    }
  }

  private void normalizeOrder(UUID warehouseId) {
    var ordered = queues.findAllOrderedByWarehouseId(warehouseId);
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
    return queues.findAllOrderedByWarehouseId(warehouseId).stream()
            .filter(q -> q.getType() != QueueType.HOLDING)
            .mapToInt(WorkQueue::getSortOrder)
            .max()
            .orElse(0)
        + 10;
  }

  public WorkerClassDto dto(WorkerClass e) {
    return mapper.toWorkerClassDto(e);
  }

  public WorkQueueDto dto(WorkQueue q) {
    return mapper.toWorkQueueDto(
        q, bindings.findAllByQueueIdOrderByBindingOrderAscIdAsc(q.getId()));
  }

  public QueueDefinitionDto dto(QueueDefinition definition) {
    return mapper.toQueueDefinitionDto(definition);
  }

  private QueueReferenceDto referenceDto(QueueUsageReference reference) {
    return mapper.toQueueReferenceDto(reference);
  }
}
