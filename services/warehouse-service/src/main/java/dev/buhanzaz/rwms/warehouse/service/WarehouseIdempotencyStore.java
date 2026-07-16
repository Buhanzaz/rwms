package dev.buhanzaz.rwms.warehouse.service;

import dev.buhanzaz.rwms.warehouse.api.WarehouseResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class WarehouseIdempotencyStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final WarehouseIdempotencyProperties properties;

  public WarehouseIdempotencyStore(
      JdbcTemplate jdbc, ObjectMapper objectMapper, WarehouseIdempotencyProperties properties) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<WarehouseResponse> replay(UUID subjectId, UUID idempotencyKey, String requestSha256) {
    lock(subjectId, idempotencyKey);
    jdbc.update(
        """
        delete from idempotency_record
         where subject_id=? and idempotency_key=? and expires_at <= clock_timestamp()
        """,
        subjectId,
        idempotencyKey);
    List<StoredResponse> rows =
        jdbc.query(
            """
            select request_sha256,response_body::text
              from idempotency_record
             where subject_id=? and idempotency_key=?
            """,
            (resultSet, rowNumber) ->
                new StoredResponse(resultSet.getString("request_sha256").trim(), resultSet.getString("response_body")),
            subjectId,
            idempotencyKey);
    if (rows.isEmpty()) return Optional.empty();
    StoredResponse stored = rows.getFirst();
    if (!stored.requestSha256().equals(requestSha256)) {
      throw new WarehouseConflictException("Idempotency-Key is already bound to another command");
    }
    return Optional.of(readResponse(stored.responseBody()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void storeSuccess(
      UUID subjectId,
      UUID idempotencyKey,
      String requestSha256,
      WarehouseResponse response) {
    jdbc.update(
        """
        insert into idempotency_record(
            subject_id,idempotency_key,request_sha256,response_status,response_body,
            warehouse_id,created_at,expires_at)
        values (?, ?, ?, 201, ?::jsonb, ?, clock_timestamp(),
                clock_timestamp() + (? * interval '1 millisecond'))
        """,
        subjectId,
        idempotencyKey,
        requestSha256,
        writeResponse(response),
        response.id(),
        properties.retention().toMillis());
  }

  public int cleanupExpired() {
    int batchSize = Math.max(1, properties.cleanupBatchSize());
    return jdbc.update(
        """
        delete from idempotency_record
         where ctid in (
           select ctid from idempotency_record
            where expires_at <= clock_timestamp()
            order by expires_at
            limit ?
         )
        """,
        batchSize);
  }

  private void lock(UUID subjectId, UUID idempotencyKey) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> {},
        subjectId + ":" + idempotencyKey);
  }

  private String writeResponse(WarehouseResponse response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Warehouse idempotency response cannot be serialized", exception);
    }
  }

  private WarehouseResponse readResponse(String responseBody) {
    try {
      return objectMapper.readValue(responseBody, WarehouseResponse.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Warehouse idempotency response is corrupt", exception);
    }
  }

  private record StoredResponse(String requestSha256, String responseBody) {}
}
