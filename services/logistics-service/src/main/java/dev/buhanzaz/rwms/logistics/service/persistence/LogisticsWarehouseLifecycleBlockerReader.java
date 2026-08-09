package dev.buhanzaz.rwms.logistics.service.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads whether one warehouse has a logistics-local lifecycle blocker in one PostgreSQL
 * statement.
 *
 * <p>The union intentionally remains a single {@code select exists} statement. At PostgreSQL
 * {@code READ_COMMITTED}, splitting it into per-table reads could observe different snapshots and
 * incorrectly report an empty warehouse during a cross-table transition.
 */
@Repository
public class LogisticsWarehouseLifecycleBlockerReader {
  private final JdbcTemplate jdbc;

  public LogisticsWarehouseLifecycleBlockerReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns the result of the single-snapshot local blocker query for one warehouse. */
  public boolean hasLocalBlockers(UUID warehouseId) {
    Boolean blocked =
        jdbc.queryForObject(
            """
            select exists (
              select 1 from logistics_warehouse_admission_intent
               where warehouse_id=? and expires_at > clock_timestamp()
              union all
              select 1 from warehouse_operation_mark_outbox
               where warehouse_id=? and state <> 'CONFIRMED'
              union all
              select 1 from logistics_document d
               where (d.warehouse_id=? or d.destination_warehouse_id=?)
                 and not (
                   (d.document_type='RETURN' and d.state in ('ACCEPTED','ESTIMATE_REQUESTED','CANCELLED'))
                   or (d.document_type='SHIPMENT' and d.state in ('SHIPPED','CANCELLED'))
                   or (d.document_type='TRANSFER' and d.state in ('COMPLETED','CANCELLED'))
                 )
              union all
              select 1 from equipment_movement_task t
               where t.warehouse_id=?
                 and t.state not in ('COMPLETED','CANCELLED','EXPIRED')
              union all
              select 1 from equipment_movement_task_line l
                join equipment_movement_task t on t.id=l.task_id
               where (l.source_warehouse_id=? or l.target_warehouse_id=?)
                 and t.state not in ('COMPLETED','CANCELLED','EXPIRED')
              union all
              select 1 from driver_logistics_task
               where warehouse_id=? and state not in ('COMPLETED','CANCELLED')
              union all
              select 1 from rental_order
               where warehouse_id=? and status not in ('CLOSED','CANCELLED')
              union all
              select 1 from rental_inquiry
               where warehouse_id=? and state <> 'ARCHIVED'
              union all
              select 1 from client_presentation
               where warehouse_id=? and state in ('ACTIVE','BOOKING_PENDING','BOOKED')
              union all
              select 1 from logistics_guard g
                join logistics_document d on d.id=g.document_id
               where (d.warehouse_id=? or d.destination_warehouse_id=?)
                 and g.guard_state <> 'RELEASED'
              union all
              select 1 from logistics_equipment_hold_reference h
                join logistics_document d on d.id=h.document_id
               where (d.warehouse_id=? or d.destination_warehouse_id=?)
                 and h.hold_state <> 'RELEASED'
              union all
              select 1 from logistics_reconciliation r
                join logistics_document d on d.id=r.document_id
               where (d.warehouse_id=? or d.destination_warehouse_id=?) and r.state='OPEN'
            )
            """,
            Boolean.class,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId,
            warehouseId);
    return Boolean.TRUE.equals(blocked);
  }
}
