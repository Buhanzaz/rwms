package dev.buhanzaz.rwms.maintenance.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Conservative maintenance-owned blocker projection used before lifecycle readiness. */
@Repository
public class WarehouseLifecycleBlockerStore {
  private final JdbcTemplate jdbc;

  public WarehouseLifecycleBlockerStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public boolean hasBlockers(UUID warehouseId) {
    if (warehouseId == null) {
      throw new IllegalArgumentException("Warehouse identity is required");
    }
    Boolean blocked = jdbc.queryForObject(
        """
        select exists (
          select 1
            from maintenance_estimate
           where warehouse_id=? and state <> 'COMPLETED'
          union all
          select 1
            from maintenance_repair
           where warehouse_id=?
             and not (
               execution_state='CANCELLED'
               or acceptance_state in ('ACCEPTED','WRITTEN_OFF')
             )
          union all
          select 1
            from repair_place_allocation
           where warehouse_id=? and state <> 'RELEASED'
          union all
          select 1
            from property_disposition_decision
           where warehouse_id=? and state not in ('EFFECTIVE','REJECTED')
          union all
          select 1
            from property_disposition_processing_claim claim
            join property_disposition_decision decision on decision.id=claim.decision_id
           where decision.warehouse_id=? and claim.status <> 'COMPLETED'
          union all
          select 1
            from warehouse_operation_mark_outbox
           where warehouse_id=? and state <> 'CONFIRMED'
          union all
          select 1
            from integration_reconciliation ir
            left join maintenance_repair repair on repair.id=ir.repair_id
            left join maintenance_estimate estimate
              on ir.operation_type='COMPLETE_EMPTY_ESTIMATE'
             and estimate.id::text=ir.response_snapshot->>'estimateId'
           where ir.state not in ('CONFIRMED','CANCELLED')
             and (
               repair.warehouse_id=?
               or ir.media_warehouse_id=?
               or estimate.warehouse_id=?
               or ir.response_snapshot->>'warehouseId'=?::text
               or (
                 repair.warehouse_id is null
                 and ir.media_warehouse_id is null
                 and estimate.warehouse_id is null
                 and ir.response_snapshot->>'warehouseId' is null
                 and not (
                   ir.dependency_type='TASK_BOARD'
                   and ir.operation_type in ('REGISTER_CATALOG_POSITION','DELETE_CATALOG_POSITION')
                   and ir.catalog_version_id is not null
                 )
               )
             )
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
        warehouseId);
    return Boolean.TRUE.equals(blocked);
  }
}
