package dev.buhanzaz.rwms.warehouse.service;

import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists immutable, source-scoped evidence that a warehouse has operated.
 *
 * <p>This evidence is the durable boundary that prevents a later timezone correction from
 * rewriting operational history. It is separate from a lifecycle admission decision.
 */
@Service
public class WarehouseOperationMarker {
  private final JdbcTemplate jdbc;

  /**
   * Creates the durable operation-evidence boundary.
   *
   * @param jdbc persistence access for immutable operation evidence
   */
  public WarehouseOperationMarker(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Returns whether any durable operation evidence has already been recorded.
   *
   * @param warehouseId stable warehouse identity
   * @return whether the warehouse has operated
   */
  public boolean hasRecordedOperation(UUID warehouseId) {
    Boolean exists =
        jdbc.queryForObject(
            "select exists(select 1 from warehouse_operation_state where warehouse_id=?)",
            Boolean.class,
            warehouseId);
    return Boolean.TRUE.equals(exists);
  }

  /**
   * Appends an operation mark or accepts its exact replay within the surrounding transaction.
   *
   * @param warehouse warehouse aggregate already locked by the caller's transaction
   * @param source operation owner inferred from the credential
   * @param operationId stable idempotency identity
   * @param occurredAt actual operation instant
   * @param recordedAt authoritative database timestamp
   * @return true for a newly stored mark, false for an exact source-and-timestamp replay
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean mark(
      Warehouse warehouse,
      WarehouseOperationSource source,
      UUID operationId,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt) {
    if (source == null) throw new IllegalArgumentException("operation source is required");
    if (operationId == null) throw new IllegalArgumentException("operationId is required");
    OffsetDateTime canonicalOccurredAt = WarehouseTimeZoneHistoryService.canonicalTimestamp(occurredAt);
    OffsetDateTime canonicalRecordedAt = WarehouseTimeZoneHistoryService.canonicalTimestamp(recordedAt);
    if (canonicalOccurredAt.isAfter(canonicalRecordedAt)) {
      throw new IllegalArgumentException("operation occurredAt cannot be in the future");
    }
    if (canonicalOccurredAt.isBefore(
        WarehouseTimeZoneHistoryService.canonicalTimestamp(warehouse.getCreatedAt()))) {
      throw new IllegalArgumentException("operation occurredAt precedes warehouse creation");
    }
    int inserted =
        jdbc.update(
            """
            insert into warehouse_operation_mark(
                warehouse_id,operation_source,operation_id,occurred_at,recorded_at)
            values (?, ?, ?, ?, ?)
            on conflict (warehouse_id,operation_source,operation_id) do nothing
            """,
            warehouse.getId(),
            source.name(),
            operationId,
            canonicalOccurredAt,
            canonicalRecordedAt);
    if (inserted == 0) {
      OffsetDateTime existing =
          jdbc.queryForObject(
              """
              select occurred_at
                from warehouse_operation_mark
               where warehouse_id=? and operation_source=? and operation_id=?
              """,
              OffsetDateTime.class,
              warehouse.getId(),
              source.name(),
              operationId);
      if (existing == null || !existing.toInstant().equals(canonicalOccurredAt.toInstant())) {
        throw new WarehouseConflictException(
            "Operation identifier is already bound to another operation timestamp");
      }
      return false;
    }
    jdbc.update(
        """
        insert into warehouse_operation_state(warehouse_id,first_operation_at)
        values (?, ?)
        on conflict (warehouse_id) do update
          set first_operation_at=least(
              warehouse_operation_state.first_operation_at,
              excluded.first_operation_at)
        """,
        warehouse.getId(),
        canonicalOccurredAt);
    return true;
  }
}
