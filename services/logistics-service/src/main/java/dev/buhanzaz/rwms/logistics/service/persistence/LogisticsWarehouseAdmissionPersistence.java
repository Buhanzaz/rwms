package dev.buhanzaz.rwms.logistics.service.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Narrow PostgreSQL persistence adapter for warehouse admission intents, readiness fences, and
 * durable replay evidence.
 *
 * <p>This adapter exposes only rows and conditional writes. The lifecycle store owns request
 * normalization, replay acceptance, and every conflict decision; keeping the provider-specific
 * locking and clock semantics here preserves the original transaction behavior without exposing a
 * general SQL surface.
 */
@Repository
public class LogisticsWarehouseAdmissionPersistence {
  private final JdbcTemplate jdbc;

  public LogisticsWarehouseAdmissionPersistence(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Reads the PostgreSQL wall clock used by the surrounding admission transaction. */
  public OffsetDateTime databaseNow() {
    OffsetDateTime now = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (now == null) {
      throw new IllegalStateException("Database clock returned null");
    }
    return now.withOffsetSameInstant(ZoneOffset.UTC);
  }

  /** Returns the persisted warehouse requirements for a live document-command replay receipt. */
  public List<StoredRequirementRow> documentReplayRequirements(
      UUID subjectId, String operationName, UUID idempotencyKey) {
    return jdbc.query(
        """
        with replay_document as (
          select d.id,d.document_type,d.warehouse_id,d.destination_warehouse_id
            from logistics_idempotency_record receipt
            join logistics_document d on d.id=receipt.document_id
           where receipt.subject_id=? and receipt.operation_name=?
             and receipt.idempotency_key=? and receipt.expires_at > clock_timestamp()
        )
        select * from (
          select id operation_id,warehouse_id,
                 case document_type when 'RETURN' then 'INCOMING' else 'OUTGOING' end
                   expected_direction
            from replay_document
           where document_type in ('RETURN','SHIPMENT','TRANSFER')
          union all
          select id operation_id,destination_warehouse_id warehouse_id,
                 'INCOMING' expected_direction
            from replay_document
           where document_type='TRANSFER'
        ) stored_requirement
        order by warehouse_id
        """,
        (rs, ignored) ->
            new StoredRequirementRow(
                rs.getObject("operation_id", UUID.class),
                rs.getObject("warehouse_id", UUID.class),
                rs.getString("expected_direction")),
        subjectId,
        operationName,
        idempotencyKey);
  }

  /** Returns the persisted warehouse requirements for an equipment-movement replay candidate. */
  public List<StoredRequirementRow> equipmentMovementReplayRequirements(
      UUID actorSubjectId, UUID idempotencyKey) {
    return jdbc.query(
        """
        with replay_task as (
          select id,warehouse_id
            from equipment_movement_task
           where created_by_subject_id=? and idempotency_key=?
        )
        select * from (
          select id operation_id,warehouse_id,'OUTGOING' expected_direction
            from replay_task
          union
          select task.id operation_id,line.target_warehouse_id warehouse_id,
                 'INCOMING' expected_direction
            from replay_task task
            join equipment_movement_task_line line on line.task_id=task.id
           where line.target_warehouse_id <> task.warehouse_id
        ) stored_requirement
        order by warehouse_id
        """,
        (rs, ignored) ->
            new StoredRequirementRow(
                rs.getObject("operation_id", UUID.class),
                rs.getObject("warehouse_id", UUID.class),
                rs.getString("expected_direction")),
        actorSubjectId,
        idempotencyKey);
  }

  /** Returns the persisted warehouse requirements for a driver-task replay candidate. */
  public List<StoredRequirementRow> driverTaskReplayRequirements(
      UUID actorSubjectId, UUID idempotencyKey) {
    return jdbc.query(
        """
        select id operation_id,warehouse_id,
               case task_kind
                 when 'REMOVE_FROM_REPAIR' then 'OUTGOING'
                 when 'CAPITAL_TO_PRODUCTION' then 'OUTGOING'
                 else 'INCOMING'
               end expected_direction
          from driver_logistics_task
         where created_by_subject_id=? and idempotency_key=?
           and task_kind in (
             'DELIVER_TO_REPAIR','REMOVE_FROM_REPAIR',
             'CAPITAL_TO_PRODUCTION','GENERAL_MOVEMENT')
        """,
        (rs, ignored) ->
            new StoredRequirementRow(
                rs.getObject("operation_id", UUID.class),
                rs.getObject("warehouse_id", UUID.class),
                rs.getString("expected_direction")),
        actorSubjectId,
        idempotencyKey);
  }

  /** Returns permanent operation-mark admission columns without deciding whether they are valid. */
  public List<MarkAdmissionRow> operationMarkAdmissions(UUID operationId) {
    return jdbc.query(
        """
        select warehouse_id,admission_direction,admission_warehouse_version
          from warehouse_operation_mark_outbox
         where operation_id=?
        """,
        (rs, ignored) ->
            new MarkAdmissionRow(
                rs.getObject("warehouse_id", UUID.class),
                rs.getString("admission_direction"),
                rs.getObject("admission_warehouse_version", Long.class)),
        operationId);
  }

  /** Deletes expired admission intents for one warehouse under the caller's transaction lock. */
  public int deleteExpired(UUID warehouseId, OffsetDateTime now) {
    return jdbc.update(
        "delete from logistics_warehouse_admission_intent where warehouse_id=? and expires_at <= ?",
        warehouseId,
        now);
  }

  /** Inserts a reserved admission intent only when its immutable identity is absent. */
  public int insertReservedIfAbsent(
      UUID operationId,
      UUID warehouseId,
      String direction,
      OffsetDateTime expiresAt,
      OffsetDateTime now) {
    return jdbc.update(
        """
        insert into logistics_warehouse_admission_intent(
          operation_id,warehouse_id,direction,state,warehouse_version,expires_at,created_at,updated_at)
        values (?, ?, ?, 'RESERVED', null, ?, ?, ?)
        on conflict (operation_id,warehouse_id) do nothing
        """,
        operationId,
        warehouseId,
        direction,
        expiresAt,
        now,
        now);
  }

  /** Locks and returns one admission intent, or {@code null} when no intent exists. */
  public AdmissionRow admissionForUpdate(UUID operationId, UUID warehouseId) {
    return jdbc.query(
            """
            select direction,state,warehouse_version,expires_at
              from logistics_warehouse_admission_intent
             where operation_id=? and warehouse_id=?
             for update
            """,
            (rs, ignored) ->
                new AdmissionRow(
                    rs.getString("direction"),
                    rs.getString("state"),
                    rs.getObject("warehouse_version", Long.class),
                    rs.getObject("expires_at", OffsetDateTime.class)),
            operationId,
            warehouseId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** Marks a matching reserved intent as admitted with the supplied immutable warehouse version. */
  public int admitReserved(
      UUID operationId,
      UUID warehouseId,
      String direction,
      long warehouseVersion,
      OffsetDateTime now) {
    return jdbc.update(
        """
        update logistics_warehouse_admission_intent
           set state='ADMITTED',warehouse_version=?,updated_at=?
         where operation_id=? and warehouse_id=? and state='RESERVED'
           and direction=? and expires_at > ?
        """,
        warehouseVersion,
        now,
        operationId,
        warehouseId,
        direction,
        now);
  }

  /** Deletes only a still-reserved admission intent with the supplied immutable direction. */
  public int deleteReserved(UUID operationId, UUID warehouseId, String direction) {
    return jdbc.update(
        """
        delete from logistics_warehouse_admission_intent
         where operation_id=? and warehouse_id=? and state='RESERVED' and direction=?
        """,
        operationId,
        warehouseId,
        direction);
  }

  /** Deletes an admitted intent after the lifecycle store has validated its evidence. */
  public int deleteAdmission(UUID operationId, UUID warehouseId) {
    return jdbc.update(
        "delete from logistics_warehouse_admission_intent where operation_id=? and warehouse_id=?",
        operationId,
        warehouseId);
  }

  /** Locks and returns the readiness fence for one warehouse, or {@code null} when absent. */
  public ReadinessRow readinessForUpdate(UUID warehouseId) {
    return jdbc.query(
            """
            select warehouse_version,state from logistics_warehouse_readiness_fence
             where warehouse_id=? for update
            """,
            (rs, ignored) -> new ReadinessRow(rs.getLong("warehouse_version"), rs.getString("state")),
            warehouseId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** Creates a confirming readiness fence using the caller's database-clock timestamp. */
  public int insertConfirmingReadiness(
      UUID warehouseId, long warehouseVersion, OffsetDateTime now) {
    return jdbc.update(
        """
        insert into logistics_warehouse_readiness_fence(
          warehouse_id,warehouse_version,state,created_at,updated_at)
        values (?,?,'CONFIRMING',?,?)
        """,
        warehouseId,
        warehouseVersion,
        now,
        now);
  }

  /** Seals a confirming readiness fence while retaining the database-side clock update. */
  public int sealReadiness(UUID warehouseId, long attemptedVersion) {
    return jdbc.update(
        """
        update logistics_warehouse_readiness_fence
           set state='SEALED',updated_at=clock_timestamp()
         where warehouse_id=? and warehouse_version=? and state='CONFIRMING'
        """,
        warehouseId,
        attemptedVersion);
  }

  /** Releases only the confirming readiness fence guarded by the attempted version. */
  public int releaseReadiness(UUID warehouseId, long attemptedVersion) {
    return jdbc.update(
        """
        delete from logistics_warehouse_readiness_fence
         where warehouse_id=? and warehouse_version=? and state='CONFIRMING'
        """,
        warehouseId,
        attemptedVersion);
  }

  /** Raw immutable columns of an admission intent locked for lifecycle validation. */
  public record AdmissionRow(
      String direction, String state, Long warehouseVersion, OffsetDateTime expiresAt) {}

  /** Raw immutable admission columns stored on a permanent operation mark. */
  public record MarkAdmissionRow(UUID warehouseId, String direction, Long warehouseVersion) {}

  /** Raw domain-derived requirement reconstructed for a possible command replay. */
  public record StoredRequirementRow(UUID operationId, UUID warehouseId, String direction) {}

  /** Raw readiness-fence state locked for the lifecycle store's decision. */
  public record ReadinessRow(long warehouseVersion, String state) {}
}
