package dev.buhanzaz.rwms.asset.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Transaction-scoped asset idempotency store keyed by subject, command scope and request fingerprint.
 */
@Repository
public class AssetIdempotencyStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final AssetIdempotencyProperties properties;

  public AssetIdempotencyStore(JdbcTemplate jdbc, ObjectMapper objectMapper, AssetIdempotencyProperties properties) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  /**
   * Looks up a prior response while holding the caller's transaction-scoped advisory lock for the
   * subject, command scope and key. A reused key with another request fingerprint fails closed.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<JsonNode> replay(UUID subjectId, String scope, UUID key, String requestHash) {
    lock(subjectId, scope, key);
    jdbc.update("delete from asset_idempotency_record where subject_id=? and command_scope=? and idempotency_key=? and expires_at <= clock_timestamp()",
        subjectId, scope, key);
    return jdbc.query(
        "select request_sha256,response_body::text from asset_idempotency_record where subject_id=? and command_scope=? and idempotency_key=?",
        (rs, row) -> new Stored(rs.getString("request_sha256").trim(), rs.getString("response_body")), subjectId, scope, key)
        .stream().findFirst().map(stored -> {
          if (!stored.requestHash().equals(requestHash)) {
            throw new AssetConflictException("Idempotency-Key is already bound to another command");
          }
          try {
            return objectMapper.readTree(stored.body());
          } catch (JacksonException exception) {
            throw new IllegalStateException("Stored idempotent asset response is corrupt", exception);
          }
        });
  }

  /**
   * Persists the successful response in the caller's command transaction so a later identical
   * retry can replay it only after the same domain change has committed.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void store(UUID subjectId, String scope, UUID key, String requestHash, int responseStatus, Object response) {
    try {
      String body = objectMapper.writeValueAsString(response);
      jdbc.update(
          """
          insert into asset_idempotency_record(subject_id,command_scope,idempotency_key,request_sha256,response_status,response_body,created_at,expires_at)
          values (?, ?, ?, ?, ?, ?::jsonb, clock_timestamp(), clock_timestamp() + (? * interval '1 millisecond'))
          """,
          subjectId, scope, key, requestHash, responseStatus, body, properties.retention().toMillis());
    } catch (JacksonException exception) {
      throw new IllegalStateException("Asset idempotency response cannot be serialized", exception);
    }
  }

  public int cleanupExpired() {
    return jdbc.update(
        "delete from asset_idempotency_record where ctid in (select ctid from asset_idempotency_record where expires_at <= clock_timestamp() order by expires_at limit ?)",
        Math.max(1, properties.cleanupBatchSize()));
  }

  private void lock(UUID subjectId, String scope, UUID key) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, subjectId + ":" + scope + ":" + key);
  }

  private record Stored(String requestHash, String body) {}
}
