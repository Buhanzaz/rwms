package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.QueueDefinition;
import dev.buhanzaz.rwms.taskboard.domain.QueueDefinitionClassBinding;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.QueueDefinitionClassBindingRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueDefinitionRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskTimeEventRepository;
import dev.buhanzaz.rwms.taskboard.repository.WarehouseMetadataRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueClassBindingRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes the single GENERAL queue standard into per-warehouse physical
 * queues. Those projections deliberately retain their UUIDs because queue
 * entries, task history and external routing refer to them.
 */
@Service
@RequiredArgsConstructor
public class GlobalQueueProjectionService {
  private final QueueDefinitionRepository definitions;
  private final QueueDefinitionClassBindingRepository definitionBindings;
  private final WarehouseMetadataRepository warehouses;
  private final WorkQueueRepository queues;
  private final WorkQueueClassBindingRepository queueBindings;
  private final QueueEntryRepository entries;
  private final TaskTimeEventRepository timeEvents;
  private final TaskBoardProjectionWriter projectionWriter;
  private final TaskBoardEventSourcing eventSourcing;
  private final JdbcTemplate jdbc;

  /** Reconciles existing warehouses after Flyway has installed the global template schema. */
  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void synchronizeOnApplicationReady() {
    synchronizeAllInternal();
  }

  @Transactional
  public void synchronizeAll() {
    synchronizeAllInternal();
  }

  @Transactional
  public void synchronizeWarehouse(UUID warehouseId) {
    if (warehouses.findById(warehouseId).filter(value -> value.isActive()).isEmpty()) {
      return;
    }
    List<QueueDefinition> standard = generalDefinitions();
    for (QueueDefinition definition : standard) {
      synchronizeDefinitionAtWarehouse(definition, warehouseId);
    }
  }

  @Transactional
  public void synchronizeDefinition(UUID definitionId) {
    QueueDefinition definition =
        definitions
            .findById(definitionId)
            .orElseThrow(() -> new NotFoundException("Общая очередь не найдена"));
    if (definition.getPurpose() != QueuePurpose.GENERAL) {
      return;
    }
    for (UUID warehouseId : activeWarehouseIds()) {
      synchronizeDefinitionAtWarehouse(definition, warehouseId);
    }
  }

  /**
   * Removes only empty physical projections. Callers must first prove that the
   * definition is not referenced by catalog routing.
   */
  @Transactional
  public void deleteDefinitionProjections(QueueDefinition definition) {
    List<WorkQueue> projections =
        queues.findAllByDefinitionIdOrderByWarehouseIdAscIdAsc(definition.getId());
    for (WorkQueue queue : projections) {
      if (entries.existsByQueueId(queue.getId()) || timeEvents.existsByQueueEntryQueueId(queue.getId())) {
        throw new ConflictException("Используемую общую очередь можно только деактивировать");
      }
    }
    for (WorkQueue queue : projections) {
      StreamState stream = lockOrCreateStream(queue);
      if (stream.existing()) {
        eventSourcing.deleted(queue, stream.version());
      } else {
        eventSourcing.created(queue);
        eventSourcing.deleted(queue, queue.getVersion());
      }
      projectionWriter.deleteAll(queueBindings, queueBindings.findAllByQueueId(queue.getId()));
      projectionWriter.delete(queues, queue);
    }
    projectionWriter.flush();
  }

  private void synchronizeAllInternal() {
    List<UUID> warehouseIds = activeWarehouseIds();
    for (QueueDefinition definition : generalDefinitions()) {
      for (UUID warehouseId : warehouseIds) {
        synchronizeDefinitionAtWarehouse(definition, warehouseId);
      }
    }
  }

  private List<QueueDefinition> generalDefinitions() {
    return definitions.findAllByPurposeOrderBySortOrderAscNameAscTypeAscIdAsc(QueuePurpose.GENERAL);
  }

  private List<UUID> activeWarehouseIds() {
    return warehouses.findAllByActiveTrueOrderByIdAsc().stream().map(value -> value.getId()).toList();
  }

  private void synchronizeDefinitionAtWarehouse(QueueDefinition definition, UUID warehouseId) {
    lockProjection(warehouseId, definition.getId());
    List<QueueDefinitionClassBinding> template =
        definitionBindings.findAllByDefinitionIdOrderByBindingOrderAscIdAsc(definition.getId());
    WorkQueue queue =
        queues.findByWarehouseIdAndDefinitionId(warehouseId, definition.getId()).orElse(null);
    if (queue == null) {
      queue = new WorkQueue();
      queue.setWarehouseId(warehouseId);
      queue.setDefinition(definition);
      applyTemplate(queue, definition);
      queue.setAvailableTaskLimit(definition.getAvailableTaskLimit());
      queue = projectionWriter.save(queues, queue);
      synchronizeBindings(queue, template);
      projectionWriter.flush();
      projectionWriter.refresh(queue);
      eventSourcing.created(queue);
      return;
    }

    List<WorkQueueClassBinding> current =
        queueBindings.findAllByQueueIdOrderByBindingOrderAscIdAsc(queue.getId());
    if (matchesTemplate(queue, definition) && bindingsMatch(current, template)) {
      return;
    }

    StreamState stream = lockOrCreateStream(queue);
    applyTemplate(queue, definition);
    synchronizeBindings(queue, template);
    queue.touch();
    queue = projectionWriter.save(queues, queue);
    projectionWriter.flush();
    projectionWriter.refresh(queue);
    if (stream.existing()) {
      eventSourcing.queueChanged(queue, stream.version(), TaskBoardEventTypes.WORK_QUEUE_CHANGED);
    } else {
      eventSourcing.created(queue);
    }
  }

  private StreamState lockOrCreateStream(WorkQueue queue) {
    try {
      return new StreamState(true, eventSourcing.lock(TaskBoardAggregateType.WORK_QUEUE, queue.getId()));
    } catch (OptimisticLockingFailureException ignored) {
      // A pre-event-store queue can still be a valid historical projection.
      // Its first synchronized fact is a normal baseline rather than a hidden fallback.
      return new StreamState(false, queue.getVersion());
    }
  }

  private void applyTemplate(WorkQueue target, QueueDefinition source) {
    target.setSortOrder(source.getSortOrder());
    target.setActive(source.isActive());
    target.setHidden(source.isHidden());
    target.setCollapsed(source.isCollapsed());
    target.setHoldingPeriodMinutes(source.getHoldingPeriodMinutes());
    target.setNotificationThreshold(source.getNotificationThreshold());
    target.setNotifyWhenThresholdReached(source.isNotifyWhenThresholdReached());
    target.setResultPhotoMinCount(source.getResultPhotoMinCount());
  }

  private boolean matchesTemplate(WorkQueue queue, QueueDefinition definition) {
    return queue.getSortOrder() == definition.getSortOrder()
        && queue.isActive() == definition.isActive()
        && queue.isHidden() == definition.isHidden()
        && queue.isCollapsed() == definition.isCollapsed()
        && java.util.Objects.equals(
            queue.getHoldingPeriodMinutes(), definition.getHoldingPeriodMinutes())
        && java.util.Objects.equals(
            queue.getNotificationThreshold(), definition.getNotificationThreshold())
        && queue.isNotifyWhenThresholdReached() == definition.isNotifyWhenThresholdReached()
        && queue.getResultPhotoMinCount() == definition.getResultPhotoMinCount();
  }

  private boolean bindingsMatch(
      List<WorkQueueClassBinding> current, List<QueueDefinitionClassBinding> template) {
    if (current.size() != template.size()) {
      return false;
    }
    for (int index = 0; index < current.size(); index++) {
      WorkQueueClassBinding actual = current.get(index);
      QueueDefinitionClassBinding expected = template.get(index);
      if (!actual.getWorkerClass().getId().equals(expected.getWorkerClass().getId())
          || actual.getBindingOrder() != expected.getBindingOrder()
          || actual.isStopTaskOnTake() != expected.isStopTaskOnTake()
          || actual.getParticipationPolicy() != expected.getParticipationPolicy()
          || actual.isNotifyOnPrimaryTake() != expected.isNotifyOnPrimaryTake()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Rebuilds the derived class bindings from the template.
   *
   * <p>Bindings are configuration rows, not task-history references. Rebuilding
   * them avoids a transient state that violates the database invariant that a
   * PRIMARY binding is always at position zero when the primary class changes.
   */
  private void synchronizeBindings(
      WorkQueue queue, List<QueueDefinitionClassBinding> template) {
    List<WorkQueueClassBinding> current = queueBindings.findAllByQueueId(queue.getId());
    if (!current.isEmpty()) {
      projectionWriter.deleteAll(queueBindings, current);
      projectionWriter.flush();
    }

    for (QueueDefinitionClassBinding source : template) {
      WorkQueueClassBinding target = new WorkQueueClassBinding();
      target.setQueue(queue);
      target.setWorkerClass(source.getWorkerClass());
      target.setBindingOrder(source.getBindingOrder());
      target.setStopTaskOnTake(source.isStopTaskOnTake());
      target.setParticipationPolicy(source.getParticipationPolicy());
      target.setNotifyOnPrimaryTake(source.isNotifyOnPrimaryTake());
      projectionWriter.save(queueBindings, target);
    }
  }

  private void lockProjection(UUID warehouseId, UUID definitionId) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "global-work-queue-projection:" + warehouseId + ":" + definitionId);
  }

  private record StreamState(boolean existing, long version) {}
}
