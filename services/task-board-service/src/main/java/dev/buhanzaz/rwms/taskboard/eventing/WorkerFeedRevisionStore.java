package dev.buhanzaz.rwms.taskboard.eventing;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Advances the native task-feed fence in the same transaction as its authoritative domain event.
 *
 * <p>Warehouse-local facts advance only their owning warehouse. Global workforce facts advance
 * each known warehouse because they can change qualification labels or queue audience everywhere.
 * The sequence supplies opaque monotonic tokens; clients must compare them only for equality.
 */
@Component
public class WorkerFeedRevisionStore {
  private final JdbcTemplate jdbc;

  /** Creates the JDBC-backed revision store. */
  public WorkerFeedRevisionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns the current opaque revision for one warehouse without creating mutable read state. */
  @Transactional(readOnly = true)
  public long current(UUID warehouseId) {
    Long revision =
        jdbc.queryForObject(
            "select coalesce((select revision from worker_feed_revision where warehouse_id=?), 0)",
            Long.class,
            warehouseId);
    return revision == null ? 0 : revision;
  }

  /** Advances the affected warehouse fence as part of the surrounding event-store transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void advance(TaskBoardAggregateType aggregateType, JsonNode payload) {
    switch (aggregateType) {
      case WORKER_CLASS -> advanceEveryKnownWarehouse();
      case QUEUE_USAGE_REFERENCE -> {
        // A deletion guard for a global definition does not change any native feed projection.
      }
      case QUEUE_ENTRY -> advanceWarehouse(queueEntryWarehouse(payload));
      case WORKER,
          WORKER_GROUP,
          WORK_QUEUE,
          BOARD_TASK,
          TASK_BOARD_ENTRY_OWNER_PROOF,
          DRIVER_SHIFT_OWNER_PROOF,
          TASK_EVIDENCE,
          GROUP_KPI_DAY -> advanceWarehouse(requiredWarehouse(payload));
    }
  }

  /**
   * Advances one explicit warehouse for a mutation, such as relocation, that removes native work
   * from its source while its persisted event payload names only the target warehouse.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void advanceWarehouse(UUID warehouseId) {
    jdbc.update(
        """
        with next_revision as (
          select nextval('worker_feed_revision_seq') as value
        )
        insert into worker_feed_revision(warehouse_id,revision,updated_at)
        select ?,value,clock_timestamp() from next_revision
        on conflict (warehouse_id) do update
          set revision=excluded.revision,
              updated_at=excluded.updated_at
        """,
        warehouseId);
  }

  private void advanceEveryKnownWarehouse() {
    jdbc.update(
        """
        update worker_feed_revision
           set revision=nextval('worker_feed_revision_seq'),
               updated_at=clock_timestamp()
        """);
  }

  private UUID queueEntryWarehouse(JsonNode payload) {
    UUID taskId = requiredUuid(payload, "taskId");
    List<UUID> warehouses =
        jdbc.query(
            "select warehouse_id from board_task where id=?",
            (result, row) -> result.getObject("warehouse_id", UUID.class),
            taskId);
    if (!warehouses.isEmpty()) return warehouses.getFirst();
    List<UUID> historicalWarehouses =
        jdbc.query(
            """
            select (payload->>'warehouseId')::uuid as warehouse_id
              from domain_event
             where aggregate_type='BOARD_TASK'
               and aggregate_id=?
               and payload ? 'warehouseId'
             order by aggregate_version desc
             limit 1
            """,
            (result, row) -> result.getObject("warehouse_id", UUID.class),
            taskId.toString());
    if (!historicalWarehouses.isEmpty()) return historicalWarehouses.getFirst();
    throw new IllegalStateException("Queue-entry event has no warehouse-owned board task");
  }

  private UUID requiredWarehouse(JsonNode payload) {
    return requiredUuid(payload, "warehouseId");
  }

  private UUID requiredUuid(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Task-board event payload is missing " + field);
    }
    try {
      return UUID.fromString(value.asText());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Task-board event payload contains invalid " + field, exception);
    }
  }
}
