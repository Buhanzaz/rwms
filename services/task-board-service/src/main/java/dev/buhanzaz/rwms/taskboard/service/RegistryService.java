package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueueDefinition;
import dev.buhanzaz.rwms.taskboard.domain.QueueDefinitionClassBinding;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the queue registry boundary.
 *
 * <p>{@link QueueDefinition} owns the shared GENERAL task-board standard: order, visibility,
 * runtime limits and worker classes. {@link WorkQueue} is a derived per-warehouse projection whose
 * stable UUID preserves task history and routing references.
 */
@Service
public class RegistryService {
  private final WorkerClassRepository classes;
  private final QueueDefinitionRepository definitions;
  private final QueueDefinitionClassBindingRepository definitionBindings;
  private final WorkQueueRepository queues;
  private final WorkQueueClassBindingRepository bindings;
  private final QueueUsageReferenceRepository references;
  private final WorkerGroupRepository groups;
  private final WorkerClassAssignmentRepository qualifications;
  private final JdbcTemplate jdbc;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;
  private final GlobalQueueProjectionService globalQueueProjections;
  private final QueueRegistryMapper mapper;

  public RegistryService(
      WorkerClassRepository classes,
      QueueDefinitionRepository definitions,
      QueueDefinitionClassBindingRepository definitionBindings,
      WorkQueueRepository queues,
      WorkQueueClassBindingRepository bindings,
      QueueUsageReferenceRepository references,
      WorkerGroupRepository groups,
      WorkerClassAssignmentRepository qualifications,
      JdbcTemplate jdbc,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter,
      GlobalQueueProjectionService globalQueueProjections,
      QueueRegistryMapper mapper) {
    this.classes = classes;
    this.definitions = definitions;
    this.definitionBindings = definitionBindings;
    this.queues = queues;
    this.bindings = bindings;
    this.references = references;
    this.groups = groups;
    this.qualifications = qualifications;
    this.jdbc = jdbc;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
    this.globalQueueProjections = globalQueueProjections;
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
        || definitionBindings.existsByWorkerClassId(id)
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

  /** Shared system catalog. GENERAL rows carry the common task-board standard. */
  @Transactional(readOnly = true)
  public List<QueueDefinitionDto> listQueueDefinitions() {
    var result = new java.util.ArrayList<QueueDefinition>(globalDefinitions());
    result.addAll(definitions.findAllByPurposeOrderByNameAscIdAsc(QueuePurpose.LOGISTICS_DRIVER));
    return result.stream()
        .map(this::dto)
        .toList();
  }

  @Transactional
  public QueueDefinitionDto createQueueDefinition(QueueDefinitionRequest request) {
    requireGeneralRequest(request);
    lockGlobalQueueOrder();
    String normalizedName = QueueDefinition.normalizeName(request.name());
    lockQueueDefinitionIdentity(normalizedName, request.type());
    if (definitions.existsByNormalizedNameAndType(normalizedName, request.type())) {
      throw new ConflictException("Общая очередь с таким названием и типом уже существует");
    }
    var definition = new QueueDefinition();
    apply(definition, request);
    definition.setSortOrder(nextGlobalRegularSortOrder());
    definition = projectionWriter.saveAndFlush(definitions, definition);
    projectionWriter.refresh(definition);
    replaceDefinitionBindings(definition, request.bindings());
    normalizeGlobalOrder();
    globalQueueProjections.synchronizeAll();
    return dto(requireGeneralQueueDefinition(definition.getId()));
  }

  @Transactional
  public QueueDefinitionDto updateQueueDefinition(UUID id, QueueDefinitionRequest request) {
    requireGeneralRequest(request);
    lockGlobalQueueOrder();
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

    apply(definition, request);
    definition.touch();
    definition = projectionWriter.saveAndFlush(definitions, definition);
    replaceDefinitionBindings(definition, request.bindings());
    normalizeGlobalOrder();
    globalQueueProjections.synchronizeAll();
    return dto(requireGeneralQueueDefinition(id));
  }

  @Transactional
  public void deleteQueueDefinition(UUID id, long expectedVersion) {
    lockGlobalQueueOrder();
    QueueDefinition definition = requireGeneralQueueDefinition(id);
    checkVersion(definition.getVersion(), expectedVersion, "Общая очередь");
    if (references.existsByDefinitionId(id)) {
      throw new ConflictException("Общая очередь используется каталогом и не может быть удалена");
    }
    globalQueueProjections.deleteDefinitionProjections(definition);
    projectionWriter.deleteAll(
        definitionBindings,
        definitionBindings.findAllByDefinitionIdOrderByBindingOrderAscIdAsc(definition.getId()));
    projectionWriter.delete(definitions, definition);
    projectionWriter.flush();
    normalizeGlobalOrder();
    globalQueueProjections.synchronizeAll();
  }

  @Transactional
  public List<QueueDefinitionDto> reorderQueueDefinitions(QueueDefinitionOrderRequest request) {
    lockGlobalQueueOrder();
    List<QueueDefinition> current = globalDefinitions();
    if (request.definitions().size() != current.size()) {
      throw new ConflictException("Порядок должен содержать полный каталог очередей");
    }
    var requestedIds = new java.util.HashSet<UUID>();
    if (request.definitions().stream().anyMatch(item -> !requestedIds.add(item.definitionId()))) {
      throw new ConflictException("Порядок содержит повторяющуюся очередь");
    }

    var byId = new LinkedHashMap<UUID, QueueDefinition>();
    current.forEach(definition -> byId.put(definition.getId(), definition));
    var regular = new java.util.ArrayList<QueueDefinition>();
    for (QueueDefinitionOrderItem item : request.definitions()) {
      QueueDefinition definition = byId.remove(item.definitionId());
      if (definition == null) {
        throw new ConflictException("Порядок содержит чужую или неизвестную очередь");
      }
      checkVersion(definition.getVersion(), item.expectedVersion(), "Общая очередь");
      if (definition.getType() != QueueType.HOLDING) {
        regular.add(definition);
      }
    }
    if (!byId.isEmpty()) {
      throw new ConflictException("Порядок должен содержать полный каталог очередей");
    }

    var ordered = new java.util.ArrayList<QueueDefinition>(regular);
    current.stream()
        .filter(definition -> definition.getType() == QueueType.HOLDING)
        .forEach(ordered::add);
    int sortOrder = 1;
    for (QueueDefinition definition : ordered) {
      if (definition.getSortOrder() != sortOrder) {
        definition.setSortOrder(sortOrder);
        definition.touch();
      }
      sortOrder++;
    }
    projectionWriter.saveAll(definitions, ordered);
    projectionWriter.flush();
    globalQueueProjections.synchronizeAll();
    return globalDefinitions().stream().map(this::dto).toList();
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
            () ->
                new ConflictException(
                    "Общая очередь не подключена: она ещё не материализована для целевого склада"));
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
        "logistics-driver-queue-order:" + warehouseId);
  }

  private void lockGlobalQueueOrder() {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "queue-definition-global-order");
  }

  private void lockQueueDefinitionIdentity(String normalizedName, QueueType type) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "queue-definition:" + type + ":" + normalizedName);
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
    entity.setActive(request.active());
    entity.setHidden(request.hidden());
    entity.setCollapsed(request.collapsed());
    boolean holding = request.type() == QueueType.HOLDING;
    entity.setHoldingPeriodMinutes(holding ? request.holdingPeriodMinutes() : null);
    entity.setNotificationThreshold(holding ? request.notificationThreshold() : null);
    entity.setNotifyWhenThresholdReached(holding && request.notifyWhenThresholdReached());
    if (request.resultPhotoMinCount() == null) {
      throw new IllegalArgumentException("Для общей очереди обязателен минимум фотографий результата");
    }
    entity.setResultPhotoMinCount(request.resultPhotoMinCount());
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

  private void replaceDefinitionBindings(
      QueueDefinition definition, List<QueueBindingRequest> requested) {
    List<QueueDefinitionClassBinding> existing =
        definitionBindings.findAllByDefinitionIdOrderByBindingOrderAscIdAsc(definition.getId());
    projectionWriter.deleteAll(definitionBindings, existing);
    if (!existing.isEmpty()) {
      projectionWriter.flush();
    }
    var unique = orderedBindingRequests(requested);
    int order = 0;
    for (QueueBindingRequest request : unique.values()) {
      ParticipationPolicy policy = request.participationPolicy();
      validateBindingPolicy(order, policy);
      var binding = new QueueDefinitionClassBinding();
      binding.setDefinition(definition);
      binding.setWorkerClass(requireClass(request.workerClassId()));
      binding.setBindingOrder(order);
      binding.setStopTaskOnTake(request.stopTaskOnTake());
      binding.setParticipationPolicy(policy);
      binding.setNotifyOnPrimaryTake(order > 0 && request.notifyOnPrimaryTake());
      projectionWriter.save(definitionBindings, binding);
      order++;
    }
    projectionWriter.flush();
  }

  private LinkedHashMap<UUID, QueueBindingRequest> orderedBindingRequests(
      List<QueueBindingRequest> requested) {
    if (requested == null) {
      throw new IllegalArgumentException("Для общей очереди обязателен список классов");
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
    return unique;
  }

  private void validateBindingPolicy(int order, ParticipationPolicy policy) {
    if (policy == null) {
      throw new IllegalArgumentException("Для класса рабочего обязательна политика участия");
    }
    if (order == 0 && policy != ParticipationPolicy.PRIMARY) {
      throw new ConflictException("Первичный класс должен иметь PRIMARY-политику");
    }
    if (order > 0 && policy == ParticipationPolicy.PRIMARY) {
      throw new ConflictException("Вторичный класс не может иметь PRIMARY-политику");
    }
  }

  private void normalizeGlobalOrder() {
    // Position is a derived ordinal, not independently editable content.  Do
    // not advance unrelated definition versions when a newly created regular
    // queue moves the terminal holding queue one position lower.
    projectionWriter.flush();
    jdbc.update(
        """
        WITH ordered AS (
          SELECT definition.id,
                 row_number() OVER (
                   ORDER BY
                     CASE WHEN definition.queue_type = 'HOLDING' THEN 1 ELSE 0 END,
                     definition.sort_order,
                     definition.normalized_name,
                     definition.queue_type,
                     definition.id
                 )::integer AS normalized_order
            FROM queue_definition definition
           WHERE definition.queue_purpose = 'GENERAL'
        )
        UPDATE queue_definition definition
           SET sort_order = ordered.normalized_order
          FROM ordered
         WHERE definition.id = ordered.id
           AND definition.sort_order IS DISTINCT FROM ordered.normalized_order
        """);
    projectionWriter.clear();
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

  private List<QueueDefinition> globalDefinitions() {
    return definitions.findAllByPurposeOrderBySortOrderAscNameAscTypeAscIdAsc(QueuePurpose.GENERAL);
  }

  private int nextGlobalRegularSortOrder() {
    return globalDefinitions().stream()
            .filter(definition -> definition.getType() != QueueType.HOLDING)
            .mapToInt(QueueDefinition::getSortOrder)
            .max()
            .orElse(0)
        + 1;
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
    return mapper.toQueueDefinitionDto(
        definition,
        definitionBindings.findAllByDefinitionIdOrderByBindingOrderAscIdAsc(definition.getId()),
        logisticsPrimaryClassIds());
  }

  private QueueReferenceDto referenceDto(QueueUsageReference reference) {
    return mapper.toQueueReferenceDto(reference);
  }

  private Set<UUID> logisticsPrimaryClassIds() {
    Set<UUID> generalClassIds =
        definitionBindings.findAll().stream()
            .map(binding -> binding.getWorkerClass().getId())
            .collect(java.util.stream.Collectors.toSet());
    return bindings
        .findDedicatedLogisticsPrimaryWorkerClassIds(
            QueuePurpose.LOGISTICS_DRIVER, QueuePurpose.GENERAL, ParticipationPolicy.PRIMARY)
        .stream()
        .filter(id -> !generalClassIds.contains(id))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
