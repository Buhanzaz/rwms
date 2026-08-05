package dev.buhanzaz.rwms.asset.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asset-local half of the warehouse lifecycle readiness handshake.
 *
 * <p>Every write which can introduce a new asset blocker takes this store's warehouse advisory
 * lock in PostgreSQL. A readiness attempt obtains the same lock before it observes local work and
 * installs its durable fence, so a command admitted remotely cannot commit a new blocker after the
 * local owner has promised readiness.
 */
@Repository
public class AssetWarehouseLifecycleStore {
  private final JdbcTemplate jdbc;

  public AssetWarehouseLifecycleStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Starts one local readiness attempt. The transaction ends before the caller performs remote
   * HTTP confirmation.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ReadinessAttempt beginReadiness(UUID warehouseId, long warehouseVersion) {
    requireReadinessIdentity(warehouseId, warehouseVersion);
    lockWarehouse(warehouseId);
    ReadinessRow existing = readiness(warehouseId);
    if (existing != null) {
      return new ReadinessAttempt(
          warehouseId,
          existing.warehouseVersion(),
          "SEALED".equals(existing.state()),
          true);
    }
    if (hasLocalBlockers(warehouseId)) {
      return new ReadinessAttempt(warehouseId, warehouseVersion, false, false);
    }
    OffsetDateTime now = databaseNow();
    jdbc.update(
        """
        insert into asset_warehouse_readiness_fence(
          warehouse_id,warehouse_version,state,created_at,updated_at)
        values (?,?,'CONFIRMING',?,?)
        """,
        warehouseId,
        warehouseVersion,
        now,
        now);
    return new ReadinessAttempt(warehouseId, warehouseVersion, false, true);
  }

  /** Marks a locally fenced readiness attempt as durably confirmed by warehouse-service. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void sealReadiness(UUID warehouseId, long attemptedVersion) {
    requireReadinessIdentity(warehouseId, attemptedVersion);
    lockWarehouse(warehouseId);
    int changed =
        jdbc.update(
            """
            update asset_warehouse_readiness_fence
               set state='SEALED',updated_at=clock_timestamp()
             where warehouse_id=? and warehouse_version=? and state='CONFIRMING'
            """,
            warehouseId,
            attemptedVersion);
    if (changed == 0) {
      ReadinessRow row = readiness(warehouseId);
      if (row == null
          || row.warehouseVersion() != attemptedVersion
          || !"SEALED".equals(row.state())) {
        throw new AssetConflictException("Asset warehouse readiness fence changed concurrently");
      }
    }
  }

  /** Releases only a confirmation whose remote request was rejected before warehouse commit. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void releaseReadiness(UUID warehouseId, long attemptedVersion) {
    requireReadinessIdentity(warehouseId, attemptedVersion);
    lockWarehouse(warehouseId);
    jdbc.update(
        """
        delete from asset_warehouse_readiness_fence
         where warehouse_id=? and warehouse_version=? and state='CONFIRMING'
        """,
        warehouseId,
        attemptedVersion);
  }

  private boolean hasLocalBlockers(UUID warehouseId) {
    Boolean blocked =
        jdbc.queryForObject(
            """
            select exists(
              select 1 from rental_item
               where warehouse_id=? and status not in ('WRITTEN_OFF','LOST')
              union all
              select 1 from equipment_balance
               where warehouse_id=? and quantity > 0
                 and location_kind not in ('WRITTEN_OFF','LOST')
              union all
              select 1 from equipment_allocation_hold
               where warehouse_id=? and state in ('ACTIVE','COMMITTED')
              union all
              select 1 from order_equipment_reservation
               where warehouse_id=? and state='ACTIVE'
              union all
              select 1 from order_unit_reservation
               where warehouse_id=? and state='ACTIVE'
              union all
              select 1 from presentation_unit_hold
               where warehouse_id=? and state='ACTIVE' and expires_at > clock_timestamp()
              union all
              select 1 from inventory_asset_capture
               where warehouse_id=? and state='ACTIVE' and expires_at > clock_timestamp()
              union all
              select 1 from rental_item_html_import
               where warehouse_id=?
                 and state not in ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED')
              union all
              select 1 from property_disposition_fence
               where warehouse_id=? and state='PREPARED'
              union all
              select 1
                from maintenance_furniture_custody_claim claim
               where claim.warehouse_id=?
                 and claim.quantity > coalesce((
                   select sum(event.quantity)
                     from maintenance_furniture_custody_event event
                    where event.claim_id=claim.id
                      and event.event_type in ('RETURNED_TO_STOCK','DISPOSITION_APPLIED')
                 ), 0)
              union all
              select 1
                from operation_lease lease
                join rental_item item on item.id=lease.rental_item_id
               where item.warehouse_id=? and lease.state='ACTIVE'
                 and lease.expires_at > clock_timestamp()
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
            warehouseId);
    return Boolean.TRUE.equals(blocked);
  }

  private ReadinessRow readiness(UUID warehouseId) {
    return jdbc.query(
            """
            select warehouse_version,state from asset_warehouse_readiness_fence
             where warehouse_id=? for update
            """,
            (rs, ignored) -> new ReadinessRow(rs.getLong("warehouse_version"), rs.getString("state")),
            warehouseId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void lockWarehouse(UUID warehouseId) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "warehouse-lifecycle:asset:" + warehouseId);
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime now = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (now == null) {
      throw new IllegalStateException("Database clock returned null");
    }
    return now.withOffsetSameInstant(ZoneOffset.UTC);
  }

  private static void requireReadinessIdentity(UUID warehouseId, long warehouseVersion) {
    if (warehouseId == null || warehouseVersion < 0) {
      throw new IllegalArgumentException("Warehouse readiness identity is invalid");
    }
  }

  public record ReadinessAttempt(
      UUID warehouseId, long warehouseVersion, boolean sealed, boolean shouldConfirm) {}

  private record ReadinessRow(long warehouseVersion, String state) {}
}
