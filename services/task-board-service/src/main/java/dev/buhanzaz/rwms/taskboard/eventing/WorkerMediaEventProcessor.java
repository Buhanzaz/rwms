package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.TaskEvidenceFact;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardEntryOwnerProofService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Links media facts to reserved worker-task evidence and Driver Shift photo correlations.
 *
 * <p>Missing evidence remains pending for reconciliation; owner, warehouse, worker and correlation
 * mismatches are rejected rather than attaching another driver's media.
 */
@Service
@RequiredArgsConstructor
public class WorkerMediaEventProcessor {
  private static final String TASK_BOARD_OWNER = "TASK_BOARD_ENTRY";
  private static final String DRIVER_SHIFT_OWNER = "DRIVER_SHIFT";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardEntryOwnerProofService ownerProofs;

  @Transactional
  public void process(byte[] body) {
    JsonNode envelope = read(body);
    JsonNode payload = requiredObject(envelope, "payload");
    String ownerType = requiredText(payload, "ownerType");
    if (DRIVER_SHIFT_OWNER.equals(ownerType)) {
      processDriverShift(envelope, payload, body);
      return;
    }
    if (!TASK_BOARD_OWNER.equals(ownerType)) {
      return;
    }

    String eventType = requiredText(envelope, "eventType");
    if (!List.of(
            "media.media.uploaded.v1",
            "media.media.ready.v1",
            "media.media.failed.v1")
        .contains(eventType)) {
      return;
    }
    if (envelope.required("envelopeVersion").intValue() != 2
        || envelope.required("eventVersion").intValue() != 1
        || !"media-service".equals(requiredText(envelope, "producer"))
        || !"MEDIA".equals(requiredText(envelope, "aggregateType"))) {
      throw new IllegalArgumentException("Некорректный media event envelope");
    }

    UUID eventId = requiredUuid(envelope, "eventId");
    UUID mediaId = requiredUuid(envelope, "aggregateId");
    if (!mediaId.equals(requiredUuid(payload, "mediaId"))
        || !"IMAGE".equals(requiredText(payload, "kind"))) {
      throw new IllegalArgumentException("Некорректная фотография результата");
    }
    UUID entryId = requiredUuid(payload, "ownerId");
    UUID evidenceId = requiredUuid(payload, "clientReferenceId");
    UUID warehouseId = requiredUuid(payload, "warehouseId");
    JsonNode actor = requiredObject(envelope, "actorRef");
    if (!"WORKER".equals(requiredText(actor, "principalType"))) {
      throw new IllegalArgumentException("Media event не принадлежит рабочему");
    }
    UUID actorWorkerId = requiredUuid(actor, "subjectId");
    UUID correlationId =
        requiredUuid(requiredObject(envelope, "correlation"), "correlationId");
    long aggregateVersion = envelope.required("aggregateVersion").longValue();
    if (aggregateVersion < 1) {
      throw new IllegalArgumentException("Некорректная версия media event");
    }
    OffsetDateTime recordedAt =
        OffsetDateTime.parse(requiredText(envelope, "recordedAt"));
    Long generation = mediaGeneration(eventType, payload);
    String expectedStatus =
        switch (eventType) {
          case "media.media.uploaded.v1" -> "PROCESSING";
          case "media.media.ready.v1" -> "READY";
          case "media.media.failed.v1" -> "FAILED";
          default -> throw new IllegalArgumentException("Unsupported media event");
        };
    if (!expectedStatus.equals(requiredText(payload, "status"))) {
      throw new IllegalArgumentException("Media event status не соответствует типу события");
    }

    int inserted =
        jdbc.update(
            """
            insert into worker_media_event_inbox(
                event_id,media_id,aggregate_version,event_type,entry_id,evidence_id,
                warehouse_id,actor_worker_id,correlation_id,media_generation,
                recorded_at,body_sha256,status,received_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',clock_timestamp())
            on conflict do nothing
            """,
            eventId,
            mediaId,
            aggregateVersion,
            eventType,
            entryId,
            evidenceId,
            warehouseId,
            actorWorkerId,
            correlationId,
            generation,
            recordedAt,
            TaskBoardEventStore.sha256(body));
    if (inserted == 1) {
      apply(eventId);
    }
  }

  @Transactional
  public void reconcilePending() {
    List<UUID> pending =
        jdbc.queryForList(
            """
            select event_id
              from worker_media_event_inbox
             where status='PENDING'
             order by received_at,event_id
             limit 100
             for update skip locked
            """,
            UUID.class);
    pending.forEach(this::apply);

    List<UUID> shiftPending =
        jdbc.queryForList(
            """
            select event_id
              from driver_shift_media_event_inbox
             where status='PENDING'
             order by received_at,event_id
             limit 100
             for update skip locked
            """,
            UUID.class);
    shiftPending.forEach(this::applyDriverShift);
  }

  private void processDriverShift(JsonNode envelope, JsonNode payload, byte[] body) {
    String eventType = requiredText(envelope, "eventType");
    if (!List.of(
            "media.media.uploaded.v1",
            "media.media.ready.v1",
            "media.media.failed.v1")
        .contains(eventType)) {
      return;
    }
    if (envelope.required("envelopeVersion").intValue() != 2
        || envelope.required("eventVersion").intValue() != 1
        || !"media-service".equals(requiredText(envelope, "producer"))
        || !"MEDIA".equals(requiredText(envelope, "aggregateType"))) {
      throw new IllegalArgumentException("Некорректный media event envelope");
    }

    UUID eventId = requiredUuid(envelope, "eventId");
    UUID mediaId = requiredUuid(envelope, "aggregateId");
    if (!mediaId.equals(requiredUuid(payload, "mediaId"))
        || !"IMAGE".equals(requiredText(payload, "kind"))) {
      throw new IllegalArgumentException("Некорректная фотография смены");
    }
    UUID shiftId = requiredUuid(payload, "ownerId");
    UUID evidenceId = requiredUuid(payload, "clientReferenceId");
    UUID warehouseId = requiredUuid(payload, "warehouseId");
    JsonNode actor = requiredObject(envelope, "actorRef");
    if (!"WORKER".equals(requiredText(actor, "principalType"))) {
      throw new IllegalArgumentException("Media event не принадлежит водителю");
    }
    UUID driverId = requiredUuid(actor, "subjectId");
    long aggregateVersion = envelope.required("aggregateVersion").longValue();
    if (aggregateVersion < 1) {
      throw new IllegalArgumentException("Некорректная версия media event");
    }
    Long generation = mediaGeneration(eventType, payload);
    String expectedStatus =
        switch (eventType) {
          case "media.media.uploaded.v1" -> "PROCESSING";
          case "media.media.ready.v1" -> "READY";
          case "media.media.failed.v1" -> "FAILED";
          default -> throw new IllegalArgumentException("Unsupported media event");
        };
    if (!expectedStatus.equals(requiredText(payload, "status"))) {
      throw new IllegalArgumentException("Media event status не соответствует типу события");
    }

    int inserted =
        jdbc.update(
            """
            insert into driver_shift_media_event_inbox(
                event_id,media_id,aggregate_version,event_type,shift_id,evidence_id,
                warehouse_id,actor_worker_id,media_generation,body_sha256,status,received_at)
            values (?,?,?,?,?,?,?,?,?,?,'PENDING',clock_timestamp())
            on conflict do nothing
            """,
            eventId,
            mediaId,
            aggregateVersion,
            eventType,
            shiftId,
            evidenceId,
            warehouseId,
            driverId,
            generation,
            TaskBoardEventStore.sha256(body));
    if (inserted == 1) {
      applyDriverShift(eventId);
    }
  }

  private void applyDriverShift(UUID eventId) {
    List<DriverShiftInboxRow> rows =
        jdbc.query(
            """
            select *
              from driver_shift_media_event_inbox
             where event_id=? and status='PENDING'
             for update
            """,
            (result, row) ->
                new DriverShiftInboxRow(
                    result.getObject("event_id", UUID.class),
                    result.getObject("media_id", UUID.class),
                    result.getString("event_type"),
                    result.getObject("shift_id", UUID.class),
                    result.getObject("evidence_id", UUID.class),
                    result.getObject("warehouse_id", UUID.class),
                    result.getObject("actor_worker_id", UUID.class),
                    result.getObject("media_generation") == null
                        ? null
                        : result.getLong("media_generation")),
            eventId);
    if (rows.isEmpty()) {
      return;
    }
    DriverShiftInboxRow event = rows.getFirst();
    List<Map<String, Object>> reservations =
        jdbc.queryForList(
            """
            select shift_id,driver_id,warehouse_id
              from driver_shift_photo
             where evidence_id=?
             for update
            """,
            event.evidenceId());
    if (reservations.isEmpty()) {
      return;
    }
    Map<String, Object> reservation = reservations.getFirst();
    if (!event.shiftId().equals(reservation.get("shift_id"))
        || !event.warehouseId().equals(reservation.get("warehouse_id"))
        || !event.driverId().equals(reservation.get("driver_id"))) {
      finishShiftInbox(event.eventId(), "REJECTED", "EVIDENCE_OWNER_MISMATCH");
      return;
    }
    if ("media.media.uploaded.v1".equals(event.eventType())) {
      jdbc.update(
          """
          update driver_shift_photo
             set version=version+1,state='PROCESSING',media_id=?
           where evidence_id=?
          """,
          event.mediaId(),
          event.evidenceId());
      finishShiftInbox(event.eventId(), "APPLIED", null);
      return;
    }
    if ("media.media.failed.v1".equals(event.eventType())) {
      jdbc.update(
          """
          update driver_shift_photo
             set version=version+1,state='REVIEW_REQUIRED',media_id=?,
                 media_generation=null,review_reason=?
           where evidence_id=?
          """,
          event.mediaId(),
          "Сервер не смог обработать фотографию. Повторите съёмку.",
          event.evidenceId());
      finishShiftInbox(event.eventId(), "APPLIED", null);
      return;
    }
    if (event.mediaGeneration() == null) {
      finishShiftInbox(event.eventId(), "REJECTED", "MEDIA_GENERATION_MISSING");
      return;
    }
    jdbc.update(
        """
        update driver_shift_photo
           set version=version+1,state='READY',media_id=?,media_generation=?,review_reason=null
         where evidence_id=?
        """,
        event.mediaId(),
        event.mediaGeneration(),
        event.evidenceId());
    finishShiftInbox(event.eventId(), "APPLIED", null);
  }

  private void finishShiftInbox(UUID eventId, String status, String failureCode) {
    jdbc.update(
        """
        update driver_shift_media_event_inbox
           set status=?,failure_code=?,processed_at=clock_timestamp()
         where event_id=? and status='PENDING'
        """,
        status,
        failureCode,
        eventId);
  }

  private void apply(UUID eventId) {
    List<InboxRow> rows =
        jdbc.query(
            """
            select *
              from worker_media_event_inbox
             where event_id=? and status='PENDING'
             for update
            """,
            (result, row) ->
                new InboxRow(
                    result.getObject("event_id", UUID.class),
                    result.getObject("media_id", UUID.class),
                    result.getString("event_type"),
                    result.getObject("entry_id", UUID.class),
                    result.getObject("evidence_id", UUID.class),
                    result.getObject("warehouse_id", UUID.class),
                    result.getObject("actor_worker_id", UUID.class),
                    result.getObject("correlation_id", UUID.class),
                    result.getObject("media_generation") == null
                        ? null
                        : result.getLong("media_generation"),
                    result.getObject("recorded_at", OffsetDateTime.class)),
            eventId);
    if (rows.isEmpty()) {
      return;
    }
    InboxRow event = rows.getFirst();
    if ("media.media.uploaded.v1".equals(event.eventType())) {
      finishInbox(event.eventId(), "IGNORED", null);
      return;
    }

    List<EvidenceProjection> evidenceRows =
        jdbc.query(
            """
            select *
              from worker_task_evidence
             where evidence_id=?
             for update
            """,
            (result, row) ->
                new EvidenceProjection(
                    result.getObject("evidence_id", UUID.class),
                    result.getObject("entry_id", UUID.class),
                    result.getObject("task_id", UUID.class),
                    result.getInt("route_index"),
                    result.getObject("warehouse_id", UUID.class),
                    result.getObject("worker_id", UUID.class),
                    result.getObject("worker_group_id", UUID.class),
                    result.getObject("captured_at", OffsetDateTime.class),
                    result.getString("source_type"),
                    result.getObject("source_id", UUID.class)),
            event.evidenceId());
    if (evidenceRows.isEmpty()) {
      return;
    }
    EvidenceProjection evidence = evidenceRows.getFirst();
    if (!evidence.entryId().equals(event.entryId())
        || !evidence.warehouseId().equals(event.warehouseId())
        || !evidence.workerId().equals(event.actorWorkerId())) {
      finishInbox(event.eventId(), "REJECTED", "EVIDENCE_OWNER_MISMATCH");
      return;
    }

    if ("media.media.failed.v1".equals(event.eventType())) {
      jdbc.update(
          """
          update worker_task_evidence
             set version=version+1,state='REVIEW_REQUIRED',media_id=?,
                 media_generation=null,review_reason=?,
                 recorded_at=?,updated_at=clock_timestamp()
           where evidence_id=?
          """,
          event.mediaId(),
          "Сервер не смог обработать фотографию. Повторите съёмку.",
          event.recordedAt(),
          evidence.evidenceId());
      finishInbox(event.eventId(), "APPLIED", null);
      closeOwnerProofIfTerminal(evidence);
      return;
    }
    if (event.mediaGeneration() == null) {
      finishInbox(event.eventId(), "REJECTED", "MEDIA_GENERATION_MISSING");
      return;
    }

    String entryStatus =
        jdbc.queryForObject(
            "select status from queue_entry where id=?",
            String.class,
            evidence.entryId());
    boolean late = "CANCELLED".equals(entryStatus);
    String evidenceState = late ? "REVIEW_REQUIRED" : "READY";
    String reviewReason =
        late ? "Задание отменено до завершения загрузки; фото сохранено для проверки." : null;
    Long projectionVersion =
        jdbc.queryForObject(
            """
            update worker_task_evidence
               set version=version+1,state=?,media_id=?,media_generation=?,
                   review_reason=?,recorded_at=?,updated_at=clock_timestamp()
             where evidence_id=?
             returning version
            """,
            Long.class,
            evidenceState,
            event.mediaId(),
            event.mediaGeneration(),
            reviewReason,
            event.recordedAt(),
            evidence.evidenceId());
    if (projectionVersion == null) {
      throw new IllegalStateException("Не удалось обновить фотографию результата");
    }
    eventSourcing.evidenceRecorded(
        new TaskEvidenceFact(
            evidence.evidenceId(),
            evidence.entryId(),
            evidence.taskId(),
            evidence.routeIndex(),
            evidence.warehouseId(),
            evidence.workerId(),
            evidence.workerGroupId(),
            event.mediaId(),
            event.mediaGeneration(),
            evidence.capturedAt(),
            event.recordedAt(),
            evidenceState,
            evidence.sourceType(),
            evidence.sourceId()),
        event.correlationId(),
        event.eventId());
    finishInbox(event.eventId(), "APPLIED", null);
    closeOwnerProofIfTerminal(evidence);
  }

  private void closeOwnerProofIfTerminal(EvidenceProjection evidence) {
    String status =
        jdbc.queryForObject(
            "select status from queue_entry where id=?",
            String.class,
            evidence.entryId());
    if ("DONE".equals(status) || "CANCELLED".equals(status)) {
      ownerProofs.publish(evidence.warehouseId(), evidence.entryId(), false);
    }
  }

  private void finishInbox(UUID eventId, String status, String failureCode) {
    jdbc.update(
        """
        update worker_media_event_inbox
           set status=?,failure_code=?,processed_at=clock_timestamp()
         where event_id=? and status='PENDING'
        """,
        status,
        failureCode,
        eventId);
  }

  private Long mediaGeneration(String eventType, JsonNode payload) {
    if (!"media.media.ready.v1".equals(eventType)) {
      return null;
    }
    long generation = payload.required("generation").longValue();
    if (generation < 1) {
      throw new IllegalArgumentException("Некорректное поколение фотографии");
    }
    return generation;
  }

  private JsonNode read(byte[] body) {
    try {
      return objectMapper.readTree(body);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Media event не является JSON: " + TaskBoardEventStore.sha256(body),
          exception);
    }
  }

  private JsonNode requiredObject(JsonNode node, String field) {
    JsonNode value = node.required(field);
    if (!value.isObject()) {
      throw new IllegalArgumentException(field + " должен быть объектом");
    }
    return value;
  }

  private String requiredText(JsonNode node, String field) {
    JsonNode value = node.required(field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " обязателен");
    }
    return value.textValue();
  }

  private UUID requiredUuid(JsonNode node, String field) {
    try {
      return UUID.fromString(requiredText(node, field));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " должен быть UUID", exception);
    }
  }

  private record InboxRow(
      UUID eventId,
      UUID mediaId,
      String eventType,
      UUID entryId,
      UUID evidenceId,
      UUID warehouseId,
      UUID actorWorkerId,
      UUID correlationId,
      Long mediaGeneration,
      OffsetDateTime recordedAt) {}

  private record EvidenceProjection(
      UUID evidenceId,
      UUID entryId,
      UUID taskId,
      int routeIndex,
      UUID warehouseId,
      UUID workerId,
      UUID workerGroupId,
      OffsetDateTime capturedAt,
      String sourceType,
      UUID sourceId) {}

  /** Safe fields retained from one driver-shift media event awaiting correlation. */
  private record DriverShiftInboxRow(
      UUID eventId,
      UUID mediaId,
      String eventType,
      UUID shiftId,
      UUID evidenceId,
      UUID warehouseId,
      UUID driverId,
      Long mediaGeneration) {}
}
