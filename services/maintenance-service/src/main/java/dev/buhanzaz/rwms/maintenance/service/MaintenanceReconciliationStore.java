package dev.buhanzaz.rwms.maintenance.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Durable local coordinator for post-commit asset and task-board effects. */
@Repository
public class MaintenanceReconciliationStore {
  static final int MAX_ATTEMPTS = 4;

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public MaintenanceReconciliationStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(
      UUID repairId,
      String dependency,
      String operation,
      UUID idempotencyKey,
      Object payload) {
    if (dependency == null || operation == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Reconciliation identity is required");
    }
    int inserted = jdbc.update("""
        insert into integration_reconciliation(
          id,repair_id,dependency_type,operation_type,idempotency_key,state,
          attempt_count,next_attempt_at,response_snapshot,created_at,updated_at)
        values (?, ?, ?, ?, ?, 'PENDING', 0, clock_timestamp(), ?::jsonb,
          clock_timestamp(), clock_timestamp())
        on conflict (dependency_type,operation_type,idempotency_key) do nothing
        """, UUID.randomUUID(), repairId, dependency, operation, idempotencyKey, write(payload));
    rejectQuarantinedConflict(dependency, operation, idempotencyKey, inserted);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueueRequired(
      UUID repairId,
      String dependency,
      String operation,
      UUID idempotencyKey,
      Object payload) {
    if (dependency == null || operation == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Reconciliation identity is required");
    }
    int inserted = jdbc.update("""
        insert into integration_reconciliation(
          id,repair_id,dependency_type,operation_type,idempotency_key,state,
          attempt_count,next_attempt_at,response_snapshot,created_at,updated_at)
        values (?, ?, ?, ?, ?, 'RECONCILIATION_REQUIRED', 0, 'infinity'::timestamptz,
          ?::jsonb, clock_timestamp(), clock_timestamp())
        on conflict (dependency_type,operation_type,idempotency_key) do nothing
        """, UUID.randomUUID(), repairId, dependency, operation, idempotencyKey, write(payload));
    rejectQuarantinedConflict(dependency, operation, idempotencyKey, inserted);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public ResumeResult resumeQuarantined(
      UUID reconciliationId,
      long expectedReviewVersion,
      UUID reviewSubjectId,
      String reviewReason) {
    if (reconciliationId == null || expectedReviewVersion < 0 || reviewSubjectId == null
        || reviewReason == null || reviewReason.isBlank() || reviewReason.trim().length() > 2000) {
      throw new IllegalArgumentException("Reviewed reconciliation resume metadata is invalid");
    }
    String reason = reviewReason.trim();
    int changed = jdbc.update("""
        update integration_reconciliation
        set state='RETRY_PENDING',attempt_count=0,next_attempt_at=clock_timestamp(),
          last_error_code=null,review_version=review_version+1,review_subject_id=?,
          review_reason=?,reviewed_at=clock_timestamp(),updated_at=clock_timestamp()
        where id=? and state='QUARANTINED' and review_version=?
        """, reviewSubjectId, reason, reconciliationId, expectedReviewVersion);
    if (changed != 1) {
      ReviewState current = jdbc.query("""
          select state,review_version from integration_reconciliation where id=?
          """, (resultSet, rowNumber) -> new ReviewState(
              resultSet.getString("state"), resultSet.getLong("review_version")),
          reconciliationId).stream().findFirst().orElseThrow(() ->
              new MaintenanceNotFoundException("Reconciliation record not found"));
      if (current.reviewVersion() != expectedReviewVersion) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_VERSION_CONFLICT", "Reconciliation review version conflict");
      }
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Only a quarantined reconciliation can be resumed");
    }
    return jdbc.query("""
        select id,idempotency_key,state,review_version,review_subject_id,review_reason,reviewed_at
        from integration_reconciliation where id=?
        """, (resultSet, rowNumber) -> new ResumeResult(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("idempotency_key", UUID.class),
            resultSet.getString("state"),
            resultSet.getLong("review_version"),
            resultSet.getObject("review_subject_id", UUID.class),
            resultSet.getString("review_reason"),
            resultSet.getObject("reviewed_at", OffsetDateTime.class)),
        reconciliationId).stream().findFirst().orElseThrow();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<WorkItem> lockNextDue() {
    return jdbc.query("""
        select id,repair_id,dependency_type,operation_type,idempotency_key,state,
          attempt_count,next_attempt_at,response_snapshot::text
        from integration_reconciliation
        where state in ('PENDING','RETRY_PENDING','RECONCILIATION_REQUIRED')
          and attempt_count < ? and next_attempt_at <= clock_timestamp()
        order by next_attempt_at,id
        for update skip locked
        limit 1
        """, (resultSet, rowNumber) -> new WorkItem(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("repair_id", UUID.class),
            resultSet.getString("dependency_type"),
            resultSet.getString("operation_type"),
            resultSet.getObject("idempotency_key", UUID.class),
            resultSet.getString("state"),
            resultSet.getInt("attempt_count"),
            resultSet.getObject("next_attempt_at", OffsetDateTime.class),
            read(resultSet.getString("response_snapshot"))),
        MAX_ATTEMPTS).stream().findFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void confirmed(WorkItem item, Object response) {
    int changed = jdbc.update("""
        update integration_reconciliation
        set state='CONFIRMED',attempt_count=attempt_count+1,response_snapshot=?::jsonb,
          last_error_code=null,updated_at=clock_timestamp()
        where id=? and state in ('PENDING','RETRY_PENDING','RECONCILIATION_REQUIRED')
          and attempt_count=?
        """, write(response), item.id(), item.attemptCount());
    if (changed != 1) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Reconciliation claim changed concurrently");
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean failed(WorkItem item, RuntimeException failure) {
    int nextAttempt = Math.addExact(item.attemptCount(), 1);
    boolean quarantined = nextAttempt >= MAX_ATTEMPTS;
    int backoffSeconds = 1 << Math.min(item.attemptCount(), 2);
    int changed = jdbc.update("""
        update integration_reconciliation
        set state=?,attempt_count=?,
          next_attempt_at=clock_timestamp() + (? * interval '1 second'),
          last_error_code=?,updated_at=clock_timestamp()
        where id=? and state in ('PENDING','RETRY_PENDING','RECONCILIATION_REQUIRED')
          and attempt_count=?
        """, quarantined ? "QUARANTINED" : "RETRY_PENDING", nextAttempt,
        backoffSeconds, failureCode(failure), item.id(), item.attemptCount());
    if (changed != 1) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Reconciliation claim changed concurrently");
    }
    return quarantined;
  }

  private static String failureCode(RuntimeException failure) {
    String value = failure.getClass().getSimpleName();
    return value.length() <= 64 ? value : value.substring(0, 64);
  }

  private void rejectQuarantinedConflict(
      String dependency, String operation, UUID idempotencyKey, int inserted) {
    if (inserted != 0) return;
    String state = jdbc.queryForObject("""
        select state from integration_reconciliation
        where dependency_type=? and operation_type=? and idempotency_key=?
        """, String.class, dependency, operation, idempotencyKey);
    if ("QUARANTINED".equals(state)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_RECONCILIATION_QUARANTINED",
          "Stable reconciliation work is quarantined and requires reviewed resume");
    }
  }

  private JsonNode read(String value) {
    try {
      return mapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored reconciliation payload is invalid", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value == null ? java.util.Map.of() : value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Reconciliation payload cannot be serialized", exception);
    }
  }

  public record WorkItem(
      UUID id,
      UUID repairId,
      String dependency,
      String operation,
      UUID idempotencyKey,
      String state,
      int attemptCount,
      OffsetDateTime nextAttemptAt,
      JsonNode payload) {}

  public record ResumeResult(
      UUID reconciliationId,
      UUID idempotencyKey,
      String state,
      long reviewVersion,
      UUID reviewSubjectId,
      String reviewReason,
      OffsetDateTime reviewedAt) {}

  private record ReviewState(String state, long reviewVersion) {}
}
