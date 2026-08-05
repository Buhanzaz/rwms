package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.ReadinessWork;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleFence.AdmissionPermit;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleFence.AdmissionTarget;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable, task-board-local interlock between task mutation and warehouse lifecycle readiness.
 *
 * <p>The rows in this store are not lifecycle state and never replace warehouse-service. They make
 * a locally admitted mutation visible across the gap between its owner-side admission check and the
 * transaction that writes the task-board projection. Each intent has a short lease, so a crashed
 * process fails closed only until the next warehouse-locked reconciliation decision.
 */
@Component
public class WarehouseLifecycleIntentStore {
  private static final String ADMISSION = "ADMISSION";
  private static final String READINESS = "READINESS";
  private static final String RESERVED = "RESERVED";
  private static final String ADMITTED = "ADMITTED";
  private static final String CONFIRMING = "CONFIRMING";

  private final JdbcTemplate jdbc;

  public WarehouseLifecycleIntentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Reserves all affected warehouses atomically before an owner-side admission read. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AdmissionPermit reserveAdmission(List<AdmissionTarget> requestedTargets) {
    List<AdmissionTarget> targets = normalizedTargets(requestedTargets);
    targets.stream().map(AdmissionTarget::warehouseId).sorted().forEach(this::lockWarehouse);
    for (AdmissionTarget target : targets) {
      removeExpiredIntents(target.warehouseId());
      if (hasReadinessBarrier(target.warehouseId())) {
        throw new ConflictException("Склад завершает вывод из эксплуатации");
      }
    }

    UUID operationId = UUID.randomUUID();
    for (AdmissionTarget target : targets) {
      jdbc.update(
          """
          insert into task_board_warehouse_lifecycle_intent(
            intent_id, operation_id, warehouse_id, intent_type, state, direction,
            expected_warehouse_version, expires_at, created_at, updated_at)
          values (
            ?, ?, ?, 'ADMISSION', 'RESERVED', ?, null,
            clock_timestamp() + interval '2 minutes', clock_timestamp(), clock_timestamp())
          """,
          UUID.randomUUID(),
          operationId,
          target.warehouseId(),
          target.direction().name());
    }
    return new AdmissionPermit(operationId, targets);
  }

  /** Records that warehouse-service accepted all reservations. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markAdmitted(AdmissionPermit reservation) {
    requireReservation(reservation);
    int updated =
        jdbc.update(
            """
            update task_board_warehouse_lifecycle_intent
               set state = 'ADMITTED', updated_at = clock_timestamp()
             where operation_id = ?
               and intent_type = 'ADMISSION'
               and state = 'RESERVED'
               and expires_at > clock_timestamp()
            """,
            reservation.operationId());
    if (updated != reservation.targets().size()) {
      throw expiredAdmission();
    }
  }

  /** Deletes an admission intent after its associated local mutation commits or is abandoned. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void terminalizeAdmission(AdmissionPermit reservation) {
    requireReservation(reservation);
    int deleted =
        jdbc.update(
            """
            delete from task_board_warehouse_lifecycle_intent
             where operation_id = ?
               and intent_type = 'ADMISSION'
               and state = 'ADMITTED'
               and expires_at > clock_timestamp()
            """,
            reservation.operationId());
    if (deleted != reservation.targets().size()) {
      throw expiredAdmission();
    }
  }

  /** Cleans up a reservation after a rejected/failed remote check or a local transaction rollback. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void abandonAdmission(AdmissionPermit reservation) {
    if (reservation == null) {
      return;
    }
    jdbc.update(
        """
        delete from task_board_warehouse_lifecycle_intent
         where operation_id = ?
           and intent_type = 'ADMISSION'
        """,
        reservation.operationId());
  }

  /**
   * Commits a readiness barrier only after task-board has found no local live work or admission
   * intent. The remote POST must happen after this transaction returns.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ReadinessReservation reserveReadinessIfClear(ReadinessWork work) {
    validateReadinessWork(work);
    lockWarehouse(work.warehouseId());
    removeExpiredIntents(work.warehouseId());
    if (hasLiveWork(work.warehouseId())
        || hasAdmissionIntent(work.warehouseId())
        || hasReadinessBarrier(work.warehouseId())) {
      return null;
    }
    UUID operationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into task_board_warehouse_lifecycle_intent(
          intent_id, operation_id, warehouse_id, intent_type, state, direction,
          expected_warehouse_version, expires_at, created_at, updated_at)
        values (
          ?, ?, ?, 'READINESS', 'CONFIRMING', null, ?,
          clock_timestamp() + interval '2 minutes', clock_timestamp(), clock_timestamp())
        """,
        UUID.randomUUID(), operationId, work.warehouseId(), work.warehouseVersion());
    return new ReadinessReservation(operationId, work.warehouseId(), work.warehouseVersion());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void completeReadiness(ReadinessReservation reservation) {
    requireReservation(reservation);
    int deleted =
        jdbc.update(
            """
            delete from task_board_warehouse_lifecycle_intent
             where operation_id = ?
               and warehouse_id = ?
               and intent_type = 'READINESS'
               and state = 'CONFIRMING'
               and expected_warehouse_version = ?
            """,
            reservation.operationId(), reservation.warehouseId(), reservation.warehouseVersion());
    if (deleted != 1) {
      throw new IllegalStateException("Warehouse lifecycle readiness intent changed unexpectedly");
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void abandonReadiness(ReadinessReservation reservation) {
    if (reservation == null) {
      return;
    }
    jdbc.update(
        """
        delete from task_board_warehouse_lifecycle_intent
         where operation_id = ?
           and intent_type = 'READINESS'
        """,
        reservation.operationId());
  }

  private List<AdmissionTarget> normalizedTargets(List<AdmissionTarget> requestedTargets) {
    if (requestedTargets == null || requestedTargets.isEmpty()) {
      throw new IllegalArgumentException("Warehouse lifecycle admission is incomplete");
    }
    List<AdmissionTarget> targets = new ArrayList<>(requestedTargets);
    Set<UUID> warehouses = new LinkedHashSet<>();
    for (AdmissionTarget target : targets) {
      if (target == null || target.warehouseId() == null || target.direction() == null) {
        throw new IllegalArgumentException("Warehouse lifecycle admission is incomplete");
      }
      if (!warehouses.add(target.warehouseId())) {
        throw new IllegalArgumentException("Warehouse lifecycle admission repeats a warehouse");
      }
    }
    return List.copyOf(targets);
  }

  private void validateReadinessWork(ReadinessWork work) {
    if (work == null
        || work.warehouseId() == null
        || work.warehouseVersion() < 0
        || !"DRAINING".equals(work.lifecycleState())) {
      throw new IllegalArgumentException("Warehouse lifecycle readiness work is malformed");
    }
  }

  private boolean hasLiveWork(UUID warehouseId) {
    Boolean result =
        jdbc.queryForObject(
            """
            select exists (
              select 1
                from board_task task
                left join queue_entry entry on entry.task_id = task.id
               where task.warehouse_id = ?
                 and (
                   task.status = 'ACTIVE'
                   or entry.status in ('WAITING', 'IN_PROGRESS', 'PAUSED')
                 )
            )
            """,
            Boolean.class,
            warehouseId);
    return Boolean.TRUE.equals(result);
  }

  private boolean hasAdmissionIntent(UUID warehouseId) {
    return hasIntent(warehouseId, ADMISSION);
  }

  private boolean hasReadinessBarrier(UUID warehouseId) {
    return hasIntent(warehouseId, READINESS);
  }

  private boolean hasIntent(UUID warehouseId, String intentType) {
    Boolean result =
        jdbc.queryForObject(
            """
            select exists (
              select 1
                from task_board_warehouse_lifecycle_intent
               where warehouse_id = ?
                 and intent_type = ?
            )
            """,
            Boolean.class,
            warehouseId,
            intentType);
    return Boolean.TRUE.equals(result);
  }

  private void removeExpiredIntents(UUID warehouseId) {
    jdbc.update(
        """
        delete from task_board_warehouse_lifecycle_intent
         where warehouse_id = ?
           and expires_at <= clock_timestamp()
        """,
        warehouseId);
  }

  private static ConflictException expiredAdmission() {
    return new ConflictException("Проверка допуска склада истекла; повторите операцию");
  }

  private void lockWarehouse(UUID warehouseId) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "warehouse-lifecycle:task-board:" + warehouseId);
  }

  private static void requireReservation(AdmissionPermit reservation) {
    if (reservation == null || reservation.operationId() == null || reservation.targets().isEmpty()) {
      throw new IllegalArgumentException("Warehouse lifecycle admission reservation is incomplete");
    }
  }

  private static void requireReservation(ReadinessReservation reservation) {
    if (reservation == null
        || reservation.operationId() == null
        || reservation.warehouseId() == null
        || reservation.warehouseVersion() < 0) {
      throw new IllegalArgumentException("Warehouse lifecycle readiness reservation is incomplete");
    }
  }

  public record ReadinessReservation(UUID operationId, UUID warehouseId, long warehouseVersion) {}
}
