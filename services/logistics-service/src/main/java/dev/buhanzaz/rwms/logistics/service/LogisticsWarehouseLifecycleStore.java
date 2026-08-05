package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logistics-local half of the warehouse lifecycle handshake.
 *
 * <p>Short-lived admission intents close the gap between the remote admission read and the local
 * aggregate commit. Readiness uses the same per-warehouse advisory lock, so it can never promise
 * an empty owner while an admitted operation is about to commit.
 */
@Repository
public class LogisticsWarehouseLifecycleStore {
  private static final int ADMISSION_TTL_SECONDS = 120;

  private final JdbcTemplate jdbc;

  public LogisticsWarehouseLifecycleStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean hasLiveDocumentReplay(
      UUID subjectId, String operationName, UUID idempotencyKey) {
    if (subjectId == null || operationName == null || idempotencyKey == null) return false;
    Boolean replay =
        jdbc.queryForObject(
            """
            select exists (
              select 1 from logistics_idempotency_record
               where subject_id=? and operation_name=? and idempotency_key=?
                 and expires_at > clock_timestamp()
            )
            """,
            Boolean.class,
            subjectId,
            operationName,
            idempotencyKey);
    return Boolean.TRUE.equals(replay);
  }

  public boolean hasEquipmentMovementReplay(UUID actorSubjectId, UUID idempotencyKey) {
    if (actorSubjectId == null || idempotencyKey == null) return false;
    Boolean replay =
        jdbc.queryForObject(
            """
            select exists (
              select 1 from equipment_movement_task
               where created_by_subject_id=? and idempotency_key=?
            )
            """,
            Boolean.class,
            actorSubjectId,
            idempotencyKey);
    return Boolean.TRUE.equals(replay);
  }

  public boolean hasDriverTaskReplay(UUID actorSubjectId, UUID idempotencyKey) {
    if (actorSubjectId == null || idempotencyKey == null) return false;
    Boolean replay =
        jdbc.queryForObject(
            """
            select exists (
              select 1 from driver_logistics_task
               where created_by_subject_id=? and idempotency_key=?
            )
            """,
            Boolean.class,
            actorSubjectId,
            idempotencyKey);
    return Boolean.TRUE.equals(replay);
  }

  /** Parent-owned child work is legal only while this owner has not promised readiness. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireOwnedContinuation(List<AdmissionRequirement> requirements) {
    List<AdmissionRequirement> normalized = normalized(UUID.randomUUID(), requirements);
    lockWarehouses(normalized);
    for (AdmissionRequirement requirement : normalized) {
      requireOpen(requirement.warehouseId());
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean reserve(UUID operationId, List<AdmissionRequirement> requirements) {
    List<AdmissionRequirement> normalized = normalized(operationId, requirements);
    lockWarehouses(normalized);
    OffsetDateTime now = databaseNow();
    deleteExpired(normalized, now);
    for (AdmissionRequirement requirement : normalized) {
      requireOpen(requirement.warehouseId());
      OffsetDateTime expiresAt = now.plusSeconds(ADMISSION_TTL_SECONDS);
      jdbc.update(
          """
          insert into logistics_warehouse_admission_intent(
            operation_id,warehouse_id,direction,state,warehouse_version,expires_at,created_at,updated_at)
          values (?, ?, ?, 'RESERVED', null, ?, ?, ?)
          on conflict (operation_id,warehouse_id) do nothing
          """,
          operationId,
          requirement.warehouseId(),
          requirement.direction().name(),
          expiresAt,
          now,
          now);
      AdmissionRow stored = admission(operationId, requirement.warehouseId());
      if (stored == null
          || stored.direction() != requirement.direction()
          || !stored.expiresAt().isAfter(now)) {
        throw new LogisticsConflictException(
            "Warehouse admission operation is bound to another immutable request");
      }
    }
    return normalized.stream()
        .allMatch(
            requirement -> {
              AdmissionRow row = admission(operationId, requirement.warehouseId());
              return row != null && "ADMITTED".equals(row.state());
            });
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void admit(
      UUID operationId,
      List<AdmissionRequirement> requirements,
      List<Long> warehouseVersions) {
    List<AdmissionRequirement> normalized = normalized(operationId, requirements);
    if (warehouseVersions == null || warehouseVersions.size() != normalized.size()) {
      throw new IllegalArgumentException("Warehouse admission versions are incomplete");
    }
    lockWarehouses(normalized);
    OffsetDateTime now = databaseNow();
    for (int index = 0; index < normalized.size(); index++) {
      AdmissionRequirement requirement = normalized.get(index);
      long warehouseVersion = warehouseVersions.get(index);
      if (warehouseVersion < 0) {
        throw new IllegalArgumentException("Warehouse admission version must not be negative");
      }
      requireOpen(requirement.warehouseId());
      AdmissionRow row = admission(operationId, requirement.warehouseId());
      if (row == null
          || row.direction() != requirement.direction()
          || !row.expiresAt().isAfter(now)) {
        throw new LogisticsConflictException("Warehouse admission intent expired or changed");
      }
      if ("ADMITTED".equals(row.state())) {
        if (row.warehouseVersion() == null || row.warehouseVersion() != warehouseVersion) {
          throw new LogisticsConflictException(
              "Warehouse admission retry returned another lifecycle version");
        }
        continue;
      }
      int changed =
          jdbc.update(
              """
              update logistics_warehouse_admission_intent
                 set state='ADMITTED',warehouse_version=?,updated_at=?
               where operation_id=? and warehouse_id=? and state='RESERVED'
                 and direction=? and expires_at > ?
              """,
              warehouseVersion,
              now,
              operationId,
              requirement.warehouseId(),
              requirement.direction().name(),
              now);
      if (changed != 1) {
        throw new LogisticsConflictException("Warehouse admission intent changed concurrently");
      }
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void cancelReserved(UUID operationId, List<AdmissionRequirement> requirements) {
    List<AdmissionRequirement> normalized = normalized(operationId, requirements);
    lockWarehouses(normalized);
    for (AdmissionRequirement requirement : normalized) {
      jdbc.update(
          """
          delete from logistics_warehouse_admission_intent
           where operation_id=? and warehouse_id=? and state='RESERVED' and direction=?
          """,
          operationId,
          requirement.warehouseId(),
          requirement.direction().name());
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void consume(
      UUID operationId, List<AdmissionRequirement> requirements, boolean bypassed) {
    if (bypassed) return;
    List<AdmissionRequirement> normalized = normalized(operationId, requirements);
    lockWarehouses(normalized);
    OffsetDateTime now = databaseNow();
    for (AdmissionRequirement requirement : normalized) {
      AdmissionRow row = admission(operationId, requirement.warehouseId());
      if (row == null
          || !"ADMITTED".equals(row.state())
          || row.direction() != requirement.direction()
          || row.warehouseVersion() == null
          || !row.expiresAt().isAfter(now)) {
        throw new LogisticsConflictException(
            "Warehouse lifecycle admission is missing or expired; retry the command");
      }
    }
    for (AdmissionRequirement requirement : normalized) {
      jdbc.update(
          "delete from logistics_warehouse_admission_intent where operation_id=? and warehouse_id=?",
          operationId,
          requirement.warehouseId());
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ReadinessAttempt beginReadiness(UUID warehouseId, long warehouseVersion) {
    if (warehouseId == null || warehouseVersion < 0) {
      throw new IllegalArgumentException("Warehouse readiness identity is invalid");
    }
    lockWarehouse(warehouseId);
    OffsetDateTime now = databaseNow();
    jdbc.update(
        "delete from logistics_warehouse_admission_intent where warehouse_id=? and expires_at <= ?",
        warehouseId,
        now);
    ReadinessRow existing = readiness(warehouseId);
    if (existing != null) {
      return new ReadinessAttempt(
          warehouseId, existing.warehouseVersion(), "SEALED".equals(existing.state()), true);
    }
    if (hasLocalBlockers(warehouseId)) {
      return new ReadinessAttempt(warehouseId, warehouseVersion, false, false);
    }
    jdbc.update(
        """
        insert into logistics_warehouse_readiness_fence(
          warehouse_id,warehouse_version,state,created_at,updated_at)
        values (?,?,'CONFIRMING',?,?)
        """,
        warehouseId,
        warehouseVersion,
        now,
        now);
    return new ReadinessAttempt(warehouseId, warehouseVersion, false, true);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void sealReadiness(UUID warehouseId, long attemptedVersion) {
    lockWarehouse(warehouseId);
    int changed =
        jdbc.update(
            """
            update logistics_warehouse_readiness_fence
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
        throw new LogisticsConflictException("Warehouse readiness fence changed concurrently");
      }
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void releaseReadiness(UUID warehouseId, long attemptedVersion) {
    lockWarehouse(warehouseId);
    jdbc.update(
        """
        delete from logistics_warehouse_readiness_fence
         where warehouse_id=? and warehouse_version=? and state='CONFIRMING'
        """,
        warehouseId,
        attemptedVersion);
  }

  boolean hasLocalBlockers(UUID warehouseId) {
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

  private void requireOpen(UUID warehouseId) {
    ReadinessRow fence = readiness(warehouseId);
    if (fence != null) {
      throw new LogisticsConflictException(
          "Logistics has already fenced this warehouse for lifecycle readiness");
    }
  }

  private AdmissionRow admission(UUID operationId, UUID warehouseId) {
    return jdbc.query(
            """
            select direction,state,warehouse_version,expires_at
              from logistics_warehouse_admission_intent
             where operation_id=? and warehouse_id=?
             for update
            """,
            (rs, ignored) ->
                new AdmissionRow(
                    WarehouseOperationDirection.valueOf(rs.getString("direction")),
                    rs.getString("state"),
                    rs.getObject("warehouse_version", Long.class),
                    rs.getObject("expires_at", OffsetDateTime.class)),
            operationId,
            warehouseId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private ReadinessRow readiness(UUID warehouseId) {
    return jdbc.query(
            """
            select warehouse_version,state from logistics_warehouse_readiness_fence
             where warehouse_id=? for update
            """,
            (rs, ignored) ->
                new ReadinessRow(rs.getLong("warehouse_version"), rs.getString("state")),
            warehouseId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void deleteExpired(List<AdmissionRequirement> requirements, OffsetDateTime now) {
    for (AdmissionRequirement requirement : requirements) {
      jdbc.update(
          "delete from logistics_warehouse_admission_intent where warehouse_id=? and expires_at <= ?",
          requirement.warehouseId(),
          now);
    }
  }

  private void lockWarehouses(List<AdmissionRequirement> requirements) {
    requirements.stream()
        .map(AdmissionRequirement::warehouseId)
        .distinct()
        .sorted()
        .forEach(this::lockWarehouse);
  }

  private void lockWarehouse(UUID warehouseId) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "warehouse-lifecycle:logistics:" + warehouseId);
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime now = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (now == null) throw new IllegalStateException("Database clock returned null");
    return now.withOffsetSameInstant(ZoneOffset.UTC);
  }

  private static List<AdmissionRequirement> normalized(
      UUID operationId, List<AdmissionRequirement> requirements) {
    if (operationId == null || requirements == null || requirements.isEmpty()) {
      throw new IllegalArgumentException("Warehouse admission requirements are incomplete");
    }
    List<AdmissionRequirement> normalized =
        requirements.stream()
            .sorted(Comparator.comparing(AdmissionRequirement::warehouseId))
            .toList();
    if (normalized.stream().map(AdmissionRequirement::warehouseId).distinct().count()
        != normalized.size()) {
      throw new IllegalArgumentException("A warehouse can appear only once in one admission");
    }
    return normalized;
  }

  public record AdmissionRequirement(
      UUID warehouseId, WarehouseOperationDirection direction) {
    public AdmissionRequirement {
      if (warehouseId == null || direction == null) {
        throw new IllegalArgumentException("Warehouse admission requirement is invalid");
      }
    }
  }

  public record ReadinessAttempt(
      UUID warehouseId, long warehouseVersion, boolean sealed, boolean shouldConfirm) {}

  private record AdmissionRow(
      WarehouseOperationDirection direction,
      String state,
      Long warehouseVersion,
      OffsetDateTime expiresAt) {}

  private record ReadinessRow(long warehouseVersion, String state) {}
}
