package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueueDefinition;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
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
import dev.buhanzaz.rwms.taskboard.mapper.QueueRegistryMapper;
import dev.buhanzaz.rwms.taskboard.repository.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the queue registry boundary.
 *
 * <p>{@link QueueDefinition} is a global catalog record. {@link WorkQueue} is the explicit
 * warehouse-local connection to that record and owns all operational settings, class bindings and
 * presentation order. No catalog operation is allowed to manufacture or rewrite those local
 * connections.
 */
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
    Set<UUID> logisticsPrimaryClassIds = logisticsPrimaryClassIds();
    return classes.findAllByOrderBySortOrderAscNameAscIdAsc().stream()
        .map(workerClass -> mapper.toWorkerClassDto(workerClass, logisticsPrimaryClassIds))
        .toList();
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
        || qualifications.existsByWorkerClassId(id)) {
      throw new ConflictException("Используемый класс рабочего можно только деактивировать");
    }
    eventSourcing.deleted(entity, streamVersion);
    projectionWriter.delete(classes, entity);
    projectionWriter.flush();
  }

  @Transactional(readOnly = true)
  public List<WorkQueueDto> listQueues(UUID warehouseId) {
    Set<UUID> logisticsPrimaryClassIds = logisticsPrimaryClassIds();
    return queues.findAllOrderedByWarehouseId(warehouseId).stream()
        .map(queue -> dto(queue, logisticsPrimaryClassIds))
        .toList();
  }

  /**
   * Shared system catalog. The logistics driver definition is listed too, while only GENERAL
   * definitions are attachable through the ordinary warehouse-queue commands.
   */
  @Transactional(readOnly = true)
  public List<QueueDefinitionDto> listQueueDefinitions() {
    return definitions.findAllByOrderByNameAscTypeAscIdAsc().stream()
        .map(this::dto)
        .toList();
  }

  @Transactional
  public QueueDefinitionDto createQueueDefinition(QueueDefinitionRequest request) {
    requireGeneralRequest(request);
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
  public QueueDefinitionDto updateQueueDefinition(UUID id, QueueDefinitionRequest request) {
    requireGeneralRequest(request);
    QueueDefinition definition = requireGeneralQueueDefinition(id);
    checkVersion(definition.getVersion(), request.version(), "Общая очередь");
    String normalizedName = QueueDefinition.normalizeName(request.name());
    lockQueueDefinitionIdentity(normalizedName, request.type());
    definitions
        .findByNormalizedNameAndType(normalizedName, request.type())
        .filter(existing -> !existing.getId().equals(id))
        .ifPresent(
            ignored -> {
              throw new ConflictException("Общая очередь с таким названием и типом уже существует");
            });

    QueueType previousType = definition.getType();
    List<WorkQueue> connected = queues.findAllByDefinitionIdOrderByWarehouseIdAscIdAsc(id);
    Map<UUID, Long> streamVersions =
        previousType == request.type()
            ? Map.of()
            : eventSourcingVersions(
                TaskBoardAggregateType.WORK_QUEUE,
                connected.stream().map(WorkQueue::getId).toList());
    apply(definition, request);
    definition.touch();
    definition = projectionWriter.saveAndFlush(definitions, definition);

    // Holding-only values belong to the local connection. Clear now-invalid values if a catalog
    // type is changed away from HOLDING, but never copy any setting between warehouses.
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
    QueueDefinition definition = requireGeneralQueueDefinition(id);
    checkVersion(definition.getVersion(), expectedVersion, "Общая очередь");
    if (references.existsByDefinitionId(id)) {
      throw new ConflictException("Общая очередь используется каталогом и не может быть удалена");
    }
    if (queues.existsByDefinitionId(id)) {
      throw new ConflictException("Подключённую к складу общую очередь удалить нельзя");
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
            .map(queue -> new MovementQueueCapability(queue.getDefinition().getId(), queue.getId()))
            .toList();
    return new WarehouseQueueCapabilities(
        warehouseId, !movementQueues.isEmpty(), movementQueues);
  }

  /** Explicitly attaches a GENERAL catalog definition to one warehouse. */
  @Transactional
  public WorkQueueDto createQueue(UUID warehouseId, WorkQueueRequest request) {
    lockQueueOrder(warehouseId);
    QueueDefinition definition = requireGeneralQueueDefinition(request.definitionId());
    if (queues.existsByWarehouseIdAndDefinitionId(warehouseId, definition.getId())) {
      throw new ConflictException("Общая очередь уже подключена к этому складу");
    }
    var existing = queues.findAllOrderedByWarehouseId(warehouseId);
    var streamVersions =
        eventSourcingVersions(
            TaskBoardAggregateType.WORK_QUEUE, existing.stream().map(WorkQueue::getId).toList());
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

  /** Updates only a warehouse-local GENERAL connection; its definition is immutable. */
  @Transactional
  public WorkQueueDto updateQueue(UUID warehouseId, UUID id, WorkQueueRequest request) {
    lockQueueOrder(warehouseId);
    var current = queues.findAllOrderedByWarehouseId(warehouseId);
    var streamVersions =
        eventSourcingVersions(
            TaskBoardAggregateType.WORK_QUEUE, current.stream().map(WorkQueue::getId).toList());
    var orderBefore = queueOrders(current);
    var queue = requireQueue(warehouseId, id);
    requireGeneralQueueDefinition(queue.getDefinition().getId());
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

  /** Reorders only the selected warehouse's connections. HOLDING remains terminal locally. */
  @Transactional
  public List<WorkQueueDto> reorder(UUID warehouseId, QueueOrderRequest request) {
    lockQueueOrder(warehouseId);
    var current = queues.findAllOrderedByWarehouseId(warehouseId);
    var streamVersions =
        eventSourcingVersions(
            TaskBoardAggregateType.WORK_QUEUE, current.stream().map(WorkQueue::getId).toList());
    if (request.queues().size() != current.size()) {
      throw new ConflictException("Порядок должен содержать полный набор очередей склада");
    }
    var requestedIds = new java.util.HashSet<UUID>();
    if (request.queues().stream().anyMatch(item -> !requestedIds.add(item.queueId()))) {
      throw new ConflictException("Порядок содержит повторяющуюся очередь");
    }
    var byId = new LinkedHashMap<UUID, WorkQueue>();
    current.forEach(queue -> byId.put(queue.getId(), queue));
    var regular = new ArrayList<WorkQueue>();
    for (QueueOrderItem item : request.queues()) {
      WorkQueue queue = byId.remove(item.queueId());
      if (queue == null) {
        throw new ConflictException("Порядок содержит чужую или неизвестную очередь");
      }
      checkVersion(queue.getVersion(), item.expectedVersion(), "Очередь");
      if (queue.getType() != QueueType.HOLDING) {
        regular.add(queue);
      }
    }
    if (!byId.isEmpty()) {
      throw new ConflictException("Порядок должен содержать полный набор очередей склада");
    }
    var holding = current.stream().filter(queue -> queue.getType() == QueueType.HOLDING).toList();
    int order = 10;
    for (WorkQueue queue : regular) {
      queue.setSortOrder(order);
      order += 10;
    }
    for (WorkQueue queue : holding) {
      queue.setSortOrder(order);
      order += 10;
    }
    projectionWriter.saveAll(queues, current);
    projectionWriter.flush();
    projectionWriter.clear();
    var reordered = queues.findAllOrderedByWarehouseId(warehouseId);
    reordered.forEach(
        queue ->
            eventSourcing.queueChanged(
                queue,
                streamVersions.get(queue.getId()),
                TaskBoardEventTypes.WORK_QUEUE_REORDERED));
    return reordered.stream().map(this::dto).toList();
  }

  @Transactional
  public void deleteQueue(UUID warehouseId, UUID id, long expectedVersion) {
    lockQueueOrder(warehouseId);
    var current = queues.findAllOrderedByWarehouseId(warehouseId);
    var streamVersions =
        eventSourcingVersions(
            TaskBoardAggregateType.WORK_QUEUE, current.stream().map(WorkQueue::getId).toList());
    var orderBefore = queueOrders(current);
    var queue = requireQueue(warehouseId, id);
    requireGeneralQueueDefinition(queue.getDefinition().getId());
    checkVersion(queue.getVersion(), expectedVersion, "Очередь");
    if (!isQueueEmpty(id)) {
      throw new ConflictException("Используемую очередь можно только скрыть или деактивировать");
    }
    eventSourcing.deleted(queue, streamVersions.get(id));
    projectionWriter.deleteAll(bindings, bindings.findAllByQueueId(id));
    projectionWriter.delete(queues, queue);
    projectionWriter.flush();
    normalizeOrder(warehouseId);
    projectionWriter.flush();
    appendReorderedQueues(warehouseId, id, orderBefore, streamVersions);
  }

  /**
   * The driver queue keeps its dedicated logistics settings endpoint. It is not copied on warehouse
   * creation and it never supplies a fallback GENERAL queue.
   */
  @Transactional
  public WorkQueueDto updateDriverQueue(UUID warehouseId, DriverQueueRequest request) {
    lockQueueOrder(warehouseId);
    List<WorkQueue> driverQueues =
        queues.findAllByWarehouseIdAndPurpose(warehouseId, QueuePurpose.LOGISTICS_DRIVER);
    if (driverQueues.size() > 1) {
      throw new ConflictException("Для склада найдено несколько очередей водителей");
    }

    WorkQueue queue;
    if (driverQueues.isEmpty()) {
      checkVersion(0, request.expectedVersion(), "Очередь водителей");
      List<QueueDefinition> driverDefinitions =
          definitions.findAllByPurposeOrderByNameAscIdAsc(QueuePurpose.LOGISTICS_DRIVER);
      if (driverDefinitions.isEmpty()) {
        throw new NotFoundException("Определение очереди водителей не найдено");
      }
      if (driverDefinitions.size() > 1) {
        throw new ConflictException("Настройка очереди водителей неоднозначна");
      }
      QueueDefinition definition = driverDefinitions.getFirst();
      if (definition.getType() != QueueType.MOVEMENT) {
        throw new ConflictException("Очередь водителей должна иметь тип MOVEMENT");
      }
      queue = new WorkQueue();
      queue.setWarehouseId(warehouseId);
      queue.setDefinition(definition);
      queue.setSortOrder(nextRegularSortOrder(warehouseId));
      apply(queue, request);
      queue = projectionWriter.save(queues, queue);
      replaceBindings(queue, request.bindings());
      refresh(queue);
      eventSourcing.created(queue);
    } else {
      queue = driverQueues.getFirst();
      checkVersion(queue.getVersion(), request.expectedVersion(), "Очередь водителей");
      long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORK_QUEUE, queue.getId());
      apply(queue, request);
      queue.touch();
      queue = projectionWriter.save(queues, queue);
      replaceBindings(queue, request.bindings());
      refresh(queue);
      eventSourcing.queueChanged(queue, streamVersion, TaskBoardEventTypes.WORK_QUEUE_CHANGED);
    }
    return dto(queue);
  }

  public WorkerClass requireClass(UUID id) {
    return classes
        .findById(id)
        .orElseThrow(() -> new NotFoundException("Класс рабочего не найден"));
  }

  public WorkQueue requireQueue(UUID warehouseId, UUID id) {
    WorkQueue queue = queues.findById(id).orElseThrow(() -> new NotFoundException("Очередь не найдена"));
    if (!queue.getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Очередь не найдена");
    }
    return queue;
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
            () -> new ConflictException("Общая очередь не подключена к целевому складу"));
  }

  public boolean isQueueEmpty(UUID id) {
    return !entries.existsByQueueId(id) && !events.existsByQueueEntryQueueId(id);
  }

  @Transactional
  public QueueReferenceDto registerReference(UUID queueDefinitionId, QueueReferenceRequest request) {
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
        throw new ConflictException("Внешняя ссылка уже закреплена за другой общей очередью");
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
    long streamVersion =
        eventSourcing.lock(TaskBoardAggregateType.QUEUE_USAGE_REFERENCE, reference.getId());
    eventSourcing.deleted(reference, streamVersion);
    projectionWriter.delete(references, reference);
    projectionWriter.flush();
  }

  public static void checkVersion(long actual, long expected, String resource) {
    if (actual != expected) {
      throw new StaleVersionException(resource);
    }
  }

  private QueueDefinition requireGeneralQueueDefinition(UUID id) {
    return definitions
        .findByIdAndPurpose(id, QueuePurpose.GENERAL)
        .orElseThrow(() -> new NotFoundException("Ремонтная таблица не найдена"));
  }

  private void requireGeneralRequest(QueueDefinitionRequest request) {
    if (request.purpose() != QueuePurpose.GENERAL) {
      throw new IllegalArgumentException(
          "Глобальный реестр принимает только ремонтные таблицы GENERAL");
    }
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

  private Map<UUID, Long> eventSourcingVersions(
      TaskBoardAggregateType type, List<UUID> aggregateIds) {
    var versions = new LinkedHashMap<UUID, Long>();
    aggregateIds.stream().sorted().forEach(id -> versions.put(id, eventSourcing.lock(type, id)));
    return versions;
  }

  private Map<UUID, Integer> queueOrders(List<WorkQueue> values) {
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
      Map<UUID, Integer> orderBefore,
      Map<UUID, Long> streamVersions) {
    projectionWriter.flush();
    for (WorkQueue value : queues.findAllOrderedByWarehouseId(warehouseId)) {
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

  private void apply(WorkerClass entity, WorkerClassRequest request) {
    entity.setName(request.name());
    entity.setDescription(request.description());
    entity.setComment(request.comment());
    entity.setSortOrder(request.sortOrder());
    entity.setActive(request.active());
  }

  private void apply(QueueDefinition entity, QueueDefinitionRequest request) {
    entity.setName(request.name());
    entity.setDescription(request.description());
    entity.setType(request.type());
    entity.setPurpose(request.purpose());
  }

  private void apply(WorkQueue queue, WorkQueueRequest request, boolean update) {
    queue.setActive(request.active());
    queue.setHidden(request.hidden());
    queue.setCollapsed(request.collapsed());
    boolean holding = queue.getType() == QueueType.HOLDING;
    queue.setHoldingPeriodMinutes(holding ? request.holdingPeriodMinutes() : null);
    queue.setNotificationThreshold(holding ? request.notificationThreshold() : null);
    queue.setNotifyWhenThresholdReached(holding && request.notifyWhenThresholdReached());
    if (request.resultPhotoMinCount() != null) {
      queue.setResultPhotoMinCount(request.resultPhotoMinCount());
    } else if (!update) {
      queue.setResultPhotoMinCount(holding ? 0 : 1);
    }
  }

  private void apply(WorkQueue queue, DriverQueueRequest request) {
    queue.setActive(request.active());
    queue.setHidden(request.hidden());
    queue.setCollapsed(request.collapsed());
    boolean holding = queue.getType() == QueueType.HOLDING;
    queue.setHoldingPeriodMinutes(holding ? request.holdingPeriodMinutes() : null);
    queue.setNotificationThreshold(holding ? request.notificationThreshold() : null);
    queue.setNotifyWhenThresholdReached(holding && request.notifyWhenThresholdReached());
    queue.setResultPhotoMinCount(request.resultPhotoMinCount());
  }

  private void replaceBindings(WorkQueue queue, List<QueueBindingRequest> requested) {
    List<WorkQueueClassBinding> existing = bindings.findAllByQueueId(queue.getId());
    projectionWriter.deleteAll(bindings, existing);
    if (!existing.isEmpty()) {
      projectionWriter.flush();
    }
    if (requested == null) {
      return;
    }
    var unique = new LinkedHashMap<UUID, QueueBindingRequest>();
    requested.stream()
        .sorted(
            Comparator.comparingInt(QueueBindingRequest::order)
                .thenComparing(request -> request.workerClassId().toString()))
        .forEach(
            request -> {
              if (unique.putIfAbsent(request.workerClassId(), request) != null) {
                throw new ConflictException("Класс рабочего указан в очереди повторно");
              }
            });
    int order = 0;
    for (QueueBindingRequest request : unique.values()) {
      ParticipationPolicy policy = request.participationPolicy();
      if (policy == null) {
        throw new IllegalArgumentException("Для класса рабочего обязательна политика участия");
      }
      if (order == 0 && policy != ParticipationPolicy.PRIMARY) {
        throw new ConflictException("Первичный класс должен иметь PRIMARY-политику");
      }
      if (order > 0 && policy == ParticipationPolicy.PRIMARY) {
        throw new ConflictException("Вторичный класс не может иметь PRIMARY-политику");
      }
      var binding = new WorkQueueClassBinding();
      binding.setQueue(queue);
      binding.setWorkerClass(requireClass(request.workerClassId()));
      binding.setBindingOrder(order);
      binding.setStopTaskOnTake(request.stopTaskOnTake());
      binding.setParticipationPolicy(policy);
      binding.setNotifyOnPrimaryTake(order > 0 && request.notifyOnPrimaryTake());
      projectionWriter.save(bindings, binding);
      order++;
    }
  }

  private void normalizeOrder(UUID warehouseId) {
    List<WorkQueue> ordered = queues.findAllOrderedByWarehouseId(warehouseId);
    var result = new ArrayList<WorkQueue>();
    ordered.stream().filter(queue -> queue.getType() != QueueType.HOLDING).forEach(result::add);
    ordered.stream().filter(queue -> queue.getType() == QueueType.HOLDING).forEach(result::add);
    int sortOrder = 10;
    for (WorkQueue queue : result) {
      queue.setSortOrder(sortOrder);
      sortOrder += 10;
    }
    projectionWriter.saveAll(queues, result);
  }

  private void refresh(WorkQueue queue) {
    projectionWriter.flush();
    projectionWriter.refresh(queue);
  }

  private int nextRegularSortOrder(UUID warehouseId) {
    return queues.findAllOrderedByWarehouseId(warehouseId).stream()
            .filter(queue -> queue.getType() != QueueType.HOLDING)
            .mapToInt(WorkQueue::getSortOrder)
            .max()
            .orElse(0)
        + 10;
  }

  @Transactional(readOnly = true)
  public WorkerClassDto dto(WorkerClass workerClass) {
    return mapper.toWorkerClassDto(workerClass, logisticsPrimaryClassIds());
  }

  @Transactional(readOnly = true)
  public WorkQueueDto dto(WorkQueue queue) {
    return dto(queue, logisticsPrimaryClassIds());
  }

  private WorkQueueDto dto(WorkQueue queue, Set<UUID> logisticsPrimaryClassIds) {
    WorkQueue managed = queues.findByIdWithDefinition(queue.getId()).orElse(queue);
    return mapper.toWorkQueueDto(
        managed,
        bindings.findAllByQueueIdOrderByBindingOrderAscIdAsc(managed.getId()),
        logisticsPrimaryClassIds);
  }

  @Transactional(readOnly = true)
  public QueueDefinitionDto dto(QueueDefinition definition) {
    return mapper.toQueueDefinitionDto(definition);
  }

  private QueueReferenceDto referenceDto(QueueUsageReference reference) {
    return mapper.toQueueReferenceDto(reference);
  }

  private Set<UUID> logisticsPrimaryClassIds() {
    return Set.copyOf(
        bindings.findDedicatedLogisticsPrimaryWorkerClassIds(
            QueuePurpose.LOGISTICS_DRIVER, QueuePurpose.GENERAL, ParticipationPolicy.PRIMARY));
  }
}
