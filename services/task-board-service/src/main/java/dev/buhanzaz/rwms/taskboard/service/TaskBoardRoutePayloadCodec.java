package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canonicalizes route payloads used for worker snapshots and source-request fingerprints.
 *
 * <p>The codec has no task-board state, authorization or transaction policy. Callers supply their
 * transaction-bound JDBC handle only to invoke the authoritative database fingerprint function;
 * all source, status and version decisions stay in the external workflow coordinators.
 */
@Service
class TaskBoardRoutePayloadCodec {
  private final ObjectMapper objectMapper;

  TaskBoardRoutePayloadCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  String fingerprint(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      LocalDate scheduledDate,
      int priority,
      TaskLane lane,
      JdbcTemplate jdbc) {
    ObjectNode payload =
        taskFingerprintPayload(
            warehouseId,
            request.externalTaskId(),
            request.title().trim(),
            trim(request.unitNumber()),
            trim(request.description()),
            request.plannedDurationMinutes(),
            request.deadlineAt(),
            scheduledDate,
            priority,
            lane);
    ArrayNode route = payload.putArray("route");
    for (RouteStepRequest step : request.route()) {
      ObjectNode item = route.addObject();
      setFingerprintValue(item, "queueDefinitionId", step.queueDefinitionId());
      setFingerprintValue(item, "taskText", trim(step.taskText()));
      setFingerprintValue(item, "plannedDurationMinutes", step.plannedDurationMinutes());
      item.set("works", fingerprintWorks(step.works()));
      setFingerprintValue(item, "materials", step.materials());
      setFingerprintValue(item, "comments", step.comments());
      setFingerprintValue(item, "sourceMedia", step.sourceMedia());
    }
    return canonicalRequestFingerprint(payload, jdbc);
  }

  String fingerprint(BoardTask task, List<QueueEntry> routeEntries, JdbcTemplate jdbc) {
    ObjectNode payload =
        taskFingerprintPayload(
            task.getWarehouseId(),
            task.getExternalTaskId(),
            task.getTitle(),
            task.getUnitNumber(),
            task.getDescription(),
            task.getPlannedDurationMinutes(),
            task.getDeadlineAt(),
            task.getScheduledDate(),
            task.getPriority(),
            task.getLane());
    ArrayNode route = payload.putArray("route");
    for (QueueEntry entry : routeEntries) {
      ObjectNode item = route.addObject();
      setFingerprintValue(item, "queueDefinitionId", entry.getQueue().getDefinition().getId());
      setFingerprintValue(item, "taskText", entry.getTaskText());
      setFingerprintValue(item, "plannedDurationMinutes", entry.getPlannedDurationMinutes());
      item.set(
          "works",
          fingerprintWorks(
              readList(
                  entry.getWorkerWorks(),
                  new TypeReference<List<TaskWorkSnapshotRequest>>() {})));
      item.set("materials", readJson(entry.getWorkerMaterials()));
      item.set("comments", readJson(entry.getWorkerComments()));
      item.set("sourceMedia", readJson(entry.getSourceMediaReferences()));
    }
    return canonicalRequestFingerprint(payload, jdbc);
  }

  void setWorkerContent(QueueEntry entry, RouteStepRequest step) {
    entry.setWorkerWorks(write(step.works()));
    entry.setWorkerMaterials(write(step.materials()));
    entry.setWorkerComments(write(step.comments()));
    entry.setSourceMediaReferences(write(step.sourceMedia()));
  }

  private ObjectNode taskFingerprintPayload(
      UUID warehouseId,
      UUID externalTaskId,
      String title,
      String unitNumber,
      String description,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      LocalDate scheduledDate,
      int priority,
      TaskLane lane) {
    ObjectNode payload = objectMapper.createObjectNode();
    setFingerprintValue(payload, "warehouseId", warehouseId);
    setFingerprintValue(payload, "externalTaskId", externalTaskId);
    setFingerprintValue(payload, "title", title);
    setFingerprintValue(payload, "unitNumber", unitNumber);
    setFingerprintValue(payload, "description", description);
    setFingerprintValue(payload, "plannedDurationMinutes", plannedDurationMinutes);
    setFingerprintValue(payload, "deadlineEpochMicros", deadlineEpochMicros(deadlineAt));
    setFingerprintValue(payload, "scheduledDate", scheduledDate);
    setFingerprintValue(payload, "lane", lane);
    setFingerprintValue(payload, "priority", priority);
    return payload;
  }

  private void setFingerprintValue(ObjectNode object, String name, Object value) {
    object.set(name, objectMapper.valueToTree(value));
  }

  private ArrayNode fingerprintWorks(List<TaskWorkSnapshotRequest> works) {
    ArrayNode result = objectMapper.createArrayNode();
    for (TaskWorkSnapshotRequest work : works) {
      ObjectNode value = result.addObject();
      setFingerprintValue(value, "id", work.id());
      setFingerprintValue(value, "name", work.name());
      setFingerprintValue(value, "quantity", work.quantity());
      setFingerprintValue(value, "unit", work.unit());
      setFingerprintValue(value, "durationMinutes", work.durationMinutes());
      setFingerprintValue(value, "comment", work.comment());
      if (!work.sourceMediaIds().isEmpty()) {
        setFingerprintValue(value, "sourceMediaIds", work.sourceMediaIds());
      }
    }
    return result;
  }

  private Long deadlineEpochMicros(OffsetDateTime deadlineAt) {
    if (deadlineAt == null) return null;
    var instant = deadlineAt.toInstant();
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
        instant.getNano() / 1_000L);
  }

  private String canonicalRequestFingerprint(ObjectNode payload, JdbcTemplate jdbc) {
    String fingerprint =
        jdbc.queryForObject(
            "select public.task_board_request_fingerprint_v4(cast(? as jsonb))",
            String.class,
            write(payload));
    if (fingerprint == null) {
      throw new IllegalStateException("Не удалось вычислить fingerprint задачи");
    }
    return fingerprint;
  }

  private JsonNode readJson(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Сохранённый снимок задания для рабочего повреждён", exception);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Снимок задания для рабочего не сериализуется", exception);
    }
  }

  private <T> List<T> readList(String value, TypeReference<List<T>> type) {
    try {
      return List.copyOf(objectMapper.readValue(value, type));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Сохранённый снимок задания для рабочего повреждён", exception);
    }
  }

  private String trim(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
