package dev.buhanzaz.rwms.maintenance.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Stores or configures idempotent maintenance command replay for the owning workflow. */
@Repository
public class MaintenanceIdempotencyStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Duration retention;

  public MaintenanceIdempotencyStore(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      @Value("${rwms.maintenance.idempotency.retention:7d}") Duration retention) {
    if (retention == null || retention.isNegative() || retention.isZero()) {
      throw new IllegalArgumentException("Maintenance idempotency retention must be positive");
    }
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.retention = retention;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<JsonNode> replay(UUID subjectId, String scope, UUID key, String requestHash) {
    requireIdentity(subjectId, scope, key, requestHash);
    // A row does not exist on the first attempt, so SELECT FOR UPDATE alone cannot arbitrate two
    // concurrent first uses. The transaction-scoped advisory lock serializes both first use and
    // replay before any domain mutation is allowed to run.
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> {},
        subjectId + ":" + scope + ":" + key);
    jdbc.update("""
        delete from maintenance_idempotency_record
        where subject_id=? and command_scope=? and idempotency_key=?
          and expires_at <= clock_timestamp()
        """, subjectId, scope, key);
    return jdbc.query("""
        select request_sha256,response_body::text from maintenance_idempotency_record
        where subject_id=? and command_scope=? and idempotency_key=? and expires_at > clock_timestamp()
        for update
        """, (rs, row) -> new Stored(rs.getString("request_sha256").trim(), read(rs.getString("response_body"))),
        subjectId, scope, key).stream().findFirst().map(stored -> {
          if (!stored.requestHash().equals(requestHash)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_IDEMPOTENCY_CONFLICT",
                "Idempotency key was already used for another canonical request");
          }
          return stored.body();
        });
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void store(
      UUID subjectId,
      String scope,
      UUID key,
      String requestHash,
      int responseStatus,
      Object response) {
    requireIdentity(subjectId, scope, key, requestHash);
    jdbc.update("""
        insert into maintenance_idempotency_record(subject_id,command_scope,idempotency_key,request_sha256,
          response_status,response_body,created_at,expires_at)
        values (?, ?, ?, ?, ?, ?::jsonb, clock_timestamp(), clock_timestamp() + (? * interval '1 millisecond'))
        """, subjectId, scope, key, requestHash, responseStatus, write(response), retention.toMillis());
  }

  private static void requireIdentity(
      UUID subjectId, String scope, UUID key, String requestHash) {
    if (subjectId == null || scope == null || scope.isBlank() || scope.length() > 96
        || key == null || requestHash == null || !requestHash.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Maintenance idempotency identity is invalid");
    }
  }

  private JsonNode read(String json) {
    try {
      return mapper.readTree(json);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance idempotency response is invalid", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Maintenance response cannot be serialized", exception);
    }
  }

  private record Stored(String requestHash, JsonNode body) {}
}
