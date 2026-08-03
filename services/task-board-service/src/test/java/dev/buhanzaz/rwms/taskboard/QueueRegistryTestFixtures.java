package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only adapter that uses the same explicit warehouse-binding commands as production. */
final class QueueRegistryTestFixtures {
  private QueueRegistryTestFixtures() {}

  static WorkQueueDto create(
      RegistryService registry,
      JdbcTemplate jdbc,
      UUID warehouseId,
      QueueFixtureModels.QueueFixtureRequest request) {
    ensureWarehouse(jdbc, warehouseId);
    QueueDefinitionDto definition =
        registry.dto(registry.requireQueueDefinition(request.definitionId()));
    if (definition.purpose() == QueuePurpose.LOGISTICS_DRIVER) {
      return registry.updateDriverQueue(
          warehouseId,
          new DriverQueueRequest(
              0L,
              request.active(),
              request.hidden(),
              request.collapsed(),
              request.holdingPeriodMinutes(),
              request.notificationThreshold(),
              request.notifyWhenThresholdReached(),
              resultPhotoMinCount(definition.type(), request.resultPhotoMinCount()),
              request.bindings()));
    }
    return registry.createQueue(
        warehouseId,
        new WorkQueueRequest(
            request.version(),
            request.definitionId(),
            request.active(),
            request.hidden(),
            request.collapsed(),
            request.holdingPeriodMinutes(),
            request.notificationThreshold(),
            request.notifyWhenThresholdReached(),
            request.resultPhotoMinCount(),
            request.bindings()));
  }

  static WorkQueueDto update(
      RegistryService registry,
      JdbcTemplate jdbc,
      UUID warehouseId,
      UUID queueId,
      QueueFixtureModels.QueueFixtureRequest request) {
    ensureWarehouse(jdbc, warehouseId);
    WorkQueueDto queue = registry.dto(registry.requireQueue(warehouseId, queueId));
    QueueDefinitionDto definition = registry.dto(registry.requireQueueDefinition(request.definitionId()));
    if (definition.purpose() == QueuePurpose.LOGISTICS_DRIVER) {
      return registry.updateDriverQueue(
          warehouseId,
          new DriverQueueRequest(
              request.version(),
              request.active(),
              request.hidden(),
              request.collapsed(),
              request.holdingPeriodMinutes(),
              request.notificationThreshold(),
              request.notifyWhenThresholdReached(),
              resultPhotoMinCount(queue.type(), request.resultPhotoMinCount()),
              request.bindings()));
    }
    return registry.updateQueue(
        warehouseId,
        queueId,
        new WorkQueueRequest(
            request.version(),
            request.definitionId(),
            request.active(),
            request.hidden(),
            request.collapsed(),
            request.holdingPeriodMinutes(),
            request.notificationThreshold(),
            request.notifyWhenThresholdReached(),
            request.resultPhotoMinCount(),
            request.bindings()));
  }

  static List<WorkQueueDto> reorder(
      RegistryService registry,
      JdbcTemplate jdbc,
      UUID warehouseId,
      QueueFixtureModels.QueueFixtureOrderRequest request) {
    ensureWarehouse(jdbc, warehouseId);
    return registry.reorder(
        warehouseId,
        new QueueOrderRequest(
            request.queues().stream()
                .map(item -> new QueueOrderItem(item.queueId(), item.expectedVersion()))
                .toList()));
  }

  static void delete(
      RegistryService registry,
      JdbcTemplate jdbc,
      UUID warehouseId,
      UUID queueId,
      long expectedVersion) {
    ensureWarehouse(jdbc, warehouseId);
    registry.deleteQueue(warehouseId, queueId, expectedVersion);
  }

  static QueueDefinitionDto ensureDriverDefinition(
      RegistryService registry, JdbcTemplate jdbc, String name, QueueType type) {
    UUID id =
        jdbc.query(
            """
            select id
              from queue_definition
             where queue_purpose = 'LOGISTICS_DRIVER'
             order by id
             limit 1
            """,
            result -> result.next() ? (UUID) result.getObject(1) : null);
    if (id == null) {
      id = UUID.randomUUID();
      jdbc.update(
          """
          insert into queue_definition(
            id, version, revision_marker, name, normalized_name, description,
            queue_type, queue_purpose)
          values (?, 0, ?, ?, ?, null, ?, 'LOGISTICS_DRIVER')
          """,
          id,
          UUID.randomUUID(),
          name,
          name.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT),
          type.name());
    }
    return registry.dto(registry.requireQueueDefinition(id));
  }

  private static int resultPhotoMinCount(QueueType type, Integer requested) {
    return requested == null ? (type == QueueType.HOLDING ? 0 : 1) : requested;
  }

  private static void ensureWarehouse(JdbcTemplate jdbc, UUID warehouseId) {
    jdbc.update(
        """
        insert into warehouse_metadata(
          id, version, source_version, time_zone, active)
        values (?, 0, 0, 'Europe/Moscow', true)
        on conflict (id) do update set active = true
        """,
        warehouseId);
  }
}
