package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerActionAppliedResult;
import dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerActionRequest;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventStore;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns immutable, concurrency-safe receipts for native worker actions.
 *
 * <p>The operation advisory lock is acquired before any live task read. An exact replay therefore
 * returns the original frozen response even if the task later changes or leaves the caller's live
 * feed. A pre-receipt legacy event is rejected because its original response and full request
 * cannot be reconstructed safely.
 */
@Component
public class WorkerActionReceiptStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Creates the JDBC-owned receipt store. */
  public WorkerActionReceiptStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * Serializes concurrent attempts for the operation and returns its frozen exact replay, if any.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<WorkerActionAppliedResult> lockAndReplay(
      MobileTaskSurface surface,
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      WorkerActionRequest request) {
    String requestBody = requestBody(surface, workerId, warehouseId, entryId, request);
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "worker-action:" + request.operationId());
    List<ReceiptRow> receipts =
        jdbc.query(
            """
            select request_body,request_sha256,response_body,response_sha256
              from worker_action_receipt
             where operation_id=?
            """,
            (result, row) ->
                new ReceiptRow(
                    result.getString("request_body"),
                    result.getString("request_sha256"),
                    result.getString("response_body"),
                    result.getString("response_sha256")),
            request.operationId());
    if (!receipts.isEmpty()) {
      ReceiptRow receipt = receipts.getFirst();
      String requestHash = sha256(requestBody);
      if (!receipt.requestBody().equals(requestBody)
          || !receipt.requestSha256().equals(requestHash)) {
        throw new ConflictException("operationId уже использован другой командой");
      }
      requireChecksum(receipt.responseBody(), receipt.responseSha256());
      return Optional.of(readResponse(receipt.responseBody()));
    }
    Boolean legacyOperation =
        jdbc.queryForObject(
            "select exists(select 1 from domain_event where correlation_id=?)",
            Boolean.class,
            request.operationId());
    if (Boolean.TRUE.equals(legacyOperation)) {
      throw new ConflictException(
          "operationId относится к legacy-команде без сохранённого ответа; используйте новый operationId");
    }
    return Optional.empty();
  }

  /** Persists and rehydrates the first response so first delivery and every replay share one form. */
  @Transactional(propagation = Propagation.MANDATORY)
  public WorkerActionAppliedResult save(
      MobileTaskSurface surface,
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      WorkerActionRequest request,
      WorkerActionAppliedResult response) {
    String requestBody = requestBody(surface, workerId, warehouseId, entryId, request);
    String responseBody = canonicalJson(write(response));
    jdbc.update(
        """
        insert into worker_action_receipt(
            operation_id,app_surface,worker_id,warehouse_id,entry_id,action,
            request_body,request_sha256,response_body,response_sha256,created_at)
        values (?,?,?,?,?,?,?,?,?,?,clock_timestamp())
        """,
        request.operationId(),
        surface.name(),
        workerId,
        warehouseId,
        entryId,
        request.action().name(),
        requestBody,
        sha256(requestBody),
        responseBody,
        sha256(responseBody));
    return readResponse(responseBody);
  }

  private String requestBody(
      MobileTaskSurface surface,
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      WorkerActionRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("surface", surface.name());
    body.put("workerId", workerId);
    body.put("warehouseId", warehouseId);
    body.put("entryId", entryId);
    body.put("request", request);
    return canonicalJson(write(body));
  }

  private String canonicalJson(String value) {
    String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
    if (canonical == null) {
      throw new IllegalStateException("PostgreSQL did not canonicalize worker action JSON");
    }
    return canonical;
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Worker action receipt cannot be serialized", exception);
    }
  }

  private WorkerActionAppliedResult readResponse(String value) {
    try {
      return objectMapper.readValue(value, WorkerActionAppliedResult.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Worker action receipt cannot be read", exception);
    }
  }

  private String sha256(String value) {
    return TaskBoardEventStore.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private void requireChecksum(String body, String expected) {
    if (!sha256(body).equals(expected)) {
      throw new IllegalStateException("Worker action receipt checksum mismatch");
    }
  }

  /** Immutable row required to compare a request and rehydrate its original response. */
  private record ReceiptRow(
      String requestBody, String requestSha256, String responseBody, String responseSha256) {}
}
